package com.polymarket.hft.polymarket.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.domain.OrderSide;
import com.polymarket.hft.polymarket.model.ClobOrderType;
import com.polymarket.hft.polymarket.service.PolymarketTradingService;
import com.polymarket.hft.polymarket.web.LimitOrderRequest;
import com.polymarket.hft.polymarket.web.OrderSubmissionResult;
import com.polymarket.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polymarket.hft.polymarket.ws.TopOfBook;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class MidpointMakerEngine {

  private static final MathContext MIDPOINT_CTX = new MathContext(18, RoundingMode.HALF_UP);

  private final @NonNull HftProperties properties;
  private final @NonNull ClobMarketWebSocketClient marketWs;
  private final @NonNull PolymarketTradingService tradingService;

  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "midpoint-maker");
    t.setDaemon(true);
    return t;
  });
  private final Map<String, QuoteState> quotesByTokenId = new ConcurrentHashMap<>();

  private static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
    if (v.compareTo(min) < 0) {
      return min;
    }
    if (v.compareTo(max) > 0) {
      return max;
    }
    return v;
  }

  private static String resolveOrderId(OrderSubmissionResult result) {
    if (result == null) {
      return null;
    }
    if (result.mode() == HftProperties.TradingMode.PAPER) {
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

  @PostConstruct
  void startIfEnabled() {
    HftProperties.MidpointMaker cfg = properties.strategy().midpointMaker();
    if (!cfg.enabled()) {
      return;
    }
    if (!properties.polymarket().marketWsEnabled()) {
      log.warn("midpoint-maker enabled, but market WS disabled (hft.polymarket.market-ws-enabled=false).");
      return;
    }
    List<String> tokenIds = properties.polymarket().marketAssetIds();
    if (tokenIds == null || tokenIds.isEmpty()) {
      log.warn("midpoint-maker enabled, but no token IDs configured (hft.polymarket.market-asset-ids).");
      return;
    }

    long periodMs = Math.max(50, cfg.refreshMillis());
    executor.scheduleAtFixedRate(() -> tick(tokenIds, cfg), 0, periodMs, TimeUnit.MILLISECONDS);
    log.info("midpoint-maker started (tokens={}, refreshMillis={})", tokenIds.size(), periodMs);
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  private void tick(List<String> tokenIds, HftProperties.MidpointMaker cfg) {
    for (String tokenId : tokenIds) {
      try {
        tickOne(tokenId, cfg);
      } catch (Exception e) {
        log.debug("midpoint-maker tick error for {}: {}", tokenId, e.toString());
      }
    }
  }

  private void tickOne(String tokenId, HftProperties.MidpointMaker cfg) {
    TopOfBook tob = marketWs.getTopOfBook(tokenId).orElse(null);
    if (tob == null || tob.bestBid() == null || tob.bestAsk() == null) {
      return;
    }
    if (tob.bestBid().compareTo(BigDecimal.ZERO) <= 0 || tob.bestAsk().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if (tob.bestBid().compareTo(tob.bestAsk()) >= 0) {
      return;
    }

    BigDecimal tickSize = tradingService.getTickSize(tokenId);
    boolean negRisk = tradingService.isNegRisk(tokenId);
    int priceDecimals = Math.max(0, tickSize.stripTrailingZeros().scale());

    BigDecimal midpoint = tob.bestBid().add(tob.bestAsk()).divide(BigDecimal.valueOf(2), MIDPOINT_CTX);
    BigDecimal halfSpread = cfg.spread().divide(BigDecimal.valueOf(2), MIDPOINT_CTX);

    BigDecimal bidPrice = midpoint.subtract(halfSpread).setScale(priceDecimals, RoundingMode.DOWN);
    BigDecimal askPrice = midpoint.add(halfSpread).setScale(priceDecimals, RoundingMode.UP);

    BigDecimal minPrice = tickSize;
    BigDecimal maxPrice = BigDecimal.ONE.subtract(tickSize);
    bidPrice = clamp(bidPrice, minPrice, maxPrice);
    askPrice = clamp(askPrice, minPrice, maxPrice);
    if (bidPrice.compareTo(askPrice) >= 0) {
      return;
    }

    QuoteState prev = quotesByTokenId.remove(tokenId);
    if (prev != null) {
      safeCancel(prev.buyOrderId);
      safeCancel(prev.sellOrderId);
    }

    OrderSubmissionResult buy = tradingService.placeLimitOrder(new LimitOrderRequest(
        tokenId,
        OrderSide.BUY,
        bidPrice,
        cfg.quoteSize(),
        ClobOrderType.GTC,
        tickSize,
        negRisk,
        null,
        null,
        null,
        null,
        false
    ));

    OrderSubmissionResult sell = tradingService.placeLimitOrder(new LimitOrderRequest(
        tokenId,
        OrderSide.SELL,
        askPrice,
        cfg.quoteSize(),
        ClobOrderType.GTC,
        tickSize,
        negRisk,
        null,
        null,
        null,
        null,
        false
    ));

    quotesByTokenId.put(tokenId, new QuoteState(
        resolveOrderId(buy),
        resolveOrderId(sell)
    ));
  }

  private void safeCancel(String orderId) {
    if (orderId == null || orderId.isBlank()) {
      return;
    }
    try {
      tradingService.cancelOrder(orderId);
    } catch (Exception ignored) {
    }
  }

  private record QuoteState(String buyOrderId, String sellOrderId) {
  }
}
