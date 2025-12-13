package com.polymarket.hft.polymarket.web;

public record PolymarketAuthStatusResponse(
    boolean signerConfigured,
    String signerAddress,
    boolean apiCredsConfigured,
    boolean autoCreateOrDeriveEnabled,
    long configuredNonce
) {
}

