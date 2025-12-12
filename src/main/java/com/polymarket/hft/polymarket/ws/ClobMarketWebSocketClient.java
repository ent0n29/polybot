package com.polymarket.hft.polymarket.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.config.HftProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class ClobMarketWebSocketClient {

  private final @NonNull HftProperties properties;
  private final @NonNull HttpClient httpClient;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;

  private final Map<String, TopOfBook> topOfBookByAssetId = new ConcurrentHashMap<>();
  private final ScheduledExecutorService pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "clob-ws-ping");
    t.setDaemon(true);
    return t;
  });

  private volatile WebSocket webSocket;
  private volatile boolean started = false;

  private static URI buildMarketWsUri(String baseWsUrl) {
    String base = baseWsUrl.endsWith("/") ? baseWsUrl.substring(0, baseWsUrl.length() - 1) : baseWsUrl;
    return URI.create(base + "/ws/market");
  }

  private static BigDecimal extractBestPrice(JsonNode levels, boolean bestIsMax) {
    if (levels == null || !levels.isArray()) {
      return null;
    }
    BigDecimal best = null;
    for (JsonNode level : levels) {
      BigDecimal p = parseDecimal(level.path("price").asText(null));
      if (p == null) {
        continue;
      }
      if (best == null) {
        best = p;
      } else if (bestIsMax && p.compareTo(best) > 0) {
        best = p;
      } else if (!bestIsMax && p.compareTo(best) < 0) {
        best = p;
      }
    }
    return best;
  }

  private static BigDecimal parseDecimal(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    return new BigDecimal(s);
  }

  public Optional<TopOfBook> getTopOfBook(String assetId) {
    return Optional.ofNullable(topOfBookByAssetId.get(assetId));
  }

  public boolean isStarted() {
    return started;
  }

  @PostConstruct
  void startIfEnabled() {
    HftProperties.Polymarket polymarket = properties.polymarket();
    if (!polymarket.marketWsEnabled()) {
      return;
    }
    List<String> assets = polymarket.marketAssetIds();
    if (assets == null || assets.isEmpty()) {
      log.warn("Market WS enabled, but no asset IDs configured (hft.polymarket.market-asset-ids).");
      return;
    }
    connect(assets);
  }

  private void connect(List<String> assetIds) {
    if (started) {
      return;
    }
    started = true;

    URI wsUri = buildMarketWsUri(properties.polymarket().clobWsUrl());
    log.info("Connecting to CLOB market websocket: {}", wsUri);

    CompletableFuture<WebSocket> cf = httpClient.newWebSocketBuilder()
        .buildAsync(wsUri, new Listener(assetIds));
    this.webSocket = cf.join();

    pingExecutor.scheduleAtFixedRate(() -> {
      WebSocket ws = this.webSocket;
      if (ws != null) {
        ws.sendText("PING", true);
      }
    }, 10, 10, TimeUnit.SECONDS);
  }

  @PreDestroy
  void shutdown() {
    WebSocket ws = this.webSocket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
      } catch (Exception ignored) {
      }
    }
    pingExecutor.shutdownNow();
  }

  private String buildSubscribeMessage(List<String> assetIds) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "assets_ids", assetIds,
          "type", "market"
      ));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build market ws subscribe message", e);
    }
  }

  private void handleMessage(String message) {
    if ("PONG".equalsIgnoreCase(message) || "PING".equalsIgnoreCase(message)) {
      return;
    }
    try {
      JsonNode node = objectMapper.readTree(message);
      String eventType = node.path("event_type").asText("");
      switch (eventType) {
        case "book" -> handleBook(node);
        case "price_change" -> handlePriceChange(node);
        case "last_trade_price" -> handleLastTradePrice(node);
        default -> {
        }
      }
    } catch (Exception e) {
      log.debug("Failed to parse ws message: {}", message);
    }
  }

  private void handleBook(JsonNode node) {
    String assetId = node.path("asset_id").asText(null);
    if (assetId == null) {
      return;
    }
    JsonNode bidsNode = node.has("bids") ? node.get("bids") : node.get("buys");
    JsonNode asksNode = node.has("asks") ? node.get("asks") : node.get("sells");

    BigDecimal bestBid = extractBestPrice(bidsNode, true);
    BigDecimal bestAsk = extractBestPrice(asksNode, false);
    TopOfBook prev = topOfBookByAssetId.get(assetId);

    topOfBookByAssetId.put(assetId, new TopOfBook(
        bestBid,
        bestAsk,
        prev == null ? null : prev.lastTradePrice(),
        Instant.now(clock)
    ));
  }

  private void handlePriceChange(JsonNode node) {
    JsonNode changes = node.path("price_changes");
    if (!changes.isArray()) {
      return;
    }
    Instant now = Instant.now(clock);
    for (JsonNode change : changes) {
      String assetId = change.path("asset_id").asText(null);
      if (assetId == null) {
        continue;
      }
      BigDecimal bestBid = parseDecimal(change.path("best_bid").asText(null));
      BigDecimal bestAsk = parseDecimal(change.path("best_ask").asText(null));
      TopOfBook prev = topOfBookByAssetId.get(assetId);
      topOfBookByAssetId.put(assetId, new TopOfBook(
          bestBid != null ? bestBid : (prev == null ? null : prev.bestBid()),
          bestAsk != null ? bestAsk : (prev == null ? null : prev.bestAsk()),
          prev == null ? null : prev.lastTradePrice(),
          now
      ));
    }
  }

  private void handleLastTradePrice(JsonNode node) {
    String assetId = node.path("asset_id").asText(null);
    if (assetId == null) {
      return;
    }
    BigDecimal price = parseDecimal(node.path("price").asText(null));
    TopOfBook prev = topOfBookByAssetId.get(assetId);
    topOfBookByAssetId.put(assetId, new TopOfBook(
        prev == null ? null : prev.bestBid(),
        prev == null ? null : prev.bestAsk(),
        price,
        Instant.now(clock)
    ));
  }

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder buf = new StringBuilder(8192);
    private final List<String> assetIds;

    private Listener(List<String> assetIds) {
      this.assetIds = assetIds;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      String subscribe = buildSubscribeMessage(assetIds);
      webSocket.sendText(subscribe, true);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      buf.append(data);
      if (last) {
        String message = buf.toString();
        buf.setLength(0);
        handleMessage(message);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      log.warn("CLOB market websocket closed (status={}, reason={})", statusCode, reason);
      started = false;
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      log.warn("CLOB market websocket error: {}", error.toString());
      started = false;
    }
  }
}
