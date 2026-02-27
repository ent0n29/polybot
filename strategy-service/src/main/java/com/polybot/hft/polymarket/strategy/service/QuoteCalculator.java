package com.polybot.hft.polymarket.strategy.service;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.strategy.config.GabagoolConfig;
import com.polybot.hft.polymarket.strategy.model.GabagoolMarket;
import com.polybot.hft.polymarket.strategy.model.MarketInventory;
import com.polybot.hft.polymarket.strategy.model.OrderState;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.polybot.hft.strategy.metrics.StrategyMetricsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Calculates quote prices and order sizes for the Gabagool strategy.
 */
public class QuoteCalculator {

    private final BankrollService bankrollService;
    private final HftProperties properties;
    private final StrategyMetricsService metricsService;

    public QuoteCalculator(BankrollService bankrollService, HftProperties properties, StrategyMetricsService metricsService) {
        this.bankrollService = bankrollService;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    /**
     * Calculate maker entry price with inventory skew adjustment.
     */
    public BigDecimal calculateEntryPrice(TopOfBook book, BigDecimal tickSize, GabagoolConfig cfg, int skewTicks) {
        BigDecimal bestBid = book.bestBid();
        BigDecimal bestAsk = book.bestAsk();

        if (bestBid == null || bestAsk == null) return null;

        BigDecimal mid = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal spread = bestAsk.subtract(bestBid);

        int effectiveImproveTicks = cfg.improveTicks() + skewTicks;

        BigDecimal entryPrice;
        if (spread.compareTo(BigDecimal.valueOf(0.06)) >= 0) {
            // Wide book (6+ ticks) - quote near mid, as target does
            entryPrice = mid.subtract(tickSize.multiply(BigDecimal.valueOf(Math.max(0, cfg.improveTicks() - skewTicks))));
        } else {
            // Tight/normal book - improve bid
            BigDecimal improvedBid = bestBid.add(tickSize.multiply(BigDecimal.valueOf(effectiveImproveTicks)));
            entryPrice = improvedBid.min(mid);
        }

        entryPrice = roundToTick(entryPrice, tickSize, RoundingMode.DOWN);

        // Sanity checks
        if (entryPrice.compareTo(BigDecimal.valueOf(0.01)) < 0) return null;
        if (entryPrice.compareTo(BigDecimal.valueOf(0.99)) > 0) return null;
        if (entryPrice.compareTo(bestAsk) >= 0) {
            entryPrice = bestAsk.subtract(tickSize);
            if (entryPrice.compareTo(BigDecimal.valueOf(0.01)) < 0) return null;
        }

        return entryPrice;
    }

    /**
     * Calculate order size based on replica schedule and caps.
     */
    public BigDecimal calculateShares(GabagoolMarket market, BigDecimal entryPrice, GabagoolConfig cfg,
                                       long secondsToEnd, BigDecimal currentExposure) {
        BigDecimal shares = replicaSharesByTimeToEnd(market, secondsToEnd);
        if (shares == null) {
            BigDecimal notional = calculateNotional(cfg, currentExposure);
            return notional == null ? null : calculateSharesFromNotional(notional, entryPrice);
        }

        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Dynamic sizing
        shares = shares.multiply(bankrollService.getDynamicSizingMultiplier(cfg));

        // Apply caps
        BigDecimal bankrollUsd = bankrollService.resolveEffective(cfg);
        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (cfg.maxOrderBankrollFraction() > 0) {
                BigDecimal perOrderCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxOrderBankrollFraction()));
                BigDecimal capShares = perOrderCap.divide(entryPrice, 2, RoundingMode.DOWN);
                shares = shares.min(capShares);
            }
            if (cfg.maxTotalBankrollFraction() > 0) {
                BigDecimal totalCap = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxTotalBankrollFraction()));
                BigDecimal remaining = totalCap.subtract(currentExposure);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) return null;
                BigDecimal capShares = remaining.divide(entryPrice, 2, RoundingMode.DOWN);
                shares = shares.min(capShares);
            }
        }

        // Global cap
        BigDecimal maxNotionalUsd = properties.risk().maxOrderNotionalUsd();
        if (maxNotionalUsd != null && maxNotionalUsd.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal capShares = maxNotionalUsd.divide(entryPrice, 2, RoundingMode.DOWN);
            shares = shares.min(capShares);
        }

        shares = shares.setScale(2, RoundingMode.DOWN);
        return shares.compareTo(BigDecimal.valueOf(0.01)) < 0 ? null : shares;
    }

    /**
     * Calculate inventory skew ticks.
     */
    public int[] calculateSkewTicks(MarketInventory inv, GabagoolConfig cfg) {
        BigDecimal imbalance = inv.imbalance();
        int skewTicksUp = 0;
        int skewTicksDown = 0;

        BigDecimal imbalanceForMax = cfg.completeSetImbalanceSharesForMaxSkew();
        int maxSkew = cfg.completeSetMaxSkewTicks();

        if (imbalanceForMax.compareTo(BigDecimal.ZERO) > 0 && maxSkew > 0) {
            double skewRatio = Math.min(1.0, Math.abs(imbalance.doubleValue()) / imbalanceForMax.doubleValue());
            int skewTicks = (int) Math.round(skewRatio * maxSkew);

            if (imbalance.compareTo(BigDecimal.ZERO) > 0) {
                // More UP → improve DOWN, penalize UP
                skewTicksDown = skewTicks;
                skewTicksUp = -skewTicks;
            } else if (imbalance.compareTo(BigDecimal.ZERO) < 0) {
                // More DOWN → improve UP, penalize DOWN
                skewTicksUp = skewTicks;
                skewTicksDown = -skewTicks;
            }
        }

        return new int[]{skewTicksUp, skewTicksDown};
    }

    /**
     * Calculate current exposure (only unhedged positions).
     */
    public BigDecimal calculateExposure(Map<String, OrderState> openOrders, Map<String, MarketInventory> inventories) {
        BigDecimal openOrdersNotional = BigDecimal.ZERO;
        for (OrderState o : openOrders.values()) {
            if (o == null || o.price() == null || o.size() == null) continue;
            BigDecimal matched = o.matchedSize() == null ? BigDecimal.ZERO : o.matchedSize();
            BigDecimal remaining = o.size().subtract(matched).max(BigDecimal.ZERO);
            openOrdersNotional = openOrdersNotional.add(o.price().multiply(remaining));
        }

        BigDecimal unhedgedNotional = BigDecimal.ZERO;
        for (MarketInventory inv : inventories.values()) {
            if (inv == null) continue;
            BigDecimal imbalance = inv.imbalance().abs();
            if (imbalance.compareTo(BigDecimal.ZERO) > 0) {
                unhedgedNotional = unhedgedNotional.add(imbalance.multiply(BigDecimal.valueOf(0.50)));
            }
        }

        BigDecimal total = openOrdersNotional.add(unhedgedNotional);
        metricsService.updateTotalExposure(total);
        return total;
    }

    /**
     * Check if complete-set edge is sufficient.
     */
    public boolean hasMinimumEdge(BigDecimal upPrice, BigDecimal downPrice, GabagoolConfig cfg) {
        BigDecimal cost = upPrice.add(downPrice);
        BigDecimal edge = BigDecimal.ONE.subtract(cost);
        return edge.compareTo(BigDecimal.valueOf(cfg.completeSetMinEdge())) >= 0;
    }

    private BigDecimal calculateNotional(GabagoolConfig cfg, BigDecimal currentExposure) {
        BigDecimal bankrollUsd = bankrollService.resolveEffective(cfg);
        BigDecimal notional;

        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0 && cfg.quoteSizeBankrollFraction() > 0) {
            notional = bankrollUsd.multiply(BigDecimal.valueOf(cfg.quoteSizeBankrollFraction()));
        } else {
            notional = cfg.quoteSize();
        }

        if (notional == null || notional.compareTo(BigDecimal.ZERO) <= 0) return null;

        BigDecimal maxNotional = properties.risk().maxOrderNotionalUsd();
        if (maxNotional != null && maxNotional.compareTo(BigDecimal.ZERO) > 0) {
            notional = notional.min(maxNotional);
        }

        if (bankrollUsd != null && bankrollUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (cfg.maxOrderBankrollFraction() > 0) {
                notional = notional.min(bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxOrderBankrollFraction())));
            }
            if (cfg.maxTotalBankrollFraction() > 0) {
                BigDecimal remaining = bankrollUsd.multiply(BigDecimal.valueOf(cfg.maxTotalBankrollFraction())).subtract(currentExposure);
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) return null;
                notional = notional.min(remaining);
            }
        }

        return notional.compareTo(BigDecimal.ZERO) > 0 ? notional : null;
    }

    private static BigDecimal calculateSharesFromNotional(BigDecimal notional, BigDecimal price) {
        if (notional == null || notional.compareTo(BigDecimal.ZERO) <= 0) return null;
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal shares = notional.divide(price, 2, RoundingMode.DOWN);
        return shares.compareTo(BigDecimal.valueOf(0.01)) < 0 ? null : shares;
    }

    private static BigDecimal replicaSharesByTimeToEnd(GabagoolMarket market, long secondsToEnd) {
        if (market == null || market.slug() == null) return null;
        String slug = market.slug();

        if (slug.startsWith("btc-updown-5m-") || slug.startsWith("eth-updown-5m-")) {
            // 5m markets: MuseumOfBees uses fixed ~20 share base, larger early
            if (secondsToEnd < 60) return BigDecimal.valueOf(15);
            if (secondsToEnd < 120) return BigDecimal.valueOf(18);
            if (secondsToEnd < 180) return BigDecimal.valueOf(20);
            if (secondsToEnd < 240) return BigDecimal.valueOf(20);
            return BigDecimal.valueOf(20);
        }
        if (slug.startsWith("btc-updown-15m-")) {
            if (secondsToEnd < 60) return BigDecimal.valueOf(11);
            if (secondsToEnd < 180) return BigDecimal.valueOf(13);
            if (secondsToEnd < 300) return BigDecimal.valueOf(17);
            if (secondsToEnd < 600) return BigDecimal.valueOf(19);
            return BigDecimal.valueOf(20);
        }
        if (slug.startsWith("eth-updown-15m-")) {
            if (secondsToEnd < 60) return BigDecimal.valueOf(8);
            if (secondsToEnd < 180) return BigDecimal.valueOf(10);
            if (secondsToEnd < 300) return BigDecimal.valueOf(12);
            if (secondsToEnd < 600) return BigDecimal.valueOf(13);
            return BigDecimal.valueOf(14);
        }
        if (slug.startsWith("bitcoin-up-or-down-")) {
            // BTC 1h sizing schedule - derived from 44k trade analysis
            if (secondsToEnd < 60) return BigDecimal.valueOf(9);
            if (secondsToEnd < 180) return BigDecimal.valueOf(10);
            if (secondsToEnd < 300) return BigDecimal.valueOf(11);
            if (secondsToEnd < 600) return BigDecimal.valueOf(12);
            if (secondsToEnd < 900) return BigDecimal.valueOf(14);
            if (secondsToEnd < 1200) return BigDecimal.valueOf(15);
            if (secondsToEnd < 1800) return BigDecimal.valueOf(17);
            return BigDecimal.valueOf(18);
        }
        if (slug.startsWith("ethereum-up-or-down-")) {
            if (secondsToEnd < 60) return BigDecimal.valueOf(7);
            if (secondsToEnd < 300) return BigDecimal.valueOf(8);
            if (secondsToEnd < 600) return BigDecimal.valueOf(9);
            if (secondsToEnd < 900) return BigDecimal.valueOf(11);
            if (secondsToEnd < 1200) return BigDecimal.valueOf(12);
            if (secondsToEnd < 1800) return BigDecimal.valueOf(13);
            return BigDecimal.valueOf(14);
        }
        return null;
    }

    public static BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize, RoundingMode mode) {
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) return value;
        BigDecimal ticks = value.divide(tickSize, 0, mode);
        return ticks.multiply(tickSize);
    }
}
