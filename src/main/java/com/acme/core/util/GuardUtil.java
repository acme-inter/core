package com.acme.core.util;

import com.acme.core.enums.Modules;
import com.acme.core.enums.RoleType;
import com.acme.core.exception.ThrowException;
import com.acme.core.payload.MemberPrincipal;
import com.acme.core.payload.audit.AuditDTO;
import com.acme.core.payload.role.PermissionElementDTO;
import com.acme.core.payload.session.SessionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

@Component
@RequiredArgsConstructor
public class GuardUtil {

  private final RedisUtil redisUtil;

  // ─── Role tiers ───────────────────────────────────────────────────────────
  public static final Set<RoleType> SUPER_ROLES = Set.of(
      RoleType.DEVELOPER,
      RoleType.ADMINISTRATOR
  );

  public static final Set<RoleType> ELEVATED_ROLES = Set.of(
      RoleType.BUMANAGER,
      RoleType.MANAGEMENT
  );

  public Mono<AuditDTO> auditContext() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(auth -> {
          Object principal = auth.getPrincipal();
          if (principal instanceof MemberPrincipal(
              Boolean isApi, Long memberId, Long departmentId, Long sessionId,
              String roleType, String module, String lang, String ip,
              String browser, String deviceType
          )) {
            return AuditDTO.from(isApi, memberId, departmentId, sessionId,
                module, lang, roleType, ip, browser, deviceType);
          }
          return EMPTY_AUDIT;
        })
        .defaultIfEmpty(EMPTY_AUDIT);
  }

  public Mono<SessionDTO> sessionContext() {
    return auditContext()
        .flatMap(audit -> {
          Long sessionId = audit.getSessionId();
          if (sessionId == null) return Mono.just(EMPTY_SESSION);
          return redisUtil.getValidSession(sessionId, SessionDTO.class)
              .onErrorReturn(EMPTY_SESSION);
        });
  }

  // ─── Context helpers (each does exactly ONE reactive read) ────────────────
  public Mono<Long> memberId() {
    return auditContext().mapNotNull(AuditDTO::getMemberId);
  }

  public Mono<RoleType> roleType() {
    return auditContext().mapNotNull(AuditDTO::getRoleType);
  }

  private static final AuditDTO   EMPTY_AUDIT   = AuditDTO.from(null, null, null, null);
  private static final SessionDTO EMPTY_SESSION = SessionDTO.empty();

  private Mono<GuardContext> guardContext() {
    return auditContext()
        .flatMap(audit -> {
          Long sessionId = audit.getSessionId();
          Mono<SessionDTO> sessionMono = (sessionId == null)
              ? Mono.just(EMPTY_SESSION)
              : redisUtil.getValidSession(sessionId, SessionDTO.class)
              .onErrorReturn(EMPTY_SESSION);
          return sessionMono.map(session -> new GuardContext(audit, session));
        });
  }

  private Mono<AuditDTO> auditOnly() {
    return auditContext(); // already has defaultIfEmpty inside
  }

  // ─── Internal helpers ─────────────────────────────────────────────────────
  private Optional<PermissionElementDTO> resolvePermission(GuardContext ctx) {
    Modules module = ctx.audit().getModule();
    if (module == null || ctx.session().getPermissions() == null) return Optional.empty();
    return ctx.session().getPermissions().stream()
        .filter(p -> module.equals(p.getModule()))
        .findFirst();
  }

  private boolean isSuper(RoleType role) {
    return SUPER_ROLES.contains(role);
  }

  public boolean isElevated(RoleType role) {
    return ELEVATED_ROLES.contains(role);
  }

  private static long nullToZero(Long v)    { return v == null ? 0L : v; }
  private static RoleType nullToMember(RoleType r) { return r == null ? RoleType.MEMBER : r; }

  // ─── Permission-flag guards (hits Redis session) ──────────────────────────
  @FunctionalInterface
  public interface PermissionFlag {
    Boolean test(PermissionElementDTO p);
  }

  public static final PermissionFlag CAN_ADD      = PermissionElementDTO::getCanAdd;
  public static final PermissionFlag CAN_EDIT     = PermissionElementDTO::getCanEdit;
  public static final PermissionFlag CAN_DELETE   = PermissionElementDTO::getCanDelete;
  public static final PermissionFlag CAN_SYNC     = PermissionElementDTO::getCanSync;
  public static final PermissionFlag CAN_IMPORT   = PermissionElementDTO::getCanImport;
  public static final PermissionFlag CAN_EXPORT   = PermissionElementDTO::getCanExport;
  public static final PermissionFlag CAN_DOWNLOAD = PermissionElementDTO::getCanDownload;
  public static final PermissionFlag CAN_COMMENT  = PermissionElementDTO::getCanComment;

  public Mono<Long> requireSuperOnly(String deniedMessageKey) {
    return requireRole(null, deniedMessageKey);
  }

  public Mono<Long> requirePermission(PermissionFlag flag, String deniedMessageKey) {
    return guardContext().flatMap(ctx -> {
      long     callerId = nullToZero(ctx.audit().getMemberId());
      RoleType role     = nullToMember(ctx.audit().getRoleType());
      if (isSuper(role)) return Mono.just(callerId);
      return resolvePermission(ctx)
          .filter(p -> Boolean.TRUE.equals(flag.test(p)))
          .map(p -> Mono.<Long>just(callerId))
          .orElseGet(() -> Mono.error(new ThrowException(deniedMessageKey)));
    });
  }

  public Mono<Long> requirePermissionOrOwner(PermissionFlag flag,
                                             LongSupplier ownerIdSupplier,
                                             String deniedMessageKey) {
    return guardContext().flatMap(ctx -> {
      long     callerId = nullToZero(ctx.audit().getMemberId());
      RoleType role     = nullToMember(ctx.audit().getRoleType());
      if (isSuper(role)) return Mono.just(callerId);
      long ownerId = ownerIdSupplier.getAsLong();
      if (ownerId != 0L && ownerId == callerId) return Mono.just(callerId);
      return resolvePermission(ctx)
          .filter(p -> Boolean.TRUE.equals(flag.test(p)))
          .map(p -> Mono.<Long>just(callerId))
          .orElseGet(() -> Mono.error(new ThrowException(deniedMessageKey)));
    });
  }

  public <T> Mono<Long> requirePermissionOrOwnerAll(Iterable<T> entities,
                                                    ToLongFunction<T> ownerExtractor,
                                                    PermissionFlag flag,
                                                    String deniedMessageKey) {
    return guardContext().flatMap(ctx -> {
      long     callerId     = nullToZero(ctx.audit().getMemberId());
      RoleType role         = nullToMember(ctx.audit().getRoleType());
      if (isSuper(role)) return Mono.just(callerId);
      boolean hasPermission = resolvePermission(ctx)
          .map(p -> Boolean.TRUE.equals(flag.test(p)))
          .orElse(false);
      for (T entity : entities) {
        long    ownerId = ownerExtractor.applyAsLong(entity);
        boolean isOwner = ownerId != 0L && ownerId == callerId;
        if (!isOwner && !hasPermission) {
          return Mono.<Long>error(new ThrowException(deniedMessageKey));
        }
      }
      return Mono.just(callerId);
    });
  }

  public Mono<Long> requireModuleAccess(String deniedMessageKey) {
    return guardContext().flatMap(ctx -> {
      long     callerId = nullToZero(ctx.audit().getMemberId());
      RoleType role     = nullToMember(ctx.audit().getRoleType());
      if (isSuper(role)) return Mono.just(callerId);
      return resolvePermission(ctx)
          .map(p -> Mono.<Long>just(callerId))
          .orElseGet(() -> Mono.error(new ThrowException(deniedMessageKey)));
    });
  }

  // ─── Role-only guards (NO Redis hit — audit context only) ─────────────────
  public Mono<Long> requireRole(Set<RoleType> requiredRoles, String deniedMessageKey) {
    return auditOnly().flatMap(audit -> {
      long     callerId = nullToZero(audit.getMemberId());
      RoleType role     = nullToMember(audit.getRoleType());
      boolean  allowed  = isSuper(role)
          || (requiredRoles != null && requiredRoles.contains(role));
      return allowed
          ? Mono.just(callerId)
          : Mono.error(new ThrowException(deniedMessageKey));
    });
  }

  public Mono<Long> requireRoleOrOwner(LongSupplier ownerIdSupplier,
                                       String deniedMessageKey) {
    return auditOnly().flatMap(audit -> {
      long     callerId = nullToZero(audit.getMemberId());
      RoleType role     = nullToMember(audit.getRoleType());
      if (isSuper(role)) return Mono.just(callerId);
      long ownerId = ownerIdSupplier.getAsLong();
      if (ownerId != 0L && ownerId == callerId) return Mono.just(callerId);
      return Mono.<Long>error(new ThrowException(deniedMessageKey));
    });
  }

  public <T> Mono<Long> requireRoleOrOwnerAll(Iterable<T> entities,
                                              ToLongFunction<T> ownerExtractor,
                                              String deniedMessageKey) {
    return auditOnly().flatMap(audit -> {
      long     callerId = nullToZero(audit.getMemberId());
      RoleType role     = nullToMember(audit.getRoleType());
      if (isSuper(role)) return Mono.just(callerId);
      for (T entity : entities) {
        long ownerId = ownerExtractor.applyAsLong(entity);
        if (ownerId == 0L || ownerId != callerId) {
          return Mono.<Long>error(new ThrowException(deniedMessageKey));
        }
      }
      return Mono.just(callerId);
    });
  }

  public Mono<Long> requireElevatedOrOwner(LongSupplier ownerIdSupplier,
                                           String deniedMessageKey) {
    return auditOnly().flatMap(audit -> {
      long     callerId = nullToZero(audit.getMemberId());
      RoleType role     = nullToMember(audit.getRoleType());
      if (isSuper(role) || isElevated(role)) return Mono.just(callerId);
      long ownerId = ownerIdSupplier.getAsLong();
      if (ownerId != 0L && ownerId == callerId) return Mono.just(callerId);
      return Mono.<Long>error(new ThrowException(deniedMessageKey));
    });
  }

  // ─── Internal record ──────────────────────────────────────────────────────

  private record GuardContext(AuditDTO audit, SessionDTO session) {}
}