package com.acme.core.config;

import com.acme.core.constant.Properties;
import com.acme.core.util.FileUtil;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class FileConfig {

  private final Properties properties;

  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder()
        .endpoint(properties.getMinio().getEndpoint())
        .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
        .build();
  }

  @Bean
  public FileUtil fileUtil(MinioClient minioClient) {
    return new FileUtil(minioClient);
  }
}
