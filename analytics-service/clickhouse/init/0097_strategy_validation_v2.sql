-- =============================================================================
-- STRATEGY VALIDATION V2 (with Taker Mode)
-- =============================================================================
-- Purpose: Validate strategy matching including both maker AND taker fills
--
-- Changes from v1:
--   1. Fixed floating-point precision issue (>= 0.0099 instead of >= 0.01)
--   2. Added taker mode logic - we take at ask when edge < 1.5% and spread tight
--   3. Match classification now includes MAKER_MATCH and TAKER_MATCH
-- =============================================================================


-- =============================================================================
-- 1) STRATEGY VALIDATION V2 ON CLEAN DATA (with Taker Mode)
-- =============================================================================

CREATE OR REPLACE VIEW polybot.strategy_validation_v2 AS
WITH
    base_sizes AS (
        SELECT 'btc-15m' AS series, 19.0 AS base_shares
        UNION ALL SELECT 'eth-15m', 14.0
        UNION ALL SELECT 'btc-1h', 18.0
        UNION ALL SELECT 'eth-1h', 14.0
    ),
    -- Taker mode parameters (matching GabagoolDirectionalEngine config)
    taker_params AS (
        SELECT
            0.015 AS taker_max_edge,      -- Take when edge < 1.5%
            0.02 AS taker_max_spread      -- Take when spread <= 2 ticks
    ),
    decisions AS (
        SELECT
            t.*,
            b.base_shares,

            -- Time window check
            t.seconds_to_end >= 0 AND t.seconds_to_end <= 3600 AS in_time_window,

            -- Edge check (>= 0.0099 to handle floating-point precision)
            t.complete_set_edge >= 0.0099 AS has_sufficient_edge,

            -- Calculate spread
            t.our_best_ask - t.our_best_bid AS our_spread,

            -- Taker mode decision: edge < 1.5% AND spread <= 2 ticks
            (t.complete_set_edge >= 0.0099
             AND t.complete_set_edge < (SELECT taker_max_edge FROM taker_params)
             AND (t.our_best_ask - t.our_best_bid) <= (SELECT taker_max_spread FROM taker_params)
            ) AS should_take,

            -- Our quote price depends on mode
            if(t.complete_set_edge >= 0.0099
               AND t.complete_set_edge < (SELECT taker_max_edge FROM taker_params)
               AND (t.our_best_ask - t.our_best_bid) <= (SELECT taker_max_spread FROM taker_params),
               t.our_best_ask,  -- Taker mode: quote at ask
               t.our_best_bid   -- Maker mode: quote at bid
            ) AS our_quote_price,

            -- Would we trade?
            (t.seconds_to_end >= 0 AND t.seconds_to_end <= 3600
             AND t.complete_set_edge >= 0.0099
            ) AS would_trade,

            -- Would we fill as MAKER? (gabagool filled at or near bid)
            t.price <= t.our_best_bid + 0.005 AS is_maker_fill,

            -- Would we fill as TAKER? (gabagool filled at or near ask)
            t.price >= t.our_best_ask - 0.005 AS is_taker_fill,

            -- Would we fill based on our mode?
            if(t.complete_set_edge >= 0.0099
               AND t.complete_set_edge < (SELECT taker_max_edge FROM taker_params)
               AND (t.our_best_ask - t.our_best_bid) <= (SELECT taker_max_spread FROM taker_params),
               t.price >= t.our_best_ask - 0.005,  -- Taker: match if gabagool filled at ask
               t.price <= t.our_best_bid + 0.01    -- Maker: match if gabagool filled at/near bid
            ) AS likely_would_fill

        FROM polybot.user_trade_clean t
        LEFT JOIN base_sizes b ON t.series = b.series
        WHERE t.username = 'gabagool22'
    )
SELECT
    ts,
    market_slug,
    series,
    outcome,
    price AS actual_price,
    size AS actual_size,
    our_quote_price,
    base_shares AS our_quote_size,
    complete_set_edge,
    seconds_to_end,
    our_spread,

    in_time_window,
    has_sufficient_edge,
    should_take,
    would_trade,
    is_maker_fill,
    is_taker_fill,
    likely_would_fill,

    -- Match classification (now with maker/taker distinction)
    multiIf(
        would_trade AND should_take AND is_taker_fill, 'TAKER_MATCH',
        would_trade AND NOT should_take AND is_maker_fill, 'MAKER_MATCH',
        would_trade AND should_take AND is_maker_fill, 'TAKER_MODE_BUT_MAKER_FILL',
        would_trade AND NOT should_take AND is_taker_fill, 'MAKER_MODE_BUT_TAKER_FILL',
        would_trade AND NOT likely_would_fill, 'WOULD_TRADE_NO_FILL',
        NOT in_time_window, 'OUTSIDE_TIME_WINDOW',
        NOT has_sufficient_edge, 'INSUFFICIENT_EDGE',
        'UNKNOWN'
    ) AS match_type,

    -- Is this a match? (either maker or taker)
    (would_trade AND likely_would_fill) AS is_match,

    -- Simulated PnL
    if(would_trade AND likely_would_fill,
       (settle_price - our_quote_price) * base_shares,
       0
    ) AS simulated_pnl,

    realized_pnl AS actual_pnl,

    -- Data quality
    our_tob_lag_ms,
    other_tob_lag_ms,
    tob_source,

    -- Book state for analysis
    our_best_bid,
    our_best_ask,
    other_best_bid,
    other_best_ask

FROM decisions;


-- =============================================================================
-- 2) REPLICATION SCORE V2 (with Taker Mode)
-- =============================================================================

CREATE OR REPLACE VIEW polybot.replication_score_v2 AS
SELECT
    count() AS total_clean_trades,
    countIf(would_trade) AS we_would_trade,
    countIf(is_match) AS we_would_match,

    round(countIf(would_trade) * 100.0 / count(), 2) AS trade_rate_pct,
    round(countIf(is_match) * 100.0 / count(), 2) AS match_rate_pct,
    round(countIf(is_match) * 100.0 / nullif(countIf(would_trade), 0), 2) AS fill_rate_if_traded_pct,

    -- Match type breakdown
    countIf(match_type = 'MAKER_MATCH') AS maker_matches,
    countIf(match_type = 'TAKER_MATCH') AS taker_matches,
    countIf(match_type = 'TAKER_MODE_BUT_MAKER_FILL') AS taker_mode_maker_fill,
    countIf(match_type = 'MAKER_MODE_BUT_TAKER_FILL') AS maker_mode_taker_fill,
    countIf(match_type = 'WOULD_TRADE_NO_FILL') AS would_trade_no_fill,
    countIf(match_type = 'OUTSIDE_TIME_WINDOW') AS outside_time_window,
    countIf(match_type = 'INSUFFICIENT_EDGE') AS insufficient_edge,

    -- Combined match rates
    countIf(match_type IN ('MAKER_MATCH', 'TAKER_MATCH')) AS exact_mode_matches,
    round(countIf(match_type IN ('MAKER_MATCH', 'TAKER_MATCH')) * 100.0 / count(), 2) AS exact_mode_match_pct,

    -- Price accuracy (when we match)
    round(avgIf(actual_price - our_quote_price, is_match), 4) AS avg_price_diff,
    round(medianIf(actual_price - our_quote_price, is_match), 4) AS median_price_diff,

    -- Size comparison
    round(avgIf(actual_size, is_match), 2) AS avg_gabagool_size,
    round(avgIf(our_quote_size, is_match), 2) AS avg_our_size,

    -- PnL
    round(sum(actual_pnl), 2) AS gabagool_total_pnl,
    round(sumIf(simulated_pnl, is_match), 2) AS our_simulated_pnl,
    round(sumIf(actual_pnl, is_match), 2) AS gabagool_pnl_on_matches,

    -- Edge stats
    round(avg(complete_set_edge) * 100, 3) AS avg_edge_pct,
    round(median(complete_set_edge) * 100, 3) AS median_edge_pct

FROM polybot.strategy_validation_v2;


-- =============================================================================
-- 3) MAKER vs TAKER ANALYSIS
-- =============================================================================

CREATE OR REPLACE VIEW polybot.maker_taker_analysis AS
SELECT
    -- Overall maker vs taker breakdown
    count() AS total_trades,
    countIf(is_maker_fill) AS gabagool_maker_fills,
    countIf(is_taker_fill) AS gabagool_taker_fills,
    round(countIf(is_maker_fill) * 100.0 / count(), 2) AS gabagool_maker_pct,
    round(countIf(is_taker_fill) * 100.0 / count(), 2) AS gabagool_taker_pct,

    -- Our mode decisions
    countIf(should_take) AS we_would_take,
    countIf(NOT should_take AND would_trade) AS we_would_make,
    round(countIf(should_take) * 100.0 / nullif(countIf(would_trade), 0), 2) AS our_taker_rate_pct,

    -- Mode alignment
    countIf(should_take AND is_taker_fill) AS aligned_taker,
    countIf(NOT should_take AND is_maker_fill AND would_trade) AS aligned_maker,
    round((countIf(should_take AND is_taker_fill) + countIf(NOT should_take AND is_maker_fill AND would_trade)) * 100.0 / nullif(countIf(would_trade), 0), 2) AS mode_alignment_pct

FROM polybot.strategy_validation_v2;


-- =============================================================================
-- 4) REPLICATION BY SERIES (V2)
-- =============================================================================

CREATE OR REPLACE VIEW polybot.replication_by_series_v2 AS
SELECT
    series,
    count() AS total_trades,
    countIf(is_match) AS matches,
    round(countIf(is_match) * 100.0 / count(), 2) AS match_rate_pct,

    countIf(match_type = 'MAKER_MATCH') AS maker_matches,
    countIf(match_type = 'TAKER_MATCH') AS taker_matches,

    round(avgIf(actual_size, is_match), 2) AS avg_actual_size,
    round(avgIf(our_quote_size, is_match), 2) AS avg_our_size,

    round(sum(actual_pnl), 2) AS gabagool_pnl,
    round(sumIf(simulated_pnl, is_match), 2) AS our_simulated_pnl,

    round(avg(complete_set_edge) * 100, 3) AS avg_edge_pct

FROM polybot.strategy_validation_v2
GROUP BY series
ORDER BY series;
