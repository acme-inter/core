package com.acme.core.payload;

public record MemberPrincipal(
    Boolean isApi,
    Long memberId,
    Long departmentId ,
    Long sessionId,
    String roleType,
    String module,
    String lang,
    String ip,
    String browser,
    String deviceType
) {
  public Long departmentIdOrDefault() {
    return departmentId != null ? departmentId : 0L;
  }
}