package com.polymarket.hft.polymarket.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.polymarket.model.OrderBook;
import com.polymarket.hft.polymarket.service.PolymarketTradingService;
import com.polymarket.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polymarket.hft.polymarket.ws.TopOfBook;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/polymarket")
@Validated
@RequiredArgsConstructor
public class PolymarketController {

  private final @NonNull PolymarketTradingService tradingService;
  private final @NonNull ClobMarketWebSocketClient marketWebSocketClient;

  @GetMapping("/health")
  public ResponseEntity<PolymarketHealthResponse> getHealth(
      @RequestParam(name = "deep", required = false, defaultValue = "false") boolean deep,
      @RequestParam(name = "tokenId", required = false) String tokenId
  ) {
    return ResponseEntity.ok(tradingService.getHealth(deep, tokenId));
  }

  @GetMapping("/orderbook/{tokenId}")
  public ResponseEntity<OrderBook> getOrderBook(@PathVariable String tokenId) {
    return ResponseEntity.ok(tradingService.getOrderBook(tokenId));
  }

  @GetMapping("/tick-size/{tokenId}")
  public ResponseEntity<BigDecimal> getTickSize(@PathVariable String tokenId) {
    return ResponseEntity.ok(tradingService.getTickSize(tokenId));
  }

  @GetMapping("/neg-risk/{tokenId}")
  public ResponseEntity<Boolean> isNegRisk(@PathVariable String tokenId) {
    return ResponseEntity.ok(tradingService.isNegRisk(tokenId));
  }

  @GetMapping("/marketdata/top/{tokenId}")
  public ResponseEntity<TopOfBook> getTopOfBook(@PathVariable String tokenId) {
    return marketWebSocketClient.getTopOfBook(tokenId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/orders/limit")
  public ResponseEntity<OrderSubmissionResult> placeLimitOrder(@Valid @RequestBody LimitOrderRequest request) {
    return ResponseEntity.ok(tradingService.placeLimitOrder(request));
  }

  @PostMapping("/orders/market")
  public ResponseEntity<OrderSubmissionResult> placeMarketOrder(@Valid @RequestBody MarketOrderRequest request) {
    return ResponseEntity.ok(tradingService.placeMarketOrder(request));
  }

  @DeleteMapping("/orders/{orderId}")
  public ResponseEntity<JsonNode> cancelOrder(@PathVariable String orderId) {
    return ResponseEntity.ok(tradingService.cancelOrder(orderId));
  }
}
