package com.polybot.hft.polymarket.strategy.events;

import java.math.BigDecimal;
import java.util.List;

public record HouseEdgeDiscoverySelectedEvent(
    List<String> queries,
    boolean require15m,
    BigDecimal minVolume,
    int maxMarkets,
    int selectedMarkets,
    int gammaCandidates,
    int gammaAfterAssetFilter,
    int clobCandidates,
    List<SelectedMarket> markets
) {
  public record SelectedMarket(
      String name,
      String yesTokenId,
      String noTokenId,
      Long endEpochMillis,
      BigDecimal volume,
      String source
  ) {
  }
}

