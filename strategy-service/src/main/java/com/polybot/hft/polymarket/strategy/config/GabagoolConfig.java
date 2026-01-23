package com.polybot.hft.polymarket.strategy.config;

import com.polybot.hft.config.HftProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Complete configuration for the Gabagool strategy.
 * Grouped into logical sub-configurations for readability.
 */
public record GabagoolConfig(
                boolean enabled,
                TimingConfig timing,
                SizingConfig sizing,
                BankrollConfig bankroll,
                CompleteSetConfig completeSet,
                TakerConfig taker,
                List<GabagoolMarketConfig> markets) {

        public static GabagoolConfig defaults() {
                return new GabagoolConfig(
                                false,
                                TimingConfig.defaults(),
                                SizingConfig.defaults(),
                                BankrollConfig.defaults(),
                                CompleteSetConfig.defaults(),
                                TakerConfig.defaults(),
                                List.of());
        }

        /**
         * Build from HftProperties.Gabagool for backwards compatibility.
         */
        public static GabagoolConfig from(HftProperties.Gabagool cfg) {
                TimingConfig timing = new TimingConfig(
                                cfg.refreshMillis(),
                                cfg.minReplaceMillis(),
                                cfg.minSecondsToEnd(),
                                cfg.maxSecondsToEnd());

                SizingConfig sizing = new SizingConfig(
                                cfg.quoteSize(),
                                cfg.quoteSizeBankrollFraction(),
                                cfg.improveTicks());

                BankrollConfig bankroll = new BankrollConfig(
                                cfg.bankrollUsd(),
                                cfg.bankrollMode(),
                                cfg.bankrollRefreshMillis(),
                                cfg.dynamicSizingEnabled(),
                                cfg.dynamicSizingMinMultiplier(),
                                cfg.dynamicSizingMaxMultiplier(),
                                cfg.bankrollSmoothingAlpha(),
                                cfg.bankrollMinThreshold(),
                                cfg.bankrollTradingFraction(),
                                cfg.maxOrderBankrollFraction(),
                                cfg.maxTotalBankrollFraction());

                CompleteSetConfig completeSet = new CompleteSetConfig(
                                cfg.completeSetMinEdge(),
                                cfg.completeSetMaxSkewTicks(),
                                cfg.completeSetImbalanceSharesForMaxSkew(),
                                cfg.completeSetTopUpEnabled(),
                                cfg.completeSetTopUpSecondsToEnd(),
                                cfg.completeSetTopUpMinShares(),
                                cfg.completeSetFastTopUpEnabled(),
                                cfg.completeSetFastTopUpMinShares(),
                                cfg.completeSetFastTopUpMinSecondsAfterFill(),
                                cfg.completeSetFastTopUpMaxSecondsAfterFill(),
                                cfg.completeSetFastTopUpCooldownMillis(),
                                cfg.completeSetFastTopUpMinEdge(),
                                cfg.strictPairingEnabled(),
                                cfg.maxImbalanceShares(),
                                cfg.aggressiveHedgeSecondsToEnd(),
                                cfg.aggressiveHedgeMinEdge());

                TakerConfig taker = new TakerConfig(
                                cfg.takerModeEnabled(),
                                cfg.takerModeMaxEdge(),
                                cfg.takerModeMaxSpread());

                List<GabagoolMarketConfig> markets = cfg.markets() == null ? List.of()
                                : cfg.markets().stream()
                                                .map(m -> {
                                                        Instant endTime = null;
                                                        if (m.endTime() != null && !m.endTime().isBlank()) {
                                                                try {
                                                                        endTime = Instant.parse(m.endTime());
                                                                } catch (Exception ignored) {
                                                                }
                                                        }
                                                        return new GabagoolMarketConfig(m.slug(), m.upTokenId(),
                                                                        m.downTokenId(), endTime);
                                                })
                                                .toList();

                return new GabagoolConfig(cfg.enabled(), timing, sizing, bankroll, completeSet, taker, markets);
        }

        // Convenience accessors for backwards compatibility
        public long refreshMillis() {
                return timing.refreshMillis();
        }

        public long minReplaceMillis() {
                return timing.minReplaceMillis();
        }

        public long minSecondsToEnd() {
                return timing.minSecondsToEnd();
        }

        public long maxSecondsToEnd() {
                return timing.maxSecondsToEnd();
        }

        public BigDecimal quoteSize() {
                return sizing.quoteSize();
        }

        public double quoteSizeBankrollFraction() {
                return sizing.quoteSizeBankrollFraction();
        }

        public int improveTicks() {
                return sizing.improveTicks();
        }

        public BigDecimal bankrollUsd() {
                return bankroll.bankrollUsd();
        }

        public HftProperties.BankrollMode bankrollMode() {
                return bankroll.bankrollMode();
        }

        public long bankrollRefreshMillis() {
                return bankroll.bankrollRefreshMillis();
        }

        public boolean dynamicSizingEnabled() {
                return bankroll.dynamicSizingEnabled();
        }

        public double dynamicSizingMinMultiplier() {
                return bankroll.dynamicSizingMinMultiplier();
        }

        public double dynamicSizingMaxMultiplier() {
                return bankroll.dynamicSizingMaxMultiplier();
        }

        public double bankrollSmoothingAlpha() {
                return bankroll.bankrollSmoothingAlpha();
        }

        public BigDecimal bankrollMinThreshold() {
                return bankroll.bankrollMinThreshold();
        }

        public double bankrollTradingFraction() {
                return bankroll.bankrollTradingFraction();
        }

        public double maxOrderBankrollFraction() {
                return bankroll.maxOrderBankrollFraction();
        }

        public double maxTotalBankrollFraction() {
                return bankroll.maxTotalBankrollFraction();
        }

        public double completeSetMinEdge() {
                return completeSet.minEdge();
        }

        public int completeSetMaxSkewTicks() {
                return completeSet.maxSkewTicks();
        }

        public BigDecimal completeSetImbalanceSharesForMaxSkew() {
                return completeSet.imbalanceSharesForMaxSkew();
        }

        public boolean completeSetTopUpEnabled() {
                return completeSet.topUpEnabled();
        }

        public long completeSetTopUpSecondsToEnd() {
                return completeSet.topUpSecondsToEnd();
        }

        public BigDecimal completeSetTopUpMinShares() {
                return completeSet.topUpMinShares();
        }

        public boolean completeSetFastTopUpEnabled() {
                return completeSet.fastTopUpEnabled();
        }

        public BigDecimal completeSetFastTopUpMinShares() {
                return completeSet.fastTopUpMinShares();
        }

        public long completeSetFastTopUpMinSecondsAfterFill() {
                return completeSet.fastTopUpMinSecondsAfterFill();
        }

        public long completeSetFastTopUpMaxSecondsAfterFill() {
                return completeSet.fastTopUpMaxSecondsAfterFill();
        }

        public long completeSetFastTopUpCooldownMillis() {
                return completeSet.fastTopUpCooldownMillis();
        }

        public double completeSetFastTopUpMinEdge() {
                return completeSet.fastTopUpMinEdge();
        }

        public boolean strictPairingEnabled() {
                return completeSet.strictPairingEnabled();
        }

        public BigDecimal maxImbalanceShares() {
                return completeSet.maxImbalanceShares();
        }

        public long aggressiveHedgeSecondsToEnd() {
                return completeSet.aggressiveHedgeSecondsToEnd();
        }

        public double aggressiveHedgeMinEdge() {
                return completeSet.aggressiveHedgeMinEdge();
        }

        public boolean takerModeEnabled() {
                return taker.enabled();
        }

        public double takerModeMaxEdge() {
                return taker.maxEdge();
        }

        public BigDecimal takerModeMaxSpread() {
                return taker.maxSpread();
        }

        public record GabagoolMarketConfig(
                        String slug,
                        String upTokenId,
                        String downTokenId,
                        Instant endTime) {
        }
}
