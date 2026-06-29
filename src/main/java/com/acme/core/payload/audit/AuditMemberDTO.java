package com.acme.core.payload.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMemberDTO {
  private Long id;
  private String username;
  private String firstName;
  private String lastName;
  private String email;
  private String phone;
  private String larkId;
  private String avatar;
}