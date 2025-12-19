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
@ConfigurationProperties(prefix="hft")
public record HftProperties(
    TradingMode mode,
    @Valid Polymarket polymarket,
    @Valid Executor executor,
    @Valid Risk risk,
    @Valid Strategy strategy
) {

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
    return new Polymarket(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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

  public record Executor(
      String baseUrl,
      @NotNull Boolean sendLiveAck
  ) {
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
       * Optional path to persist the market WS top-of-book cache (JSON). When blank, disabled.
       * Useful to warm-start after restarts (avoids an empty TOB cache until the first WS update).
       */
      String marketWsCachePath,
      /**
       * Flush interval for the WS cache snapshot. Ignored when {@code marketWsCachePath} is blank.
       */
      @NotNull @PositiveOrZero Long marketWsCacheFlushMillis,
      /**
       * Treat the WS as stale when no messages (including PONG) are received for this long.
       * When stale and {@code marketWsEnabled=true} with active subscriptions, the client auto-reconnects.
       * Set to 0 to disable.
       */
      @NotNull @PositiveOrZero Long marketWsStaleTimeoutMillis,
      /**
       * Minimum interval between reconnect attempts when the WS is stale/disconnected.
       */
      @NotNull @PositiveOrZero Long marketWsReconnectBackoffMillis
  ) {
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
      @NotNull @PositiveOrZero Integer burst
  ) {
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
      @NotNull @PositiveOrZero Long maxBackoffMillis
  ) {
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
      @NotNull Boolean autoCreateOrDeriveApiCreds
  ) {
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
      @NotNull @PositiveOrZero BigDecimal maxOrderSize
  ) {
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
    return new Gabagool(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * Gabagool-style Up/Down strategy configuration.
   * Based on reverse-engineering gabagool22's trading patterns.
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
       * Target order size in USDC notional (approx. {@code entryPrice * shares} for BUY orders).
       */
      @NotNull @PositiveOrZero BigDecimal quoteSize,
      /**
       * Optional bankroll-based sizing target (0..1). When > 0 and {@code bankrollUsd > 0}, the strategy uses
       * {@code bankrollUsd * quoteSizeBankrollFraction} as the base order notional instead of {@code quoteSize}.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double quoteSizeBankrollFraction,
      @NotNull @PositiveOrZero Double imbalanceThreshold,
      @NotNull @Min(0) Integer improveTicks,
      /**
       * Optional bankroll (USDC) to enable fractional sizing caps.
       * When 0, bankroll-based caps are disabled.
       */
      @NotNull @PositiveOrZero BigDecimal bankrollUsd,
      /**
       * Optional cap per order as a fraction of {@code bankrollUsd} (0..1). When 0, disabled.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double maxOrderBankrollFraction,
      /**
       * Optional cap for total exposure as a fraction of {@code bankrollUsd} (0..1). When 0, disabled.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double maxTotalBankrollFraction,
      /**
       * Minimum complete-set edge required to quote both outcomes (edge = 1 - (p_up + p_down)).
       *
       * Typical observed maker-side edges for gabagool22 are ~0.01–0.02 when WS TOB is fresh.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("1.0") Double completeSetMinEdge,
      /**
       * Maximum inventory skew (in ticks) applied to one leg and subtracted from the other.
       */
      @NotNull @Min(0) Integer completeSetMaxSkewTicks,
      /**
       * Share imbalance at which max skew is applied (linear ramp from 0..max).
       */
      @NotNull @PositiveOrZero BigDecimal completeSetImbalanceSharesForMaxSkew,
      /**
       * When enabled, occasionally cross the spread (taker-like) on the lagging leg to rebalance inventory
       * near market end.
       */
      @NotNull Boolean completeSetTopUpEnabled,
      /**
       * Only perform top-ups when {@code secondsToEnd <= completeSetTopUpSecondsToEnd}.
       */
      @NotNull @Min(0) Long completeSetTopUpSecondsToEnd,
      /**
       * Only perform top-ups when the per-market share imbalance is at least this amount.
       */
      @NotNull @PositiveOrZero BigDecimal completeSetTopUpMinShares,
      /**
       * Enable directional bias based on order book imbalance.
       * When enabled, quotes more aggressively on the side favored by book imbalance.
       * Based on gabagool22's observed behavior: he tilts 5-7x toward the side with stronger book support.
       */
      @NotNull Boolean directionalBiasEnabled,
      /**
       * Multiplier for sizing on the favored side when book imbalance exceeds threshold.
       * The unfavored side gets 1/factor (e.g., factor=1.5 means favored=1.5x, unfavored=0.67x).
       * Gabagool22 shows ratios of 5-7x, but we start conservatively.
       */
      @NotNull @PositiveOrZero @jakarta.validation.constraints.DecimalMax("3.0") Double directionalBiasFactor,
      /**
       * Enable taker mode for aggressive order placement.
       * When enabled, the strategy will sometimes cross the spread (buy at ask) instead of posting at bid.
       * Based on gabagool22's behavior: ~39% of trades are taker fills.
       */
      @NotNull Boolean takerModeEnabled,
      /**
       * Maximum edge threshold for taker mode. When edge < this value and spread is tight,
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
      @Valid List<GabagoolMarket> markets
  ) {
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
      if (imbalanceThreshold == null) {
        imbalanceThreshold = 0.05;
      }
      if (improveTicks == null) {
        improveTicks = 1;
      }
      if (bankrollUsd == null) {
        bankrollUsd = BigDecimal.ZERO;
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
      if (directionalBiasEnabled == null) {
        directionalBiasEnabled = false;  // Disabled by default for safety
      }
      if (directionalBiasFactor == null) {
        directionalBiasFactor = 1.5;  // Conservative: 1.5x favored, 0.67x unfavored
      }
      if (takerModeEnabled == null) {
        takerModeEnabled = true;  // Enabled by default - gabagool22 takes ~39% of the time
      }
      if (takerModeMaxEdge == null) {
        takerModeMaxEdge = 0.015;  // Take when edge < 1.5% (low edge = fleeting opportunity)
      }
      if (takerModeMaxSpread == null) {
        takerModeMaxSpread = BigDecimal.valueOf(0.02);  // Max 2 ticks spread cost
      }
      markets = sanitizeGabagoolMarkets(markets);
    }
  }

  public record GabagoolMarket(
      String slug,
      String upTokenId,
      String downTokenId,
      String endTime  // ISO-8601 format
  ) {}

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
