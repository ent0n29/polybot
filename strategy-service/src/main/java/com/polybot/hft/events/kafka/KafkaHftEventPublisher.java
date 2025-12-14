package com.polybot.hft.events.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventsProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class KafkaHftEventPublisher implements HftEventPublisher {

  private final HftEventsProperties properties;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String source;

  private final AtomicLong failures = new AtomicLong(0);

  public KafkaHftEventPublisher(
      @NonNull HftEventsProperties properties,
      @NonNull KafkaTemplate<String, String> kafkaTemplate,
      @NonNull ObjectMapper objectMapper,
      @NonNull Clock clock,
      @NonNull String source
  ) {
    this.properties = properties;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.source = source.trim().isEmpty() ? "app" : source.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean isEnabled() {
    return Boolean.TRUE.equals(properties.enabled());
  }

  @Override
  public void publish(Instant ts, String type, String key, Object data) {
    if (!isEnabled()) {
      return;
    }
    if (type == null || type.isBlank()) {
      return;
    }
    Instant when = ts != null ? ts : Instant.now(clock);

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("ts", when);
    envelope.put("source", source);
    envelope.put("type", type.trim());
    envelope.put("data", data == null ? Map.of() : data);

    String json;
    try {
      json = objectMapper.writeValueAsString(envelope);
    } catch (Exception e) {
      maybeLogFailure("serialize", e);
      return;
    }

    String topic = properties.topic();
    if (key == null || key.isBlank()) {
      kafkaTemplate.send(topic, json).exceptionally(ex -> {
        maybeLogFailure("send", ex);
        return null;
      });
      return;
    }
    kafkaTemplate.send(topic, key, json).exceptionally(ex -> {
      maybeLogFailure("send", ex);
      return null;
    });
  }

  private void maybeLogFailure(String stage, Throwable t) {
    long n = failures.incrementAndGet();
    if (n == 1 || n % 1000 == 0) {
      log.warn("Kafka event publish failed stage={} failures={} error={}", stage, n, t.toString());
    }
  }
}

