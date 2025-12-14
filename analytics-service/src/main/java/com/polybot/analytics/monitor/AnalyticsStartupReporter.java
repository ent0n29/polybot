package com.polybot.analytics.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsStartupReporter {

  private final JdbcTemplate jdbcTemplate;

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    try {
      String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
      log.info("ClickHouse connected version={}", version);
    } catch (Exception e) {
      log.warn("ClickHouse connectivity check failed error={}", e.toString());
      return;
    }

    logEventsTableStats();
    logKafkaConsumerStats();
  }

  private void logEventsTableStats() {
    try {
      jdbcTemplate.query(
          """
              SELECT
                count() AS cnt,
                max(ingested_at) AS last_ingested_at,
                max(ts) AS last_event_ts
              FROM analytics_events
              """,
          rs -> {
            if (!rs.next()) {
              return;
            }
            long count = rs.getLong("cnt");
            Timestamp lastIngestedAt = rs.getTimestamp("last_ingested_at");
            Timestamp lastEventTs = rs.getTimestamp("last_event_ts");
            log.info(
                "analytics_events stats rows={} lastIngestedAt={} lastEventTs={}",
                count,
                lastIngestedAt == null ? null : lastIngestedAt.toInstant(),
                lastEventTs == null ? null : lastEventTs.toInstant()
            );
          }
      );
    } catch (Exception e) {
      log.warn("analytics_events stats query failed error={}", e.toString());
    }
  }

  private void logKafkaConsumerStats() {
    try {
      jdbcTemplate.query(
          """
              SELECT
                count() AS consumers,
                sum(is_currently_used) AS used,
                sum(length(assignments.partition_id)) AS assigned_partitions,
                sum(num_messages_read) AS messages_read,
                max(last_poll_time) AS last_poll_time,
                max(last_commit_time) AS last_commit_time,
                sum(length(exceptions.text)) AS recent_exceptions
              FROM system.kafka_consumers
              WHERE database = currentDatabase()
                AND `table` = 'kafka_events_raw'
              """,
          rs -> {
            if (!rs.next()) {
              return;
            }
            long consumers = rs.getLong("consumers");
            long used = rs.getLong("used");
            long assignedPartitions = rs.getLong("assigned_partitions");
            long messagesRead = rs.getLong("messages_read");
            Timestamp lastPollTime = rs.getTimestamp("last_poll_time");
            Timestamp lastCommitTime = rs.getTimestamp("last_commit_time");
            long recentExceptions = rs.getLong("recent_exceptions");

            log.info(
                "ClickHouse Kafka consumer stats consumers={} used={} assignedPartitions={} messagesRead={} lastPollTime={} lastCommitTime={} recentExceptions={}",
                consumers,
                used,
                assignedPartitions,
                messagesRead,
                lastPollTime == null ? null : lastPollTime.toInstant(),
                lastCommitTime == null ? null : lastCommitTime.toInstant(),
                recentExceptions
            );
          }
      );
    } catch (Exception e) {
      log.warn("ClickHouse Kafka consumer stats query failed error={}", e.toString());
    }
  }
}

