package com.acme.core.config;

import com.acme.core.constant.Properties;
import com.acme.core.helper.ApiResponseUtil;
import com.acme.core.query.PagedQueryFactory;
import com.acme.core.util.*;
import io.minio.MinioClient;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import tools.jackson.databind.ObjectMapper;

@Import({
    SharedRedisConfig.class,
    SharedRabbitConfig.class,
    FileConfig.class
})
@AutoConfiguration
@EnableConfigurationProperties(Properties.class)
public class SharedConfig {

  @Bean
  @ConditionalOnMissingBean
  public Encryption encryption() {
    return new Encryption();
  }

  @Bean
  @ConditionalOnMissingBean
  public JwtUtil jwtUtil(Encryption encryption, Properties properties) {
    return new JwtUtil(properties, encryption);
  }

  @Bean
  @ConditionalOnMissingBean
  public MsgUtil msgUtil(MessageSource messageSource) {
    return new MsgUtil(messageSource);
  }

  @Bean
  @ConditionalOnMissingBean
  public RabbitUtil rabbitUtil(RabbitTemplate rabbitTemplate,ObjectMapper objectMapper){
    return new RabbitUtil(rabbitTemplate, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public CookieUtil cookieUtil(Encryption encryption, Properties properties) {
    return new CookieUtil(encryption, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public GuardUtil guardUtil(RedisUtil redisUtil) {
    return new GuardUtil(redisUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(ConnectionFactory.class)
  public DatabaseClient databaseClient(ConnectionFactory connectionFactory) {
    return DatabaseClient.create(connectionFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public PagedQueryFactory pagedQueryFactory(DatabaseClient databaseClient, MsgUtil msgUtil, GuardUtil guardUtil) {
    return new PagedQueryFactory(databaseClient, msgUtil, guardUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  public ApiResponseUtil apiResponseUtil(MsgUtil msgUtil) {
    return new ApiResponseUtil(msgUtil);
  }

  @Bean
  @ConditionalOnMissingBean
  public RedisUtil redisUtil(
      @Qualifier("reactiveStringRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
      ObjectMapper objectMapper) {
    return new RedisUtil(redisTemplate, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(MinioClient.class)
  public FileUtil fileUtil(MinioClient minioClient) {
    return new FileUtil(minioClient);
  }
}