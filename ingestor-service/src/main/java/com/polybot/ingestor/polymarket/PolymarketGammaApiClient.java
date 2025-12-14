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
public class PolymarketGammaApiClient {

  private final @NonNull @Qualifier("polymarketGammaApiRestClient") RestClient polymarketGammaApiRestClient;
  private final @NonNull ObjectMapper objectMapper;

  public GammaMarketSnapshot getMarketSnapshotBySlug(String slug) {
    String body = polymarketGammaApiRestClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/events")
            .queryParam("slug", slug)
            .queryParam("limit", 1)
            .build())
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      return null;
    }

    try {
      JsonNode parsed = objectMapper.readTree(body);
      if (!(parsed instanceof ArrayNode arr)) {
        log.warn("Unexpected gamma response type path=/events slug={} jsonType={}", slug, parsed.getNodeType());
        return null;
      }
      if (arr.isEmpty()) {
        return null;
      }
      JsonNode event = arr.get(0);
      if (event == null || event.isNull()) {
        return null;
      }
      JsonNode market = pickMarket(event, slug);
      if (market == null || market.isMissingNode() || market.isNull()) {
        return null;
      }
      return new GammaMarketSnapshot(slug, event, market);
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing gamma response slug=%s".formatted(slug), e);
    }
  }

  private static JsonNode pickMarket(JsonNode event, String slug) {
    JsonNode markets = event.path("markets");
    if (markets.isArray()) {
      for (JsonNode m : markets) {
        if (m == null || m.isNull()) {
          continue;
        }
        String marketSlug = m.path("slug").asText(null);
        if (slug.equals(marketSlug)) {
          return m;
        }
      }
      if (markets.size() > 0) {
        return markets.get(0);
      }
    }
    return event.path("market");
  }

  public record GammaMarketSnapshot(
      String slug,
      JsonNode event,
      JsonNode market
  ) {
  }
}

