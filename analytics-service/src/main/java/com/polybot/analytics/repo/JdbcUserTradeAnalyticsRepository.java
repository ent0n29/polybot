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

  private static final String TRADE_EVENT_TYPE = "polymarket.user.trade";

  private final JdbcTemplate jdbcTemplate;

  @Override
  public UserTradeStats stats(String username) {
    String sql = """
        SELECT
          uniqExact(kafka_key) AS trades,
          min(ts) AS first_trade_at,
          max(ts) AS last_trade_at,
          uniqExact(JSONExtractString(data, 'trade', 'slug')) AS unique_markets,
          uniqExact(JSONExtractString(data, 'trade', 'asset')) AS unique_assets,
          sum(JSONExtractFloat(data, 'trade', 'size') * JSONExtractFloat(data, 'trade', 'price')) AS notional_usd,
          avg(JSONExtractFloat(data, 'trade', 'price')) AS avg_price,
          avg(JSONExtractFloat(data, 'trade', 'size')) AS avg_size
        FROM analytics_events
        WHERE type = ?
          AND JSONExtractString(data, 'username') = ?
        """;
    return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapStats(rs), TRADE_EVENT_TYPE, username);
  }

  @Override
  public List<NamedCount> sideBreakdown(String username) {
    String sql = """
        SELECT
          JSONExtractString(data, 'trade', 'side') AS side,
          uniqExact(kafka_key) AS trades
        FROM analytics_events
        WHERE type = ?
          AND JSONExtractString(data, 'username') = ?
        GROUP BY side
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new NamedCount(rs.getString(1), rs.getLong(2)), TRADE_EVENT_TYPE, username);
  }

  @Override
  public List<NamedCount> outcomeBreakdown(String username) {
    String sql = """
        SELECT
          JSONExtractString(data, 'trade', 'outcome') AS outcome,
          uniqExact(kafka_key) AS trades
        FROM analytics_events
        WHERE type = ?
          AND JSONExtractString(data, 'username') = ?
        GROUP BY outcome
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new NamedCount(rs.getString(1), rs.getLong(2)), TRADE_EVENT_TYPE, username);
  }

  @Override
  public List<MarketCount> topMarkets(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          JSONExtractString(data, 'trade', 'slug') AS slug,
          any(JSONExtractString(data, 'trade', 'title')) AS title,
          uniqExact(kafka_key) AS trades
        FROM analytics_events
        WHERE type = ?
          AND JSONExtractString(data, 'username') = ?
        GROUP BY slug
        ORDER BY trades DESC
        LIMIT %d
        """.formatted(safeLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new MarketCount(rs.getString(1), rs.getString(2), rs.getLong(3)), TRADE_EVENT_TYPE, username);
  }

  @Override
  public UpDown15mTimingStats upDown15mTiming(String username) {
    String sql = """
        WITH
          JSONExtractString(data, 'trade', 'slug') AS slug,
          toUInt32OrZero(arrayElement(splitByChar('-', slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end
        SELECT
          uniqExact(kafka_key) AS trades,
          min(seconds_to_end) AS min_seconds_to_end,
          quantileExact(0.10)(seconds_to_end) AS p10_seconds_to_end,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          quantileExact(0.90)(seconds_to_end) AS p90_seconds_to_end,
          max(seconds_to_end) AS max_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM analytics_events
        WHERE type = ?
          AND JSONExtractString(data, 'username') = ?
          AND position(slug, 'updown-15m-') > 0
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
    }, TRADE_EVENT_TYPE, username);
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
