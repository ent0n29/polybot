package com.polybot.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:clickhouse://localhost:8123/polybot",
    "spring.datasource.driver-class-name=com.clickhouse.jdbc.ClickHouseDriver",
    "spring.datasource.username=default",
    "spring.datasource.password=",
    "spring.datasource.hikari.initialization-fail-timeout=-1",
    "spring.sql.init.mode=never"
})
class AnalyticsServiceApplicationTests {

  @Test
  void contextLoads() {
  }
}
