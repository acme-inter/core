package com.acme.core.payload.department;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DepartmentElementDTO {
  private Long id;
  private String name;
  private String code;
  private String color;
  private Boolean isPrimary;
  private OffsetDateTime updatedAt;
}