package com.polybot.ingestor.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class IngestorHttpConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RestClientCustomizer polymarketCommonHeadersRestClientCustomizer(IngestorProperties properties) {
    String userAgent = properties.polymarket().userAgent();
    return builder -> builder
        .defaultHeader(HttpHeaders.USER_AGENT, userAgent);
  }

  @Bean
  public RestClient polymarketDataApiRestClient(
      IngestorProperties properties,
      RestClient.Builder builder
  ) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    return builder
        .baseUrl(properties.polymarket().dataApiBaseUrl().toString())
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, "application/json")
        .build();
  }

  @Bean
  public RestClient polymarketSiteRestClient(RestClient.Builder builder) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    return builder
        .baseUrl("https://polymarket.com")
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html")
        .build();
  }

  @Bean
  public RestClient polymarketGammaApiRestClient(
      IngestorProperties properties,
      RestClient.Builder builder
  ) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    return builder
        .baseUrl(properties.marketContext().gammaApiBaseUrl().toString())
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, "application/json")
        .build();
  }

  @Bean
  public RestClient polymarketClobRestClient(
      IngestorProperties properties,
      RestClient.Builder builder
  ) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    return builder
        .baseUrl(properties.marketContext().clobRestBaseUrl().toString())
        .requestFactory(requestFactory)
        .defaultHeader(HttpHeaders.ACCEPT, "application/json")
        .build();
  }
}
