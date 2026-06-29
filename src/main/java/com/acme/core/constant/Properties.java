package com.acme.core.constant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class Properties {
  private Security security = new Security();
  private Minio minio = new Minio();

  @Data
  public static class Security {
    private String secret;
    private boolean secure;
  }

  @Data
  public static class Minio {
    private String endpoint;
    private String accessKey;
    private String secretKey;
  }
}