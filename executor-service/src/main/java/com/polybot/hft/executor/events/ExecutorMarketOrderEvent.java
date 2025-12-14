package com.polybot.hft.executor.events;

import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.polymarket.model.ClobOrderType;

import java.math.BigDecimal;

public record ExecutorMarketOrderEvent(
    String tokenId,
    OrderSide side,
    BigDecimal amount,
    BigDecimal price,
    ClobOrderType orderType,
    BigDecimal tickSize,
    Boolean negRisk,
    Integer feeRateBps,
    Long nonce,
    Boolean deferExec,
    String mode,
    boolean success,
    String orderId,
    ExecutorOrderError error
) {
}

