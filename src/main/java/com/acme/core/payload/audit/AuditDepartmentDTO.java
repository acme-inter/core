package com.acme.core.payload.audit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditDepartmentDTO {
  private Long   id;
  private String code;
  private String name;
  private String color;
}