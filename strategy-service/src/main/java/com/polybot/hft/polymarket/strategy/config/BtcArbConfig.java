package com.polybot.hft.polymarket.strategy.config;

import java.math.BigDecimal;

/**
 * Configuration for the BTC_Arb strategy.
 * 
 * Simple complete-set arbitrage on BTC 15m markets.
 */
public record BtcArbConfig(
        boolean enabled,
        BigDecimal walletUsd,
        long refreshMillis,
        long minSecondsToEnd,
        long maxSecondsToEnd
) {
    private static final BigDecimal FEE_MULTIPLIER = BigDecimal.valueOf(0.25);
    
    public static BtcArbConfig defaults() {
        return new BtcArbConfig(
                false,
                BigDecimal.valueOf(10),   // $10 wallet
                500L,                      // 500ms refresh
                0L,                        // min seconds to end
                900L                       // max 15 minutes (900s)
        );
    }
    
    /**
     * Sizing logic: floor(wallet) - 1.
     * Leaves a buffer for fees.
     */
    public int availableShares() {
        if (walletUsd == null || walletUsd.compareTo(BigDecimal.ONE) <= 0) {
            return 0;
        }
        return walletUsd.intValue() - 1;
    }
    
    /**
     * Computes the Polymarket fee for an order.
     * Formula: shares * price * 0.25 * (price * (1 - price))^2
     */
    public static BigDecimal calculateFee(BigDecimal shares, BigDecimal price) {
        if (shares == null || price == null || price.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ONE) >= 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal p1p = price.multiply(BigDecimal.ONE.subtract(price));
        BigDecimal p1pSq = p1p.multiply(p1p);
        
        return shares.multiply(price)
                .multiply(FEE_MULTIPLIER)
                .multiply(p1pSq);
    }
    
    /**
     * Calculates net profit for a complete-set arbitrage trade.
     */
    public static BigDecimal calculateNetProfit(BigDecimal upPrice, BigDecimal downPrice, BigDecimal shares) {
        BigDecimal totalCost = upPrice.add(downPrice);
        BigDecimal grossProfit = BigDecimal.ONE.subtract(totalCost).multiply(shares);
        BigDecimal totalFees = calculateFee(shares, upPrice).add(calculateFee(shares, downPrice));
        
        return grossProfit.subtract(totalFees);
    }
    
    public static boolean isProfitable(BigDecimal upPrice, BigDecimal downPrice, BigDecimal shares) {
        return calculateNetProfit(upPrice, downPrice, shares).compareTo(BigDecimal.ZERO) >= 0;
    }
}
