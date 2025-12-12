package com.polymarket.hft.polymarket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.polymarket.clob.PolymarketClobClient;
import com.polymarket.hft.polymarket.http.PolymarketHttpTransport;
import com.polymarket.hft.polymarket.http.RequestRateLimiter;
import com.polymarket.hft.polymarket.http.RetryPolicy;
import com.polymarket.hft.polymarket.http.TokenBucketRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class PolymarketConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public HttpClient httpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  @Bean
  public PolymarketHttpTransport polymarketHttpTransport(
      HftProperties properties,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    HftProperties.Polymarket polymarket = properties.polymarket();
    RequestRateLimiter rateLimiter = buildRateLimiter(polymarket.rest().rateLimit(), clock);
    RetryPolicy retry = buildRetryPolicy(polymarket.rest().retry());
    return new PolymarketHttpTransport(httpClient, objectMapper, rateLimiter, retry);
  }

  @Bean
  public PolymarketClobClient polymarketClobClient(
      HftProperties properties,
      PolymarketHttpTransport transport,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    HftProperties.Polymarket polymarket = properties.polymarket();
    return new PolymarketClobClient(
        URI.create(polymarket.clobRestUrl()),
        transport,
        objectMapper,
        clock,
        polymarket.chainId(),
        polymarket.useServerTime()
    );
  }

  private static RequestRateLimiter buildRateLimiter(HftProperties.RateLimit cfg, Clock clock) {
    if (cfg == null || !cfg.enabled()) {
      return RequestRateLimiter.noop();
    }
    if (cfg.requestsPerSecond() <= 0 || cfg.burst() <= 0) {
      return RequestRateLimiter.noop();
    }
    return new TokenBucketRateLimiter(cfg.requestsPerSecond(), cfg.burst(), clock);
  }

  private static RetryPolicy buildRetryPolicy(HftProperties.Retry cfg) {
    if (cfg == null) {
      return new RetryPolicy(false, 1, 0, 0);
    }
    return new RetryPolicy(
        cfg.enabled(),
        Math.max(1, cfg.maxAttempts()),
        Math.max(0, cfg.initialBackoffMillis()),
        Math.max(0, cfg.maxBackoffMillis())
    );
  }
}
