package com.polybot.ingestor.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "ingestor")
public record IngestorProperties(
    @Valid Polymarket polymarket,
    @Valid Polling polling,
    @Valid MarketContext marketContext
) {

  public IngestorProperties {
    if (polymarket == null) {
      polymarket = new Polymarket(null, null, null, null);
    }
    if (polling == null) {
      polling = new Polling(null, null, null, null, null, null);
    }
    if (marketContext == null) {
      marketContext = new MarketContext(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
  }

  public record Polymarket(
      URI dataApiBaseUrl,
      String username,
      String proxyAddress,
      String userAgent
  ) {
    public Polymarket {
      if (dataApiBaseUrl == null) {
        dataApiBaseUrl = URI.create("https://data-api.polymarket.com");
      }
      if (userAgent == null || userAgent.isBlank()) {
        userAgent = "polybot-ingestor/1.0";
      }
      if (username != null) {
        username = username.trim();
        if (username.startsWith("@")) {
          username = username.substring(1);
        }
      }
      if (proxyAddress != null) {
        proxyAddress = proxyAddress.trim();
      }
    }

    public boolean hasIdentity() {
      return (username != null && !username.isBlank()) || (proxyAddress != null && !proxyAddress.isBlank());
    }
  }

  public record Polling(
      @NotNull Boolean enabled,
      @NotNull Boolean backfillOnStart,
      @NotNull @Min(1) Integer pollIntervalSeconds,
      @NotNull @Min(1) Integer pageSize,
      @NotNull @PositiveOrZero Long requestDelayMillis,
      @PositiveOrZero Integer backfillMaxPages
  ) {
    public Polling {
      if (enabled == null) {
        enabled = true;
      }
      if (backfillOnStart == null) {
        backfillOnStart = false;
      }
      if (pollIntervalSeconds == null) {
        pollIntervalSeconds = 15;
      }
      if (pageSize == null) {
        pageSize = 100;
      }
      if (requestDelayMillis == null) {
        requestDelayMillis = 250L;
      }
    }
  }

  public record MarketContext(
      @NotNull Boolean enabled,
      URI gammaApiBaseUrl,
      URI clobRestBaseUrl,
      @NotNull @Min(1) Integer marketTradesLimit,
      @NotNull @Min(1) Integer marketTradesMinIntervalSeconds,
      @NotNull @Min(1) Integer clobBookMinIntervalSeconds,
      @NotNull @Min(1) Integer gammaMinIntervalSeconds,
      @NotNull @Min(1) Integer gammaPollIntervalSeconds,
      @NotNull @Min(1) Integer gammaPollLookaheadSeconds,
      @NotNull @Min(1) Integer gammaPollMaxIdleSeconds,
      @NotNull @PositiveOrZero Long requestDelayMillis,
      @NotNull @Min(1) Integer maxTrackedMarkets,
      @NotNull @PositiveOrZero Integer onTradeContextMaxAgeSeconds
  ) {
    public MarketContext {
      if (enabled == null) {
        enabled = true;
      }
      if (gammaApiBaseUrl == null) {
        gammaApiBaseUrl = URI.create("https://gamma-api.polymarket.com");
      }
      if (clobRestBaseUrl == null) {
        clobRestBaseUrl = URI.create("https://clob.polymarket.com");
      }
      if (marketTradesLimit == null) {
        marketTradesLimit = 50;
      }
      if (marketTradesMinIntervalSeconds == null) {
        marketTradesMinIntervalSeconds = 10;
      }
      if (clobBookMinIntervalSeconds == null) {
        clobBookMinIntervalSeconds = 1;
      }
      if (gammaMinIntervalSeconds == null) {
        gammaMinIntervalSeconds = 60;
      }
      if (gammaPollIntervalSeconds == null) {
        gammaPollIntervalSeconds = 60;
      }
      if (gammaPollLookaheadSeconds == null) {
        gammaPollLookaheadSeconds = 3600;
      }
      if (gammaPollMaxIdleSeconds == null) {
        gammaPollMaxIdleSeconds = 6 * 3600;
      }
      if (requestDelayMillis == null) {
        requestDelayMillis = 100L;
      }
      if (maxTrackedMarkets == null) {
        maxTrackedMarkets = 500;
      }
      if (onTradeContextMaxAgeSeconds == null) {
        onTradeContextMaxAgeSeconds = 300;
      }
    }
  }
}
