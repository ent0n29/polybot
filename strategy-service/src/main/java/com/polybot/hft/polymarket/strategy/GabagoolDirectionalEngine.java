package com.polybot.hft.polymarket.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.data.PolymarketPosition;
import com.polybot.hft.polymarket.model.ClobOrderType;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.strategy.executor.ExecutorApiClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Gabagool22-style strategy for Up/Down binary markets (replica-oriented).
 *
 * What we can match from public fills:
 * - Markets: BTC/ETH Up/Down 15m + 1h series
 * - Activity spans most of the market lifetime (15m: ~0-15m to end, 1h: ~0-60m to end)
 * - Discrete sizing patterns by series (shares, not notional)
 * - Execution edge dominates realized PnL (maker-like fills are much better)
 *
 * What we cannot observe/replicate exactly without the trader's private order stream:
 * - Quote refresh cadence, cancels, unfilled orders, queue priority.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GabagoolDirectionalEngine {

    private static final Duration TICK_SIZE_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration POSITIONS_CACHE_TTL = Duration.ofSeconds(5);
    private static final Duration ORDER_STALE_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration ORDER_STATUS_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final int ERROR_MAX_LEN = 512;

    private final @NonNull HftProperties properties;
    private final @NonNull ClobMarketWebSocketClient marketWs;
    private final @NonNull ExecutorApiClient executorApi;
    private final @NonNull HftEventPublisher events;
    private final @NonNull GabagoolMarketDiscovery marketDiscovery;
    private final @NonNull Clock clock;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gabagool-directional");
        t.setDaemon(true);
        return t;
    });

    // Open maker orders: tokenId -> OrderState (one working order per token).
    private final Map<String, OrderState> ordersByTokenId = new ConcurrentHashMap<>();

    // Tick size cache: tokenId -> (tickSize, fetchedAt)
    private final Map<String, TickSizeEntry> tickSizeCache = new ConcurrentHashMap<>();

    // Active markets being tracked
    private final AtomicReference<List<GabagoolMarket>> activeMarkets = new AtomicReference<>(List.of());

    private final AtomicReference<PositionsCache> positionsCache = new AtomicReference<>(
            new PositionsCache(Instant.EPOCH, Map.of(), BigDecimal.ZERO)
    );

    // Per-market inventory tracking for complete-set coordination (marketSlug -> MarketInventory)
    private final Map<String, MarketInventory> inventoryByMarket = new ConcurrentHashMap<>();

    // Fills observed via order-status polling since the last positions refresh.
    // Used to avoid under-counting exposure between positions snapshots.
    private final Map<String, BigDecimal> fillsSincePositionsRefreshByTokenId = new ConcurrentHashMap<>();
    private final AtomicReference<BigDecimal> fillsSincePositionsRefreshNotionalUsd = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<Instant> fillsSincePositionsRefreshAt = new AtomicReference<>(Instant.EPOCH);

    private final String runId = UUID.randomUUID().toString();

    @PostConstruct
    void startIfEnabled() {
        GabagoolConfig cfg = getConfig();
        log.info("gabagool strategy config loaded (enabled={}, refreshMillis={}, quoteSizeUsd={}, quoteSizeFrac={}, bankrollUsd={}, maxOrderFrac={}, maxTotalFrac={})",
                cfg.enabled(),
                cfg.refreshMillis(),
                cfg.quoteSize(),
                cfg.quoteSizeBankrollFraction(),
                cfg.bankrollUsd(),
                cfg.maxOrderBankrollFraction(),
                cfg.maxTotalBankrollFraction());
        log.info("gabagool complete-set config (minEdge={}, maxSkewTicks={}, imbalanceForMaxSkew={}, topUpEnabled={}, topUpSecondsToEnd={}, topUpMinShares={})",
                cfg.completeSetMinEdge(),
                cfg.completeSetMaxSkewTicks(),
                cfg.completeSetImbalanceSharesForMaxSkew(),
                cfg.completeSetTopUpEnabled(),
                cfg.completeSetTopUpSecondsToEnd(),
                cfg.completeSetTopUpMinShares());
        log.info("gabagool directional-bias config (enabled={}, factor={})",
                cfg.directionalBiasEnabled(),
                cfg.directionalBiasFactor());
        log.info("gabagool taker-mode config (enabled={}, maxEdge={}, maxSpread={})",
                cfg.takerModeEnabled(),
                cfg.takerModeMaxEdge(),
                cfg.takerModeMaxSpread());

        if (!cfg.enabled()) {
            log.info("gabagool-directional strategy is disabled");
            return;
        }

        if (!properties.polymarket().marketWsEnabled()) {
            log.warn("gabagool-directional enabled, but market WS disabled");
            return;
        }

        // Schedule the main tick loop
        long periodMs = Math.max(100, cfg.refreshMillis());
        executor.scheduleAtFixedRate(() -> tick(cfg), 1000, periodMs, TimeUnit.MILLISECONDS);

        // Schedule market discovery
        executor.scheduleAtFixedRate(this::discoverMarkets, 0, 30, TimeUnit.SECONDS);

        log.info("gabagool-directional started (refreshMillis={})", periodMs);
    }

    /**
     * Returns the count of currently active markets being tracked.
     */
    public int activeMarketCount() {
        return activeMarkets.get().size();
    }

    /**
     * Returns true if the strategy is running (executor not shut down).
     */
    public boolean isRunning() {
        return !executor.isShutdown() && getConfig().enabled();
    }

    @PreDestroy
    void shutdown() {
        log.info("gabagool-directional shutting down, cancelling {} open orders", ordersByTokenId.size());
        ordersByTokenId.values().forEach(state -> safeCancel(state, CancelReason.SHUTDOWN, null, null, null));
        executor.shutdownNow();
    }

    /**
     * Main tick loop - evaluates each tracked market for entry/exit.
     */
    private void tick(GabagoolConfig cfg) {
        Instant now = clock.instant();
        refreshPositionsCacheIfStale(now);

        for (GabagoolMarket market : activeMarkets.get()) {
            try {
                evaluateMarket(market, cfg, now);
            } catch (Exception e) {
                log.error("Error evaluating market {}: {}", market.slug(), e.getMessage());
            }
        }

        // Check pending orders for fills/cancellations
        checkPendingOrders();
    }

    private void refreshPositionsCacheIfStale(Instant now) {
        if (now == null) {
            return;
        }
        if (properties.mode() == HftProperties.TradingMode.PAPER) {
            return;
        }
        PositionsCache cached = positionsCache.get();
        if (cached != null && cached.fetchedAt() != null && Duration.between(cached.fetchedAt(), now).compareTo(POSITIONS_CACHE_TTL) < 0) {
            return;
        }

        try {
            positionsCache.set(fetchPositionsCache(now));
            resetFillsSincePositionsRefresh(now);
        } catch (Exception e) {
            PositionsCache existing = positionsCache.get();
            if (existing == null) {
                positionsCache.set(new PositionsCache(now, Map.of(), BigDecimal.ZERO));
            } else {
                positionsCache.set(new PositionsCache(
                        now,
                        existing.openNotionalByTokenId() == null ? Map.of() : existing.openNotionalByTokenId(),
                        existing.openNotionalUsd() == null ? BigDecimal.ZERO : existing.openNotionalUsd()
                ));
            }
            log.debug("positions refresh failed: {}", e.getMessage());
        }
    }

    private void resetFillsSincePositionsRefresh(Instant now) {
        fillsSincePositionsRefreshByTokenId.clear();
        fillsSincePositionsRefreshNotionalUsd.set(BigDecimal.ZERO);
        fillsSincePositionsRefreshAt.set(now == null ? Instant.EPOCH : now);
    }

    private PositionsCache fetchPositionsCache(Instant now) {
        int limit = 200;
        int maxOffset = 2_000;

        Map<String, BigDecimal> notionalByTokenId = new HashMap<>();
        BigDecimal totalNotional = BigDecimal.ZERO;

        for (int offset = 0; offset <= maxOffset; offset += limit) {
            PolymarketPosition[] page = executorApi.getPositions(limit, offset);
            if (page == null || page.length == 0) {
                break;
            }

            for (PolymarketPosition p : page) {
                if (p == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(p.redeemable())) {
                    continue;
                }
                BigDecimal initialValue = p.initialValue();
                if (initialValue == null) {
                    continue;
                }
                if (initialValue.compareTo(BigDecimal.ZERO) < 0) {
                    initialValue = initialValue.abs();
                }
                totalNotional = totalNotional.add(initialValue);
                if (p.asset() != null && !p.asset().isBlank()) {
                    notionalByTokenId.merge(p.asset(), initialValue, BigDecimal::add);
                }
            }

            if (page.length < limit) {
                break;
            }
        }

        return new PositionsCache(now, notionalByTokenId, totalNotional);
    }

    /**
     * Evaluate a single market for trading opportunity.
     *
     * COMPLETE-SET STRATEGY (gabagool22 replica):
     * - Quote BOTH UP and DOWN outcomes simultaneously
     * - The edge is: 1.0 - (bid_up + bid_down) (typically 0.01-0.02)
     * - Track inventory imbalance and skew quotes to favor lagging leg
     * - Near market end, use taker top-up to rebalance if needed
     */
    private void evaluateMarket(GabagoolMarket market, GabagoolConfig cfg, Instant now) {
        // Calculate seconds to market end
        long secondsToEnd = Duration.between(now, market.endTime()).getSeconds();

        // Empirical fill timing spans most of each market instance lifetime.
        // - 15m series: 0..900s to end
        // - 1h series: 0..3600s to end
        long maxLifetimeSeconds = "updown-15m".equals(market.marketType()) ? 900L : 3600L;
        if (secondsToEnd < 0 || secondsToEnd > maxLifetimeSeconds) {
            cancelMarketOrders(market, CancelReason.OUTSIDE_LIFETIME, secondsToEnd);
            return;
        }

        long minSecondsToEnd = Math.max(0L, cfg.minSecondsToEnd());
        long maxSecondsToEnd = Math.min(maxLifetimeSeconds, Math.max(minSecondsToEnd, cfg.maxSecondsToEnd()));
        if (secondsToEnd < minSecondsToEnd || secondsToEnd > maxSecondsToEnd) {
            cancelMarketOrders(market, CancelReason.OUTSIDE_TIME_WINDOW, secondsToEnd);
            return;
        }

        // Get order book data for BOTH outcomes (complete-set coordination)
        TopOfBook upBook = marketWs.getTopOfBook(market.upTokenId()).orElse(null);
        TopOfBook downBook = marketWs.getTopOfBook(market.downTokenId()).orElse(null);

        // ========== COMPLETE-SET EDGE CHECK ==========
        // Only quote when we have fresh TOB for BOTH outcomes and edge is sufficient
        if (upBook == null || downBook == null || isStale(upBook) || isStale(downBook)) {
            if (upBook == null || isStale(upBook)) {
                cancelTokenOrder(market.upTokenId(), CancelReason.BOOK_STALE, secondsToEnd, upBook, downBook);
            }
            if (downBook == null || isStale(downBook)) {
                cancelTokenOrder(market.downTokenId(), CancelReason.BOOK_STALE, secondsToEnd, downBook, upBook);
            }
            return;
        }

        // Calculate complete-set edge: 1.0 - (bid_up + bid_down)
        // This is the theoretical profit if we buy both sides at bid and they resolve
        BigDecimal bidUp = upBook.bestBid();
        BigDecimal bidDown = downBook.bestBid();
        if (bidUp == null || bidDown == null) {
            cancelMarketOrders(market, CancelReason.BOOK_STALE, secondsToEnd);
            return;
        }

        BigDecimal completeSetCost = bidUp.add(bidDown);
        BigDecimal completeSetEdge = BigDecimal.ONE.subtract(completeSetCost);
        BigDecimal minEdgeBD = BigDecimal.valueOf(cfg.completeSetMinEdge());

        // Use BigDecimal comparison to avoid floating-point precision issues
        // (e.g., 0.009999999999999953 should be treated as >= 0.01)
        if (completeSetEdge.compareTo(minEdgeBD) < 0) {
            log.debug("GABAGOOL: Skipping {} - complete-set edge {} < min {} (bid_up={}, bid_down={})",
                    market.slug(), completeSetEdge, minEdgeBD, bidUp, bidDown);
            cancelMarketOrders(market, CancelReason.INSUFFICIENT_EDGE, secondsToEnd);
            return;
        }

        // ========== INVENTORY TRACKING & SKEW ==========
        MarketInventory inv = inventoryByMarket.computeIfAbsent(market.slug(),
                k -> new MarketInventory(BigDecimal.ZERO, BigDecimal.ZERO));

        // Calculate imbalance: positive = more UP shares, negative = more DOWN shares
        BigDecimal imbalance = inv.upShares().subtract(inv.downShares());
        int skewTicksUp = 0;
        int skewTicksDown = 0;

        BigDecimal imbalanceForMaxSkew = cfg.completeSetImbalanceSharesForMaxSkew();
        int maxSkewTicks = cfg.completeSetMaxSkewTicks();

        if (imbalanceForMaxSkew.compareTo(BigDecimal.ZERO) > 0 && maxSkewTicks > 0) {
            // Linear ramp: at imbalanceForMaxSkew, apply full maxSkewTicks
            double skewRatio = Math.min(1.0, Math.abs(imbalance.doubleValue()) / imbalanceForMaxSkew.doubleValue());
            int skewTicks = (int) Math.round(skewRatio * maxSkewTicks);

            if (imbalance.compareTo(BigDecimal.ZERO) > 0) {
                // More UP shares -> improve DOWN quote (add ticks), penalize UP quote (subtract ticks)
                skewTicksDown = skewTicks;
                skewTicksUp = -skewTicks;
            } else if (imbalance.compareTo(BigDecimal.ZERO) < 0) {
                // More DOWN shares -> improve UP quote, penalize DOWN quote
                skewTicksUp = skewTicks;
                skewTicksDown = -skewTicks;
            }
        }

        // ========== TAKER TOP-UP (near market end) ==========
        // If imbalanced near end, cross the spread on lagging leg to rebalance
        if (cfg.completeSetTopUpEnabled() && secondsToEnd <= cfg.completeSetTopUpSecondsToEnd()) {
            BigDecimal absImbalance = imbalance.abs();
            if (absImbalance.compareTo(cfg.completeSetTopUpMinShares()) >= 0) {
                Direction laggingLeg = imbalance.compareTo(BigDecimal.ZERO) > 0 ? Direction.DOWN : Direction.UP;
                TopOfBook laggingBook = laggingLeg == Direction.UP ? upBook : downBook;
                String laggingTokenId = laggingLeg == Direction.UP ? market.upTokenId() : market.downTokenId();

                maybeTopUpLaggingLeg(market, laggingTokenId, laggingLeg, laggingBook,
                        laggingLeg == Direction.UP ? downBook : upBook, cfg, secondsToEnd, absImbalance);
            }
        }

        // ========== DIRECTIONAL BIAS (based on book imbalance) ==========
        // Gabagool22 trades WITH the order book - buys more on the side with stronger bid support.
        // Observed behavior: UP/DOWN ratio of 5-7x when book imbalance is significant.
        double upSizeFactor = 1.0;
        double downSizeFactor = 1.0;

        if (cfg.directionalBiasEnabled() && cfg.directionalBiasFactor() > 1.0) {
            // Calculate book imbalance: (upBidSize - downBidSize) / (upBidSize + downBidSize)
            BigDecimal upBidSize = upBook.bestBidSize();
            BigDecimal downBidSize = downBook.bestBidSize();

            if (upBidSize != null && downBidSize != null
                    && upBidSize.compareTo(BigDecimal.ZERO) > 0
                    && downBidSize.compareTo(BigDecimal.ZERO) > 0) {

                BigDecimal totalBidSize = upBidSize.add(downBidSize);
                double bookImbalance = upBidSize.subtract(downBidSize)
                        .divide(totalBidSize, 8, RoundingMode.HALF_UP)
                        .doubleValue();

                // If imbalance exceeds threshold, apply directional bias
                // Positive imbalance = more UP bid support -> favor UP
                // Negative imbalance = more DOWN bid support -> favor DOWN
                double threshold = cfg.imbalanceThreshold();

                if (Math.abs(bookImbalance) >= threshold) {
                    double factor = cfg.directionalBiasFactor();
                    if (bookImbalance > 0) {
                        // Favor UP: increase UP size, decrease DOWN size
                        upSizeFactor = factor;
                        downSizeFactor = 1.0 / factor;
                    } else {
                        // Favor DOWN: increase DOWN size, decrease UP size
                        upSizeFactor = 1.0 / factor;
                        downSizeFactor = factor;
                    }

                    log.debug("GABAGOOL: Directional bias on {} - bookImbalance={}, upFactor={}, downFactor={}",
                            market.slug(), String.format("%.3f", bookImbalance), String.format("%.2f", upSizeFactor), String.format("%.2f", downSizeFactor));
                }
            }
        }

        // ========== DECIDE: MAKER vs TAKER MODE ==========
        // Gabagool22 takes ~39% of the time, especially when:
        //   - Edge is low (<1.5%) - opportunity is fleeting
        //   - Spread is tight (1-2 ticks) - taker cost is acceptable
        boolean shouldTake = decideTakerMode(completeSetEdge, upBook, downBook, cfg);

        if (shouldTake) {
            // ========== TAKER MODE: Cross the spread to get immediate fills ==========
            maybeTakeToken(market, market.upTokenId(), Direction.UP, upBook, downBook, cfg, secondsToEnd, upSizeFactor);
            maybeTakeToken(market, market.downTokenId(), Direction.DOWN, downBook, upBook, cfg, secondsToEnd, downSizeFactor);
        } else {
            // ========== MAKER MODE: Quote at bid with skew and directional bias ==========
            maybeQuoteTokenWithSkew(market, market.upTokenId(), Direction.UP, upBook, downBook, cfg, secondsToEnd, skewTicksUp, upSizeFactor);
            maybeQuoteTokenWithSkew(market, market.downTokenId(), Direction.DOWN, downBook, upBook, cfg, secondsToEnd, skewTicksDown, downSizeFactor);
        }
    }

    /**
     * Decide whether to use taker mode (cross the spread) vs maker mode (post at bid).
     *
     * Based on empirical analysis of gabagool22's trading behavior:
     * - ~39% of fills are at the ask (taker)
     * - Taker probability increases with lower edge (need to act fast)
     * - Taker probability increases with tighter spreads (lower cost)
     */
    private boolean decideTakerMode(BigDecimal completeSetEdge, TopOfBook upBook, TopOfBook downBook, GabagoolConfig cfg) {
        if (!cfg.takerModeEnabled()) {
            return false;
        }

        // Check edge threshold - take more when edge is low (fleeting opportunity)
        double edgeThreshold = cfg.takerModeMaxEdge();
        if (completeSetEdge.doubleValue() > edgeThreshold) {
            return false;  // Edge is high enough to wait for maker fills
        }

        // Check spread - only take when spread cost is acceptable
        BigDecimal maxSpread = cfg.takerModeMaxSpread();
        BigDecimal upSpread = upBook.bestAsk().subtract(upBook.bestBid());
        BigDecimal downSpread = downBook.bestAsk().subtract(downBook.bestBid());

        if (upSpread.compareTo(maxSpread) > 0 || downSpread.compareTo(maxSpread) > 0) {
            return false;  // Spread too wide, taker cost too high
        }

        // When edge is low AND spread is tight, use taker mode
        // The exact probability (~39% overall) emerges from these conditions being met
        log.debug("GABAGOOL: Taker mode triggered - edge={}, upSpread={}, downSpread={}",
                completeSetEdge, upSpread, downSpread);
        return true;
    }

    /**
     * Place a taker order (cross the spread) to get immediate fill.
     * Used when edge is low and we need to capture fleeting opportunity.
     */
    private void maybeTakeToken(GabagoolMarket market, String tokenId, Direction direction,
                                 TopOfBook book, TopOfBook otherBook, GabagoolConfig cfg,
                                 long secondsToEnd, double sizeFactor) {
        if (tokenId == null || tokenId.isBlank() || book == null) {
            return;
        }

        BigDecimal bestAsk = book.bestAsk();
        if (bestAsk == null || bestAsk.compareTo(BigDecimal.valueOf(0.99)) > 0) {
            return;  // Ask too high, don't take
        }

        BigDecimal shares = calculateReplicaShares(market, bestAsk, cfg, secondsToEnd);
        if (shares == null) {
            return;
        }

        // Apply directional bias factor to sizing
        if (sizeFactor != 1.0 && sizeFactor > 0) {
            shares = shares.multiply(BigDecimal.valueOf(sizeFactor)).setScale(2, RoundingMode.DOWN);
            if (shares.compareTo(BigDecimal.valueOf(0.01)) < 0) {
                return;
            }
        }

        // Don't place if we already have an open order on this token
        OrderState existing = ordersByTokenId.get(tokenId);
        if (existing != null) {
            // Cancel existing maker order before placing taker order
            long ageMillis = Duration.between(existing.placedAt(), clock.instant()).toMillis();
            if (ageMillis < cfg.minReplaceMillis()) {
                return;  // Too soon to replace
            }
            safeCancel(existing, CancelReason.REPLACE_PRICE, secondsToEnd, book, otherBook);
            ordersByTokenId.remove(tokenId);
        }

        String otherTokenId = direction == Direction.UP ? market.downTokenId() : market.upTokenId();

        try {
            log.info("GABAGOOL: TAKER {} order on {} at ask {} (size={}, secondsToEnd={})",
                    direction, market.slug(), bestAsk, shares, secondsToEnd);

            LimitOrderRequest request = new LimitOrderRequest(
                    tokenId,
                    OrderSide.BUY,
                    bestAsk,  // Cross the spread (taker price)
                    shares,
                    ClobOrderType.GTC,
                    null, null, null, null, null, null, null
            );

            OrderSubmissionResult result = executorApi.placeLimitOrder(request);
            String orderId = resolveOrderId(result);

            if (orderId != null) {
                ordersByTokenId.put(tokenId, new OrderState(
                        orderId, market, tokenId, direction, bestAsk, shares,
                        clock.instant(), BigDecimal.ZERO, null, secondsToEnd
                ));
            }

            publishOrderEvent(new OrderLifecycleEvent(
                    "gabagool-directional", runId, "PLACE", PlaceReason.TAKER.name(),
                    market.slug(), market.marketType(), tokenId, direction.name(),
                    secondsToEnd, null, orderId != null, orderId == null ? "orderId null" : null,
                    orderId, bestAsk, shares, null, null, null, null, null,
                    book, otherTokenId, otherBook
            ));
        } catch (Exception e) {
            log.error("GABAGOOL: Failed to place TAKER {} order on {}: {}", direction, market.slug(), e.getMessage());
            publishOrderEvent(new OrderLifecycleEvent(
                    "gabagool-directional", runId, "PLACE", PlaceReason.TAKER.name(),
                    market.slug(), market.marketType(), tokenId, direction.name(),
                    secondsToEnd, null, false, truncateError(e),
                    null, bestAsk, shares, null, null, null, null, null,
                    book, otherTokenId, otherBook
            ));
        }
    }

    /**
     * Replica-style quoting with inventory skew: place/maintain a single maker-like BUY order per token.
     *
     * @param skewTicks Positive = improve quote (bid higher), Negative = penalize quote (bid lower)
     * @param sizeFactor Directional bias multiplier (e.g., 1.5 for favored side, 0.67 for unfavored)
     */
    private void maybeQuoteTokenWithSkew(GabagoolMarket market, String tokenId, Direction direction,
                                          TopOfBook book, TopOfBook otherBook, GabagoolConfig cfg,
                                          long secondsToEnd, int skewTicks, double sizeFactor) {
        if (tokenId == null || tokenId.isBlank() || book == null) {
            return;
        }

        BigDecimal tickSize = getTickSize(tokenId);
        if (tickSize == null) {
            return;
        }

        BigDecimal entryPrice = calculateMakerEntryPriceWithSkew(book, tickSize, cfg, skewTicks);
        if (entryPrice == null) {
            return;
        }

        BigDecimal shares = calculateReplicaShares(market, entryPrice, cfg, secondsToEnd);
        if (shares == null) {
            return;
        }

        // Apply directional bias factor to sizing
        if (sizeFactor != 1.0 && sizeFactor > 0) {
            shares = shares.multiply(BigDecimal.valueOf(sizeFactor)).setScale(2, RoundingMode.DOWN);
            if (shares.compareTo(BigDecimal.valueOf(0.01)) < 0) {
                return;  // Too small after applying bias
            }
        }

        OrderState existing = ordersByTokenId.get(tokenId);
        if (existing != null) {
            long ageMillis = Duration.between(existing.placedAt(), clock.instant()).toMillis();
            if (ageMillis < cfg.minReplaceMillis()) {
                return;
            }

            boolean samePrice = existing.price() != null && existing.price().compareTo(entryPrice) == 0;
            boolean sameSize = existing.size() != null && existing.size().compareTo(shares) == 0;
            if (samePrice && sameSize) {
                return;
            }

            CancelReason reason = (!samePrice && !sameSize)
                    ? CancelReason.REPLACE_PRICE_AND_SIZE
                    : (!samePrice ? CancelReason.REPLACE_PRICE : CancelReason.REPLACE_SIZE);
            safeCancel(existing, reason, secondsToEnd, book, otherBook);
            ordersByTokenId.remove(tokenId);
        }

        placeDirectionalOrder(market, tokenId, direction, entryPrice, shares, secondsToEnd, tickSize, book, otherBook, existing);
    }

    /**
     * Taker top-up: Cross the spread on the lagging leg to rebalance inventory near market end.
     * This ensures we end up with balanced UP/DOWN positions for complete-set settlement.
     */
    private void maybeTopUpLaggingLeg(GabagoolMarket market, String tokenId, Direction direction,
                                       TopOfBook book, TopOfBook otherBook, GabagoolConfig cfg,
                                       long secondsToEnd, BigDecimal imbalanceShares) {
        if (tokenId == null || tokenId.isBlank() || book == null) {
            return;
        }

        BigDecimal bestAsk = book.bestAsk();
        if (bestAsk == null || bestAsk.compareTo(BigDecimal.valueOf(0.99)) > 0) {
            return;
        }

        // Calculate top-up size: fill up to the imbalance, but respect caps
        BigDecimal topUpShares = imbalanceShares.min(cfg.completeSetTopUpMinShares().multiply(BigDecimal.valueOf(2)));

        // Check exposure caps
        BigDecimal notional = topUpShares.multiply(bestAsk);
        BigDecimal bankrollUsd = cfg.bankrollUsd();
        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (cfg.maxOrderBankrollFraction() > 0) {
                BigDecimal perOrderCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxOrderBankrollFraction()));
                if (notional.compareTo(perOrderCap) > 0) {
                    topUpShares = perOrderCap.divide(bestAsk, 2, RoundingMode.DOWN);
                }
            }
        }

        if (topUpShares.compareTo(BigDecimal.valueOf(0.01)) < 0) {
            return;
        }

        // Don't top-up if we already have an open order on this token
        if (ordersByTokenId.containsKey(tokenId)) {
            return;
        }

        log.info("GABAGOOL: TOP-UP {} on {} at ask {} (imbalance={}, topUpShares={}, secondsToEnd={})",
                direction, market.slug(), bestAsk, imbalanceShares, topUpShares, secondsToEnd);

        String otherTokenId = direction == Direction.UP ? market.downTokenId() : market.upTokenId();

        try {
            LimitOrderRequest request = new LimitOrderRequest(
                    tokenId,
                    OrderSide.BUY,
                    bestAsk,  // Cross the spread (taker-like)
                    topUpShares,
                    ClobOrderType.GTC,
                    null, null, null, null, null, null, null
            );

            OrderSubmissionResult result = executorApi.placeLimitOrder(request);
            String orderId = resolveOrderId(result);

            if (orderId != null) {
                ordersByTokenId.put(tokenId, new OrderState(
                        orderId, market, tokenId, direction, bestAsk, topUpShares,
                        clock.instant(), BigDecimal.ZERO, null, secondsToEnd
                ));
            }

            publishOrderEvent(new OrderLifecycleEvent(
                    "gabagool-directional", runId, "PLACE", PlaceReason.TOP_UP.name(),
                    market.slug(), market.marketType(), tokenId, direction.name(),
                    secondsToEnd, null, orderId != null, orderId == null ? "orderId null" : null,
                    orderId, bestAsk, topUpShares, null, null, null, null, null,
                    book, otherTokenId, otherBook
            ));
        } catch (Exception e) {
            log.error("GABAGOOL: Failed to top-up {} on {}: {}", direction, market.slug(), e.getMessage());
            publishOrderEvent(new OrderLifecycleEvent(
                    "gabagool-directional", runId, "PLACE", PlaceReason.TOP_UP.name(),
                    market.slug(), market.marketType(), tokenId, direction.name(),
                    secondsToEnd, null, false, truncateError(e),
                    null, bestAsk, topUpShares, null, null, null, null, null,
                    book, otherTokenId, otherBook
            ));
        }
    }

    /**
     * Calculate maker entry price with inventory skew adjustment.
     *
     * @param skewTicks Positive = bid more aggressively (higher price), Negative = bid less aggressively
     */
    private BigDecimal calculateMakerEntryPriceWithSkew(TopOfBook book, BigDecimal tickSize, GabagoolConfig cfg, int skewTicks) {
        BigDecimal bestBid = book.bestBid();
        BigDecimal bestAsk = book.bestAsk();

        if (bestBid == null || bestAsk == null) {
            return null;
        }

        BigDecimal mid = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal spread = bestAsk.subtract(bestBid);

        // Base improve ticks + skew adjustment
        int effectiveImproveTicks = cfg.improveTicks() + skewTicks;

        BigDecimal entryPrice;
        if (spread.compareTo(BigDecimal.valueOf(0.20)) >= 0) {
            // When the book is extremely wide (often 0.01/0.99 in these markets), quoting off the
            // displayed bestBid yields orders that never fill. In that case, quote near mid.
            entryPrice = mid.subtract(tickSize.multiply(BigDecimal.valueOf(Math.max(0, cfg.improveTicks() - skewTicks))));
        } else {
            // Place order at bid + N ticks (improve the bid slightly) but never above mid.
            BigDecimal improvedBid = bestBid.add(tickSize.multiply(BigDecimal.valueOf(effectiveImproveTicks)));
            entryPrice = improvedBid.min(mid);
        }

        // Round to tick
        entryPrice = roundToTick(entryPrice, tickSize, RoundingMode.DOWN);

        // Sanity checks
        if (entryPrice.compareTo(BigDecimal.valueOf(0.01)) < 0) {
            return null;
        }
        if (entryPrice.compareTo(BigDecimal.valueOf(0.99)) > 0) {
            return null;
        }
        // Never cross the spread
        if (entryPrice.compareTo(bestAsk) >= 0) {
            entryPrice = bestAsk.subtract(tickSize);
            if (entryPrice.compareTo(BigDecimal.valueOf(0.01)) < 0) {
                return null;
            }
        }

        return entryPrice;
    }

    /**
     * Place a directional order.
     */
    private void placeDirectionalOrder(GabagoolMarket market, String tokenId, Direction direction,
                                       BigDecimal price, BigDecimal size, long secondsToEnd, BigDecimal tickSize, TopOfBook book, TopOfBook otherBook, OrderState replaced) {
        String placeReason = replaced == null ? PlaceReason.QUOTE.name() : PlaceReason.REPLACE.name();
        String replacedOrderId = replaced == null ? null : replaced.orderId();
        BigDecimal replacedPrice = replaced == null ? null : replaced.price();
        BigDecimal replacedSize = replaced == null ? null : replaced.size();
        Long replacedAgeMillis = replaced == null ? null : Duration.between(replaced.placedAt(), clock.instant()).toMillis();

        String otherTokenId = null;
        if (market != null && direction != null) {
            otherTokenId = direction == Direction.UP ? market.downTokenId() : market.upTokenId();
        }

        try {
            log.info("GABAGOOL: Placing {} order on {} at {} (size={}, secondsToEnd={})",
                    direction, market.slug(), price, size, secondsToEnd);

            LimitOrderRequest request = new LimitOrderRequest(
                    tokenId,
                    OrderSide.BUY,
                    price,
                    size,
                    ClobOrderType.GTC,
                    null,  // tickSize
                    null,  // negRisk
                    null,  // feeRateBps
                    null,  // nonce
                    null,  // expirationSeconds
                    null,  // taker
                    null   // deferExec
            );

            OrderSubmissionResult result = executorApi.placeLimitOrder(request);
            String orderId = resolveOrderId(result);
            if (orderId == null) {
                log.warn("GABAGOOL: Order submission returned null orderId for {}", market.slug());
                publishOrderEvent(
                        new OrderLifecycleEvent(
                                "gabagool-directional",
                                runId,
                                "PLACE",
                                placeReason,
                                market.slug(),
                                market.marketType(),
                                tokenId,
                                direction.name(),
                                secondsToEnd,
                                tickSize,
                                false,
                                "orderId null",
                                null,
                                price,
                                size,
                                replacedOrderId,
                                replacedPrice,
                                replacedSize,
                                replacedAgeMillis,
                                null,
                                book,
                                otherTokenId,
                                otherBook
                        )
                );
                return;
            }

            ordersByTokenId.put(tokenId, new OrderState(
                    orderId,
                    market,
                    tokenId,
                    direction,
                    price,
                    size,
                    clock.instant(),
                    BigDecimal.ZERO,
                    null,
                    secondsToEnd
            ));

            log.info("GABAGOOL: Order placed successfully: {} (direction={}, price={}, size={})",
                    orderId, direction, price, size);

            publishOrderEvent(
                    new OrderLifecycleEvent(
                            "gabagool-directional",
                            runId,
                            "PLACE",
                            placeReason,
                            market.slug(),
                            market.marketType(),
                            tokenId,
                            direction.name(),
                            secondsToEnd,
                            tickSize,
                            true,
                            null,
                            orderId,
                            price,
                            size,
                            replacedOrderId,
                            replacedPrice,
                            replacedSize,
                            replacedAgeMillis,
                            null,
                            book,
                            otherTokenId,
                            otherBook
                    )
            );
        } catch (Exception e) {
            log.error("GABAGOOL: Failed to place order on {}: {}", market.slug(), e.getMessage());
            publishOrderEvent(
                    new OrderLifecycleEvent(
                            "gabagool-directional",
                            runId,
                            "PLACE",
                            placeReason,
                            market.slug(),
                            market.marketType(),
                            tokenId,
                            direction.name(),
                            secondsToEnd,
                            tickSize,
                            false,
                            truncateError(e),
                            null,
                            price,
                            size,
                            replacedOrderId,
                            replacedPrice,
                            replacedSize,
                            replacedAgeMillis,
                            null,
                            book,
                            otherTokenId,
                            otherBook
                    )
            );
        }
    }

    /**
     * Check pending orders for fills or timeout.
     */
    private void checkPendingOrders() {
        Instant now = clock.instant();

        for (Map.Entry<String, OrderState> entry : ordersByTokenId.entrySet()) {
            String tokenId = entry.getKey();
            OrderState state = entry.getValue();
            if (state == null) {
                continue;
            }

            refreshOrderStatusIfDue(tokenId, state, now);
            state = ordersByTokenId.get(tokenId);
            if (state == null) {
                continue;
            }

            Duration pendingTime = Duration.between(state.placedAt(), now);
            if (pendingTime.compareTo(ORDER_STALE_TIMEOUT) > 0) {
                log.info("GABAGOOL: Cancelling stale order {} tokenId={} after {}s", state.orderId(), tokenId, pendingTime.getSeconds());
                Long secondsToEndNow = state.market() == null ? null : Duration.between(now, state.market().endTime()).getSeconds();
                safeCancel(state, CancelReason.STALE_TIMEOUT, secondsToEndNow, null, null);
                ordersByTokenId.remove(tokenId);
            }
        }
    }

    private void refreshOrderStatusIfDue(String tokenId, OrderState state, Instant now) {
        if (properties.mode() != HftProperties.TradingMode.LIVE) {
            return;
        }
        if (state == null || state.orderId() == null || state.orderId().isBlank()) {
            return;
        }
        if (now == null) {
            now = clock.instant();
        }
        if (state.lastStatusCheckAt() != null && Duration.between(state.lastStatusCheckAt(), now).compareTo(ORDER_STATUS_POLL_INTERVAL) < 0) {
            return;
        }

        JsonNode order;
        try {
            order = executorApi.getOrder(state.orderId());
        } catch (Exception e) {
            // Don't drop the order immediately; staleness timeout still applies.
            ordersByTokenId.put(tokenId, new OrderState(
                    state.orderId(),
                    state.market(),
                    state.tokenId(),
                    state.direction(),
                    state.price(),
                    state.size(),
                    state.placedAt(),
                    state.matchedSize(),
                    now,
                    state.secondsToEndAtEntry()
            ));
            return;
        }

        String mode = order == null ? null : order.path("mode").asText(null);
        if (mode != null && "PAPER".equalsIgnoreCase(mode)) {
            return;
        }

        String status = firstText(order, "status", "state", "order_status", "orderStatus");
        BigDecimal matched = firstDecimal(order, "matched_size", "matchedSize", "size_matched", "sizeMatched", "filled_size", "filledSize", "size_filled", "sizeFilled");
        BigDecimal remaining = firstDecimal(order, "remaining_size", "remainingSize", "size_remaining", "sizeRemaining");
        if (remaining == null && matched != null && state.size() != null) {
            remaining = state.size().subtract(matched);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                remaining = BigDecimal.ZERO;
            }
        }

        BigDecimal prevMatched = state.matchedSize() == null ? BigDecimal.ZERO : state.matchedSize();
        if (matched != null && matched.compareTo(prevMatched) > 0 && state.price() != null) {
            BigDecimal delta = matched.subtract(prevMatched);
            BigDecimal deltaNotional = delta.multiply(state.price());
            fillsSincePositionsRefreshByTokenId.merge(state.tokenId(), deltaNotional, BigDecimal::add);
            fillsSincePositionsRefreshNotionalUsd.set(fillsSincePositionsRefreshNotionalUsd.get().add(deltaNotional));

            // Update per-market inventory for complete-set tracking
            if (state.market() != null && state.direction() != null) {
                String slug = state.market().slug();
                inventoryByMarket.compute(slug, (k, inv) -> {
                    MarketInventory current = inv == null ? new MarketInventory(BigDecimal.ZERO, BigDecimal.ZERO) : inv;
                    if (state.direction() == Direction.UP) {
                        return current.addUp(delta);
                    } else {
                        return current.addDown(delta);
                    }
                });
                log.debug("GABAGOOL: Updated inventory for {} after fill: {} +{} shares (new inventory: {})",
                        slug, state.direction(), delta, inventoryByMarket.get(slug));
            }
        }

        if (isTerminalOrderStatus(status, matched, remaining, state.size())) {
            ordersByTokenId.remove(tokenId);
            return;
        }

        ordersByTokenId.put(tokenId, new OrderState(
                state.orderId(),
                state.market(),
                state.tokenId(),
                state.direction(),
                state.price(),
                state.size(),
                state.placedAt(),
                matched != null ? matched : prevMatched,
                now,
                state.secondsToEndAtEntry()
        ));
    }

    private static boolean isTerminalOrderStatus(String status, BigDecimal matched, BigDecimal remaining, BigDecimal requestedSize) {
        if (remaining != null && remaining.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        if (matched != null && requestedSize != null && matched.compareTo(requestedSize) >= 0) {
            return true;
        }
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        return s.contains("FILLED")
                || s.contains("CANCELED")
                || s.contains("CANCELLED")
                || s.contains("EXPIRED")
                || s.contains("REJECTED")
                || s.contains("FAILED")
                || s.contains("DONE")
                || s.contains("CLOSED");
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            String s = v.asText(null);
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... keys) {
        if (node == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            try {
                if (v.isNumber()) {
                    return v.decimalValue();
                }
                String s = v.asText(null);
                if (s == null || s.isBlank()) {
                    continue;
                }
                return new BigDecimal(s.trim());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void cancelMarketOrders(GabagoolMarket market, CancelReason reason, Long secondsToEndNow) {
        if (market == null) {
            return;
        }
        cancelTokenOrder(market.upTokenId(), reason, secondsToEndNow, null, null);
        cancelTokenOrder(market.downTokenId(), reason, secondsToEndNow, null, null);
    }

    private void cancelTokenOrder(String tokenId, CancelReason reason, Long secondsToEndNow, TopOfBook book, TopOfBook otherBook) {
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }
        OrderState state = ordersByTokenId.remove(tokenId);
        safeCancel(state, reason, secondsToEndNow, book, otherBook);
    }

    /**
     * Discover Up/Down markets from discovery service and config.
     */
    private void discoverMarkets() {
        try {
            List<GabagoolMarket> markets = new ArrayList<>();

            // Get markets from discovery service
            List<GabagoolMarketDiscovery.DiscoveredMarket> discovered = marketDiscovery.getActiveMarkets();
            for (GabagoolMarketDiscovery.DiscoveredMarket d : discovered) {
                markets.add(new GabagoolMarket(
                        d.slug(),
                        d.upTokenId(),
                        d.downTokenId(),
                        d.endTime(),
                        d.marketType()
                ));
            }

            // Also add any statically configured markets
            GabagoolConfig cfg = getConfig();
            if (cfg.markets() != null) {
                for (GabagoolMarketConfig m : cfg.markets()) {
                    if (m.upTokenId() != null && m.downTokenId() != null) {
                        Instant endTime = m.endTime() != null ? m.endTime() : clock.instant().plus(Duration.ofMinutes(15));
                        // Avoid duplicates
                        String upToken = m.upTokenId();
                        boolean exists = markets.stream().anyMatch(existing -> existing.upTokenId().equals(upToken));
                        if (!exists) {
                            markets.add(new GabagoolMarket(
                                    m.slug() != null ? m.slug() : "configured",
                                    m.upTokenId(),
                                    m.downTokenId(),
                                    endTime,
                                    "unknown"
                            ));
                        }
                    }
                }
            }

            activeMarkets.set(markets);

            // Ensure the market WS is subscribed to the active token ids.
            List<String> assetIds = markets.stream()
                    .flatMap(m -> Stream.of(m.upTokenId(), m.downTokenId()))
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            if (!assetIds.isEmpty()) {
                marketWs.subscribeAssets(assetIds);
            }

            if (!markets.isEmpty()) {
                log.debug("GABAGOOL: Tracking {} markets ({} discovered, {} configured)",
                        markets.size(), discovered.size(), cfg.markets() != null ? cfg.markets().size() : 0);
            }
        } catch (Exception e) {
            log.error("GABAGOOL: Error discovering markets: {}", e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private GabagoolConfig getConfig() {
        HftProperties.Gabagool cfg = properties.strategy().gabagool();

        List<GabagoolMarketConfig> marketConfigs = new ArrayList<>();
        if (cfg.markets() != null) {
            for (HftProperties.GabagoolMarket m : cfg.markets()) {
                Instant endTime = null;
                if (m.endTime() != null && !m.endTime().isBlank()) {
                    try {
                        endTime = Instant.parse(m.endTime());
                    } catch (Exception e) {
                        log.warn("Failed to parse endTime '{}': {}", m.endTime(), e.getMessage());
                    }
                }
                marketConfigs.add(new GabagoolMarketConfig(
                        m.slug(),
                        m.upTokenId(),
                        m.downTokenId(),
                        endTime
                ));
            }
        }

        return new GabagoolConfig(
                cfg.enabled(),
                cfg.refreshMillis(),
                cfg.minReplaceMillis(),
                cfg.minSecondsToEnd(),
                cfg.maxSecondsToEnd(),
                cfg.quoteSize(),
                cfg.quoteSizeBankrollFraction(),
                cfg.imbalanceThreshold(),
                cfg.improveTicks(),
                cfg.bankrollUsd(),
                cfg.maxOrderBankrollFraction(),
                cfg.maxTotalBankrollFraction(),
                // Complete-set coordination parameters
                cfg.completeSetMinEdge(),
                cfg.completeSetMaxSkewTicks(),
                cfg.completeSetImbalanceSharesForMaxSkew(),
                cfg.completeSetTopUpEnabled(),
                cfg.completeSetTopUpSecondsToEnd(),
                cfg.completeSetTopUpMinShares(),
                // Directional bias parameters
                cfg.directionalBiasEnabled(),
                cfg.directionalBiasFactor(),
                // Taker mode parameters
                cfg.takerModeEnabled(),
                cfg.takerModeMaxEdge(),
                cfg.takerModeMaxSpread(),
                marketConfigs
        );
    }

    private BigDecimal calculateMid(TopOfBook book) {
        if (book == null || book.bestBid() == null || book.bestAsk() == null) {
            return null;
        }
        return book.bestBid().add(book.bestAsk()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private Double calculateImbalance(TopOfBook book) {
        if (book == null || book.bestBid() == null || book.bestAsk() == null) {
            return null;
        }

        BigDecimal bidSize = book.bestBidSize();
        BigDecimal askSize = book.bestAskSize();
        if (bidSize != null && askSize != null) {
            BigDecimal total = bidSize.add(askSize);
            if (total.signum() == 0) {
                return 0.0;
            }
            return bidSize.subtract(askSize)
                    .divide(total, 8, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        // Fallback proxy (if the WS event doesn't include sizes).
        BigDecimal mid = book.bestBid().add(book.bestAsk()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return mid.subtract(BigDecimal.valueOf(0.5)).doubleValue() * 2;
    }

    private BigDecimal getTickSize(String tokenId) {
        TickSizeEntry cached = tickSizeCache.get(tokenId);
        if (cached != null && Duration.between(cached.fetchedAt(), clock.instant()).compareTo(TICK_SIZE_CACHE_TTL) < 0) {
            return cached.tickSize();
        }

        try {
            BigDecimal tickSize = executorApi.getTickSize(tokenId);
            tickSizeCache.put(tokenId, new TickSizeEntry(tickSize, clock.instant()));
            return tickSize;
        } catch (Exception e) {
            log.warn("Failed to get tick size for {}: {}", tokenId, e.getMessage());
            return BigDecimal.valueOf(0.01); // Default
        }
    }

    private boolean isStale(TopOfBook tob) {
        if (tob == null || tob.updatedAt() == null) {
            return true;
        }
        Duration age = Duration.between(tob.updatedAt(), clock.instant());
        return age.toMillis() > 2_000;
    }

    private static BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize, RoundingMode mode) {
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        BigDecimal ticks = value.divide(tickSize, 0, mode);
        return ticks.multiply(tickSize);
    }

    private static BigDecimal calculateSharesFromNotional(BigDecimal notionalUsd, BigDecimal price) {
        if (notionalUsd == null || notionalUsd.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // Use 2 decimals to match Polymarket sizing constraints (see PolymarketOrderBuilder).
        BigDecimal shares = notionalUsd.divide(price, 2, RoundingMode.DOWN);
        if (shares.compareTo(BigDecimal.valueOf(0.01)) < 0) {
            return null;
        }
        return shares;
    }

    private static BigDecimal baseReplicaShares(GabagoolMarket market) {
        if (market == null || market.slug() == null) {
            return null;
        }
        String slug = market.slug();
        // Empirical mode sizes (shares) from gabagool22 fills:
        // - btc-updown-15m: 20
        // - eth-updown-15m: 14
        // - bitcoin-up-or-down: 18
        // - ethereum-up-or-down: 14
        if (slug.startsWith("btc-updown-15m-")) {
            return BigDecimal.valueOf(20);
        }
        if (slug.startsWith("eth-updown-15m-")) {
            return BigDecimal.valueOf(14);
        }
        if (slug.startsWith("bitcoin-up-or-down-")) {
            return BigDecimal.valueOf(18);
        }
        if (slug.startsWith("ethereum-up-or-down-")) {
            return BigDecimal.valueOf(14);
        }
        return null;
    }

    /**
     * Calculate size reduction factor based on time remaining to market end.
     * Based on empirical analysis of gabagool22's sizing behavior:
     * - Near expiry (<60s): ~45% of target size (reduces risk exposure)
     * - 1-3 min: ~55%
     * - 3-5 min: ~67%
     * - 5-10 min: ~75%
     * - >10 min: 100% (full target)
     */
    private static double calculateTimeToEndSizeFactor(long secondsToEnd) {
        if (secondsToEnd < 60) {
            // < 1 minute: 45%
            return 0.45;
        } else if (secondsToEnd < 180) {
            // 1-3 minutes: 55%
            return 0.55;
        } else if (secondsToEnd < 300) {
            // 3-5 minutes: 67%
            return 0.67;
        } else if (secondsToEnd < 600) {
            // 5-10 minutes: 75%
            return 0.75;
        } else {
            // > 10 minutes: full size
            return 1.0;
        }
    }

    private BigDecimal calculateReplicaShares(GabagoolMarket market, BigDecimal entryPrice, GabagoolConfig cfg, long secondsToEnd) {
        BigDecimal baseShares = baseReplicaShares(market);
        if (baseShares == null) {
            // Fallback to existing notional-based sizing if we don't recognize the market family.
            BigDecimal notionalUsd = calculateNotionalUsd(cfg);
            return notionalUsd == null ? null : calculateSharesFromNotional(notionalUsd, entryPrice);
        }

        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // Apply time-to-end sizing adjustment (based on gabagool22's empirical behavior):
        // Near expiry he trades smaller to reduce risk exposure.
        // Empirical observations:
        //   < 1 min to end: ~9 shares avg (45% of target)
        //   1-3 min: ~11 shares avg (55%)
        //   3-5 min: ~13.5 shares avg (67%)
        //   5-10 min: ~15 shares avg (75%)
        //   > 10 min: full target size
        double sizeFactor = calculateTimeToEndSizeFactor(secondsToEnd);
        BigDecimal shares = baseShares.multiply(BigDecimal.valueOf(sizeFactor));

        // Apply optional per-order and total exposure caps using bankroll configuration.
        BigDecimal bankrollUsd = cfg.bankrollUsd();
        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (cfg.maxOrderBankrollFraction() > 0) {
                BigDecimal perOrderCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxOrderBankrollFraction()));
                BigDecimal capShares = perOrderCap.divide(entryPrice, 2, RoundingMode.DOWN);
                shares = shares.min(capShares);
            }
            if (cfg.maxTotalBankrollFraction() > 0) {
                BigDecimal totalCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxTotalBankrollFraction()));
                BigDecimal open = currentExposureNotionalUsd();
                BigDecimal remaining = totalCap.subtract(open);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    return null;
                }
                BigDecimal capShares = remaining.divide(entryPrice, 2, RoundingMode.DOWN);
                shares = shares.min(capShares);
            }
        }

        // Global per-order cap from risk config (USDC notional).
        BigDecimal maxNotionalUsd = properties.risk().maxOrderNotionalUsd();
        if (maxNotionalUsd != null && maxNotionalUsd.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal capShares = maxNotionalUsd.divide(entryPrice, 2, RoundingMode.DOWN);
            shares = shares.min(capShares);
        }

        shares = shares.setScale(2, RoundingMode.DOWN);
        if (shares.compareTo(BigDecimal.valueOf(0.01)) < 0) {
            return null;
        }
        return shares;
    }

    private BigDecimal calculateNotionalUsd(GabagoolConfig cfg) {
        if (cfg == null) {
            return null;
        }

        BigDecimal maxNotionalUsd = properties.risk().maxOrderNotionalUsd();
        BigDecimal bankrollUsd = cfg.bankrollUsd();
        BigDecimal notional;
        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0 && cfg.quoteSizeBankrollFraction() > 0) {
            notional = bankrollUsd.multiply(BigDecimal.valueOf(cfg.quoteSizeBankrollFraction()));
        } else {
            notional = cfg.quoteSize();
        }

        if (notional == null || notional.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        if (maxNotionalUsd != null && maxNotionalUsd.compareTo(BigDecimal.ZERO) > 0) {
            notional = notional.min(maxNotionalUsd);
        }

        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (cfg.maxOrderBankrollFraction() > 0) {
                BigDecimal perOrderCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxOrderBankrollFraction()));
                notional = notional.min(perOrderCap);
            }
            if (cfg.maxTotalBankrollFraction() > 0) {
                BigDecimal totalCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxTotalBankrollFraction()));
                BigDecimal open = currentExposureNotionalUsd();
                BigDecimal remaining = totalCap.subtract(open);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    return null;
                }
                notional = notional.min(remaining);
            }
        }

        return notional.compareTo(BigDecimal.ZERO) > 0 ? notional : null;
    }

    private BigDecimal currentExposureNotionalUsd() {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderState o : ordersByTokenId.values()) {
            if (o == null || o.price() == null || o.size() == null) {
                continue;
            }
            BigDecimal matched = o.matchedSize() == null ? BigDecimal.ZERO : o.matchedSize();
            BigDecimal remainingShares = o.size().subtract(matched);
            if (remainingShares.compareTo(BigDecimal.ZERO) < 0) {
                remainingShares = BigDecimal.ZERO;
            }
            total = total.add(o.price().multiply(remainingShares));
        }
        PositionsCache cache = positionsCache.get();
        BigDecimal positionsNotional = cache == null || cache.openNotionalUsd() == null ? BigDecimal.ZERO : cache.openNotionalUsd();
        BigDecimal fillsNotional = fillsSincePositionsRefreshNotionalUsd.get();
        if (fillsNotional == null) {
            fillsNotional = BigDecimal.ZERO;
        }
        return total.add(positionsNotional).add(fillsNotional);
    }

    private void safeCancel(OrderState state, CancelReason reason, Long secondsToEndNow, TopOfBook book, TopOfBook otherBook) {
        if (state == null || state.orderId() == null || state.orderId().isBlank()) {
            return;
        }

        boolean success = false;
        String error = null;
        try {
            executorApi.cancelOrder(state.orderId());
            success = true;
        } catch (Exception e) {
            error = truncateError(e);
        }

        String otherTokenId = null;
        if (state.market() != null && state.direction() != null) {
            otherTokenId = state.direction() == Direction.UP ? state.market().downTokenId() : state.market().upTokenId();
        }

        publishOrderEvent(
                new OrderLifecycleEvent(
                        "gabagool-directional",
                        runId,
                        "CANCEL",
                        reason == null ? null : reason.name(),
                        state.market() == null ? null : state.market().slug(),
                        state.market() == null ? null : state.market().marketType(),
                        state.tokenId(),
                        state.direction() == null ? null : state.direction().name(),
                        secondsToEndNow,
                        null,
                        success,
                        error,
                        state.orderId(),
                        state.price(),
                        state.size(),
                        null,
                        null,
                        null,
                        null,
                        Duration.between(state.placedAt(), clock.instant()).toMillis(),
                        book,
                        otherTokenId,
                        otherBook
                )
        );
    }

    private static String resolveOrderId(OrderSubmissionResult result) {
        if (result == null) {
            return null;
        }
        JsonNode resp = result.clobResponse();
        if (resp != null) {
            if (resp.hasNonNull("orderID")) {
                return resp.get("orderID").asText();
            }
            if (resp.hasNonNull("orderId")) {
                return resp.get("orderId").asText();
            }
        }

        if (result.mode() == HftProperties.TradingMode.PAPER) {
            return "paper-" + UUID.randomUUID();
        }
        return null;
    }

    private void publishOrderEvent(OrderLifecycleEvent event) {
        try {
            if (!events.isEnabled()) {
                return;
            }
            String key = event != null && event.orderId() != null && !event.orderId().isBlank()
                    ? event.orderId()
                    : ("gabagool:" + (event == null ? "unknown" : event.marketSlug()) + ":" + (event == null ? "unknown" : event.tokenId()));
            events.publish(HftEventTypes.STRATEGY_GABAGOOL_ORDER, key, event);
        } catch (Exception e) {
            log.warn("Failed to publish order event: {}", e.getMessage());
        }
    }

    private static String truncateError(Throwable t) {
        if (t == null) {
            return null;
        }
        String s = t.toString();
        return s.length() <= ERROR_MAX_LEN ? s : s.substring(0, ERROR_MAX_LEN) + "...";
    }

    // ==================== Inner Types ====================

    public enum Direction {
        UP, DOWN
    }

    private enum PlaceReason {
        QUOTE,
        REPLACE,
        TOP_UP,  // Taker top-up for lagging leg rebalancing
        TAKER    // Aggressive taker order (cross the spread)
    }

    private enum CancelReason {
        BOOK_STALE,
        OUTSIDE_TIME_WINDOW,
        OUTSIDE_LIFETIME,
        REPLACE_PRICE,
        REPLACE_SIZE,
        REPLACE_PRICE_AND_SIZE,
        STALE_TIMEOUT,
        SHUTDOWN,
        INSUFFICIENT_EDGE  // Complete-set edge below minimum threshold
    }

    /**
     * Tracks per-market inventory for complete-set coordination.
     * Used to detect imbalance between UP and DOWN positions.
     */
    public record MarketInventory(
            BigDecimal upShares,
            BigDecimal downShares
    ) {
        public MarketInventory {
            if (upShares == null) upShares = BigDecimal.ZERO;
            if (downShares == null) downShares = BigDecimal.ZERO;
        }

        public BigDecimal imbalance() {
            return upShares.subtract(downShares);
        }

        public BigDecimal totalShares() {
            return upShares.add(downShares);
        }

        public MarketInventory addUp(BigDecimal shares) {
            return new MarketInventory(upShares.add(shares), downShares);
        }

        public MarketInventory addDown(BigDecimal shares) {
            return new MarketInventory(upShares, downShares.add(shares));
        }
    }

    public record OrderLifecycleEvent(
            String strategy,
            String runId,
            String action,
            String reason,
            String marketSlug,
            String marketType,
            String tokenId,
            String direction,
            Long secondsToEnd,
            BigDecimal tickSize,
            boolean success,
            String error,
            String orderId,
            BigDecimal price,
            BigDecimal size,
            String replacedOrderId,
            BigDecimal replacedPrice,
            BigDecimal replacedSize,
            Long replacedOrderAgeMillis,
            Long orderAgeMillis,
            TopOfBook book,
            String otherTokenId,
            TopOfBook otherBook
    ) {}

    public record GabagoolConfig(
            boolean enabled,
            long refreshMillis,
            long minReplaceMillis,
            long minSecondsToEnd,
            long maxSecondsToEnd,
            BigDecimal quoteSize,
            double quoteSizeBankrollFraction,
            double imbalanceThreshold,
            int improveTicks,
            BigDecimal bankrollUsd,
            double maxOrderBankrollFraction,
            double maxTotalBankrollFraction,
            // Complete-set coordination parameters
            double completeSetMinEdge,
            int completeSetMaxSkewTicks,
            BigDecimal completeSetImbalanceSharesForMaxSkew,
            boolean completeSetTopUpEnabled,
            long completeSetTopUpSecondsToEnd,
            BigDecimal completeSetTopUpMinShares,
            // Directional bias parameters (based on gabagool22's book imbalance trading)
            boolean directionalBiasEnabled,
            double directionalBiasFactor,
            // Taker mode parameters (gabagool22 takes ~39% of the time)
            boolean takerModeEnabled,
            double takerModeMaxEdge,
            BigDecimal takerModeMaxSpread,
            List<GabagoolMarketConfig> markets
    ) {}

    public record GabagoolMarketConfig(
            String slug,
            String upTokenId,
            String downTokenId,
            Instant endTime
    ) {}

    public record GabagoolMarket(
            String slug,
            String upTokenId,
            String downTokenId,
            Instant endTime,
            String marketType  // "updown-15m" or "up-or-down"
    ) {}

    public record OrderState(
            String orderId,
            GabagoolMarket market,
            String tokenId,
            Direction direction,
            BigDecimal price,
            BigDecimal size,
            Instant placedAt,
            BigDecimal matchedSize,
            Instant lastStatusCheckAt,
            long secondsToEndAtEntry
    ) {}

    public record TickSizeEntry(
            BigDecimal tickSize,
            Instant fetchedAt
    ) {}

    private record PositionsCache(
            Instant fetchedAt,
            Map<String, BigDecimal> openNotionalByTokenId,
            BigDecimal openNotionalUsd
    ) {}
}
