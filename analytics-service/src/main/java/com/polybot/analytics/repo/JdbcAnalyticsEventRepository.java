package com.polybot.analytics.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcAnalyticsEventRepository implements AnalyticsEventRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  @Override
  public List<StoredEvent> recent(String type, int limit) {
    int safeLimit = Math.max(1, Math.min(1_000, limit));
    boolean filterType = type != null && !type.isBlank();
    String sql = """
        SELECT ts, source, type, kafka_topic, kafka_partition, kafka_offset, kafka_key, data, ingested_at
        FROM analytics_events
        %s
        ORDER BY ts DESC
        LIMIT ?
        """.formatted(filterType ? "WHERE type = ?" : "");
    Object[] args = filterType ? new Object[]{type.trim(), safeLimit} : new Object[]{safeLimit};
    return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), args);
  }

  private StoredEvent mapRow(ResultSet rs) {
    try {
      Instant ts = rs.getTimestamp(1).toInstant();
      String source = rs.getString(2);
      String type = rs.getString(3);
      String kafkaTopic = rs.getString(4);
      int kafkaPartition = rs.getInt(5);
      long kafkaOffset = rs.getLong(6);
      String kafkaKey = rs.getString(7);
      String dataJson = rs.getString(8);
      Instant ingestedAt = rs.getTimestamp(9).toInstant();
      JsonNode data = dataJson == null ? objectMapper.createObjectNode() : objectMapper.readTree(dataJson);
      return new StoredEvent(ts, source, type, kafkaTopic, kafkaPartition, kafkaOffset, kafkaKey, data, ingestedAt);
    } catch (Exception e) {
      throw new RuntimeException("Failed to map analytics_events row", e);
    }
  }
}
