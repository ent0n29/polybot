package com.polybot.analytics.web;

import com.polybot.analytics.repo.AnalyticsEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

  private final Environment env;
  private final AnalyticsEventRepository repository;

  @GetMapping("/status")
  public Status status() {
    return new Status(
        env.getProperty("spring.application.name"),
        env.getProperty("spring.datasource.url"),
        "analytics_events"
    );
  }

  @GetMapping("/events")
  public List<AnalyticsEventRepository.StoredEvent> recent(
      @RequestParam(name="type", required=false) String type,
      @RequestParam(name="limit", required=false, defaultValue="100") int limit
  ) {
    return repository.recent(type, limit);
  }

  public record Status(
      String app,
      String datasourceUrl,
      String eventsTable
  ) {
  }
}
