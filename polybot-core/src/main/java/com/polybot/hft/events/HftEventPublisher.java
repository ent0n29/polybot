package com.polybot.hft.events;

import java.time.Instant;

public interface HftEventPublisher {

  boolean isEnabled();

  void publish(Instant ts, String type, String key, Object data);

  default void publish(String type, Object data) {
    publish(null, type, null, data);
  }

  default void publish(String type, String key, Object data) {
    publish(null, type, key, data);
  }
}

