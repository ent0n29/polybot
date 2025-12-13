package com.polymarket.hft.polymarket.web;

public record PolymarketDeriveCredsResponse(
    boolean attempted,
    boolean success,
    String method,
    long nonce,
    String error
) {
}

