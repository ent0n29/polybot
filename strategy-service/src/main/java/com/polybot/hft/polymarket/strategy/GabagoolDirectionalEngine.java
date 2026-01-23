package com.polybot.hft.polymarket.strategy;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.polymarket.strategy.config.GabagoolConfig;
import com.polybot.hft.polymarket.strategy.model.Direction;
import com.polybot.hft.polymarket.strategy.model.GabagoolMarket;
import com.polybot.hft.polymarket.strategy.model.MarketInventory;
import com.polybot.hft.polymarket.strategy.model.OrderState;
import com.polybot.hft.polymarket.strategy.service.BankrollService;
import com.polybot.hft.polymarket.strategy.service.OrderManager;
import com.polybot.hft.polymarket.strategy.service.OrderManager.CancelReason;
import com.polybot.hft.polymarket.strategy.service.OrderManager.PlaceReason;
import com.polybot.hft.polymarket.strategy.service.PositionTracker;
import com.polybot.hft.polymarket.strategy.service.QuoteCalculator;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.polybot.hft.strategy.executor.ExecutorApiClient;
import com.polybot.hft.strategy.metrics.StrategyMetricsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Gabagool22-style strategy for Up/Down binary markets (replica-oriented).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GabagoolDirectionalEngine {

    private static final Duration TICK_SIZE_CACHE_TTL = Duration.ofMinutes(10);

    private final @NonNull HftProperties properties;
    private final @NonNull ClobMarketWebSocketClient marketWs;
    private final @NonNull ExecutorApiClient executorApi;
    private final @NonNull HftEventPublisher events;
    private final @NonNull GabagoolMarketDiscovery marketDiscovery;
    private final @NonNull Clock clock;
    private final @NonNull StrategyMetricsService metricsService;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gabagool-directional");
        t.setDaemon(true);
        return t;
    });

    private final String runId = UUID.randomUUID().toString();
    private final AtomicReference<List<GabagoolMarket>> activeMarkets = new AtomicReference<>(List.of());
    private final Map<String, TickSizeEntry> tickSizeCache = new ConcurrentHashMap<>();

    // Services (initialized in startIfEnabled)
    private BankrollService bankrollService;
    private PositionTracker positionTracker;
    private QuoteCalculator quoteCalculator;
    private OrderManager orderManager;

    @PostConstruct
    void startIfEnabled() {
        GabagoolConfig cfg = getConfig();
        logStartupConfig(cfg);

        if (!cfg.enabled()) {
            log.info("gabagool-directional strategy is disabled");
            return;
        }

        if (!properties.polymarket().marketWsEnabled()) {
            log.warn("gabagool-directional enabled, but market WS disabled");
            return;
        }

        // Initialize services
        bankrollService = new BankrollService(executorApi, metricsService, clock);
        positionTracker = new PositionTracker(executorApi, clock);
        quoteCalculator = new QuoteCalculator(bankrollService, properties, metricsService);
        orderManager = new OrderManager(executorApi, events, clock, runId);

        long periodMs = Math.max(100, cfg.refreshMillis());
        executor.scheduleAtFixedRate(() -> tick(cfg), 1000, periodMs, TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::discoverMarkets, 0, 30, TimeUnit.SECONDS);

        log.info("gabagool-directional started (refreshMillis={})", periodMs);
    }

    public int activeMarketCount() {
        return activeMarkets.get().size();
    }

    public boolean isRunning() {
        return !executor.isShutdown() && getConfig().enabled();
    }

    @PreDestroy
    void shutdown() {
        log.info("gabagool-directional shutting down");
        if (orderManager != null) {
            orderManager.cancelAll(CancelReason.SHUTDOWN);
        }
        executor.shutdownNow();
    }

    private void tick(GabagoolConfig cfg) {
        positionTracker.refreshIfStale();
        bankrollService.refreshIfStale(cfg);
        positionTracker.syncInventory(activeMarkets.get());

        if (bankrollService.isBelowThreshold(cfg)) {
            log.warn("CIRCUIT BREAKER: Effective bankroll below threshold ({}), skipping market evaluation",
                    cfg.bankrollMinThreshold());
            orderManager.checkPendingOrders(this::handleFill);
            return;
        }

        Instant now = clock.instant();
        for (GabagoolMarket market : activeMarkets.get()) {
            try {
                evaluateMarket(market, cfg, now);
            } catch (Exception e) {
                log.error("Error evaluating market {}: {}", market.slug(), e.getMessage());
            }
        }

        orderManager.checkPendingOrders(this::handleFill);
    }

    private void handleFill(OrderState state, BigDecimal filledShares) {
        if (state.market() == null || state.direction() == null)
            return;
        positionTracker.recordFill(state.market().slug(),
                state.direction() == Direction.UP, filledShares, state.price());
        log.debug("GABAGOOL: Updated inventory for {} after fill: {} +{} shares",
                state.market().slug(), state.direction(), filledShares);
    }

    private void evaluateMarket(GabagoolMarket market, GabagoolConfig cfg, Instant now) {
        long secondsToEnd = Duration.between(now, market.endTime()).getSeconds();
        long maxLifetimeSeconds = "updown-15m".equals(market.marketType()) ? 900L : 3600L;

        if (secondsToEnd < 0 || secondsToEnd > maxLifetimeSeconds) {
            orderManager.cancelMarketOrders(market, CancelReason.OUTSIDE_LIFETIME, secondsToEnd);
            return;
        }

        long minSecondsToEnd = Math.max(0L, cfg.minSecondsToEnd());
        long maxSecondsToEnd = Math.min(maxLifetimeSeconds, Math.max(minSecondsToEnd, cfg.maxSecondsToEnd()));
        if (secondsToEnd < minSecondsToEnd || secondsToEnd > maxSecondsToEnd) {
            orderManager.cancelMarketOrders(market, CancelReason.OUTSIDE_TIME_WINDOW, secondsToEnd);
            return;
        }

        TopOfBook upBook = marketWs.getTopOfBook(market.upTokenId()).orElse(null);
        TopOfBook downBook = marketWs.getTopOfBook(market.downTokenId()).orElse(null);

        if (upBook == null || downBook == null || isStale(upBook) || isStale(downBook)) {
            if (upBook == null || isStale(upBook)) {
                orderManager.cancelOrder(market.upTokenId(), CancelReason.BOOK_STALE, secondsToEnd, upBook, downBook);
            }
            if (downBook == null || isStale(downBook)) {
                orderManager.cancelOrder(market.downTokenId(), CancelReason.BOOK_STALE, secondsToEnd, downBook, upBook);
            }
            return;
        }

        MarketInventory inv = positionTracker.getInventory(market.slug());
        BigDecimal imbalance = inv.imbalance();
        BigDecimal absImbalance = imbalance.abs();

        // Aggressive Taker hedging near end OR if strict pairing requires it
        boolean needsAggressiveHedge = false;
        if (cfg.strictPairingEnabled()) {
            if (absImbalance.compareTo(cfg.maxImbalanceShares()) >= 0) {
                needsAggressiveHedge = true;
            } else if (secondsToEnd <= cfg.aggressiveHedgeSecondsToEnd()
                    && absImbalance.compareTo(BigDecimal.ONE) >= 0) {
                needsAggressiveHedge = true;
            }
        }

        if (needsAggressiveHedge) {
            Direction laggingLeg = imbalance.compareTo(BigDecimal.ZERO) > 0 ? Direction.DOWN : Direction.UP;
            TopOfBook laggingBook = laggingLeg == Direction.UP ? upBook : downBook;
            String laggingTokenId = laggingLeg == Direction.UP ? market.upTokenId() : market.downTokenId();

            BigDecimal leadPrice = laggingLeg == Direction.DOWN ? inv.lastUpFillPrice() : inv.lastDownFillPrice();
            if (leadPrice == null)
                leadPrice = laggingLeg == Direction.DOWN ? upBook.bestBid() : downBook.bestBid();

            if (leadPrice != null && laggingBook.bestAsk() != null) {
                BigDecimal hedgeEdge = BigDecimal.ONE.subtract(leadPrice.add(laggingBook.bestAsk()));
                if (hedgeEdge.compareTo(BigDecimal.valueOf(cfg.aggressiveHedgeMinEdge())) >= 0) {
                    log.info(
                            "GABAGOOL: STRICT PAIRING: Triggering aggressive hedge for {} (imbalance={}, hedgeEdge={})",
                            market.slug(), imbalance, hedgeEdge);
                    maybeTopUpLaggingLeg(market, laggingTokenId, laggingLeg, laggingBook,
                            laggingLeg == Direction.UP ? downBook : upBook, cfg, secondsToEnd, absImbalance,
                            PlaceReason.TOP_UP);
                }
            }
        }

        int[] skew = quoteCalculator.calculateSkewTicks(inv, cfg);
        int skewTicksUp = skew[0];
        int skewTicksDown = skew[1];

        // Fast top-up after recent fill
        maybeFastTopUp(market, inv, upBook, downBook, cfg, secondsToEnd);

        // Near-end taker top-up
        if (cfg.completeSetTopUpEnabled() && secondsToEnd <= cfg.completeSetTopUpSecondsToEnd()) {
            if (absImbalance.compareTo(cfg.completeSetTopUpMinShares()) >= 0) {
                Direction laggingLeg = inv.imbalance().compareTo(BigDecimal.ZERO) > 0 ? Direction.DOWN : Direction.UP;
                TopOfBook laggingBook = laggingLeg == Direction.UP ? upBook : downBook;
                String laggingTokenId = laggingLeg == Direction.UP ? market.upTokenId() : market.downTokenId();
                maybeTopUpLaggingLeg(market, laggingTokenId, laggingLeg, laggingBook,
                        laggingLeg == Direction.UP ? downBook : upBook, cfg, secondsToEnd, absImbalance,
                        PlaceReason.TOP_UP);
            }
        }

        // Check planned edge
        BigDecimal upTickSize = getTickSize(market.upTokenId());
        BigDecimal downTickSize = getTickSize(market.downTokenId());
        if (upTickSize == null || downTickSize == null) {
            orderManager.cancelMarketOrders(market, CancelReason.BOOK_STALE, secondsToEnd);
            return;
        }

        BigDecimal upEntryPrice = quoteCalculator.calculateEntryPrice(upBook, upTickSize, cfg, skewTicksUp);
        BigDecimal downEntryPrice = quoteCalculator.calculateEntryPrice(downBook, downTickSize, cfg, skewTicksDown);
        if (upEntryPrice == null || downEntryPrice == null) {
            orderManager.cancelMarketOrders(market, CancelReason.BOOK_STALE, secondsToEnd);
            return;
        }

        if (!quoteCalculator.hasMinimumEdge(upEntryPrice, downEntryPrice, cfg)) {
            log.debug("GABAGOOL: Skipping {} - insufficient edge", market.slug());
            orderManager.cancelMarketOrders(market, CancelReason.INSUFFICIENT_EDGE, secondsToEnd);
            return;
        }

        // Optional taker mode
        BigDecimal plannedEdge = BigDecimal.ONE.subtract(upEntryPrice.add(downEntryPrice));
        if (shouldTake(plannedEdge, upBook, downBook, cfg)) {
            Direction takeLeg = decideTakerLeg(inv, upBook, downBook, cfg);
            if (takeLeg == Direction.UP) {
                maybeTakeToken(market, market.upTokenId(), Direction.UP, upBook, downBook, cfg, secondsToEnd);
                maybeQuoteToken(market, market.downTokenId(), Direction.DOWN, downBook, upBook, cfg, secondsToEnd,
                        skewTicksDown, downTickSize);
                return;
            } else if (takeLeg == Direction.DOWN) {
                maybeTakeToken(market, market.downTokenId(), Direction.DOWN, downBook, upBook, cfg, secondsToEnd);
                maybeQuoteToken(market, market.upTokenId(), Direction.UP, upBook, downBook, cfg, secondsToEnd,
                        skewTicksUp, upTickSize);
                return;
            }
        }

        // Maker mode
        // Strict Pairing: disable quoting on the heavy leg if imbalance is too high
        boolean disableUp = cfg.strictPairingEnabled() && imbalance.compareTo(cfg.maxImbalanceShares()) >= 0;
        boolean disableDown = cfg.strictPairingEnabled()
                && imbalance.compareTo(cfg.maxImbalanceShares().negate()) <= 0;

        if (!disableUp) {
            maybeQuoteToken(market, market.upTokenId(), Direction.UP, upBook, downBook, cfg, secondsToEnd, skewTicksUp,
                    upTickSize);
        } else {
            log.debug("GABAGOOL: STRICT PAIRING: Disabling UP quotes due to imbalance {}", imbalance);
            orderManager.cancelOrder(market.upTokenId(), CancelReason.INSUFFICIENT_EDGE, secondsToEnd, upBook,
                    downBook);
        }

        if (!disableDown) {
            maybeQuoteToken(market, market.downTokenId(), Direction.DOWN, downBook, upBook, cfg, secondsToEnd,
                    skewTicksDown, downTickSize);
        } else {
            log.debug("GABAGOOL: STRICT PAIRING: Disabling DOWN quotes due to imbalance {}", imbalance);
            orderManager.cancelOrder(market.downTokenId(), CancelReason.INSUFFICIENT_EDGE, secondsToEnd, downBook,
                    upBook);
        }
    }

    private void maybeQuoteToken(GabagoolMarket market, String tokenId, Direction direction,
            TopOfBook book, TopOfBook otherBook, GabagoolConfig cfg,
            long secondsToEnd, int skewTicks, BigDecimal tickSize) {
        if (tokenId == null || book == null)
            return;

        BigDecimal entryPrice = quoteCalculator.calculateEntryPrice(book, tickSize, cfg, skewTicks);
        if (entryPrice == null)
            return;

        BigDecimal exposure = quoteCalculator.calculateExposure(orderManager.getOpenOrders(),
                positionTracker.getAllInventories());
        BigDecimal shares = quoteCalculator.calculateShares(market, entryPrice, cfg, secondsToEnd, exposure);
        if (shares == null)
            return;

        OrderState existing = orderManager.getOrder(tokenId);
        OrderManager.ReplaceDecision decision = orderManager.maybeReplaceOrder(
                tokenId, entryPrice, shares, cfg, CancelReason.REPLACE_PRICE, secondsToEnd, book, otherBook);
        if (decision == OrderManager.ReplaceDecision.SKIP) {
            return;
        }

        PlaceReason reason = decision == OrderManager.ReplaceDecision.REPLACE ? PlaceReason.REPLACE : PlaceReason.QUOTE;
        orderManager.placeOrder(market, tokenId, direction, entryPrice, shares, secondsToEnd, tickSize, book, otherBook,
                existing, reason);
    }

    private void maybeTakeToken(GabagoolMarket market, String tokenId, Direction direction,
            TopOfBook book, TopOfBook otherBook, GabagoolConfig cfg, long secondsToEnd) {
        if (tokenId == null || book == null)
            return;

        BigDecimal bestAsk = book.bestAsk();
        if (bestAsk == null || bestAsk.compareTo(BigDecimal.valueOf(0.99)) > 0)
            return;

        BigDecimal exposure = quoteCalculator.calculateExposure(orderManager.getOpenOrders(),
                positionTracker.getAllInventories());
        BigDecimal shares = quoteCalculator.calculateShares(market, bestAsk, cfg, secondsToEnd, exposure);
        if (shares == null)
            return;

        OrderState existing = orderManager.getOrder(tokenId);
        if (existing != null) {
            long ageMillis = Duration.between(existing.placedAt(), clock.instant()).toMillis();
            if (ageMillis < cfg.minReplaceMillis())
                return;
            orderManager.cancelOrder(tokenId, CancelReason.REPLACE_PRICE, secondsToEnd, book, otherBook);
        }

        log.info("GABAGOOL: TAKER {} order on {} at ask {} (size={}, secondsToEnd={})",
                direction, market.slug(), bestAsk, shares, secondsToEnd);
        orderManager.placeOrder(market, tokenId, direction, bestAsk, shares, secondsToEnd, null, book, otherBook,
                existing, PlaceReason.TAKER);
    }

    private void maybeFastTopUp(GabagoolMarket market, MarketInventory inv, TopOfBook upBook,
            TopOfBook downBook, GabagoolConfig cfg, long secondsToEnd) {
        if (!cfg.completeSetFastTopUpEnabled())
            return;

        BigDecimal imbalance = inv.imbalance();
        BigDecimal absImbalance = imbalance.abs();
        if (absImbalance.compareTo(cfg.completeSetFastTopUpMinShares()) < 0)
            return;

        Instant now = clock.instant();
        if (inv.lastTopUpAt() != null &&
                Duration.between(inv.lastTopUpAt(), now).toMillis() < cfg.completeSetFastTopUpCooldownMillis()) {
            return;
        }

        Direction laggingLeg = imbalance.compareTo(BigDecimal.ZERO) > 0 ? Direction.DOWN : Direction.UP;
        Instant leadFillAt = laggingLeg == Direction.DOWN ? inv.lastUpFillAt() : inv.lastDownFillAt();
        if (leadFillAt == null)
            return;

        long sinceLeadFillSeconds = Duration.between(leadFillAt, now).getSeconds();
        if (sinceLeadFillSeconds < cfg.completeSetFastTopUpMinSecondsAfterFill() ||
                sinceLeadFillSeconds > cfg.completeSetFastTopUpMaxSecondsAfterFill()) {
            return;
        }

        Instant lagFillAt = laggingLeg == Direction.DOWN ? inv.lastDownFillAt() : inv.lastUpFillAt();
        if (lagFillAt != null && !lagFillAt.isBefore(leadFillAt))
            return;

        TopOfBook laggingBook = laggingLeg == Direction.UP ? upBook : downBook;
        TopOfBook otherBook = laggingLeg == Direction.UP ? downBook : upBook;
        String laggingTokenId = laggingLeg == Direction.UP ? market.upTokenId() : market.downTokenId();

        if (laggingBook.bestBid() == null || laggingBook.bestAsk() == null)
            return;
        BigDecimal spread = laggingBook.bestAsk().subtract(laggingBook.bestBid());
        if (spread.compareTo(cfg.takerModeMaxSpread()) > 0)
            return;

        BigDecimal leadFillPrice = laggingLeg == Direction.DOWN ? inv.lastUpFillPrice() : inv.lastDownFillPrice();
        if (leadFillPrice == null) {
            leadFillPrice = laggingLeg == Direction.DOWN ? upBook.bestBid() : downBook.bestBid();
        }
        if (leadFillPrice != null) {
            BigDecimal hedgedEdge = BigDecimal.ONE.subtract(leadFillPrice.add(laggingBook.bestAsk()));
            if (hedgedEdge.compareTo(BigDecimal.valueOf(cfg.completeSetFastTopUpMinEdge())) < 0)
                return;
        }

        positionTracker.markTopUp(market.slug());
        maybeTopUpLaggingLeg(market, laggingTokenId, laggingLeg, laggingBook, otherBook, cfg, secondsToEnd,
                absImbalance, PlaceReason.FAST_TOP_UP);
    }

    private void maybeTopUpLaggingLeg(GabagoolMarket market, String tokenId, Direction direction,
            TopOfBook book, TopOfBook otherBook, GabagoolConfig cfg,
            long secondsToEnd, BigDecimal imbalanceShares, PlaceReason reason) {
        if (tokenId == null || book == null)
            return;
        if (imbalanceShares == null || imbalanceShares.compareTo(BigDecimal.valueOf(0.01)) < 0)
            return;

        BigDecimal bestAsk = book.bestAsk();
        if (bestAsk == null || bestAsk.compareTo(BigDecimal.valueOf(0.99)) > 0)
            return;

        BigDecimal bestBid = book.bestBid();
        if (bestBid != null) {
            BigDecimal spread = bestAsk.subtract(bestBid);
            if (spread.compareTo(cfg.takerModeMaxSpread()) > 0)
                return;
        }

        BigDecimal topUpShares = imbalanceShares;
        BigDecimal bankrollUsd = bankrollService.resolveEffective(cfg);

        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (cfg.maxOrderBankrollFraction() > 0) {
                BigDecimal perOrderCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxOrderBankrollFraction()));
                BigDecimal capShares = perOrderCap.divide(bestAsk, 2, RoundingMode.DOWN);
                topUpShares = topUpShares.min(capShares);
            }
            if (cfg.maxTotalBankrollFraction() > 0) {
                BigDecimal totalCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxTotalBankrollFraction()));
                BigDecimal exposure = quoteCalculator.calculateExposure(orderManager.getOpenOrders(),
                        positionTracker.getAllInventories());
                BigDecimal remaining = totalCap.subtract(exposure);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0)
                    return;
                BigDecimal capShares = remaining.divide(bestAsk, 2, RoundingMode.DOWN);
                topUpShares = topUpShares.min(capShares);
            }
        }

        BigDecimal maxNotionalUsd = properties.risk().maxOrderNotionalUsd();
        if (maxNotionalUsd != null && maxNotionalUsd.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal capShares = maxNotionalUsd.divide(bestAsk, 2, RoundingMode.DOWN);
            topUpShares = topUpShares.min(capShares);
        }

        topUpShares = topUpShares.setScale(2, RoundingMode.DOWN);
        if (topUpShares.compareTo(BigDecimal.valueOf(0.01)) < 0)
            return;

        OrderState existing = orderManager.getOrder(tokenId);
        if (existing != null) {
            long ageMillis = Duration.between(existing.placedAt(), clock.instant()).toMillis();
            if (ageMillis < cfg.minReplaceMillis())
                return;
            orderManager.cancelOrder(tokenId, CancelReason.REPLACE_PRICE, secondsToEnd, book, otherBook);
        }

        log.info("GABAGOOL: TOP-UP {} on {} at ask {} (imbalance={}, topUpShares={}, secondsToEnd={})",
                direction, market.slug(), bestAsk, imbalanceShares, topUpShares, secondsToEnd);
        orderManager.placeOrder(market, tokenId, direction, bestAsk, topUpShares, secondsToEnd, null, book, otherBook,
                existing, reason);
    }

    private boolean shouldTake(BigDecimal edge, TopOfBook upBook, TopOfBook downBook, GabagoolConfig cfg) {
        if (!cfg.takerModeEnabled())
            return false;
        if (edge.doubleValue() > cfg.takerModeMaxEdge())
            return false;

        BigDecimal maxSpread = cfg.takerModeMaxSpread();
        BigDecimal upSpread = upBook.bestAsk().subtract(upBook.bestBid());
        BigDecimal downSpread = downBook.bestAsk().subtract(downBook.bestBid());

        if (upSpread.compareTo(maxSpread) > 0 || downSpread.compareTo(maxSpread) > 0)
            return false;

        log.debug("GABAGOOL: Taker mode triggered - edge={}, upSpread={}, downSpread={}", edge, upSpread, downSpread);
        return true;
    }

    private Direction decideTakerLeg(MarketInventory inv, TopOfBook upBook, TopOfBook downBook, GabagoolConfig cfg) {
        BigDecimal bidUp = upBook.bestBid(), askUp = upBook.bestAsk();
        BigDecimal bidDown = downBook.bestBid(), askDown = downBook.bestAsk();
        if (bidUp == null || askUp == null || bidDown == null || askDown == null)
            return null;

        BigDecimal edgeTakeUp = BigDecimal.ONE.subtract(askUp.add(bidDown));
        BigDecimal edgeTakeDown = BigDecimal.ONE.subtract(bidUp.add(askDown));
        BigDecimal minEdge = BigDecimal.valueOf(cfg.completeSetFastTopUpMinEdge());

        boolean upOk = edgeTakeUp.compareTo(minEdge) >= 0;
        boolean downOk = edgeTakeDown.compareTo(minEdge) >= 0;

        if (!upOk && !downOk)
            return null;
        if (upOk && !downOk)
            return Direction.UP;
        if (downOk && !upOk)
            return Direction.DOWN;

        int cmp = edgeTakeUp.compareTo(edgeTakeDown);
        if (cmp > 0)
            return Direction.UP;
        if (cmp < 0)
            return Direction.DOWN;

        BigDecimal imbalance = inv.imbalance();
        if (imbalance.compareTo(BigDecimal.ZERO) > 0)
            return Direction.DOWN;
        if (imbalance.compareTo(BigDecimal.ZERO) < 0)
            return Direction.UP;
        return Direction.UP;
    }

    private void discoverMarkets() {
        try {
            List<GabagoolMarket> markets = new ArrayList<>();

            List<GabagoolMarketDiscovery.DiscoveredMarket> discovered = marketDiscovery.getActiveMarkets();
            for (GabagoolMarketDiscovery.DiscoveredMarket d : discovered) {
                markets.add(new GabagoolMarket(d.slug(), d.upTokenId(), d.downTokenId(), d.endTime(), d.marketType()));
            }

            GabagoolConfig cfg = getConfig();
            if (cfg.markets() != null) {
                for (GabagoolConfig.GabagoolMarketConfig m : cfg.markets()) {
                    if (m.upTokenId() != null && m.downTokenId() != null) {
                        Instant endTime = m.endTime() != null ? m.endTime()
                                : clock.instant().plus(Duration.ofMinutes(15));
                        String upToken = m.upTokenId();
                        boolean exists = markets.stream().anyMatch(existing -> existing.upTokenId().equals(upToken));
                        if (!exists) {
                            markets.add(new GabagoolMarket(
                                    m.slug() != null ? m.slug() : "configured",
                                    m.upTokenId(), m.downTokenId(), endTime, "unknown"));
                        }
                    }
                }
            }

            activeMarkets.set(markets);
            metricsService.updateActiveMarketsCount(markets.size());
            if (cfg.bankrollUsd() != null)
                metricsService.updateBankroll(cfg.bankrollUsd());

            List<String> assetIds = markets.stream()
                    .flatMap(m -> Stream.of(m.upTokenId(), m.downTokenId()))
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            if (!assetIds.isEmpty())
                marketWs.setSubscribedAssets(assetIds);

            if (!markets.isEmpty()) {
                log.debug("GABAGOOL: Tracking {} markets ({} discovered, {} configured)",
                        markets.size(), discovered.size(), cfg.markets() != null ? cfg.markets().size() : 0);
            }
        } catch (Exception e) {
            log.error("GABAGOOL: Error discovering markets: {}", e.getMessage());
        }
    }

    private GabagoolConfig getConfig() {
        return GabagoolConfig.from(properties.strategy().gabagool());
    }

    private BigDecimal getTickSize(String tokenId) {
        TickSizeEntry cached = tickSizeCache.get(tokenId);
        if (cached != null
                && Duration.between(cached.fetchedAt(), clock.instant()).compareTo(TICK_SIZE_CACHE_TTL) < 0) {
            return cached.tickSize();
        }
        try {
            BigDecimal tickSize = executorApi.getTickSize(tokenId);
            tickSizeCache.put(tokenId, new TickSizeEntry(tickSize, clock.instant()));
            return tickSize;
        } catch (Exception e) {
            log.warn("Failed to get tick size for {}: {}", tokenId, e.getMessage());
            return BigDecimal.valueOf(0.01);
        }
    }

    private boolean isStale(TopOfBook tob) {
        if (tob == null || tob.updatedAt() == null)
            return true;
        return Duration.between(tob.updatedAt(), clock.instant()).toMillis() > 2_000;
    }

    private void logStartupConfig(GabagoolConfig cfg) {
        log.info("gabagool strategy config loaded (enabled={}, refreshMillis={}, quoteSizeUsd={}, bankrollUsd={})",
                cfg.enabled(), cfg.refreshMillis(), cfg.quoteSize(), cfg.bankrollUsd());
        log.info("gabagool complete-set config (minEdge={}, maxSkewTicks={}, topUpEnabled={}, fastTopUpEnabled={})",
                cfg.completeSetMinEdge(), cfg.completeSetMaxSkewTicks(),
                cfg.completeSetTopUpEnabled(), cfg.completeSetFastTopUpEnabled());
        log.info("gabagool taker-mode config (enabled={}, maxEdge={}, maxSpread={})",
                cfg.takerModeEnabled(), cfg.takerModeMaxEdge(), cfg.takerModeMaxSpread());
    }

    public record TickSizeEntry(BigDecimal tickSize, Instant fetchedAt) {
    }
}
