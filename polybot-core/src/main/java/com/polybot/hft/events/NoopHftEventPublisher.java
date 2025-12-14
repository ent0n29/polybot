package com.polybot.hft.events;

import java.time.Instant;

public final class NoopHftEventPublisher implements HftEventPublisher {

  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public void publish(Instant ts, String type, String key, Object data) {
  }
}

