package com.acme.core.payload.audit;

import com.acme.core.enums.Modules;
import com.acme.core.enums.RoleType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditDTO {
  private Boolean isApi;
  private Long memberId;
  private Long departmentId;
  private Long sessionId;
  private RoleType roleType;
  private String ip;
  private String browser;
  private String deviceType;
  private String lang;
  private Modules module;

  public static AuditDTO from(Long memberId, Long departmentId, Long sessionId, String lang) {
    return AuditDTO.builder()
        .memberId(memberId)
        .departmentId(departmentId)
        .sessionId(sessionId)
        .lang(lang)
        .build();
  }

  public static AuditDTO from(Boolean isApi ,Long memberId, Long departmentId, Long sessionId, String module, String lang, String roleType, String ip, String browser, String deviceType) {
    return AuditDTO.builder()
        .isApi(isApi)
        .memberId(memberId)
        .departmentId(departmentId)
        .sessionId(sessionId)
        .lang(lang)
        .roleType(RoleType.valueOf(roleType))
        .ip(ip)
        .browser(browser)
        .deviceType(deviceType)
        .module(Modules.valueOf(module))
        .build();
  }
}