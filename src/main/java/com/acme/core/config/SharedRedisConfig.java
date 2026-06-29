package com.acme.core.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.core.publisher.Hooks;

@Slf4j
@Configuration
public class SharedRedisConfig {

  @Bean("reactiveStringRedisTemplate")
  @ConditionalOnMissingBean(name = "reactiveStringRedisTemplate")
  public ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
    StringRedisSerializer strSerializer = new StringRedisSerializer();
    RedisSerializationContext<String, String> context =
        RedisSerializationContext.<String, String>newSerializationContext(strSerializer)
            .value(strSerializer)
            .hashKey(strSerializer)
            .hashValue(strSerializer)
            .build();

    return new ReactiveRedisTemplate<>(factory, context);
  }

  @PostConstruct
  public void suppressDroppedErrors() {
    Hooks.onErrorDropped(error -> {
      if (error instanceof RedisSystemException) {
        log.warn("Redis system error (dropped): {}", error.getMessage());
      } else {
        log.warn("Dropped error in reactive pipeline: {}", error.toString());
      }
    });
  }
}