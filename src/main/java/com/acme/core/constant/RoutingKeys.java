package com.acme.core.constant;

public final class RoutingKeys {

  private RoutingKeys() {}

  // ── Member ────────────────────────────────────────────────────────────────
  public static final String MEMBER_CREATED  = "user.member.created";
  public static final String MEMBER_UPDATED  = "user.member.updated";
  public static final String MEMBER_DELETED  = "user.member.deleted";

  // ── Department ────────────────────────────────────────────────────────────
  public static final String DEPARTMENT_CREATED = "user.department.created";
  public static final String DEPARTMENT_UPDATED = "user.department.updated";
  public static final String DEPARTMENT_DELETED = "user.department.deleted";

  // ── Session ───────────────────────────────────────────────────────────────
  public static final String SESSION_INVALIDATE = "user.session.invalidate";

  // ── Log ───────────────────────────────────────────────────────────────────
  // Each service publishes with its own prefix, e.g. "crm.log", "account.log"
  public static final String LOG_SUFFIX = ".log";

  public static String logKey(String service) {
    return service + LOG_SUFFIX;  // e.g. "crm.log", "account.log"
  }

  // ── Queue names ───────────────────────────────────────────────────────────
  @SuppressWarnings("unused")
  public static final class Queues {
    private Queues() {}
    public static final String CRM_MEMBER_SYNC       = "crm.member.sync";
    public static final String ACCOUNT_MEMBER_SYNC   = "account.member.sync";
    public static final String CHECKLIST_MEMBER_SYNC = "checklist.member.sync";
    public static final String USER_LOG_INGEST       = "user.log.ingest";
  }
}
