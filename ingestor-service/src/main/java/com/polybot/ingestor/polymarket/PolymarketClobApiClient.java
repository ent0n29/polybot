package com.polybot.ingestor.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketClobApiClient {

  private final @NonNull @Qualifier("polymarketClobRestClient") RestClient polymarketClobRestClient;
  private final @NonNull ObjectMapper objectMapper;

  public JsonNode getOrderBook(String tokenId) {
    String body = polymarketClobRestClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/book")
            .queryParam("token_id", tokenId)
            .build())
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      return objectMapper.createObjectNode();
    }

    try {
      return objectMapper.readTree(body);
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing clob book response tokenId=%s".formatted(tokenId), e);
    }
  }

  public static boolean isNotFoundError(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return false;
    }
    String err = node.path("error").asText(null);
    return err != null && err.toLowerCase().contains("no orderbook exists");
  }
}

