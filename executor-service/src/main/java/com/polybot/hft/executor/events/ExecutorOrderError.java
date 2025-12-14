package com.polybot.hft.executor.events;

public record ExecutorOrderError(
    String type,
    Integer upstreamStatus,
    String upstreamMethod,
    String upstreamUrl,
    String message
) {
}

