package com.polybot.analytics.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcUserPositionAnalyticsRepository implements UserPositionAnalyticsRepository {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public PositionSummary summary(String username) {
    String sql = """
        WITH
          open_markets AS (
            SELECT uniqExact(market_slug)
            FROM user_trades_dedup u
            LEFT JOIN gamma_markets_latest g ON g.slug = u.market_slug
            WHERE username = ?
              AND (
                if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0) < 0.999
                OR if(length(g.outcome_prices) > 0, arrayMin(g.outcome_prices), 1) > 0.001
              )
          ),
          resolved_markets AS (
            SELECT uniqExact(market_slug)
            FROM user_trades_dedup u
            LEFT JOIN gamma_markets_latest g ON g.slug = u.market_slug
            WHERE username = ?
              AND (
                if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0) >= 0.999
                AND if(length(g.outcome_prices) > 0, arrayMin(g.outcome_prices), 1) <= 0.001
              )
          ),
          token_positions AS (
            SELECT
              market_slug,
              token_id,
              any(outcome) AS outcome,
              sumIf(size, side = 'BUY') AS buy_shares,
              sumIf(size, side = 'SELL') AS sell_shares,
              sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL') AS net_shares,
              sumIf(size * price, side = 'BUY') AS buy_cost,
              sumIf(size * price, side = 'SELL') AS sell_proceeds,
              sumIf(size * price, side = 'BUY') - sumIf(size * price, side = 'SELL') AS net_cost
            FROM user_trades_dedup
            WHERE username = ?
            GROUP BY market_slug, token_id
          ),
          tob_latest AS (
            SELECT
              market_slug,
              token_id,
              argMax(mid, captured_at) AS mid
            FROM clob_tob
            GROUP BY market_slug, token_id
          )
        SELECT
          uniqExact(market_slug) AS markets,
          open_markets AS open_markets,
          resolved_markets AS resolved_markets,
          sumIf(p.net_cost, NOT is_resolved AND p.net_shares != 0) AS open_net_cost_usd,
          sumIf(
            (t.mid * p.net_shares) - p.net_cost,
            NOT is_resolved AND p.net_shares != 0 AND t.mid > 0
          ) AS open_mtm_pnl_usd,
          sumIf(
            (settle_price * p.net_shares) - p.net_cost,
            is_resolved AND settle_price IS NOT NULL
          ) AS realized_pnl_usd
        FROM (
          SELECT
            p.*,
            g.outcomes AS outcomes,
            g.outcome_prices AS outcome_prices,
            if(length(outcome_prices) > 0, arrayMax(outcome_prices), 0) AS max_outcome_price,
            if(length(outcome_prices) > 0, arrayMin(outcome_prices), 1) AS min_outcome_price,
            (max_outcome_price >= 0.999 AND min_outcome_price <= 0.001) AS is_resolved,
            indexOf(outcomes, p.outcome) AS outcome_idx,
            if(
              is_resolved AND outcome_idx > 0,
              arrayElement(outcome_prices, outcome_idx),
              CAST(NULL, 'Nullable(Float64)')
            ) AS settle_price
          FROM token_positions p
          LEFT JOIN gamma_markets_latest g ON g.slug = p.market_slug
        ) p
        LEFT JOIN tob_latest t ON t.market_slug = p.market_slug AND t.token_id = p.token_id
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new PositionSummary(0, 0, 0, 0, 0, 0);
      }
      return new PositionSummary(
          rs.getLong("markets"),
          rs.getLong("open_markets"),
          rs.getLong("resolved_markets"),
          rs.getDouble("open_net_cost_usd"),
          rs.getDouble("open_mtm_pnl_usd"),
          rs.getDouble("realized_pnl_usd")
      );
    }, username, username, username);
  }

  @Override
  public List<TokenPosition> tokenPositions(String username, PositionState state, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String whereState = switch (state) {
      case OPEN -> "net_shares != 0 AND NOT is_resolved";
      case RESOLVED -> "is_resolved";
      case ALL -> "1";
    };
    String orderBy = switch (state) {
      case RESOLVED -> "realized_pnl_usd DESC";
      case OPEN -> "abs(net_cost_usd) DESC";
      case ALL -> "last_trade_at DESC";
    };

    String sql = """
        WITH tob_latest AS (
          SELECT
            market_slug,
            token_id,
            max(captured_at) AS latest_tob_at,
            argMax(best_bid_price, captured_at) AS best_bid_price,
            argMax(best_ask_price, captured_at) AS best_ask_price,
            argMax(mid, captured_at) AS mid,
            argMax(spread, captured_at) AS spread
          FROM clob_tob
          GROUP BY market_slug, token_id
        )
        SELECT
          market_slug,
          title,
          token_id,
          outcome,
          trades,
          first_trade_at,
          last_trade_at,
          buy_shares,
          sell_shares,
          net_shares,
          buy_cost_usd,
          sell_proceeds_usd,
          net_cost_usd,
          latest_tob_at,
          best_bid_price,
          best_ask_price,
          mid,
          spread,
          end_date,
          seconds_to_end_now,
          is_resolved,
          settle_price,
          realized_pnl_usd,
          mtm_pnl_usd
        FROM (
          SELECT
            p.market_slug AS market_slug,
            p.title,
            p.token_id AS token_id,
            p.outcome AS outcome,
            p.trades,
            p.first_trade_at,
            p.last_trade_at,
            p.buy_shares,
            p.sell_shares,
            p.net_shares,
            p.buy_cost_usd,
            p.sell_proceeds_usd,
            p.net_cost_usd,
            nullIf(t.latest_tob_at, toDateTime64(0, 3)) AS latest_tob_at,
            nullIf(t.best_bid_price, 0) AS best_bid_price,
            nullIf(t.best_ask_price, 0) AS best_ask_price,
            nullIf(t.mid, 0) AS mid,
            nullIf(t.spread, 0) AS spread,
            nullIf(g.end_date, toDateTime64(0, 3)) AS end_date,
            dateDiff('second', now(), g.end_date) AS seconds_to_end_now,
            (
              if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0) >= 0.999
              AND if(length(g.outcome_prices) > 0, arrayMin(g.outcome_prices), 1) <= 0.001
            ) AS is_resolved,
            if(
              is_resolved AND indexOf(g.outcomes, p.outcome) > 0,
              arrayElement(g.outcome_prices, indexOf(g.outcomes, p.outcome)),
              CAST(NULL, 'Nullable(Float64)')
            ) AS settle_price,
            if(
              is_resolved AND settle_price IS NOT NULL,
              (settle_price * p.net_shares) - p.net_cost_usd,
              CAST(NULL, 'Nullable(Float64)')
            ) AS realized_pnl_usd,
            if(
              t.mid > 0,
              (t.mid * p.net_shares) - p.net_cost_usd,
              CAST(NULL, 'Nullable(Float64)')
            ) AS mtm_pnl_usd
          FROM (
            SELECT
              market_slug,
              any(title) AS title,
              token_id,
              any(outcome) AS outcome,
              count() AS trades,
              min(ts) AS first_trade_at,
              max(ts) AS last_trade_at,
              sumIf(size, side = 'BUY') AS buy_shares,
              sumIf(size, side = 'SELL') AS sell_shares,
              sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL') AS net_shares,
              sumIf(size * price, side = 'BUY') AS buy_cost_usd,
              sumIf(size * price, side = 'SELL') AS sell_proceeds_usd,
              sumIf(size * price, side = 'BUY') - sumIf(size * price, side = 'SELL') AS net_cost_usd
            FROM user_trades_dedup
            WHERE username = ?
            GROUP BY market_slug, token_id
          ) p
          LEFT JOIN gamma_markets_latest g ON g.slug = p.market_slug
          LEFT JOIN tob_latest t ON t.market_slug = p.market_slug AND t.token_id = p.token_id
        )
        WHERE %s
        ORDER BY %s
        LIMIT %d
        """.formatted(whereState, orderBy, safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> mapTokenPosition(rs), username);
  }

  @Override
  public List<MarketPosition> marketPositions(String username, PositionState state, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String whereState = switch (state) {
      case OPEN -> "NOT is_resolved";
      case RESOLVED -> "is_resolved";
      case ALL -> "1";
    };
    String orderBy = switch (state) {
      case RESOLVED -> "realized_pnl_usd DESC";
      case OPEN -> "abs(net_cost_usd) DESC";
      case ALL -> "last_trade_at DESC";
    };

    String sql = """
        WITH tob_latest AS (
          SELECT
            market_slug,
            token_id,
            argMax(mid, captured_at) AS mid
          FROM clob_tob
          GROUP BY market_slug, token_id
        )
        SELECT
          market_slug,
          any(title) AS title,
          sum(trades) AS trades,
          min(first_trade_at) AS first_trade_at,
          max(last_trade_at) AS last_trade_at,
          end_date,
          seconds_to_end_now,
          is_resolved,
          resolved_outcome,
          count() AS token_positions,
          sum(net_cost_usd) AS net_cost_usd,
          if(is_resolved, sumIf(realized_pnl_token, realized_pnl_token IS NOT NULL), CAST(NULL, 'Nullable(Float64)')) AS realized_pnl_usd,
          if(NOT is_resolved, sumIf(mtm_pnl_token, mtm_pnl_token IS NOT NULL), CAST(NULL, 'Nullable(Float64)')) AS mtm_pnl_usd
        FROM (
          SELECT
            p.market_slug AS market_slug,
            p.title,
            p.token_id,
            p.outcome,
            p.trades,
            p.first_trade_at,
            p.last_trade_at,
            p.net_shares,
            p.net_cost_usd,
            nullIf(g.end_date, toDateTime64(0, 3)) AS end_date,
            dateDiff('second', now(), g.end_date) AS seconds_to_end_now,
            (
              if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0) >= 0.999
              AND if(length(g.outcome_prices) > 0, arrayMin(g.outcome_prices), 1) <= 0.001
            ) AS is_resolved,
            if(
              is_resolved,
              arrayElement(g.outcomes, indexOf(g.outcome_prices, if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0))),
              CAST(NULL, 'Nullable(String)')
            ) AS resolved_outcome,
            if(
              is_resolved AND indexOf(g.outcomes, p.outcome) > 0,
              arrayElement(g.outcome_prices, indexOf(g.outcomes, p.outcome)),
              CAST(NULL, 'Nullable(Float64)')
            ) AS settle_price,
            if(
              is_resolved AND settle_price IS NOT NULL,
              (settle_price * p.net_shares) - p.net_cost_usd,
              CAST(NULL, 'Nullable(Float64)')
            ) AS realized_pnl_token,
            if(
              t.mid > 0,
              (t.mid * p.net_shares) - p.net_cost_usd,
              CAST(NULL, 'Nullable(Float64)')
            ) AS mtm_pnl_token
          FROM (
            SELECT
              market_slug,
              any(title) AS title,
              token_id,
              any(outcome) AS outcome,
              count() AS trades,
              min(ts) AS first_trade_at,
              max(ts) AS last_trade_at,
              sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL') AS net_shares,
              sumIf(size * price, side = 'BUY') - sumIf(size * price, side = 'SELL') AS net_cost_usd
            FROM user_trades_dedup
            WHERE username = ?
            GROUP BY market_slug, token_id
          ) p
          LEFT JOIN gamma_markets_latest g ON g.slug = p.market_slug
          LEFT JOIN tob_latest t ON t.market_slug = p.market_slug AND t.token_id = p.token_id
        )
        WHERE %s
        GROUP BY market_slug, end_date, seconds_to_end_now, is_resolved, resolved_outcome
        ORDER BY %s
        LIMIT %d
        """.formatted(whereState, orderBy, safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> mapMarketPosition(rs), username);
  }

  @Override
  public List<LedgerRow> ledger(String username, String marketSlug, String tokenId, int limit) {
    int safeLimit = Math.max(1, Math.min(500, limit));

    String sql = """
        WITH trades AS (
          SELECT
            ts,
            side,
            outcome,
            price,
            size,
            transaction_hash,
            event_key,
            if(side = 'BUY', size, -size) AS signed_shares,
            if(side = 'BUY', price * size, -(price * size)) AS signed_cost_usd
          FROM user_trades_dedup
          WHERE username = ?
            AND market_slug = ?
            AND token_id = ?
        )
        SELECT
          ts,
          side,
          outcome,
          price,
          size,
          signed_shares,
          signed_cost_usd,
          position_shares,
          position_cost_usd,
          if(position_shares != 0, position_cost_usd / position_shares, CAST(NULL, 'Nullable(Float64)')) AS avg_entry_price,
          transaction_hash,
          event_key
        FROM (
          SELECT
            *,
            sum(signed_shares) OVER (ORDER BY ts ASC, event_key ASC) AS position_shares,
            sum(signed_cost_usd) OVER (ORDER BY ts ASC, event_key ASC) AS position_cost_usd
          FROM trades
        )
        ORDER BY ts DESC, event_key DESC
        LIMIT %d
        """.formatted(safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> mapLedgerRow(rs), username, marketSlug, tokenId);
  }

  @Override
  public List<UpDown15mAssetPnl> upDown15mRealizedPnlByAsset(String username) {
    String sql = """
        WITH positions AS (
          SELECT
            market_slug,
            token_id,
            any(outcome) AS outcome,
            sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL') AS net_shares,
            sumIf(size * price, side = 'BUY') - sumIf(size * price, side = 'SELL') AS net_cost_usd
          FROM user_trades_dedup
          WHERE username = ?
            AND position(market_slug, 'updown-15m-') > 0
          GROUP BY market_slug, token_id
        )
        SELECT
          x.asset AS asset,
          count() AS token_positions,
          sum(x.net_cost_usd) AS net_cost_usd,
          sumIf(x.realized_pnl_usd, x.realized_pnl_usd IS NOT NULL) AS realized_pnl_usd,
          if(sum(x.net_cost_usd) > 0, sumIf(x.realized_pnl_usd, x.realized_pnl_usd IS NOT NULL) / sum(x.net_cost_usd), 0) AS roi
        FROM (
          SELECT
            upper(arrayElement(splitByChar('-', p.market_slug), 1)) AS asset,
            p.net_cost_usd AS net_cost_usd,
            (
              if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0) >= 0.999
              AND if(length(g.outcome_prices) > 0, arrayMin(g.outcome_prices), 1) <= 0.001
            ) AS is_resolved,
            if(
              is_resolved AND indexOf(g.outcomes, p.outcome) > 0,
              arrayElement(g.outcome_prices, indexOf(g.outcomes, p.outcome)),
              CAST(NULL, 'Nullable(Float64)')
            ) AS settle_price,
            if(
              is_resolved AND settle_price IS NOT NULL,
              (settle_price * p.net_shares) - p.net_cost_usd,
              CAST(NULL, 'Nullable(Float64)')
            ) AS realized_pnl_usd
          FROM positions p
          LEFT JOIN gamma_markets_latest g ON g.slug = p.market_slug
        ) AS x
        WHERE x.asset != ''
          AND x.realized_pnl_usd IS NOT NULL
        GROUP BY x.asset
        ORDER BY realized_pnl_usd DESC
        """;

    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mAssetPnl(
        rs.getString(1),
        rs.getLong(2),
        rs.getDouble(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public List<UpDown15mEntryBucketPnl> upDown15mRealizedPnlByEntryBucket(String username, int bucketSeconds) {
    int safeBucketSeconds = Math.max(1, Math.min(900, bucketSeconds));
    String sql = """
        WITH
          %d AS bucket_seconds
        SELECT
          bucket_start AS bucket_start_seconds_to_end,
          bucket_start + bucket_seconds AS bucket_end_seconds_to_end,
          count() AS token_positions,
          sum(net_cost_usd) AS net_cost_usd_sum,
          sum(realized_pnl_usd) AS realized_pnl_usd_sum,
          if(net_cost_usd_sum > 0, realized_pnl_usd_sum / net_cost_usd_sum, 0) AS roi
        FROM (
          SELECT
            intDiv(entry_seconds_to_end, bucket_seconds) * bucket_seconds AS bucket_start,
            net_cost_usd,
            realized_pnl_usd
          FROM (
            SELECT
              market_slug,
              toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
              first_trade_at,
              dateDiff('second', first_trade_at, toDateTime(market_start_epoch + 900)) AS entry_seconds_to_end,
              net_cost_usd,
              realized_pnl_usd
            FROM (
              SELECT
                p.market_slug AS market_slug,
                p.outcome AS outcome,
                p.first_trade_at AS first_trade_at,
                p.net_shares AS net_shares,
                p.net_cost_usd AS net_cost_usd,
                (
                  if(length(g.outcome_prices) > 0, arrayMax(g.outcome_prices), 0) >= 0.999
                  AND if(length(g.outcome_prices) > 0, arrayMin(g.outcome_prices), 1) <= 0.001
                ) AS is_resolved,
                if(
                  is_resolved AND indexOf(g.outcomes, p.outcome) > 0,
                  arrayElement(g.outcome_prices, indexOf(g.outcomes, p.outcome)),
                  CAST(NULL, 'Nullable(Float64)')
                ) AS settle_price,
                if(
                  is_resolved AND settle_price IS NOT NULL,
                  (settle_price * p.net_shares) - p.net_cost_usd,
                  CAST(NULL, 'Nullable(Float64)')
                ) AS realized_pnl_usd
              FROM (
                SELECT
                  market_slug,
                  token_id,
                  any(outcome) AS outcome,
                  min(ts) AS first_trade_at,
                  sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL') AS net_shares,
                  sumIf(size * price, side = 'BUY') - sumIf(size * price, side = 'SELL') AS net_cost_usd
                FROM user_trades_dedup
                WHERE username = ?
                  AND position(market_slug, 'updown-15m-') > 0
                GROUP BY market_slug, token_id
              ) p
              LEFT JOIN gamma_markets_latest g ON g.slug = p.market_slug
            )
          )
          WHERE market_start_epoch > 0
            AND entry_seconds_to_end BETWEEN -60 AND 900
            AND realized_pnl_usd IS NOT NULL
        )
        GROUP BY bucket_start
        ORDER BY bucket_start ASC
        """.formatted(safeBucketSeconds);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mEntryBucketPnl(
        rs.getLong(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), username);
  }

  private static TokenPosition mapTokenPosition(ResultSet rs) {
    try {
      return new TokenPosition(
          rs.getString("market_slug"),
          rs.getString("title"),
          rs.getString("token_id"),
          rs.getString("outcome"),
          rs.getLong("trades"),
          rs.getTimestamp("first_trade_at").toInstant(),
          rs.getTimestamp("last_trade_at").toInstant(),
          rs.getDouble("buy_shares"),
          rs.getDouble("sell_shares"),
          rs.getDouble("net_shares"),
          rs.getDouble("buy_cost_usd"),
          rs.getDouble("sell_proceeds_usd"),
          rs.getDouble("net_cost_usd"),
          getInstantOrNull(rs, "latest_tob_at"),
          getDoubleOrNull(rs, "best_bid_price"),
          getDoubleOrNull(rs, "best_ask_price"),
          getDoubleOrNull(rs, "mid"),
          getDoubleOrNull(rs, "spread"),
          getInstantOrNull(rs, "end_date"),
          rs.getLong("seconds_to_end_now"),
          rs.getBoolean("is_resolved"),
          getDoubleOrNull(rs, "settle_price"),
          getDoubleOrNull(rs, "realized_pnl_usd"),
          getDoubleOrNull(rs, "mtm_pnl_usd")
      );
    } catch (Exception e) {
      throw new RuntimeException("Failed to map token position row", e);
    }
  }

  private static MarketPosition mapMarketPosition(ResultSet rs) {
    try {
      return new MarketPosition(
          rs.getString("market_slug"),
          rs.getString("title"),
          rs.getLong("trades"),
          rs.getTimestamp("first_trade_at").toInstant(),
          rs.getTimestamp("last_trade_at").toInstant(),
          getInstantOrNull(rs, "end_date"),
          rs.getLong("seconds_to_end_now"),
          rs.getBoolean("is_resolved"),
          rs.getString("resolved_outcome"),
          rs.getLong("token_positions"),
          rs.getDouble("net_cost_usd"),
          getDoubleOrNull(rs, "realized_pnl_usd"),
          getDoubleOrNull(rs, "mtm_pnl_usd")
      );
    } catch (Exception e) {
      throw new RuntimeException("Failed to map market position row", e);
    }
  }

  private static LedgerRow mapLedgerRow(ResultSet rs) {
    try {
      return new LedgerRow(
          rs.getTimestamp("ts").toInstant(),
          rs.getString("side"),
          rs.getString("outcome"),
          rs.getDouble("price"),
          rs.getDouble("size"),
          rs.getDouble("signed_shares"),
          rs.getDouble("signed_cost_usd"),
          rs.getDouble("position_shares"),
          rs.getDouble("position_cost_usd"),
          getDoubleOrNull(rs, "avg_entry_price"),
          rs.getString("transaction_hash"),
          rs.getString("event_key")
      );
    } catch (Exception e) {
      throw new RuntimeException("Failed to map ledger row", e);
    }
  }

  private static Double getDoubleOrNull(ResultSet rs, String column) throws Exception {
    Object value = rs.getObject(column);
    if (value == null) {
      return null;
    }
    double d = ((Number) value).doubleValue();
    return Double.isFinite(d) ? d : null;
  }

  private static Instant getInstantOrNull(ResultSet rs, String column) throws Exception {
    Timestamp ts = rs.getTimestamp(column);
    if (ts == null) {
      return null;
    }
    Instant instant = ts.toInstant();
    return instant.equals(Instant.EPOCH) ? null : instant;
  }
}
