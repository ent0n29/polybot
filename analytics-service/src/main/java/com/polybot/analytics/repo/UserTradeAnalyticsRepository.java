package com.polybot.analytics.repo;

import java.time.Instant;
import java.util.List;

public interface UserTradeAnalyticsRepository {

  UserTradeStats stats(String username);

  List<NamedCount> sideBreakdown(String username);

  List<NamedCount> outcomeBreakdown(String username);

  List<MarketCount> topMarkets(String username, int limit);

  UpDown15mTimingStats upDown15mTiming(String username);

  record UserTradeStats(
      long trades,
      Instant firstTradeAt,
      Instant lastTradeAt,
      long uniqueMarkets,
      long uniqueAssets,
      double notionalUsd,
      double avgPrice,
      double avgSize
  ) {
  }

  record NamedCount(String name, long count) {
  }

  record MarketCount(String slug, String title, long trades) {
  }

  record UpDown15mTimingStats(
      long trades,
      long minSecondsToEnd,
      long p10SecondsToEnd,
      long p50SecondsToEnd,
      long p90SecondsToEnd,
      long maxSecondsToEnd,
      double avgSecondsToEnd
  ) {
  }
}

