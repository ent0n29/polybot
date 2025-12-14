package com.polybot.hft.events;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="hft.events")
public record HftEventsProperties(
    @NotNull Boolean enabled,
    String topic,
    @NotNull @PositiveOrZero Long marketWsTobMinIntervalMillis
) {
  public HftEventsProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (topic == null || topic.isBlank()) {
      topic = "polybot.events";
    }
    if (marketWsTobMinIntervalMillis == null) {
      marketWsTobMinIntervalMillis = 250L;
    }
  }
}

