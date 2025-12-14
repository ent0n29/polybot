package com.polybot.hft.executor.events;

public record ExecutorCancelOrderEvent(
    String orderId,
    String mode,
    boolean success,
    ExecutorOrderError error
) {
}

