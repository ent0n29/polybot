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
        repository.topMarkets(username, topMarkets)
    );
  }

  public record UserTradeReport(
      String username,
      UserTradeAnalyticsRepository.UserTradeStats stats,
      List<UserTradeAnalyticsRepository.NamedCount> sideBreakdown,
      List<UserTradeAnalyticsRepository.NamedCount> outcomeBreakdown,
      UserTradeAnalyticsRepository.UpDown15mTimingStats upDown15mTiming,
      List<UserTradeAnalyticsRepository.MarketCount> topMarkets
  ) {
  }
}

