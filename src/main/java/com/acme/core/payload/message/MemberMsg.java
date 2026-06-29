package com.acme.core.payload.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemberMsg {
  private Long   id;
  private String username;
  private String employeeId;
  private String firstName;
  private String lastName;
  private String email;
  private String phone;
  private String avatar;
  private String larkId;
  private String lineId;
  private Long managerId;
  private Boolean isActive;
  private Instant updatedAt;
}