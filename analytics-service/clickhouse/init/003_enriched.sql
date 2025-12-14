-- Derived views built on top of canonical extracted tables.

CREATE VIEW IF NOT EXISTS polybot.gamma_markets_latest AS
SELECT
  slug,
  argMax(market_id, captured_at) AS market_id,
  argMax(condition_id, captured_at) AS condition_id,
  argMax(end_date, captured_at) AS end_date,
  argMax(event_start_time, captured_at) AS event_start_time,
  argMax(active, captured_at) AS active,
  argMax(closed, captured_at) AS closed,
  argMax(uma_resolution_status, captured_at) AS uma_resolution_status,
  argMax(outcomes, captured_at) AS outcomes,
  argMax(outcome_prices, captured_at) AS outcome_prices,
  argMax(token_ids, captured_at) AS token_ids,
  argMax(best_bid, captured_at) AS best_bid,
  argMax(best_ask, captured_at) AS best_ask,
  argMax(last_trade_price, captured_at) AS last_trade_price,
  argMax(volume_num, captured_at) AS volume_num,
  argMax(liquidity_num, captured_at) AS liquidity_num,
  argMax(restricted, captured_at) AS restricted,
  max(captured_at) AS latest_captured_at
FROM polybot.gamma_markets
GROUP BY slug;

CREATE VIEW IF NOT EXISTS polybot.user_trades_dedup AS
SELECT
  username,
  event_key,
  argMax(ts, kafka_offset) AS ts,
  argMax(proxy_address, kafka_offset) AS proxy_address,
  argMax(market_slug, kafka_offset) AS market_slug,
  argMax(title, kafka_offset) AS title,
  argMax(token_id, kafka_offset) AS token_id,
  argMax(condition_id, kafka_offset) AS condition_id,
  argMax(side, kafka_offset) AS side,
  argMax(outcome, kafka_offset) AS outcome,
  argMax(outcome_index, kafka_offset) AS outcome_index,
  argMax(price, kafka_offset) AS price,
  argMax(size, kafka_offset) AS size,
  argMax(transaction_hash, kafka_offset) AS transaction_hash,
  max(ingested_at) AS ingested_at,
  max(kafka_partition) AS kafka_partition,
  max(kafka_offset) AS latest_kafka_offset,
  max(kafka_timestamp) AS kafka_timestamp
FROM polybot.user_trades
GROUP BY
  username,
  event_key;

CREATE VIEW IF NOT EXISTS polybot.clob_tob_by_trade AS
SELECT
  trade_key,
  max(captured_at) AS tob_captured_at,
  argMax(market_slug, captured_at) AS market_slug,
  argMax(token_id, captured_at) AS token_id,
  argMax(outcome, captured_at) AS outcome,
  argMax(best_bid_price, captured_at) AS best_bid_price,
  argMax(best_bid_size, captured_at) AS best_bid_size,
  argMax(best_ask_price, captured_at) AS best_ask_price,
  argMax(best_ask_size, captured_at) AS best_ask_size,
  argMax(mid, captured_at) AS mid,
  argMax(spread, captured_at) AS spread,
  argMax(book_timestamp_ms, captured_at) AS book_timestamp_ms,
  argMax(book_hash, captured_at) AS book_hash,
  argMax(cached, captured_at) AS cached,
  argMax(cache_age_millis, captured_at) AS cache_age_millis
FROM polybot.clob_tob
GROUP BY trade_key;

CREATE OR REPLACE VIEW polybot.user_trade_enriched AS
WITH
  -- Gamma market metadata can be missing for many markets (depending on what ingestors publish).
  -- When missing, ClickHouse JOIN defaults non-nullable DateTime64 columns to epoch (1970-01-01),
  -- which then produces huge negative seconds_to_end. Treat “epoch-ish” values as NULL and fall
  -- back to parsing end time for known series (e.g., updown-15m slugs).
  toDateTime64('2000-01-01 00:00:00', 3) AS min_valid_dt,
  if(g.end_date < min_valid_dt, CAST(NULL, 'Nullable(DateTime64(3))'), toNullable(g.end_date)) AS gamma_end_date,
  if(g.event_start_time < min_valid_dt, CAST(NULL, 'Nullable(DateTime64(3))'), toNullable(g.event_start_time)) AS gamma_event_start_time,
  toUInt32OrZero(arrayElement(splitByChar('-', u.market_slug), -1)) AS slug_epoch_start,
  if(
    position(u.market_slug, 'updown-15m-') > 0 AND slug_epoch_start > 0,
    toDateTime64(slug_epoch_start + 900, 3),
    CAST(NULL, 'Nullable(DateTime64(3))')
  ) AS slug_end_date,
  coalesce(gamma_end_date, slug_end_date) AS end_date,
  if(end_date IS NULL, CAST(NULL, 'Nullable(Int64)'), dateDiff('second', u.ts, end_date)) AS seconds_to_end,
  arrayMax(g.outcome_prices) AS max_outcome_price,
  arrayMin(g.outcome_prices) AS min_outcome_price,
  (max_outcome_price >= 0.999 AND min_outcome_price <= 0.001) AS is_resolved,
  indexOf(g.outcomes, u.outcome) AS trade_outcome_idx,
  if(is_resolved AND trade_outcome_idx > 0, arrayElement(g.outcome_prices, trade_outcome_idx), CAST(NULL, 'Nullable(Float64)')) AS settle_price
SELECT
  u.ts,
  u.username,
  u.proxy_address,
  u.market_slug AS market_slug,
  u.title,
  u.token_id AS token_id,
  u.condition_id AS condition_id,
  u.side,
  u.outcome AS outcome,
  u.outcome_index,
  u.price,
  u.size,
  u.transaction_hash,
  u.event_key,
  t.tob_captured_at,
  t.best_bid_price,
  t.best_bid_size,
  t.best_ask_price,
  t.best_ask_size,
  t.mid,
  t.spread,
  (u.price - t.mid) AS price_minus_mid,
  g.market_id,
  end_date,
  gamma_event_start_time AS event_start_time,
  seconds_to_end,
  g.active,
  g.closed,
  g.uma_resolution_status,
  g.outcomes,
  g.outcome_prices,
  g.token_ids,
  is_resolved,
  if(is_resolved, arrayElement(g.outcomes, indexOf(g.outcome_prices, max_outcome_price)), CAST(NULL, 'Nullable(String)')) AS resolved_outcome,
  settle_price,
  if(
    is_resolved AND settle_price IS NOT NULL,
    u.size * if(u.side = 'SELL', (u.price - settle_price), (settle_price - u.price)),
    CAST(NULL, 'Nullable(Float64)')
  ) AS realized_pnl
FROM polybot.user_trades_dedup u
LEFT JOIN polybot.clob_tob_by_trade t ON t.trade_key = u.event_key
LEFT JOIN polybot.gamma_markets_latest g ON g.slug = u.market_slug;
