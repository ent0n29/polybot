-- Canonical extracted tables built from polybot.analytics_events (JSON payloads).
-- These tables make analysis fast and avoid repeating JSONExtract* in every query.

CREATE TABLE IF NOT EXISTS polybot.user_trades (
  ts DateTime64(3),
  username LowCardinality(String),
  proxy_address String,
  market_slug LowCardinality(String),
  title String,
  token_id String,
  condition_id String,
  side LowCardinality(String),
  outcome LowCardinality(String),
  outcome_index Int32,
  price Float64,
  size Float64,
  transaction_hash String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (username, ts, event_key);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.user_trades_mv
TO polybot.user_trades
AS
SELECT
  ts,
  JSONExtractString(data, 'username') AS username,
  JSONExtractString(data, 'proxyAddress') AS proxy_address,
  JSONExtractString(data, 'trade', 'slug') AS market_slug,
  JSONExtractString(data, 'trade', 'title') AS title,
  JSONExtractString(data, 'trade', 'asset') AS token_id,
  JSONExtractString(data, 'trade', 'conditionId') AS condition_id,
  JSONExtractString(data, 'trade', 'side') AS side,
  JSONExtractString(data, 'trade', 'outcome') AS outcome,
  toInt32(JSONExtractInt(data, 'trade', 'outcomeIndex')) AS outcome_index,
  JSONExtractFloat(data, 'trade', 'price') AS price,
  JSONExtractFloat(data, 'trade', 'size') AS size,
  JSONExtractString(data, 'trade', 'transactionHash') AS transaction_hash,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'polymarket.user.trade';

CREATE TABLE IF NOT EXISTS polybot.clob_tob (
  ts DateTime64(3),
  captured_at DateTime64(3),
  market_slug LowCardinality(String),
  token_id String,
  outcome LowCardinality(String),
  trade_key String,
  trade_at DateTime64(3),
  username LowCardinality(String),
  proxy_address String,
  best_bid_price Float64,
  best_bid_size Float64,
  best_ask_price Float64,
  best_ask_size Float64,
  mid Float64,
  spread Float64,
  book_timestamp_ms Int64,
  book_hash String,
  cached UInt8,
  cache_age_millis Int64,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (market_slug, token_id, ts, trade_key);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.clob_tob_mv
TO polybot.clob_tob
AS
SELECT
  ts,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'capturedAt')), ts) AS captured_at,
  JSONExtractString(data, 'marketSlug') AS market_slug,
  JSONExtractString(data, 'tokenId') AS token_id,
  JSONExtractString(data, 'outcome') AS outcome,
  JSONExtractString(data, 'trigger', 'tradeKey') AS trade_key,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'trigger', 'tradeAt')) AS trade_at,
  JSONExtractString(data, 'trigger', 'username') AS username,
  JSONExtractString(data, 'trigger', 'proxyAddress') AS proxy_address,
  toFloat64OrZero(JSONExtractString(data, 'book', 'bestBid', 'price')) AS best_bid_price,
  toFloat64OrZero(JSONExtractString(data, 'book', 'bestBid', 'size')) AS best_bid_size,
  toFloat64OrZero(JSONExtractString(data, 'book', 'bestAsk', 'price')) AS best_ask_price,
  toFloat64OrZero(JSONExtractString(data, 'book', 'bestAsk', 'size')) AS best_ask_size,
  toFloat64OrZero(JSONExtractString(data, 'book', 'mid')) AS mid,
  toFloat64OrZero(JSONExtractString(data, 'book', 'spread')) AS spread,
  toInt64(JSONExtractInt(data, 'book', 'timestamp')) AS book_timestamp_ms,
  JSONExtractString(data, 'book', 'hash') AS book_hash,
  ifNull(toUInt8(JSONExtractBool(data, 'cached')), 0) AS cached,
  toInt64(JSONExtractInt(data, 'cacheAgeMillis')) AS cache_age_millis,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'polymarket.clob.tob';

CREATE TABLE IF NOT EXISTS polybot.market_trades (
  ts DateTime64(3),
  captured_at DateTime64(3),
  market_slug LowCardinality(String),
  token_id String,
  condition_id String,
  side LowCardinality(String),
  outcome LowCardinality(String),
  outcome_index Int32,
  price Float64,
  size Float64,
  transaction_hash String,
  proxy_wallet String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (market_slug, token_id, ts, event_key);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.market_trades_mv
TO polybot.market_trades
AS
SELECT
  ts,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'capturedAt')), ts) AS captured_at,
  JSONExtractString(data, 'marketSlug') AS market_slug,
  JSONExtractString(data, 'trade', 'asset') AS token_id,
  JSONExtractString(data, 'trade', 'conditionId') AS condition_id,
  JSONExtractString(data, 'trade', 'side') AS side,
  JSONExtractString(data, 'trade', 'outcome') AS outcome,
  toInt32(JSONExtractInt(data, 'trade', 'outcomeIndex')) AS outcome_index,
  JSONExtractFloat(data, 'trade', 'price') AS price,
  JSONExtractFloat(data, 'trade', 'size') AS size,
  JSONExtractString(data, 'trade', 'transactionHash') AS transaction_hash,
  JSONExtractString(data, 'trade', 'proxyWallet') AS proxy_wallet,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'polymarket.market.trade';

CREATE TABLE IF NOT EXISTS polybot.gamma_markets (
  ts DateTime64(3),
  captured_at DateTime64(3),
  slug LowCardinality(String),
  market_id String,
  condition_id String,
  end_date DateTime64(3),
  event_start_time DateTime64(3),
  active UInt8,
  closed UInt8,
  uma_resolution_status LowCardinality(String),
  outcomes Array(String),
  outcome_prices Array(Float64),
  token_ids Array(String),
  best_bid Float64,
  best_ask Float64,
  last_trade_price Float64,
  volume_num Float64,
  liquidity_num Float64,
  restricted UInt8,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (slug, captured_at);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.gamma_markets_mv
TO polybot.gamma_markets
AS
SELECT
  ts,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'capturedAt')), ts) AS captured_at,
  JSONExtractString(data, 'slug') AS slug,
  JSONExtractString(data, 'market', 'id') AS market_id,
  JSONExtractString(data, 'market', 'conditionId') AS condition_id,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'market', 'endDate')) AS end_date,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'market', 'eventStartTime')) AS event_start_time,
  ifNull(toUInt8(JSONExtractBool(data, 'market', 'active')), 0) AS active,
  ifNull(toUInt8(JSONExtractBool(data, 'market', 'closed')), 0) AS closed,
  JSONExtractString(data, 'market', 'umaResolutionStatus') AS uma_resolution_status,
  JSONExtract(JSONExtractString(data, 'market', 'outcomes'), 'Array(String)') AS outcomes,
  arrayMap(x -> toFloat64OrZero(x), JSONExtract(JSONExtractString(data, 'market', 'outcomePrices'), 'Array(String)')) AS outcome_prices,
  JSONExtract(JSONExtractString(data, 'market', 'clobTokenIds'), 'Array(String)') AS token_ids,
  toFloat64OrZero(JSONExtractString(data, 'market', 'bestBid')) AS best_bid,
  toFloat64OrZero(JSONExtractString(data, 'market', 'bestAsk')) AS best_ask,
  toFloat64OrZero(JSONExtractString(data, 'market', 'lastTradePrice')) AS last_trade_price,
  toFloat64OrZero(JSONExtractString(data, 'market', 'volumeNum')) AS volume_num,
  toFloat64OrZero(JSONExtractString(data, 'market', 'liquidityNum')) AS liquidity_num,
  ifNull(toUInt8(JSONExtractBool(data, 'market', 'restricted')), 0) AS restricted,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'polymarket.gamma.market';
