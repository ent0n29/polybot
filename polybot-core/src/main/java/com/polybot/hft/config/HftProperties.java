package com.polybot.hft.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix = "hft")
public record HftProperties(
    TradingMode mode,
    @Valid Polymarket polymarket,
    @Valid Executor executor,
    @Valid Risk risk,
    @Valid Strategy strategy) {

  public HftProperties {
    if (mode == null) {
      mode = TradingMode.PAPER;
    }
    if (polymarket == null) {
      polymarket = defaultPolymarket();
    }
    if (executor == null) {
      executor = defaultExecutor();
    }
    if (risk == null) {
      risk = defaultRisk();
    }
    if (strategy == null) {
      strategy = defaultStrategy();
    }
  }

  private static List<String> sanitizeStringList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private static Executor defaultExecutor() {
    return new Executor(null, null);
  }

  private static Polymarket defaultPolymarket() {
    return new Polymarket(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null);
  }

  private static Rest defaultRest() {
    return new Rest(null, null);
  }

  private static RateLimit defaultRateLimit() {
    return new RateLimit(null, null, null);
  }

  private static Retry defaultRetry() {
    return new Retry(null, null, null, null);
  }

  private static Auth defaultAuth() {
    return new Auth(null, null, null, null, null, null, null, null);
  }

  private static Risk defaultRisk() {
    return new Risk(false, null, null);
  }

  private static Strategy defaultStrategy() {
    return new Strategy(null);
  }

  public enum TradingMode {
    PAPER,
    LIVE,
  }

  public enum BankrollMode {
    /**
     * Use the configured {@code bankrollUsd} value (static).
     */
    FIXED,
    /**
     * Use executor-reported total equity (USDC balance + positions current value).
     */
    AUTO_EQUITY,
    /**
     * Use executor-reported USDC balance only (cash-like).
     */
    AUTO_CASH
  }

  public record Executor(
      String baseUrl,
      @NotNull Boolean sendLiveAck) {
    public Executor {
      if (baseUrl == null || baseUrl.isBlank()) {
        baseUrl = "http://localhost:8080";
      }
      if (sendLiveAck == null) {
        sendLiveAck = true;
      }
    }
  }

  public record Polymarket(
      String clobRestUrl,
      String clobWsUrl,
      String gammaUrl,
      String dataApiUrl,
      @Min(1) Integer chainId,
      Boolean useServerTime,
      Boolean marketWsEnabled,
      Boolean userWsEnabled,
      List<String> marketAssetIds,
      List<String> userMarketIds,
      @Valid Rest rest,
      @Valid Auth auth,
      /**
       * Optional path to persist the market WS top-of-book cache (JSON). When blank,
       * disabled.
       * Useful to warm-start after restarts (avoids an empty TOB cache until the
       * first WS update).
       */
      String marketWsCachePath,
      /**
       * Flush interval for the WS cache snapshot. Ignored when
       * {@code marketWsCachePath} is blank.
       */
      @NotNull @PositiveOrZero Long marketWsCacheFlushMillis,
      /**
       * Treat the WS as stale when no messages (including PONG) are received for this
       * long.
       * When stale and {@code marketWsEnabled=true} with active subscriptions, the
       * client auto-reconnects.
       * Set to 0 to disable.
       */
      @NotNull @PositiveOrZero Long marketWsStaleTimeoutMillis,
      /**
       * Minimum interval between reconnect attempts when the WS is
       * stale/disconnected.
       */
      @NotNull @PositiveOrZero Long marketWsReconnectBackoffMillis) {
    public Polymarket {
      if (clobRestUrl == null || clobRestUrl.isBlank()) {
        clobRestUrl = "https://clob.polymarket.com";
      }
      if (clobWsUrl == null || clobWsUrl.isBlank()) {
        clobWsUrl = "wss://ws-subscriptions-clob.polymarket.com";
      }
      if (gammaUrl == null || gammaUrl.isBlank()) {
        gammaUrl = "https://gamma-api.polymarket.com";
      }
      if (dataApiUrl == null || dataApiUrl.isBlank()) {
        dataApiUrl = "https://data-api.polymarket.com";
      }
      if (chainId == null) {
        chainId = 137;
      }
      if (useServerTime == null) {
        useServerTime = true;
      }
      if (marketWsEnabled == null) {
        marketWsEnabled = false;
      }
      if (userWsEnabled == null) {
        userWsEnabled = false;
      }
      marketAssetIds = sanitizeStringList(marketAssetIds);
      userMarketIds = sanitizeStringList(userMarketIds);
      if (rest == null) {
        rest = defaultRest();
      }
      if (auth == null) {
        auth = defaultAuth();
      }
      if (marketWsCachePath == null) {
        marketWsCachePath = "";
      }
      if (marketWsCacheFlushMillis == null) {
        marketWsCacheFlushMillis = 5_000L;
      }
      if (marketWsStaleTimeoutMillis == null) {
        marketWsStaleTimeoutMillis = 60_000L;
      }
      if (marketWsReconnectBackoffMillis == null) {
        marketWsReconnectBackoffMillis = 10_000L;
      }
    }
  }

  public record Rest(@Valid RateLimit rateLimit, @Valid Retry retry) {
    public Rest {
      if (rateLimit == null) {
        rateLimit = defaultRateLimit();
      }
      if (retry == null) {
        retry = defaultRetry();
      }
    }
  }

  public record RateLimit(
      @NotNull Boolean enabled,
      @NotNull @PositiveOrZero Double requestsPerSecond,
      @NotNull @PositiveOrZero Integer burst) {
    public RateLimit {
      if (enabled == null) {
        enabled = true;
      }
      if (requestsPerSecond == null) {
        requestsPerSecond = 20.0;
      }
      if (burst == null) {
        burst = 50;
      }
    }
  }

  public record Retry(
      @NotNull Boolean enabled,
      @NotNull @Min(1) Integer maxAttempts,
      @NotNull @PositiveOrZero Long initialBackoffMillis,
      @NotNull @PositiveOrZero Long maxBackoffMillis) {
    public Retry {
      if (enabled == null) {
        enabled = true;
      }
      if (maxAttempts == null) {
        maxAttempts = 3;
      }
      if (initialBackoffMillis == null) {
        initialBackoffMillis = 200L;
      }
      if (maxBackoffMillis == null) {
        maxBackoffMillis = 2_000L;
      }
    }
  }

  public record Auth(
      String privateKey,
      @NotNull @Min(0) Integer signatureType,
      String funderAddress,
      String apiKey,
      String apiSecret,
      String apiPassphrase,
      @NotNull @PositiveOrZero Long nonce,
      @NotNull Boolean autoCreateOrDeriveApiCreds) {
    public Auth {
      if (signatureType == null) {
        signatureType = 0;
      }
      if (nonce == null) {
        nonce = 0L;
      }
      if (autoCreateOrDeriveApiCreds == null) {
        autoCreateOrDeriveApiCreds = false;
      }
    }
  }

  public record Risk(
      boolean killSwitch,
      @NotNull @PositiveOrZero BigDecimal maxOrderNotionalUsd,
      @NotNull @PositiveOrZero BigDecimal maxOrderSize) {
    public Risk {
      if (maxOrderNotionalUsd == null) {
        maxOrderNotionalUsd = BigDecimal.ZERO;
      }
      if (maxOrderSize == null) {
        maxOrderSize = BigDecimal.ZERO;
      }
    }
  }

  public record Strategy(@Valid Gabagool gabagool) {
    public Strategy {
      if (gabagool == null) {
        gabagool = defaultGabagool();
      }
    }
  }

  private static Gabagool defaultGabagool() {
    return new Gabagool(
        false, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null);
  }

  /**
   * Gabagool-style Up/Down strategy configuration.
   * Based on reverse-engineering target user's trading patterns.
   */
  public record Gabagool(
      boolean enabled,
      @NotNull @Min(50) Long refreshMillis,
      /**
       * Minimum interval between cancel/replace cycles for a given tokenId.
       * Helps avoid spam when the WS book is noisy.
       */
      @NotNull @Min(0) Long minReplaceMillis,
      @NotNull @Min(0) Long minSecondsToEnd,
      @NotNull @Min(0) Long maxSecondsToEnd,
      /**
       * Target order size in USDC notional (approx. {@code entryPrice * shares} for
       * BUY orders).
       */
      @NotNull @PositiveOrZero BigDecimal quoteSize,
      /**
       * Optional bankroll-based sizing target (0..1). When > 0 and
       * {@code bankrollUsd > 0}, the strategy uses
       * {@code bankrollUsd * quoteSizeBankrollFraction} as the base order notional
       * instead of {@code quoteSize}.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double quoteSizeBankrollFraction,
      @NotNull @Min(0) Integer improveTicks,
      /**
       * Optional bankroll (USDC) to enable fractional sizing caps.
       * When 0, bankroll-based caps are disabled.
       */
      @NotNull @PositiveOrZero BigDecimal bankrollUsd,
      /**
       * How the strategy should interpret bankroll for sizing/caps.
       * When {@code FIXED}, uses {@code bankrollUsd}. When {@code AUTO_*}, uses the
       * executor bankroll snapshot.
       */
      @NotNull BankrollMode bankrollMode,
      /**
       * Refresh interval for executor bankroll snapshots when
       * {@code bankrollMode != FIXED}.
       */
      @NotNull @Min(1_000) Long bankrollRefreshMillis,
      /**
       * When enabled and {@code bankrollMode != FIXED}, scale the replica share
       * schedule by:
       * {@code (actualBankrollUsd / bankrollUsd)}.
       */
      @NotNull Boolean dynamicSizingEnabled,
      /**
       * Lower bound for the dynamic sizing multiplier.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("100.0") Double dynamicSizingMinMultiplier,
      /**
       * Upper bound for the dynamic sizing multiplier.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("100.0") Double dynamicSizingMaxMultiplier,
      /**
       * EMA smoothing alpha for bankroll updates (0..1). Higher = faster response.
       * Example: 0.1 = slow smoothing (90% old, 10% new), 1.0 = no smoothing.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double bankrollSmoothingAlpha,
      /**
       * Minimum bankroll threshold (USDC). Strategy will stop trading if effective
       * bankroll falls below this.
       * Acts as a circuit breaker to protect capital during drawdowns.
       * When 0, circuit breaker is disabled.
       */
      @NotNull @PositiveOrZero BigDecimal bankrollMinThreshold,
      /**
       * Fraction of bankroll to deploy for trading (0..1).
       * Example: 0.8 = only use 80% of bankroll, keep 20% as safety buffer.
       * When 1.0, deploy full bankroll.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double bankrollTradingFraction,
      /**
       * Optional cap for total exposure per market instance (USDC notional).
       * This applies across BOTH legs (UP+DOWN) and includes open orders + positions
       * for that market.
       *
       * Example: with maxMarketNotionalUsd=10, the strategy will size the paired
       * quotes so that
       * {@code shares * (price_up + price_down) <= 10} (subject to other caps).
       *
       * When 0, per-market cap is disabled.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double maxOrderBankrollFraction,
      /**
       * Optional cap for total exposure as a fraction of {@code bankrollUsd} (0..1).
       * When 0, disabled.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double maxTotalBankrollFraction,
      /**
       * Minimum complete-set edge required to quote both outcomes (edge = 1 - (p_up +
       * p_down)).
       *
       * Typical observed maker-side edges for target user are ~0.01–0.02 when WS TOB
       * is fresh.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double completeSetMinEdge,
      /**
       * Maximum inventory skew (in ticks) applied to one leg and subtracted from the
       * other.
       */
      @NotNull @Min(0) Integer completeSetMaxSkewTicks,
      /**
       * Share imbalance at which max skew is applied (linear ramp from 0..max).
       */
      @NotNull @PositiveOrZero BigDecimal completeSetImbalanceSharesForMaxSkew,
      /**
       * When enabled, occasionally cross the spread (taker-like) on the lagging leg
       * to rebalance inventory
       * near market end.
       */
      @NotNull Boolean completeSetTopUpEnabled,
      /**
       * Only perform top-ups when
       * {@code secondsToEnd <= completeSetTopUpSecondsToEnd}.
       */
      @NotNull @Min(0) Long completeSetTopUpSecondsToEnd,
      /**
       * Only perform top-ups when the per-market share imbalance is at least this
       * amount.
       */
      @NotNull @PositiveOrZero BigDecimal completeSetTopUpMinShares,
      /**
       * When enabled, perform a fast top-up (taker-like) shortly after one leg fills,
       * to quickly complete the paired position (complete-set style).
       *
       * This is the key mechanism to match the observed UP/DOWN pairing timing
       * (median ~10s, p90 ~66s).
       */
      @NotNull Boolean completeSetFastTopUpEnabled,
      /**
       * Minimum per-market share imbalance required to trigger a fast top-up.
       *
       * This should typically be low (e.g., 0.01–1.0) because target user often
       * trades in small sizes (5–20 shares),
       * and the pairing behavior applies at those sizes too.
       */
      @NotNull @PositiveOrZero BigDecimal completeSetFastTopUpMinShares,
      /**
       * Minimum delay after a leading-leg fill before attempting a fast top-up.
       */
      @NotNull @Min(0) Long completeSetFastTopUpMinSecondsAfterFill,
      /**
       * Maximum delay window after a leading-leg fill where fast top-up is allowed.
       */
      @NotNull @Min(0) Long completeSetFastTopUpMaxSecondsAfterFill,
      /**
       * Cooldown between fast top-up attempts per market to avoid spamming taker
       * orders.
       */
      @NotNull @Min(0) Long completeSetFastTopUpCooldownMillis,
      /**
       * Minimum estimated hedged edge required for a fast top-up (edge = 1 -
       * (leadFillPrice + laggingAsk)).
       * Use 0.0 for breakeven-or-better hedging.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double completeSetFastTopUpMinEdge,
      /**
       * Enable taker mode for aggressive order placement.
       * When enabled, the strategy will sometimes cross the spread (buy at ask)
       * instead of posting at bid.
       * Based on target user's behavior: ~39% of trades are taker fills.
       */
      @NotNull Boolean takerModeEnabled,
      /**
       * Maximum edge threshold for taker mode. When edge < this value and spread is
       * tight,
       * prefer taker orders to capture fleeting opportunities.
       * Empirical: gabagool takes more often when edge is low (<1.5%).
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double takerModeMaxEdge,
      /**
       * Maximum spread (in price units) to consider for taker orders.
       * Only take when spread cost is acceptable.
       * Typical: 0.02 (2 ticks) means spread cost of 2 cents per share.
       */
      @NotNull @PositiveOrZero BigDecimal takerModeMaxSpread,
      /**
       * When enabled, strictly enforce delta-neutrality by restricting new quotes
       * on the heavy leg and triggering aggressive hedging.
       */
      @NotNull Boolean strictPairingEnabled,
      /**
       * Maximum share imbalance allowed before shutting down the heavy leg's quotes.
       */
      @NotNull @PositiveOrZero BigDecimal maxImbalanceShares,
      /**
       * Aggressive hedging (taker) window near market end.
       */
      @NotNull @Min(0) Long aggressiveHedgeSecondsToEnd,
      /**
       * Minimum edge allowed for aggressive hedging (can be negative to force
       * closure).
       */
      @NotNull @jakarta.validation.constraints.DecimalMin("-1.0") @jakarta.validation.constraints.DecimalMax("1.0") Double aggressiveHedgeMinEdge,
      @Valid List<GabagoolMarket> markets) {
    public Gabagool {
      if (refreshMillis == null) {
        refreshMillis = 250L;
      }
      if (minReplaceMillis == null) {
        minReplaceMillis = 1_000L;
      }
      if (minSecondsToEnd == null) {
        minSecondsToEnd = 0L;
      }
      if (maxSecondsToEnd == null) {
        maxSecondsToEnd = 3_600L;
      }
      if (quoteSize == null) {
        quoteSize = BigDecimal.valueOf(10);
      }
      if (quoteSizeBankrollFraction == null) {
        quoteSizeBankrollFraction = 0.0;
      }
      if (improveTicks == null) {
        improveTicks = 1;
      }
      if (bankrollUsd == null) {
        bankrollUsd = BigDecimal.ZERO;
      }
      if (bankrollMode == null) {
        bankrollMode = BankrollMode.FIXED;
      }
      if (bankrollRefreshMillis == null) {
        bankrollRefreshMillis = 10_000L;
      }
      if (dynamicSizingEnabled == null) {
        dynamicSizingEnabled = false;
      }
      if (dynamicSizingMinMultiplier == null) {
        dynamicSizingMinMultiplier = 0.25;
      }
      if (dynamicSizingMaxMultiplier == null) {
        dynamicSizingMaxMultiplier = 5.0;
      }
      if (bankrollSmoothingAlpha == null) {
        bankrollSmoothingAlpha = 0.1; // Slow smoothing by default (90% old, 10% new)
      }
      if (bankrollMinThreshold == null) {
        bankrollMinThreshold = BigDecimal.ZERO; // Circuit breaker disabled by default
      }
      if (bankrollTradingFraction == null) {
        bankrollTradingFraction = 1.0; // Deploy full bankroll by default
      }
      if (maxOrderBankrollFraction == null) {
        maxOrderBankrollFraction = 0.0;
      }
      if (maxTotalBankrollFraction == null) {
        maxTotalBankrollFraction = 0.0;
      }
      if (completeSetMinEdge == null) {
        completeSetMinEdge = 0.01;
      }
      if (completeSetMaxSkewTicks == null) {
        completeSetMaxSkewTicks = 2;
      }
      if (completeSetImbalanceSharesForMaxSkew == null) {
        completeSetImbalanceSharesForMaxSkew = BigDecimal.valueOf(40);
      }
      if (completeSetTopUpEnabled == null) {
        completeSetTopUpEnabled = true;
      }
      if (completeSetTopUpSecondsToEnd == null) {
        completeSetTopUpSecondsToEnd = 60L;
      }
      if (completeSetTopUpMinShares == null) {
        completeSetTopUpMinShares = BigDecimal.valueOf(10);
      }
      if (completeSetFastTopUpEnabled == null) {
        completeSetFastTopUpEnabled = true;
      }
      if (completeSetFastTopUpMinShares == null) {
        completeSetFastTopUpMinShares = BigDecimal.ONE;
      }
      if (completeSetFastTopUpMinSecondsAfterFill == null) {
        completeSetFastTopUpMinSecondsAfterFill = 2L;
      }
      if (completeSetFastTopUpMaxSecondsAfterFill == null) {
        completeSetFastTopUpMaxSecondsAfterFill = 120L;
      }
      if (completeSetFastTopUpCooldownMillis == null) {
        completeSetFastTopUpCooldownMillis = 5_000L;
      }
      if (completeSetFastTopUpMinEdge == null) {
        completeSetFastTopUpMinEdge = 0.0;
      }
      if (takerModeEnabled == null) {
        takerModeEnabled = false; // Disabled by default - target user's taker fills come from FAST_TOP_UP, not
                                  // explicit taker mode
      }
      if (takerModeMaxEdge == null) {
        takerModeMaxEdge = 0.015; // Take when edge < 1.5% (low edge = fleeting opportunity)
      }
      if (takerModeMaxSpread == null) {
        takerModeMaxSpread = BigDecimal.valueOf(0.02); // Max 2 ticks spread cost
      }
      if (strictPairingEnabled == null) {
        strictPairingEnabled = false;
      }
      if (maxImbalanceShares == null) {
        maxImbalanceShares = BigDecimal.valueOf(10);
      }
      if (aggressiveHedgeSecondsToEnd == null) {
        aggressiveHedgeSecondsToEnd = 300L;
      }
      if (aggressiveHedgeMinEdge == null) {
        aggressiveHedgeMinEdge = -0.01;
      }
      markets = sanitizeGabagoolMarkets(markets);
    }
  }

  public record GabagoolMarket(
      String slug,
      String upTokenId,
      String downTokenId,
      String endTime // ISO-8601 format
  ) {
  }

  private static List<GabagoolMarket> sanitizeGabagoolMarkets(List<GabagoolMarket> markets) {
    if (markets == null || markets.isEmpty()) {
      return List.of();
    }
    return markets.stream()
        .filter(Objects::nonNull)
        .filter(m -> m.upTokenId() != null && !m.upTokenId().isBlank())
        .filter(m -> m.downTokenId() != null && !m.downTokenId().isBlank())
        .toList();
  }

}
