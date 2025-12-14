CREATE DATABASE IF NOT EXISTS polybot;

CREATE TABLE IF NOT EXISTS polybot.analytics_events (
  ts DateTime64(3),
  source LowCardinality(String),
  type LowCardinality(String),
  data String,
  ingested_at DateTime64(3),
  kafka_topic LowCardinality(String),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3),
  kafka_key String
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (type, ts, kafka_partition, kafka_offset);

CREATE TABLE IF NOT EXISTS polybot.kafka_events_raw (
  raw String
)
ENGINE = Kafka
SETTINGS
  kafka_broker_list = 'redpanda:29092',
  kafka_topic_list = 'polybot.events',
  kafka_group_name = 'clickhouse-polybot-analytics',
  kafka_format = 'JSONAsString',
  kafka_num_consumers = 1;

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.kafka_events_mv
TO polybot.analytics_events
AS
SELECT
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(raw, 'ts')), toDateTime64(_timestamp, 3)) AS ts,
  ifNull(JSONExtractString(raw, 'source'), 'unknown') AS source,
  ifNull(JSONExtractString(raw, 'type'), 'unknown') AS type,
  ifNull(JSONExtractRaw(raw, 'data'), '{}') AS data,
  now64(3) AS ingested_at,
  _topic AS kafka_topic,
  toInt32(_partition) AS kafka_partition,
  toInt64(_offset) AS kafka_offset,
  toDateTime64(_timestamp, 3) AS kafka_timestamp,
  ifNull(_key, '') AS kafka_key
FROM polybot.kafka_events_raw;
