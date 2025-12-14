package com.polybot.hft.polymarket.strategy.events;

import java.math.BigDecimal;

public record HouseEdgeQuoteEvent(
    String marketKey,
    String marketName,
    String bias,
    String yesTokenId,
    String noTokenId,
    String tokenId,
    BigDecimal bestBid,
    BigDecimal bestAsk,
    BigDecimal currentYes,
    BigDecimal fairYes,
    BigDecimal buyPrice,
    BigDecimal sellPrice,
    BigDecimal size,
    boolean attemptedBuy,
    boolean placedBuy,
    boolean attemptedSell,
    boolean placedSell,
    String buyOrderId,
    String sellOrderId
) {
}

