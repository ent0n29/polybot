package com.polymarket.hft.polymarket.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.domain.OrderSide;
import com.polymarket.hft.polymarket.auth.PolymarketAuthContext;
import com.polymarket.hft.polymarket.clob.PolymarketClobClient;
import com.polymarket.hft.polymarket.model.ApiCreds;
import com.polymarket.hft.polymarket.model.ClobOrderType;
import com.polymarket.hft.polymarket.model.OrderBook;
import com.polymarket.hft.polymarket.model.SignedOrder;
import com.polymarket.hft.polymarket.order.PolymarketOrderBuilder;
import com.polymarket.hft.polymarket.web.LimitOrderRequest;
import com.polymarket.hft.polymarket.web.MarketOrderRequest;
import com.polymarket.hft.polymarket.web.OrderSubmissionResult;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketTradingService {

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketAuthContext authContext;
  private final @NonNull PolymarketClobClient clobClient;
  private final @NonNull ObjectMapper objectMapper;

  public OrderBook getOrderBook(String tokenId) {
    log.info("Fetching order book for tokenId={}", tokenId);
    return clobClient.getOrderBook(tokenId);
  }

  public BigDecimal getTickSize(String tokenId) {
    return clobClient.getMinimumTickSize(tokenId);
  }

  public boolean isNegRisk(String tokenId) {
    return clobClient.isNegRisk(tokenId);
  }

  public OrderSubmissionResult placeLimitOrder(LimitOrderRequest request) {
    if (properties.risk().killSwitch()) {
      throw new IllegalStateException("Trading disabled by kill switch (hft.risk.kill-switch=true)");
    }
    enforceRiskLimits(request.side(), request.price(), request.size());

    Credentials signer = authContext.requireSignerCredentials();
    SignedOrder order = orderBuilder(signer).buildLimitOrder(
        request.tokenId(),
        request.side(),
        request.price(),
        request.size(),
        resolveTickSize(request.tokenId(), request.tickSize()),
        resolveNegRisk(request.tokenId(), request.negRisk()),
        resolveFeeRateBps(request.tokenId(), request.feeRateBps()),
        request.nonce(),
        request.expirationSeconds(),
        request.taker()
    );

    if (properties.mode() == HftProperties.TradingMode.PAPER) {
      return new OrderSubmissionResult(properties.mode(), order, null);
    }

    ApiCreds creds = authContext.requireApiCreds();
    ClobOrderType orderType = request.orderType() == null ? ClobOrderType.GTC : request.orderType();
    JsonNode resp = clobClient.postOrder(
        signer,
        creds,
        order,
        orderType,
        request.deferExec() != null && request.deferExec()
    );
    return new OrderSubmissionResult(properties.mode(), order, resp);
  }

  public OrderSubmissionResult placeMarketOrder(MarketOrderRequest request) {
    if (properties.risk().killSwitch()) {
      throw new IllegalStateException("Trading disabled by kill switch (hft.risk.kill-switch=true)");
    }
    enforceMarketRiskLimits(request.side(), request.price(), request.amount());

    Credentials signer = authContext.requireSignerCredentials();
    SignedOrder order = orderBuilder(signer).buildMarketOrder(
        request.tokenId(),
        request.side(),
        request.amount(),
        request.price(),
        resolveTickSize(request.tokenId(), request.tickSize()),
        resolveNegRisk(request.tokenId(), request.negRisk()),
        resolveFeeRateBps(request.tokenId(), request.feeRateBps()),
        request.nonce(),
        request.taker()
    );

    if (properties.mode() == HftProperties.TradingMode.PAPER) {
      return new OrderSubmissionResult(properties.mode(), order, null);
    }

    ApiCreds creds = authContext.requireApiCreds();
    ClobOrderType orderType = request.orderType() == null ? ClobOrderType.FOK : request.orderType();
    JsonNode resp = clobClient.postOrder(
        signer,
        creds,
        order,
        orderType,
        request.deferExec() != null && request.deferExec()
    );
    return new OrderSubmissionResult(properties.mode(), order, resp);
  }

  public JsonNode cancelOrder(String orderId) {
    if (properties.mode() == HftProperties.TradingMode.PAPER) {
      return objectMapper.createObjectNode()
          .put("mode", properties.mode().name())
          .put("canceled", true)
          .put("orderId", orderId);
    }

    Credentials signer = authContext.requireSignerCredentials();
    ApiCreds creds = authContext.requireApiCreds();
    return clobClient.cancelOrder(signer, creds, orderId);
  }

  private PolymarketOrderBuilder orderBuilder(Credentials signer) {
    HftProperties.Polymarket polymarket = properties.polymarket();
    HftProperties.Auth auth = polymarket.auth();
    return new PolymarketOrderBuilder(
        polymarket.chainId(),
        signer,
        auth.signatureType(),
        auth.funderAddress()
    );
  }

  private BigDecimal resolveTickSize(String tokenId, BigDecimal tickSizeOverride) {
    return tickSizeOverride != null ? tickSizeOverride : clobClient.getMinimumTickSize(tokenId);
  }

  private boolean resolveNegRisk(String tokenId, Boolean negRiskOverride) {
    return negRiskOverride != null ? negRiskOverride : clobClient.isNegRisk(tokenId);
  }

  private Integer resolveFeeRateBps(String tokenId, Integer feeRateBpsOverride) {
    return feeRateBpsOverride != null ? feeRateBpsOverride : clobClient.getBaseFeeBps(tokenId);
  }

  private void enforceRiskLimits(OrderSide side, BigDecimal price, BigDecimal size) {
    if (size == null || price == null) {
      return;
    }
    BigDecimal maxSize = properties.risk().maxOrderSize();
    if (maxSize != null && maxSize.compareTo(BigDecimal.ZERO) > 0 && size.compareTo(maxSize) > 0) {
      throw new IllegalArgumentException("Order size exceeds maxOrderSize (" + maxSize + ")");
    }

    BigDecimal notional = price.multiply(size);
    BigDecimal maxNotional = properties.risk().maxOrderNotionalUsd();
    if (maxNotional != null && maxNotional.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(maxNotional) > 0) {
      throw new IllegalArgumentException("Order notional exceeds maxOrderNotionalUsd (" + maxNotional + ")");
    }
  }

  private void enforceMarketRiskLimits(OrderSide side, BigDecimal price, BigDecimal amount) {
    if (price == null || amount == null || side == null) {
      return;
    }
    BigDecimal notional = side == OrderSide.BUY ? amount : amount.multiply(price);
    BigDecimal maxNotional = properties.risk().maxOrderNotionalUsd();
    if (maxNotional != null && maxNotional.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(maxNotional) > 0) {
      throw new IllegalArgumentException("Order notional exceeds maxOrderNotionalUsd (" + maxNotional + ")");
    }
  }
}
