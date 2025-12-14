package com.polybot.hft.events.payload;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketTopOfBookEvent(
    String assetId,
    BigDecimal bestBid,
    BigDecimal bestAsk,
    BigDecimal lastTradePrice,
    Instant updatedAt,
    Instant lastTradeAt
) {
}

