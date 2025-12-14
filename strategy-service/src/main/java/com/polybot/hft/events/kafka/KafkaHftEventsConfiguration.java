package com.polybot.hft.events.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods=false)
public class KafkaHftEventsConfiguration {

  @Bean
  @ConditionalOnProperty(prefix="hft.events", name="enabled", havingValue="true")
  public HftEventPublisher kafkaHftEventPublisher(
      HftEventsProperties properties,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Clock clock,
      Environment env
  ) {
    String source = env.getProperty("spring.application.name", "app");
    return new KafkaHftEventPublisher(properties, kafkaTemplate, objectMapper, clock, source);
  }
}

