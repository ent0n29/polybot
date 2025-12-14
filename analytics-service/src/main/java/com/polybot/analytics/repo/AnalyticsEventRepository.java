package com.polybot.analytics.repo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public interface AnalyticsEventRepository {

  List<StoredEvent> recent(String type, int limit);

  record StoredEvent(
      Instant ts,
      String source,
      String type,
      String kafkaTopic,
      int kafkaPartition,
      long kafkaOffset,
      String kafkaKey,
      JsonNode data,
      Instant ingestedAt
  ) {
  }
}
