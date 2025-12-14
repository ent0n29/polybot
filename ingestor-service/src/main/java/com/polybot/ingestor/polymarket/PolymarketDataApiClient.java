package com.polybot.ingestor.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketDataApiClient {

  private final @NonNull @Qualifier("polymarketDataApiRestClient") RestClient polymarketDataApiRestClient;
  private final @NonNull ObjectMapper objectMapper;

  public ArrayNode getTrades(String userAddress, int limit, int offset) {
    return getArray("/trades", userAddress, limit, offset);
  }

  public ArrayNode getPositions(String userAddress, int limit, int offset) {
    return getArray("/positions", userAddress, limit, offset);
  }

  public ArrayNode getMarketTrades(String marketSlug, int limit, int offset) {
    String body = polymarketDataApiRestClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/trades")
            .queryParam("market", marketSlug)
            .queryParam("limit", limit)
            .queryParam("offset", offset)
            .build())
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      return objectMapper.createArrayNode();
    }

    try {
      JsonNode parsed = objectMapper.readTree(body);
      if (parsed instanceof ArrayNode arr) {
        return arr;
      }
      log.warn("Unexpected data-api response type path=/trades market={} limit={} offset={} jsonType={}", marketSlug, limit, offset, parsed.getNodeType());
      return objectMapper.createArrayNode();
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing data-api response path=/trades market=%s limit=%d offset=%d".formatted(marketSlug, limit, offset), e);
    }
  }

  private ArrayNode getArray(String path, String userAddress, int limit, int offset) {
    String body = polymarketDataApiRestClient.get()
        .uri(uriBuilder -> uriBuilder
            .path(path)
            .queryParam("user", userAddress)
            .queryParam("limit", limit)
            .queryParam("offset", offset)
            .build())
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      return objectMapper.createArrayNode();
    }

    try {
      JsonNode parsed = objectMapper.readTree(body);
      if (parsed instanceof ArrayNode arr) {
        return arr;
      }
      log.warn("Unexpected data-api response type path={} user={} limit={} offset={} jsonType={}", path, userAddress, limit, offset, parsed.getNodeType());
      return objectMapper.createArrayNode();
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing data-api response path=%s user=%s limit=%d offset=%d".formatted(path, userAddress, limit, offset), e);
    }
  }
}
