package com.acme.core.config;

import com.acme.core.util.RabbitUtil;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SharedRabbitConfig {

  @Bean
  @ConditionalOnMissingBean(MessageConverter.class)
  @ConditionalOnBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
  public MessageConverter messageConverter(JsonMapper objectMapper) {
    return new JacksonJsonMessageConverter(objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(RabbitTemplate.class)
  @ConditionalOnBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
  public RabbitTemplate rabbitTemplate(
      org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
      MessageConverter messageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(messageConverter);
    return template;
  }

  @Bean
  @ConditionalOnMissingBean(RabbitAdmin.class)
  @ConditionalOnBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
  public RabbitAdmin rabbitAdmin(
      org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
    RabbitAdmin admin = new RabbitAdmin(connectionFactory);
    admin.setAutoStartup(true);
    return admin;
  }

  @Bean
  @ConditionalOnMissingBean(TopicExchange.class)
  @ConditionalOnBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
  public TopicExchange acmeEventsExchange() {
    return ExchangeBuilder
        .topicExchange(RabbitUtil.EXCHANGE)
        .durable(true)
        .build();
  }
}