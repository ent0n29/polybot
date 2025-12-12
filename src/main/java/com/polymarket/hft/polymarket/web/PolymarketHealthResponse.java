package com.polymarket.hft.polymarket.web;

import com.polymarket.hft.polymarket.model.OrderBook;

public record PolymarketHealthResponse(
    String mode,
    String clobRestUrl,
    String clobWsUrl,
    int chainId,
    boolean useServerTime,
    boolean marketWsEnabled,
    boolean userWsEnabled,
    boolean deep,
    String tokenId,
    Long serverTimeSeconds,
    OrderBook orderBook,
    String deepError
) {
}

