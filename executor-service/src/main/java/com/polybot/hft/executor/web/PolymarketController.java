package com.polybot.hft.executor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.executor.events.ExecutorCancelOrderEvent;
import com.polybot.hft.executor.events.ExecutorLimitOrderEvent;
import com.polybot.hft.executor.events.ExecutorMarketOrderEvent;
import com.polybot.hft.executor.events.ExecutorOrderError;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.MarketOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.api.PolymarketHealthResponse;
import com.polybot.hft.polymarket.http.PolymarketHttpException;
import com.polybot.hft.polymarket.model.OrderBook;
import com.polybot.hft.polymarket.service.PolymarketTradingService;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.hft.polymarket.ws.TopOfBook;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/polymarket")
@Validated
@RequiredArgsConstructor
@Slf4j
public class PolymarketController {

  private static final int ERROR_MAX_LEN = 512;

  private final @NonNull PolymarketTradingService tradingService;
  private final @NonNull ClobMarketWebSocketClient marketWebSocketClient;
  private final @NonNull HftEventPublisher events;

  @GetMapping("/health")
  public ResponseEntity<PolymarketHealthResponse> getHealth(
      @RequestParam(name="deep", required=false, defaultValue="false") boolean deep,
      @RequestParam(name="tokenId", required=false) String tokenId
  ) {
    log.info("api /health deep={} tokenId={}", deep, tokenId);
    return ResponseEntity.ok(tradingService.getHealth(deep, tokenId));
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
      OrderSubmissionResult result = tradingService.placeLimitOrder(request);
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
      OrderSubmissionResult result = tradingService.placeMarketOrder(request);
      safePublishMarketOrderEvent(request, result, null);
      return ResponseEntity.ok(result);
    } catch (RuntimeException e) {
      safePublishMarketOrderEvent(request, null, e);
      throw e;
    }
  }

  @DeleteMapping("/orders/{orderId}")
  public ResponseEntity<JsonNode> cancelOrder(@PathVariable String orderId) {
    log.info("api /orders/cancel orderId={}", orderId);
    try {
      JsonNode result = tradingService.cancelOrder(orderId);
      safePublishCancelOrderEvent(orderId, result, null);
      return ResponseEntity.ok(result);
    } catch (RuntimeException e) {
      safePublishCancelOrderEvent(orderId, null, e);
      throw e;
    }
  }

  private void safePublishLimitOrderEvent(LimitOrderRequest request, OrderSubmissionResult result, RuntimeException error) {
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
              err
          )
      );
    } catch (Exception ignored) {
    }
  }

  private void safePublishMarketOrderEvent(MarketOrderRequest request, OrderSubmissionResult result, RuntimeException error) {
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
              err
          )
      );
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
          new ExecutorCancelOrderEvent(orderId, mode, error == null, err)
      );
    } catch (Exception ignored) {
    }
  }

  private static String resolveOrderId(OrderSubmissionResult result) {
    if (result == null) {
      return null;
    }
    if (result.mode() != null && "PAPER".equalsIgnoreCase(result.mode().name())) {
      return "paper-" + UUID.randomUUID();
    }
    JsonNode resp = result.clobResponse();
    if (resp == null) {
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
          truncate(phe.getMessage(), ERROR_MAX_LEN)
      );
    }
    return new ExecutorOrderError(
        e.getClass().getSimpleName(),
        null,
        null,
        null,
        truncate(e.getMessage() != null ? e.getMessage() : e.toString(), ERROR_MAX_LEN)
    );
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
