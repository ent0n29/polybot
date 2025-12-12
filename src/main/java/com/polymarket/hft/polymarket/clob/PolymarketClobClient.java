package com.polymarket.hft.polymarket.clob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.polymarket.auth.PolymarketAuthHeaders;
import com.polymarket.hft.polymarket.http.PolymarketHttpTransport;
import com.polymarket.hft.polymarket.http.HttpHeadersUtil;
import com.polymarket.hft.polymarket.http.HttpRequestFactory;
import com.polymarket.hft.polymarket.model.ApiCreds;
import com.polymarket.hft.polymarket.model.ClobOrderType;
import com.polymarket.hft.polymarket.model.OrderBook;
import com.polymarket.hft.polymarket.model.SignedOrder;
import org.springframework.http.HttpMethod;
import org.web3j.crypto.Credentials;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class PolymarketClobClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

  private final HttpRequestFactory requestFactory;
  private final PolymarketHttpTransport transport;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final int chainId;
  private final boolean useServerTime;

  private final AtomicLong serverTimeOffsetSeconds = new AtomicLong(0);
  private final Duration serverTimeTtl = Duration.ofSeconds(30);
  private volatile Instant lastServerTimeSync = Instant.EPOCH;

  public PolymarketClobClient(
      URI baseUri,
      PolymarketHttpTransport transport,
      ObjectMapper objectMapper,
      Clock clock,
      int chainId,
      boolean useServerTime
  ) {
    this.requestFactory = new HttpRequestFactory(Objects.requireNonNull(baseUri, "baseUri"));
    this.transport = Objects.requireNonNull(transport, "transport");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.chainId = chainId;
    this.useServerTime = useServerTime;
  }

  public long getServerTimeSeconds() {
    String raw = getString(PolymarketClobPaths.TIME, Map.of(), Map.of());
    return Long.parseLong(raw.trim());
  }

  public OrderBook getOrderBook(String tokenId) {
    return getJson(PolymarketClobPaths.BOOK, Map.of("token_id", tokenId), Map.of(), OrderBook.class);
  }

  public BigDecimal getMinimumTickSize(String tokenId) {
    JsonNode node = getJsonNode(PolymarketClobPaths.TICK_SIZE, Map.of("token_id", tokenId), Map.of());
    return node.get("minimum_tick_size").decimalValue();
  }

  public boolean isNegRisk(String tokenId) {
    JsonNode node = getJsonNode(PolymarketClobPaths.NEG_RISK, Map.of("token_id", tokenId), Map.of());
    return node.get("neg_risk").booleanValue();
  }

  public int getBaseFeeBps(String tokenId) {
    JsonNode node = getJsonNode(PolymarketClobPaths.FEE_RATE, Map.of("token_id", tokenId), Map.of());
    return node.get("base_fee").intValue();
  }

  public ApiCreds createApiCreds(Credentials signingCredentials, long nonce) {
    return l1Auth(signingCredentials, HttpMethod.POST, PolymarketClobPaths.AUTH_API_KEY, null, nonce, ApiCredsRaw.class)
        .toCreds();
  }

  public ApiCreds deriveApiCreds(Credentials signingCredentials, long nonce) {
    return l1Auth(signingCredentials, HttpMethod.GET, PolymarketClobPaths.AUTH_DERIVE_API_KEY, null, nonce, ApiCredsRaw.class)
        .toCreds();
  }

  public ApiCreds createOrDeriveApiCreds(Credentials signingCredentials, long nonce) {
    try {
      ApiCreds created = createApiCreds(signingCredentials, nonce);
      if (created.key() != null && !created.key().isBlank()) {
        return created;
      }
    } catch (RuntimeException ignored) {
      // fall through to derive
    }
    return deriveApiCreds(signingCredentials, nonce);
  }

  public JsonNode postOrder(
      Credentials signingCredentials,
      ApiCreds apiCreds,
      SignedOrder order,
      ClobOrderType orderType,
      boolean deferExec
  ) {
    Objects.requireNonNull(order, "order");
    if (order.signature() == null || order.signature().isBlank()) {
      throw new IllegalArgumentException("order.signature must be set");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    Map<String, Object> orderObj = new LinkedHashMap<>();
    orderObj.put("salt", Long.parseLong(order.salt()));
    orderObj.put("maker", order.maker());
    orderObj.put("signer", order.signer());
    orderObj.put("taker", order.taker());
    orderObj.put("tokenId", order.tokenId());
    orderObj.put("makerAmount", order.makerAmount());
    orderObj.put("takerAmount", order.takerAmount());
    orderObj.put("expiration", order.expiration());
    orderObj.put("nonce", order.nonce());
    orderObj.put("feeRateBps", order.feeRateBps());
    orderObj.put("side", order.side().name());
    orderObj.put("signatureType", order.signatureType());
    orderObj.put("signature", order.signature());
    payload.put("order", orderObj);
    payload.put("owner", apiCreds.key());
    payload.put("orderType", orderType.name());
    payload.put("deferExec", deferExec);

    String body = writeJson(payload);
    Map<String, String> headers = l2Headers(signingCredentials, apiCreds, HttpMethod.POST, PolymarketClobPaths.ORDER, body);
    return postJsonNode(PolymarketClobPaths.ORDER, Map.of(), headers, body);
  }

  public JsonNode cancelOrder(Credentials signingCredentials, ApiCreds apiCreds, String orderId) {
    Map<String, Object> payload = Map.of("orderID", orderId);
    String body = writeJson(payload);
    Map<String, String> headers = l2Headers(signingCredentials, apiCreds, HttpMethod.DELETE, PolymarketClobPaths.ORDER, body);
    return deleteJsonNode(PolymarketClobPaths.ORDER, headers, body);
  }

  private <T> T l1Auth(
      Credentials signingCredentials,
      HttpMethod method,
      String path,
      String body,
      long nonce,
      Class<T> responseType
  ) {
    long ts = authTimestampSeconds();
    Map<String, String> headers = PolymarketAuthHeaders.l1(signingCredentials, chainId, ts, nonce);

    if (method == HttpMethod.GET) {
      return getJson(path, Map.of(), headers, responseType);
    }
    if (method == HttpMethod.POST) {
      return postJson(path, Map.of(), headers, body, responseType);
    }
    throw new IllegalArgumentException("Unsupported L1 method: " + method);
  }

  private Map<String, String> l2Headers(
      Credentials signingCredentials,
      ApiCreds creds,
      HttpMethod method,
      String requestPath,
      String body
  ) {
    long ts = authTimestampSeconds();
    return PolymarketAuthHeaders.l2(signingCredentials, creds, ts, method, requestPath, body);
  }

  private long authTimestampSeconds() {
    if (!useServerTime) {
      return Instant.now(clock).getEpochSecond();
    }

    Instant now = Instant.now(clock);
    if (Duration.between(lastServerTimeSync, now).compareTo(serverTimeTtl) > 0) {
      long serverTime = getServerTimeSeconds();
      long local = now.getEpochSecond();
      serverTimeOffsetSeconds.set(serverTime - local);
      lastServerTimeSync = now;
    }
    return now.getEpochSecond() + serverTimeOffsetSeconds.get();
  }

  private JsonNode getJsonNode(String path, Map<String, String> query, Map<String, String> headers) {
    return getJson(path, query, headers, JsonNode.class);
  }

  private <T> T getJson(String path, Map<String, String> query, Map<String, String> headers, Class<T> type) {
    HttpRequest.Builder builder = requestFactory.request(path, query)
        .GET()
        .timeout(HTTP_TIMEOUT);
    HttpHeadersUtil.apply(builder, headers);
    HttpRequest request = builder.build();
    return sendJson(request, type);
  }

  private JsonNode postJsonNode(String path, Map<String, String> query, Map<String, String> headers, String body) {
    return postJson(path, query, headers, body, JsonNode.class);
  }

  private <T> T postJson(String path, Map<String, String> query, Map<String, String> headers, String body, Class<T> type) {
    HttpRequest.Builder builder = requestFactory.request(path, query)
        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
        .timeout(HTTP_TIMEOUT)
        .header("Content-Type", "application/json");
    HttpHeadersUtil.apply(builder, headers);
    HttpRequest request = builder.build();
    return sendJson(request, type);
  }

  private JsonNode deleteJsonNode(String path, Map<String, String> headers, String body) {
    HttpRequest.Builder builder = requestFactory.request(path, Map.of())
        .method("DELETE", HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
        .timeout(HTTP_TIMEOUT)
        .header("Content-Type", "application/json");
    HttpHeadersUtil.apply(builder, headers);
    HttpRequest request = builder.build();
    return sendJson(request, JsonNode.class);
  }

  private String getString(String path, Map<String, String> query, Map<String, String> headers) {
    HttpRequest.Builder builder = requestFactory.request(path, query)
        .GET()
        .timeout(HTTP_TIMEOUT);
    HttpHeadersUtil.apply(builder, headers);
    HttpRequest request = builder.build();
    return sendString(request);
  }

  private <T> T sendJson(HttpRequest request, Class<T> type) {
    return transport.sendJson(request, type);
  }

  private String sendString(HttpRequest request) {
    boolean idempotent = "GET".equalsIgnoreCase(request.method()) || "HEAD".equalsIgnoreCase(request.method());
    return transport.sendString(request, idempotent);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException e) {
      throw new RuntimeException("Failed to encode JSON", e);
    }
  }

  private record ApiCredsRaw(String apiKey, String secret, String passphrase) {
    ApiCreds toCreds() {
      return new ApiCreds(
          Optional.ofNullable(apiKey).orElse(""),
          Optional.ofNullable(secret).orElse(""),
          Optional.ofNullable(passphrase).orElse("")
      );
    }
  }
}
