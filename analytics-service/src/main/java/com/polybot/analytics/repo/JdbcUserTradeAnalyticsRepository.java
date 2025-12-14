package com.polybot.analytics.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcUserTradeAnalyticsRepository implements UserTradeAnalyticsRepository {

  private static final double EPS = 1e-6;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public UserTradeStats stats(String username) {
    String sql = """
        SELECT
          count() AS trades,
          min(ts) AS first_trade_at,
          max(ts) AS last_trade_at,
          uniqExact(market_slug) AS unique_markets,
          uniqExact(token_id) AS unique_assets,
          sum(size * price) AS notional_usd,
          avg(price) AS avg_price,
          avg(size) AS avg_size
        FROM user_trades_dedup
        WHERE username = ?
        """;
    return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapStats(rs), username);
  }

  @Override
  public List<NamedCount> sideBreakdown(String username) {
    String sql = """
        SELECT
          side,
          count() AS trades
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY side
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new NamedCount(rs.getString(1), rs.getLong(2)), username);
  }

  @Override
  public List<NamedCount> outcomeBreakdown(String username) {
    String sql = """
        SELECT
          outcome,
          count() AS trades
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY outcome
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new NamedCount(rs.getString(1), rs.getLong(2)), username);
  }

  @Override
  public List<MarketCount> topMarkets(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug AS slug,
          any(title) AS title,
          count() AS trades
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY market_slug
        ORDER BY trades DESC
        LIMIT %d
        """.formatted(safeLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new MarketCount(rs.getString(1), rs.getString(2), rs.getLong(3)), username);
  }

  @Override
  public UpDown15mTimingStats upDown15mTiming(String username) {
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end
        SELECT
          count() AS trades,
          min(seconds_to_end) AS min_seconds_to_end,
          quantileExact(0.10)(seconds_to_end) AS p10_seconds_to_end,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          quantileExact(0.90)(seconds_to_end) AS p90_seconds_to_end,
          max(seconds_to_end) AS max_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN -60 AND 3600
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new UpDown15mTimingStats(0, 0, 0, 0, 0, 0, 0);
      }
      return new UpDown15mTimingStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getLong(3),
          rs.getLong(4),
          rs.getLong(5),
          rs.getLong(6),
          rs.getDouble(7)
      );
    }, username);
  }

  @Override
  public CompleteSetStats completeSetStats(String username) {
    String sql = """
        SELECT
          count() AS markets_traded,
          countIf(complete_set_shares > 0) AS markets_with_complete_sets,
          sum(complete_set_shares) AS complete_set_shares_total,
          sum(complete_set_shares * implied_edge_per_share) AS total_implied_edge_usd,
          if(sum(complete_set_shares) > 0, sum(complete_set_shares * implied_edge_per_share) / sum(complete_set_shares), 0) AS avg_implied_edge_per_share
        FROM (
          SELECT
            market_slug,
            least(up_shares, down_shares) AS complete_set_shares,
            (1 - (up_avg_price + down_avg_price)) AS implied_edge_per_share
          FROM (
            SELECT
              market_slug,
              sumIf(size, outcome = 'Up') AS up_shares,
              sumIf(size * price, outcome = 'Up') AS up_cost,
              sumIf(size, outcome = 'Down') AS down_shares,
              sumIf(size * price, outcome = 'Down') AS down_cost,
              if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
              if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
            FROM user_trades_dedup
            WHERE username = ?
              AND side = 'BUY'
              AND outcome IN ('Up', 'Down')
            GROUP BY market_slug
          )
        )
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new CompleteSetStats(0, 0, 0, 0, 0);
      }
      return new CompleteSetStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getDouble(3),
          rs.getDouble(4),
          rs.getDouble(5)
      );
    }, username);
  }

  @Override
  public RealizedPnlStats realizedPnl(String username) {
    String sql = """
        SELECT
          sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
          countIf(realized_pnl IS NOT NULL) AS resolved_trades,
          uniqExactIf(market_slug, realized_pnl IS NOT NULL) AS resolved_markets,
          uniqExact(market_slug) AS total_markets,
          if(
            countIf(realized_pnl IS NOT NULL) > 0,
            countIf(realized_pnl > 0) / countIf(realized_pnl IS NOT NULL),
            0
          ) AS win_rate
        FROM user_trade_enriched
        WHERE username = ?
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new RealizedPnlStats(0, 0, 0, 0, 0);
      }
      return new RealizedPnlStats(
          rs.getDouble(1),
          rs.getLong(2),
          rs.getLong(3),
          rs.getLong(4),
          rs.getDouble(5)
      );
    }, username);
  }

  @Override
  public List<CompleteSetMarket> completeSetMarkets(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug,
          title,
          up_shares,
          down_shares,
          least(up_shares, down_shares) AS complete_set_shares,
          up_avg_price,
          down_avg_price,
          (1 - (up_avg_price + down_avg_price)) AS implied_edge_per_share,
          least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price)) AS implied_edge_usd
        FROM (
          SELECT
            market_slug,
            any(title) AS title,
            sumIf(size, outcome = 'Up') AS up_shares,
            sumIf(size * price, outcome = 'Up') AS up_cost,
            sumIf(size, outcome = 'Down') AS down_shares,
            sumIf(size * price, outcome = 'Down') AS down_cost,
            if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
            if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
          FROM user_trades_dedup
          WHERE username = ?
            AND side = 'BUY'
            AND outcome IN ('Up', 'Down')
          GROUP BY market_slug
        )
        ORDER BY complete_set_shares DESC
        LIMIT %d
        """.formatted(safeLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new CompleteSetMarket(
        rs.getString(1),
        rs.getString(2),
        rs.getDouble(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6),
        rs.getDouble(7),
        rs.getDouble(8),
        rs.getDouble(9)
    ), username);
  }

  @Override
  public ExecutionQualityStats executionQuality(String username) {
    String sql = """
        WITH
          (tob_captured_at IS NOT NULL AND best_bid_price > 0 AND best_ask_price > 0) AS tob_known,
          (price >= best_ask_price - ?) AS buy_taker_like_flag,
          (price <= best_bid_price + ?) AS buy_maker_like_flag,
          (price > best_bid_price + ? AND price < best_ask_price - ?) AS buy_inside_flag
        SELECT
          count() AS trades,
          countIf(tob_known) AS trades_with_tob,
          if(count() > 0, countIf(tob_known) / count(), 0) AS tob_coverage,
          countIf(side = 'BUY' AND tob_known AND buy_taker_like_flag) AS buy_taker_like,
          countIf(side = 'BUY' AND tob_known AND buy_maker_like_flag) AS buy_maker_like,
          countIf(side = 'BUY' AND tob_known AND buy_inside_flag) AS buy_inside,
          avgIf(spread, tob_known) AS avg_spread,
          avgIf(price_minus_mid, tob_known) AS avg_price_minus_mid
        FROM user_trade_enriched
        WHERE username = ?
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new ExecutionQualityStats(0, 0, 0, 0, 0, 0, 0, 0);
      }
      return new ExecutionQualityStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getDouble(3),
          rs.getLong(4),
          rs.getLong(5),
          rs.getLong(6),
          rs.getDouble(7),
          rs.getDouble(8)
      );
    }, EPS, EPS, EPS, EPS, username);
  }

  @Override
  public List<MarketPnl> realizedPnlByMarket(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug,
          title,
          resolved_trades,
          realized_pnl_usd,
          if(resolved_trades > 0, wins / resolved_trades, 0) AS win_rate
        FROM (
          SELECT
            market_slug,
            any(title) AS title,
            countIf(realized_pnl IS NOT NULL) AS resolved_trades,
            sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
            countIf(realized_pnl > 0) AS wins
          FROM user_trade_enriched
          WHERE username = ?
          GROUP BY market_slug
        )
        WHERE resolved_trades > 0
        ORDER BY realized_pnl_usd DESC
        LIMIT %d
        """.formatted(safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new MarketPnl(
        rs.getString(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public List<TimingBucket> upDown15mTimingBuckets(String username, int bucketSeconds) {
    int safeBucketSeconds = Math.max(1, Math.min(900, bucketSeconds));
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end,
          intDiv(seconds_to_end, %d) * %d AS bucket_start
        SELECT
          bucket_start AS bucket_start_seconds_to_end,
          (bucket_start + %d) AS bucket_end_seconds_to_end,
          count() AS trades,
          avg(price) AS avg_price,
          avg(size) AS avg_size
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN 0 AND 900
        GROUP BY bucket_start
        ORDER BY bucket_start ASC
        """.formatted(safeBucketSeconds, safeBucketSeconds, safeBucketSeconds);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new TimingBucket(
        rs.getLong(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public DetectedCompleteSetStats detectedCompleteSetStats(String username, int windowSeconds) {
    int safeWindowSeconds = Math.max(1, Math.min(300, windowSeconds));
    String sql = """
        WITH
          (
            SELECT uniqExact(market_slug)
            FROM user_trades_dedup
            WHERE username = ?
              AND side = 'BUY'
              AND outcome IN ('Up', 'Down')
          ) AS markets_traded_total
        SELECT
          markets_traded_total AS markets_traded,
          uniqExact(market_slug) AS markets_with_detected_complete_sets,
          count() AS windows_with_both_sides,
          sum(least(up_shares, down_shares)) AS detected_complete_set_shares,
          sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) AS total_implied_edge_usd,
          if(
            sum(least(up_shares, down_shares)) > 0,
            sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) / sum(least(up_shares, down_shares)),
            0
          ) AS avg_implied_edge_per_share
        FROM (
          SELECT
            market_slug,
            toStartOfInterval(ts, INTERVAL %d SECOND) AS bucket_start,
            sumIf(size, outcome = 'Up') AS up_shares,
            sumIf(size * price, outcome = 'Up') AS up_cost,
            sumIf(size, outcome = 'Down') AS down_shares,
            sumIf(size * price, outcome = 'Down') AS down_cost,
            if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
            if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
          FROM user_trades_dedup
          WHERE username = ?
            AND side = 'BUY'
            AND outcome IN ('Up', 'Down')
          GROUP BY market_slug, bucket_start
          HAVING up_shares > 0 AND down_shares > 0
        )
        """.formatted(safeWindowSeconds);

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new DetectedCompleteSetStats(0, 0, 0, 0, 0, 0);
      }
      return new DetectedCompleteSetStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getLong(3),
          rs.getDouble(4),
          rs.getDouble(5),
          rs.getDouble(6)
      );
    }, username, username);
  }

  @Override
  public List<DetectedCompleteSetMarket> detectedCompleteSetMarkets(String username, int windowSeconds, int limit) {
    int safeWindowSeconds = Math.max(1, Math.min(300, windowSeconds));
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug,
          any(title) AS title,
          count() AS windows_with_both_sides,
          sum(least(up_shares, down_shares)) AS detected_complete_set_shares,
          sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) AS total_implied_edge_usd,
          if(
            sum(least(up_shares, down_shares)) > 0,
            sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) / sum(least(up_shares, down_shares)),
            0
          ) AS avg_implied_edge_per_share
        FROM (
          SELECT
            market_slug,
            any(title) AS title,
            toStartOfInterval(ts, INTERVAL %d SECOND) AS bucket_start,
            sumIf(size, outcome = 'Up') AS up_shares,
            sumIf(size * price, outcome = 'Up') AS up_cost,
            sumIf(size, outcome = 'Down') AS down_shares,
            sumIf(size * price, outcome = 'Down') AS down_cost,
            if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
            if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
          FROM user_trades_dedup
          WHERE username = ?
            AND side = 'BUY'
            AND outcome IN ('Up', 'Down')
          GROUP BY market_slug, bucket_start
          HAVING up_shares > 0 AND down_shares > 0
        )
        GROUP BY market_slug
        ORDER BY detected_complete_set_shares DESC
        LIMIT %d
        """.formatted(safeWindowSeconds, safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new DetectedCompleteSetMarket(
        rs.getString(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), username);
  }

  @Override
  public List<TimingPnlBucket> upDown15mPnlByTimingBucket(String username, int bucketSeconds) {
    int safeBucketSeconds = Math.max(1, Math.min(900, bucketSeconds));
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end,
          intDiv(seconds_to_end, %d) * %d AS bucket_start
        SELECT
          bucket_start AS bucket_start_seconds_to_end,
          (bucket_start + %d) AS bucket_end_seconds_to_end,
          countIf(realized_pnl IS NOT NULL) AS resolved_trades,
          sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
          if(
            countIf(realized_pnl IS NOT NULL) > 0,
            sumIf(realized_pnl, realized_pnl IS NOT NULL) / countIf(realized_pnl IS NOT NULL),
            0
          ) AS avg_pnl_per_trade,
          if(
            countIf(realized_pnl IS NOT NULL) > 0,
            countIf(realized_pnl > 0) / countIf(realized_pnl IS NOT NULL),
            0
          ) AS win_rate
        FROM user_trade_enriched
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN 0 AND 900
        GROUP BY bucket_start
        ORDER BY bucket_start ASC
        """.formatted(safeBucketSeconds, safeBucketSeconds, safeBucketSeconds);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new TimingPnlBucket(
        rs.getLong(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), username);
  }

  @Override
  public List<HourlyTradeActivity> hourlyTradeActivity(String username) {
    String sql = """
        SELECT
          toHour(ts) AS hour_utc,
          count() AS trades,
          sum(size * price) AS notional_usd
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY hour_utc
        ORDER BY hour_utc ASC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new HourlyTradeActivity(
        rs.getInt(1),
        rs.getLong(2),
        rs.getDouble(3)
    ), username);
  }

  @Override
  public List<UpDown15mAssetActivity> upDown15mAssetActivity(String username) {
    String sql = """
        WITH upper(arrayElement(splitByChar('-', market_slug), 1)) AS asset
        SELECT
          asset,
          count() AS trades,
          sum(size * price) AS notional_usd,
          avg(price) AS avg_price,
          avg(size) AS avg_size
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND asset != ''
        GROUP BY asset
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mAssetActivity(
        rs.getString(1),
        rs.getLong(2),
        rs.getDouble(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  private static UserTradeStats mapStats(ResultSet rs) {
    try {
      long trades = rs.getLong(1);
      Instant firstTradeAt = rs.getTimestamp(2).toInstant();
      Instant lastTradeAt = rs.getTimestamp(3).toInstant();
      long uniqueMarkets = rs.getLong(4);
      long uniqueAssets = rs.getLong(5);
      double notionalUsd = rs.getDouble(6);
      double avgPrice = rs.getDouble(7);
      double avgSize = rs.getDouble(8);
      return new UserTradeStats(trades, firstTradeAt, lastTradeAt, uniqueMarkets, uniqueAssets, notionalUsd, avgPrice, avgSize);
    } catch (Exception e) {
      throw new RuntimeException("Failed to map trade stats row", e);
    }
  }
}
