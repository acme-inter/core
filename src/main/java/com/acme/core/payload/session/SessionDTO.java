package com.acme.core.payload.session;

import com.acme.core.payload.department.DepartmentElementDTO;
import com.acme.core.payload.role.PermissionElementDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionDTO {
  private Long id;
  private Long memberId;
  private String firstName;
  private String lastName;
  private String email;
  private String language;
  private String gender;
  private String roleType;
  private String avatarKey;
  private List<DepartmentElementDTO> departments;
  private List<PermissionElementDTO> permissions;

  public static SessionDTO empty() {
    return SessionDTO.builder().build();
  }
}