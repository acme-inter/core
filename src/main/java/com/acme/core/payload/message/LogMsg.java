package com.acme.core.payload.message;

import com.acme.core.enums.Modules;
import com.acme.core.payload.audit.AuditDTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogMsg {
  private Modules module;
  private String action;
  private String clazz;
  private String description;

  // Who did it
  private Long memberId;
  private Long departmentId;

  // What was affected
  private Long ownerId;
  private Long recordId;
  private String oldValue;   // JSON before (null for CREATE)
  private String newValue;   // JSON after  (null for DELETE)

  private Boolean isCollaborate;
  private List<Long> collaborators;

  private Instant createdAt;

  public static LogMsg from(
      AuditDTO audit,
      Modules module,
      String action,
      String description,
      String clazz,
      Long ownerId,
      Long recordId,
      String oldValue,
      String newValue
  ) {
    return LogMsg.builder()
        .module(module)
        .action(action)
        .description(description)
        .clazz(clazz)
        .memberId(audit.getMemberId())
        .departmentId(audit.getDepartmentId())
        .ownerId(ownerId)
        .recordId(recordId)
        .oldValue(oldValue)
        .newValue(newValue)
        .createdAt(Instant.now())
        .build();
  }

  public static LogMsg from(
      Modules module,
      String action,
      String description,
      String clazz,
      Long ownerId,
      Long recordId,
      Long memberId,
      Long departmentId
  ) {
    return LogMsg.builder()
        .module(module)
        .action(action)
        .description(description)
        .clazz(clazz)
        .memberId(memberId)
        .departmentId(departmentId)
        .ownerId(ownerId)
        .recordId(recordId)
        .oldValue(null)
        .newValue(null)
        .createdAt(Instant.now())
        .build();
  }

  public static LogMsg from(
      AuditDTO audit,
      Modules module,
      String action,
      String description,
      String clazz,
      Long ownerId,
      Long recordId,
      String oldValue,
      String newValue,
      Boolean isCollaborate,
      List<Long> collaborators
  ) {
    return LogMsg.builder()
        .module(module)
        .action(action)
        .description(description)
        .clazz(clazz)
        .memberId(audit.getMemberId())
        .departmentId(audit.getDepartmentId())
        .ownerId(ownerId)
        .recordId(recordId)
        .oldValue(oldValue)
        .newValue(newValue)
        .isCollaborate(isCollaborate)
        .collaborators(collaborators)
        .createdAt(Instant.now())
        .build();
  }
}
