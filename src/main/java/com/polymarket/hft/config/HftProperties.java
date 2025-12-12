package com.polymarket.hft.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "hft")
public record HftProperties(
    TradingMode mode,
    @Valid Polymarket polymarket,
    @Valid Risk risk,
    @Valid Strategy strategy
) {

  public HftProperties {
    if (mode == null) {
      mode = TradingMode.PAPER;
    }
    if (polymarket == null) {
      polymarket = new Polymarket();
    }
    if (risk == null) {
      risk = new Risk();
    }
    if (strategy == null) {
      strategy = new Strategy();
    }
  }

  public enum TradingMode {
    PAPER,
    LIVE,
  }

  public record Polymarket(
      String clobRestUrl,
      String clobWsUrl,
      @Min(1) Integer chainId,
      Boolean useServerTime,
      Boolean marketWsEnabled,
      Boolean userWsEnabled,
      List<String> marketAssetIds,
      List<String> userMarketIds,
      @Valid Rest rest,
      @Valid Auth auth
  ) {
    public Polymarket {
      if (clobRestUrl == null || clobRestUrl.isBlank()) {
        clobRestUrl = "https://clob.polymarket.com";
      }
      if (clobWsUrl == null || clobWsUrl.isBlank()) {
        clobWsUrl = "wss://ws-subscriptions-clob.polymarket.com";
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
      marketAssetIds = marketAssetIds == null ? List.of() : List.copyOf(marketAssetIds);
      userMarketIds = userMarketIds == null ? List.of() : List.copyOf(userMarketIds);
      if (rest == null) {
        rest = new Rest();
      }
      if (auth == null) {
        auth = new Auth();
      }
    }

    public Polymarket() {
      this(null, null, null, null, null, null, null, null, null, null);
    }
  }

  public record Rest(@Valid RateLimit rateLimit, @Valid Retry retry) {
    public Rest {
      if (rateLimit == null) {
        rateLimit = new RateLimit();
      }
      if (retry == null) {
        retry = new Retry();
      }
    }

    public Rest() {
      this(null, null);
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

    public RateLimit() {
      this(null, null, null);
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

    public Retry() {
      this(null, null, null, null);
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

    public Auth() {
      this(null, null, null, null, null, null, null, null);
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

    public Risk() {
      this(false, null, null);
    }
  }

  public record Strategy(@Valid MidpointMaker midpointMaker) {
    public Strategy {
      if (midpointMaker == null) {
        midpointMaker = new MidpointMaker();
      }
    }

    public Strategy() {
      this(null);
    }
  }

  public record MidpointMaker(
      boolean enabled,
      @NotNull @PositiveOrZero BigDecimal quoteSize,
      @NotNull @PositiveOrZero BigDecimal spread,
      @NotNull @Min(1) Long refreshMillis
  ) {
    public MidpointMaker {
      if (quoteSize == null) {
        quoteSize = BigDecimal.valueOf(5);
      }
      if (spread == null) {
        spread = BigDecimal.valueOf(0.01);
      }
      if (refreshMillis == null) {
        refreshMillis = 1_000L;
      }
    }

    public MidpointMaker() {
      this(false, null, null, null);
    }
  }
}

