package com.polybot.hft.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class HftEventsConfiguration {

  @Bean
  @ConditionalOnMissingBean(HftEventPublisher.class)
  public HftEventPublisher noopHftEventPublisher() {
    return new NoopHftEventPublisher();
  }
}

