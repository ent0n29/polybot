package com.polybot.hft.executor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.executor.events.ExecutorCancelOrderEvent;
import com.polybot.hft.executor.events.ExecutorLimitOrderEvent;
import com.polybot.hft.executor.events.ExecutorMarketOrderEvent;
import com.polybot.hft.executor.events.ExecutorOrderError;
import com.polybot.hft.executor.portfolio.PolymarketBankrollService;
import com.polybot.hft.executor.metrics.ExecutorMetricsService;
import com.polybot.hft.executor.order.ExecutorOrderMonitor;
import com.polybot.hft.executor.sim.PaperExchangeSimulator;
import com.polybot.hft.polymarket.api.PolymarketAccountResponse;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.MarketOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.api.PolymarketBankrollResponse;
import com.polybot.hft.polymarket.api.PolymarketHealthResponse;
import com.polybot.hft.polymarket.auth.PolymarketAuthContext;
import com.polybot.hft.polymarket.data.PolymarketDataApiClient;
import com.polybot.hft.polymarket.http.PolymarketHttpException;
import com.polybot.hft.polymarket.model.OrderBook;
import com.polybot.hft.polymarket.service.PolymarketTradingService;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.web3j.crypto.Credentials;

@RestController
@RequestMapping("/api/polymarket")
@Validated
@RequiredArgsConstructor
@Slf4j
public class PolymarketController {

  private static final int ERROR_MAX_LEN = 512;

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketAuthContext authContext;
  private final @NonNull PolymarketTradingService tradingService;
  private final @NonNull ClobMarketWebSocketClient marketWebSocketClient;
  private final @NonNull PolymarketDataApiClient dataApiClient;
  private final @NonNull PolymarketBankrollService bankrollService;
  private final @NonNull HftEventPublisher events;
  private final @NonNull ExecutorOrderMonitor orderMonitor;
  private final @NonNull PaperExchangeSimulator simulator;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull ExecutorMetricsService metricsService;

  private static String normalizeAddress(String address) {
    if (address == null) {
      return null;
    }
    String trimmed = address.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.toLowerCase();
  }

  private String signerAddress() {
    return authContext.signerCredentials()
        .map(Credentials::getAddress)
        .map(PolymarketController::normalizeAddress)
        .orElse(null);
  }

  private String funderAddress() {
    return authContext.funderAddress().map(PolymarketController::normalizeAddress).orElse(null);
  }

  private String makerAddress() {
    String funder = funderAddress();
    return funder != null ? funder : signerAddress();
  }

  @GetMapping("/health")
  public ResponseEntity<PolymarketHealthResponse> getHealth(
      @RequestParam(name = "deep", required = false, defaultValue = "false") boolean deep,
      @RequestParam(name = "tokenId", required = false) String tokenId) {
    log.info("api /health deep={} tokenId={}", deep, tokenId);
    return ResponseEntity.ok(tradingService.getHealth(deep, tokenId));
  }

  @GetMapping("/account")
  public ResponseEntity<PolymarketAccountResponse> getAccount() {
    return ResponseEntity.ok(
        new PolymarketAccountResponse(
            properties.mode().name(),
            signerAddress(),
            makerAddress(),
            funderAddress()));
  }

  @GetMapping("/bankroll")
  public ResponseEntity<PolymarketBankrollResponse> getBankroll() {
    return ResponseEntity.ok(bankrollService.snapshot());
  }

  @GetMapping("/positions")
  public ResponseEntity<JsonNode> getPositions(
      @RequestParam(name = "user", required = false) String user,
      @RequestParam(name = "limit", required = false, defaultValue = "200") int limit,
      @RequestParam(name = "offset", required = false, defaultValue = "0") int offset) {
    if (simulator.enabled()) {
      return ResponseEntity.ok(objectMapper.valueToTree(simulator.getPositions(limit, offset)));
    }
    String resolvedUser = (user != null && !user.isBlank()) ? user : makerAddress();
    if (resolvedUser == null || resolvedUser.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    log.info("api /positions user={} limit={} offset={}", resolvedUser, limit, offset);
    return ResponseEntity.ok(dataApiClient.getPositions(resolvedUser, limit, offset));
  }

  @GetMapping("/orderbook/{tokenId}")
  public ResponseEntity<OrderBook> getOrderBook(@PathVariable String tokenId) {
    log.info("api /orderbook tokenId={}", tokenId);
    return ResponseEntity.ok(tradingService.getOrderBook(tokenId));
  }

  @GetMapping("/tick-size/{tokenId}")
  public ResponseEntity<BigDecimal> getTickSize(@PathVariable String tokenId) {
    log.info("api /tick-size tokenId={}", tokenId);
    return ResponseEntity.ok(tradingService.getTickSize(tokenId));
  }

  @GetMapping("/neg-risk/{tokenId}")
  public ResponseEntity<Boolean> isNegRisk(@PathVariable String tokenId) {
    log.info("api /neg-risk tokenId={}", tokenId);
    return ResponseEntity.ok(tradingService.isNegRisk(tokenId));
  }

  @GetMapping("/marketdata/top/{tokenId}")
  public ResponseEntity<TopOfBook> getTopOfBook(@PathVariable String tokenId) {
    log.info("api /marketdata/top tokenId={}", tokenId);
    return marketWebSocketClient.getTopOfBook(tokenId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/orders/limit")
  public ResponseEntity<OrderSubmissionResult> placeLimitOrder(@Valid @RequestBody LimitOrderRequest request) {
    log.info("api /orders/limit tokenId={} side={} price={} size={} orderType={}",
        request.tokenId(), request.side(), request.price(), request.size(), request.orderType());
    try {
      metricsService.recordOrderPlaced();
      OrderSubmissionResult result = simulator.enabled()
          ? simulator.placeLimitOrder(request)
          : tradingService.placeLimitOrder(request);
      String orderId = resolveOrderId(result);
      if (!simulator.enabled() && orderId != null && !orderId.isBlank()) {
        orderMonitor.trackNewOrder(orderId, request.tokenId(), request.side(), request.price(), request.size());
      }
      safePublishLimitOrderEvent(request, result, null);
      return ResponseEntity.ok(result);
    } catch (RuntimeException e) {
      safePublishLimitOrderEvent(request, null, e);
      throw e;
    }
  }

  @PostMapping("/orders/market")
  public ResponseEntity<OrderSubmissionResult> placeMarketOrder(@Valid @RequestBody MarketOrderRequest request) {
    log.info("api /orders/market tokenId={} side={} amount={} price={} orderType={}",
        request.tokenId(), request.side(), request.amount(), request.price(), request.orderType());
    try {
      metricsService.recordOrderPlaced();
      OrderSubmissionResult result = simulator.enabled()
          ? simulator.placeMarketOrder(request)
          : tradingService.placeMarketOrder(request);
      String orderId = resolveOrderId(result);
      if (!simulator.enabled() && orderId != null && !orderId.isBlank()) {
        // For BUY market orders, request.amount is USDC; size in shares is unknown
        // without querying.
        BigDecimal size = request.side() == OrderSide.SELL ? request.amount() : null;
        orderMonitor.trackNewOrder(orderId, request.tokenId(), request.side(), request.price(), size);
      }
      safePublishMarketOrderEvent(request, result, null);
      return ResponseEntity.ok(result);
    } catch (RuntimeException e) {
      safePublishMarketOrderEvent(request, null, e);
      throw e;
    }
  }

  @PostMapping("/orders/merge")
  public ResponseEntity<JsonNode> mergePositions(
      @RequestParam(name = "upTokenId") String upTokenId,
      @RequestParam(name = "downTokenId") String downTokenId,
      @RequestParam(name = "amount") BigDecimal amount) {
    if (!simulator.enabled()) {
      return ResponseEntity.status(405).build(); // Not allowed for live
    }
    log.info("api /orders/merge amount={} up={} down={}", amount, upTokenId, downTokenId);
    return ResponseEntity.ok(simulator.mergePositions(upTokenId, downTokenId, amount));
  }

  @PostMapping("/orders/split")
  public ResponseEntity<JsonNode> splitPosition(
      @RequestParam(name = "upTokenId") String upTokenId,
      @RequestParam(name = "downTokenId") String downTokenId,
      @RequestParam(name = "amount") BigDecimal amount) {
    if (!simulator.enabled()) {
      return ResponseEntity.status(405).build(); // Not allowed for live
    }
    log.info("api /orders/split amount={} up={} down={}", amount, upTokenId, downTokenId);
    return ResponseEntity.ok(simulator.splitPosition(upTokenId, downTokenId, amount));
  }

  @DeleteMapping("/orders/{orderId}")
  public ResponseEntity<JsonNode> cancelOrder(@PathVariable String orderId) {
    log.info("api /orders/cancel orderId={}", orderId);
    try {
      JsonNode result = simulator.enabled()
          ? simulator.cancelOrder(orderId)
          : tradingService.cancelOrder(orderId);
      safePublishCancelOrderEvent(orderId, result, null);
      return ResponseEntity.ok(result);
    } catch (RuntimeException e) {
      safePublishCancelOrderEvent(orderId, null, e);
      throw e;
    }
  }

  @GetMapping("/orders/{orderId}")
  public ResponseEntity<JsonNode> getOrder(@PathVariable String orderId) {
    log.info("api /orders/get orderId={}", orderId);
    return ResponseEntity.ok(simulator.enabled() ? simulator.getOrder(orderId) : tradingService.getOrder(orderId));
  }

  @GetMapping("/orders")
  public ResponseEntity<JsonNode> getOrders(
      @RequestParam(name = "market", required = false) String market,
      @RequestParam(name = "asset_id", required = false) String assetId,
      @RequestParam(name = "id", required = false) String id,
      @RequestParam(name = "next_cursor", required = false) String nextCursor) {
    Map<String, String> query = new LinkedHashMap<>();
    if (market != null && !market.isBlank()) {
      query.put("market", market);
    }
    if (assetId != null && !assetId.isBlank()) {
      query.put("asset_id", assetId);
    }
    if (id != null && !id.isBlank()) {
      query.put("id", id);
    }
    if (nextCursor != null && !nextCursor.isBlank()) {
      query.put("next_cursor", nextCursor);
    }
    log.info("api /orders/list market={} asset_id={} id={} next_cursor={}", market, assetId, id, nextCursor);
    return ResponseEntity.ok(tradingService.getOrders(query));
  }

  @GetMapping("/trades")
  public ResponseEntity<JsonNode> getTrades(
      @RequestParam(name = "maker_address", required = false) String makerAddress,
      @RequestParam(name = "market", required = false) String market,
      @RequestParam(name = "asset_id", required = false) String assetId,
      @RequestParam(name = "before", required = false) Integer before,
      @RequestParam(name = "after", required = false) Integer after,
      @RequestParam(name = "id", required = false) String id,
      @RequestParam(name = "next_cursor", required = false) String nextCursor) {
    Map<String, String> query = new LinkedHashMap<>();
    if (makerAddress != null && !makerAddress.isBlank()) {
      query.put("maker_address", makerAddress);
    }
    if (market != null && !market.isBlank()) {
      query.put("market", market);
    }
    if (assetId != null && !assetId.isBlank()) {
      query.put("asset_id", assetId);
    }
    if (before != null) {
      query.put("before", before.toString());
    }
    if (after != null) {
      query.put("after", after.toString());
    }
    if (id != null && !id.isBlank()) {
      query.put("id", id);
    }
    if (nextCursor != null && !nextCursor.isBlank()) {
      query.put("next_cursor", nextCursor);
    }
    log.info("api /trades maker_address={} market={} asset_id={} before={} after={} id={} next_cursor={}", makerAddress,
        market, assetId, before, after, id, nextCursor);
    return ResponseEntity.ok(tradingService.getTrades(query));
  }

  private void safePublishLimitOrderEvent(LimitOrderRequest request, OrderSubmissionResult result,
      RuntimeException error) {
    if (!events.isEnabled()) {
      return;
    }
    try {
      String mode = result != null && result.mode() != null ? result.mode().name() : null;
      String orderId = resolveOrderId(result);
      ExecutorOrderError err = error == null ? null : toOrderError(error);
      events.publish(
          HftEventTypes.EXECUTOR_ORDER_LIMIT,
          request.tokenId(),
          new ExecutorLimitOrderEvent(
              request.tokenId(),
              request.side(),
              request.price(),
              request.size(),
              request.orderType(),
              request.tickSize(),
              request.negRisk(),
              request.feeRateBps(),
              request.nonce(),
              request.expirationSeconds(),
              request.deferExec(),
              mode,
              error == null,
              orderId,
              err));
    } catch (Exception ignored) {
    }
  }

  private void safePublishMarketOrderEvent(MarketOrderRequest request, OrderSubmissionResult result,
      RuntimeException error) {
    if (!events.isEnabled()) {
      return;
    }
    try {
      String mode = result != null && result.mode() != null ? result.mode().name() : null;
      String orderId = resolveOrderId(result);
      ExecutorOrderError err = error == null ? null : toOrderError(error);
      events.publish(
          HftEventTypes.EXECUTOR_ORDER_MARKET,
          request.tokenId(),
          new ExecutorMarketOrderEvent(
              request.tokenId(),
              request.side(),
              request.amount(),
              request.price(),
              request.orderType(),
              request.tickSize(),
              request.negRisk(),
              request.feeRateBps(),
              request.nonce(),
              request.deferExec(),
              mode,
              error == null,
              orderId,
              err));
    } catch (Exception ignored) {
    }
  }

  private void safePublishCancelOrderEvent(String orderId, JsonNode result, RuntimeException error) {
    if (!events.isEnabled()) {
      return;
    }
    try {
      String mode = result != null && result.hasNonNull("mode") ? result.get("mode").asText(null) : null;
      ExecutorOrderError err = error == null ? null : toOrderError(error);
      events.publish(
          HftEventTypes.EXECUTOR_ORDER_CANCEL,
          orderId,
          new ExecutorCancelOrderEvent(orderId, mode, error == null, err));
    } catch (Exception ignored) {
    }
  }

  private static String resolveOrderId(OrderSubmissionResult result) {
    if (result == null) {
      return null;
    }
    JsonNode resp = result.clobResponse();
    if (resp == null) {
      if (result.mode() != null && "PAPER".equalsIgnoreCase(result.mode().name())) {
        return "paper-" + UUID.randomUUID();
      }
      return null;
    }
    if (resp.hasNonNull("orderID")) {
      return resp.get("orderID").asText();
    }
    if (resp.hasNonNull("orderId")) {
      return resp.get("orderId").asText();
    }
    if (resp.hasNonNull("order_id")) {
      return resp.get("order_id").asText();
    }
    return null;
  }

  private static ExecutorOrderError toOrderError(RuntimeException e) {
    if (e instanceof PolymarketHttpException phe) {
      return new ExecutorOrderError(
          phe.getClass().getSimpleName(),
          phe.statusCode(),
          phe.method(),
          phe.uri().toString(),
          truncate(phe.getMessage(), ERROR_MAX_LEN));
    }
    return new ExecutorOrderError(
        e.getClass().getSimpleName(),
        null,
        null,
        null,
        truncate(e.getMessage() != null ? e.getMessage() : e.toString(), ERROR_MAX_LEN));
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    if (max <= 0 || s.length() <= max) {
      return s;
    }
    return s.substring(0, max) + "...";
  }
}
