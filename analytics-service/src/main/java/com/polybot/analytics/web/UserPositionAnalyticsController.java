package com.polybot.analytics.web;

import com.polybot.analytics.repo.UserPositionAnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/analytics/users/{username}/positions")
@RequiredArgsConstructor
public class UserPositionAnalyticsController {

  private final UserPositionAnalyticsRepository repository;

  @GetMapping("/summary")
  public UserPositionAnalyticsRepository.PositionSummary summary(
      @PathVariable("username") String username
  ) {
    return repository.summary(username);
  }

  @GetMapping("/tokens")
  public List<UserPositionAnalyticsRepository.TokenPosition> tokenPositions(
      @PathVariable("username") String username,
      @RequestParam(name = "state", required = false, defaultValue = "open") String state,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.tokenPositions(username, parseState(state), limit);
  }

  @GetMapping("/markets")
  public List<UserPositionAnalyticsRepository.MarketPosition> marketPositions(
      @PathVariable("username") String username,
      @RequestParam(name = "state", required = false, defaultValue = "open") String state,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.marketPositions(username, parseState(state), limit);
  }

  @GetMapping("/ledger")
  public List<UserPositionAnalyticsRepository.LedgerRow> ledger(
      @PathVariable("username") String username,
      @RequestParam("marketSlug") String marketSlug,
      @RequestParam("tokenId") String tokenId,
      @RequestParam(name = "limit", required = false, defaultValue = "200") int limit
  ) {
    return repository.ledger(username, marketSlug, tokenId, limit);
  }

  @GetMapping("/pnl/updown-15m/assets")
  public List<UserPositionAnalyticsRepository.UpDown15mAssetPnl> upDown15mPnlByAsset(
      @PathVariable("username") String username
  ) {
    return repository.upDown15mRealizedPnlByAsset(username);
  }

  @GetMapping("/pnl/updown-15m/entry-buckets")
  public List<UserPositionAnalyticsRepository.UpDown15mEntryBucketPnl> upDown15mPnlByEntryBucket(
      @PathVariable("username") String username,
      @RequestParam(name = "bucketSeconds", required = false, defaultValue = "60") int bucketSeconds
  ) {
    return repository.upDown15mRealizedPnlByEntryBucket(username, bucketSeconds);
  }

  private static UserPositionAnalyticsRepository.PositionState parseState(String raw) {
    if (raw == null) {
      return UserPositionAnalyticsRepository.PositionState.OPEN;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return UserPositionAnalyticsRepository.PositionState.valueOf(normalized);
    } catch (IllegalArgumentException ignored) {
      return UserPositionAnalyticsRepository.PositionState.OPEN;
    }
  }
}
