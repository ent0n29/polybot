package com.polybot.hft.executor.events;

import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.polymarket.model.ClobOrderType;

import java.math.BigDecimal;

public record ExecutorLimitOrderEvent(
    String tokenId,
    OrderSide side,
    BigDecimal price,
    BigDecimal size,
    ClobOrderType orderType,
    BigDecimal tickSize,
    Boolean negRisk,
    Integer feeRateBps,
    Long nonce,
    Long expirationSeconds,
    Boolean deferExec,
    String mode,
    boolean success,
    String orderId,
    ExecutorOrderError error
) {
}

