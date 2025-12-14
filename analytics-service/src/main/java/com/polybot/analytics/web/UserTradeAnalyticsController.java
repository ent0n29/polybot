package com.polybot.analytics.web;

import com.polybot.analytics.repo.UserTradeAnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics/users/{username}/trades")
@RequiredArgsConstructor
public class UserTradeAnalyticsController {

  private final UserTradeAnalyticsRepository repository;

  @GetMapping("/report")
  public UserTradeReport report(
      @PathVariable("username") String username,
      @RequestParam(name = "topMarkets", required = false, defaultValue = "20") int topMarkets
  ) {
    return new UserTradeReport(
        username,
        repository.stats(username),
        repository.sideBreakdown(username),
        repository.outcomeBreakdown(username),
        repository.upDown15mTiming(username),
        repository.completeSetStats(username),
        repository.realizedPnl(username),
        repository.topMarkets(username, topMarkets)
    );
  }

  @GetMapping("/complete-sets")
  public List<UserTradeAnalyticsRepository.CompleteSetMarket> completeSets(
      @PathVariable("username") String username,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.completeSetMarkets(username, limit);
  }

  @GetMapping("/complete-sets/detected")
  public UserTradeAnalyticsRepository.DetectedCompleteSetStats detectedCompleteSetStats(
      @PathVariable("username") String username,
      @RequestParam(name = "windowSeconds", required = false, defaultValue = "10") int windowSeconds
  ) {
    return repository.detectedCompleteSetStats(username, windowSeconds);
  }

  @GetMapping("/complete-sets/detected/markets")
  public List<UserTradeAnalyticsRepository.DetectedCompleteSetMarket> detectedCompleteSetMarkets(
      @PathVariable("username") String username,
      @RequestParam(name = "windowSeconds", required = false, defaultValue = "10") int windowSeconds,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.detectedCompleteSetMarkets(username, windowSeconds, limit);
  }

  @GetMapping("/execution")
  public UserTradeAnalyticsRepository.ExecutionQualityStats execution(
      @PathVariable("username") String username
  ) {
    return repository.executionQuality(username);
  }

  @GetMapping("/pnl/markets")
  public List<UserTradeAnalyticsRepository.MarketPnl> pnlByMarket(
      @PathVariable("username") String username,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.realizedPnlByMarket(username, limit);
  }

  @GetMapping("/pnl/timing/updown-15m")
  public List<UserTradeAnalyticsRepository.TimingPnlBucket> pnlByTimingUpDown15m(
      @PathVariable("username") String username,
      @RequestParam(name = "bucketSeconds", required = false, defaultValue = "60") int bucketSeconds
  ) {
    return repository.upDown15mPnlByTimingBucket(username, bucketSeconds);
  }

  @GetMapping("/timing/updown-15m/buckets")
  public List<UserTradeAnalyticsRepository.TimingBucket> upDown15mTimingBuckets(
      @PathVariable("username") String username,
      @RequestParam(name = "bucketSeconds", required = false, defaultValue = "60") int bucketSeconds
  ) {
    return repository.upDown15mTimingBuckets(username, bucketSeconds);
  }

  @GetMapping("/activity/hourly")
  public List<UserTradeAnalyticsRepository.HourlyTradeActivity> hourlyActivity(
      @PathVariable("username") String username
  ) {
    return repository.hourlyTradeActivity(username);
  }

  @GetMapping("/selection/updown-15m/assets")
  public List<UserTradeAnalyticsRepository.UpDown15mAssetActivity> upDown15mAssets(
      @PathVariable("username") String username
  ) {
    return repository.upDown15mAssetActivity(username);
  }

  public record UserTradeReport(
      String username,
      UserTradeAnalyticsRepository.UserTradeStats stats,
      List<UserTradeAnalyticsRepository.NamedCount> sideBreakdown,
      List<UserTradeAnalyticsRepository.NamedCount> outcomeBreakdown,
      UserTradeAnalyticsRepository.UpDown15mTimingStats upDown15mTiming,
      UserTradeAnalyticsRepository.CompleteSetStats completeSetStats,
      UserTradeAnalyticsRepository.RealizedPnlStats realizedPnl,
      List<UserTradeAnalyticsRepository.MarketCount> topMarkets
  ) {
  }
}
