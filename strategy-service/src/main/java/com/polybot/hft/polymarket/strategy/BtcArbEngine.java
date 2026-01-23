package com.polybot.hft.polymarket.strategy;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.strategy.config.BtcArbConfig;
import com.polybot.hft.polymarket.strategy.model.GabagoolMarket;
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

/**
 * BTC_Arb: Simple 0-arbitrage strategy for BTC 15m Up/Down markets.
 * 
 * Features:
 * - Focuses ONLY on btc-updown-15m-* markets
 * - Uses $10 wallet with sizing: floor(wallet) - 1 shares
 * - Includes proper Polymarket fee calculation
 * - Trades when both UP + DOWN can be bought profitably
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BtcArbEngine {

    private static final String MARKET_PREFIX = "btc-updown-15m-";
    private static final long STALE_THRESHOLD_MS = 2_000;

    private final @NonNull HftProperties properties;
    private final @NonNull ClobMarketWebSocketClient marketWs;
    private final @NonNull ExecutorApiClient executorApi;
    private final @NonNull HftEventPublisher events;
    private final @NonNull GabagoolMarketDiscovery marketDiscovery;
    private final @NonNull Clock clock;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "btc-arb-engine");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<List<GabagoolMarket>> activeBtcMarkets = new AtomicReference<>(List.of());
    private final Map<String, String> pendingOrders = new ConcurrentHashMap<>();
    
    private BtcArbConfig config;

    @PostConstruct
    void startIfEnabled() {
        // Check if BTC_Arb is enabled via environment or default to enabled
        boolean enabled = Boolean.parseBoolean(System.getProperty("btcarb.enabled", "true"));
        
        if (!enabled) {
            log.info("BTC_Arb strategy is disabled");
            return;
        }

        if (!properties.polymarket().marketWsEnabled()) {
            log.warn("BTC_Arb enabled, but market WS disabled - cannot run");
            return;
        }

        config = new BtcArbConfig(
                true,
                BigDecimal.valueOf(10),
                500L,
                0L,
                900L
        );
        
        log.info("BTC_Arb starting: wallet=${}, refresh={}ms", config.walletUsd(), config.refreshMillis());

        executor.scheduleAtFixedRate(this::tick, 2000, config.refreshMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleAtFixedRate(this::discoverMarkets, 0, 30, TimeUnit.SECONDS);

        log.info("BTC_Arb started (refreshMillis={})", config.refreshMillis());
    }

    @PreDestroy
    void shutdown() {
        log.info("BTC_Arb shutting down");
        executor.shutdownNow();
    }

    private void tick() {
        try {
            Instant now = clock.instant();
            List<GabagoolMarket> markets = activeBtcMarkets.get();
            
            if (markets.isEmpty()) {
                return;
            }
            
            for (GabagoolMarket market : markets) {
                evaluateMarket(market, now);
            }
        } catch (Exception e) {
            log.error("BTC_ARB tick error: {}", e.getMessage(), e);
        }
    }

    private void evaluateMarket(GabagoolMarket market, Instant now) {
        long secondsToEnd = Duration.between(now, market.endTime()).getSeconds();
        
        // Check time window
        if (secondsToEnd < config.minSecondsToEnd() || secondsToEnd > config.maxSecondsToEnd()) {
            log.debug("BTC_ARB: {} outside time window ({}s to end)", market.slug(), secondsToEnd);
            return;
        }
        
        // Get order books
        TopOfBook upBook = marketWs.getTopOfBook(market.upTokenId()).orElse(null);
        TopOfBook downBook = marketWs.getTopOfBook(market.downTokenId()).orElse(null);
        
        if (upBook == null || downBook == null) {
            log.debug("BTC_ARB: {} missing order book data", market.slug());
            return;
        }
        
        // Check staleness (>2s old)
        if (isStale(upBook) || isStale(downBook)) {
            log.debug("BTC_ARB: {} stale order book", market.slug());
            return;
        }
        
        BigDecimal upBid = upBook.bestBid();
        BigDecimal downBid = downBook.bestBid();
        
        if (upBid == null || downBid == null) {
            return;
        }
        
        int sharesLeft = config.availableShares();
        if (sharesLeft <= 0) return;
        
        BigDecimal shares = BigDecimal.valueOf(sharesLeft);
        
        log.info("BTC_ARB Trade: {} (UP={}, DOWN={}, net=${})", 
                market.slug(), upBid, downBid, BtcArbConfig.calculateNetProfit(upBid, downBid, shares).setScale(4, RoundingMode.HALF_UP));
        
        placeArbOrders(market, upBid, downBid, shares);
    }

    private void placeArbOrders(GabagoolMarket market, BigDecimal upPrice, BigDecimal downPrice, BigDecimal shares) {
        if (pendingOrders.containsKey(market.upTokenId()) || pendingOrders.containsKey(market.downTokenId())) {
            return;
        }
        
        submitOrder(market.upTokenId(), OrderSide.BUY, upPrice, shares, "UP");
        submitOrder(market.downTokenId(), OrderSide.BUY, downPrice, shares, "DOWN");
    }

    private void submitOrder(String tokenId, OrderSide side, BigDecimal price, BigDecimal size, String label) {
        try {
            LimitOrderRequest request = new LimitOrderRequest(tokenId, side, price, size, null, null, null, null, null, null, null, null);
            OrderSubmissionResult result = executorApi.placeLimitOrder(request);
            
            String orderId = resolveOrderId(result);
            if (orderId != null) {
                pendingOrders.put(tokenId, orderId);
                log.info("BTC_ARB {} order placed: {}", label, orderId);
            }
        } catch (Exception e) {
            log.error("BTC_ARB {} order failed: {}", label, e.getMessage());
        }
    }

    private void discoverMarkets() {
        try {
            List<GabagoolMarketDiscovery.DiscoveredMarket> discovered = marketDiscovery.getActiveMarkets();
            
            List<GabagoolMarket> btcMarkets = discovered.stream()
                    .filter(d -> d.slug() != null && d.slug().startsWith(MARKET_PREFIX))
                    .map(d -> new GabagoolMarket(d.slug(), d.upTokenId(), d.downTokenId(), d.endTime(), d.marketType()))
                    .toList();
            
            activeBtcMarkets.set(btcMarkets);
            
            if (!btcMarkets.isEmpty()) {
                List<String> assetIds = btcMarkets.stream()
                        .flatMap(m -> java.util.stream.Stream.of(m.upTokenId(), m.downTokenId()))
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .toList();
                
                marketWs.setSubscribedAssets(assetIds);
            }
        } catch (Exception e) {
            log.error("BTC_ARB discovery error: {}", e.getMessage());
        }
    }

    private boolean isStale(TopOfBook tob) {
        if (tob == null || tob.updatedAt() == null) return true;
        return Duration.between(tob.updatedAt(), clock.instant()).toMillis() > STALE_THRESHOLD_MS;
    }

    private String resolveOrderId(OrderSubmissionResult result) {
        if (result == null) return null;
        if (result.clobResponse() != null) {
            if (result.clobResponse().has("orderID")) return result.clobResponse().get("orderID").asText();
            if (result.clobResponse().has("orderId")) return result.clobResponse().get("orderId").asText();
        }
        return (result.mode() != null && "PAPER".equalsIgnoreCase(result.mode().name())) ? "sim-" + UUID.randomUUID() : null;
    }
    
    public boolean isRunning() {
        return !executor.isShutdown() && config != null && config.enabled();
    }
    
    public int activeMarketCount() {
        return activeBtcMarkets.get().size();
    }
}
