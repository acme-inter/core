package com.acme.core.filter;

import com.acme.core.enums.Modules;
import com.acme.core.enums.RoleType;
import com.acme.core.exception.ThrowException;
import com.acme.core.payload.GuestPrincipal;
import com.acme.core.payload.MemberPrincipal;
import com.acme.core.payload.agent.AgentDTO;
import com.acme.core.payload.agent.GeoDTO;
import com.acme.core.payload.role.PermissionElementDTO;
import com.acme.core.payload.session.SessionDTO;
import com.acme.core.util.*;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Slf4j
public abstract class SharedSecurityFilter implements WebFilter {

  private final Encryption encryption;
  private final JwtUtil jwtUtil;
  private final MsgUtil msgUtil;
  private final RedisUtil redisUtil;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String REFRESH_TOKEN_COOKIE_KEY = "refresh";
  private static final String ACCESS_TOKEN_COOKIE_KEY  = "authorize";
  private static final String BEARER_PREFIX            = "Bearer ";

  public static final String ATTR_AGENT = "agentDTO";
  public static final String ATTR_GUEST = "guestPrincipal";

  protected SharedSecurityFilter(Encryption encryption, JwtUtil jwtUtil, MsgUtil msgUtil, RedisUtil redisUtil) {
    this.encryption = encryption;
    this.jwtUtil = jwtUtil;
    this.msgUtil = msgUtil;
    this.redisUtil = redisUtil;
  }

  protected abstract Set<String> excludedPaths();
  protected abstract String      module();

  protected Set<String> geoExcludedPaths() {
    return Set.of();
  }

  protected Set<String> bypassRoles() { return Set.of(); }

  @SuppressWarnings("unused")
  protected Mono<GeoDTO> resolveGeo(String ip) {
    return Mono.empty();
  }

  protected abstract Mono<Void> authorizeSessionById(SessionDTO sessionDTO,
                                                     String token,
                                                     ServerWebExchange exchange,
                                                     WebFilterChain chain,
                                                     String path,
                                                     String lang,
                                                     AgentDTO agent);

  // ── Main filter ──────────────────────────────────────────────────────────────

  @NonNull
  @Override
  public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();
    String path = request.getPath().value();
    String lang = request.getHeaders().getFirst("Accept-Language");

    Locale locale = exchange.getLocaleContext().getLocale();
    if (locale != null) LocaleContextHolder.setLocale(locale);

    boolean excluded = isExcluded(path);
    boolean withGeo  = excluded && !isGeoExcluded(path);

    Mono<AgentDTO> agentMono;
    if (withGeo) {
      agentMono = UserAgentUtil.parse(request)
          .flatMap(agent -> resolveGeo(agent.getIp())
              .map(geo -> {
                agent.setGeo(geo);
                return agent;
              })
              .defaultIfEmpty(agent)
          );
    } else {
      agentMono = UserAgentUtil.parse(request);
    }

    return agentMono.flatMap(agent -> {
      exchange.getAttributes().put(ATTR_AGENT, agent);

      if (agent.isBot()) {
        log.warn("Bot request blocked: ip={} path={}", agent.getIp(), path);
        return sendErrorResponse(exchange,
            msgUtil.filter("error.bot.blocked", lang),
            HttpStatus.FORBIDDEN);
      }
      if (agent.isSuspicious()) {
        log.warn("Suspicious user-agent blocked: ip={} path={}", agent.getIp(), path);
        return sendErrorResponse(exchange,
            msgUtil.filter("error.suspicious.blocked", lang),
            HttpStatus.FORBIDDEN);
      }
      if (agent.isHacker()) {
        log.warn("Malicious user-agent blocked: ip={} vector={} toolkit={} path={}",
            agent.getIp(), agent.getHackerAttackVector(), agent.getHackerToolkit(), path);
        return sendErrorResponse(exchange,
            msgUtil.filter("error.hacker.blocked", lang),
            HttpStatus.FORBIDDEN);
      }

      if (excluded) {
        GeoDTO geo = agent.getGeo();
        exchange.getAttributes().put(ATTR_GUEST, new GuestPrincipal(
            agent.getIp(),
            geo.getCountryCode(),
            geo.getCountryName(),
            geo.getCity()
        ));
        return chain.filter(exchange);
      }

      return extractAccessToken(request)
          .switchIfEmpty(Mono.defer(() ->
              sendErrorResponse(exchange,
                  msgUtil.filter("error.token.missing", lang),
                  HttpStatus.UNAUTHORIZED)
                  .then(Mono.empty())
          ))
          .flatMap(token -> {
            if (token.isBlank()) {
              return sendErrorResponse(exchange,
                  msgUtil.filter("error.token.missing", lang),
                  HttpStatus.UNAUTHORIZED);
            }
            return validateToken(token, exchange, chain, path, lang, agent)
                .onErrorResume(err -> handleError(err, exchange, path, lang));
          });
    });
  }

  // ── Token validation ─────────────────────────────────────────────────────────

  private Mono<Void> validateToken(String token, ServerWebExchange exchange,
                                   WebFilterChain chain, String path,
                                   String lang, AgentDTO agent) {
    return jwtUtil.isInvalid(token)
        .flatMap(isInvalid -> {
          if (Boolean.TRUE.equals(isInvalid)) {
            log.warn("Expired or invalid token: path={} ip={}", path, agent.getIp());
            return sendErrorResponse(exchange,
                msgUtil.filter("error.token.expired", lang),
                HttpStatus.UNAUTHORIZED);
          }
          return decodeAndValidateSession(token, exchange, chain, path, lang, agent);
        });
  }

  private Mono<Void> decodeAndValidateSession(String token,
                                              ServerWebExchange exchange,
                                              WebFilterChain chain,
                                              String path,
                                              String lang,
                                              AgentDTO agent) {
    return jwtUtil.decode(token)
        .flatMap(jwt -> {
          Boolean external = jwt.getClaim("external").asBoolean();
          if (Boolean.TRUE.equals(external)) {
            return continueAsExternal(exchange, chain, jwt, token, lang, agent);
          }
          Long sid = Long.valueOf(jwt.getSubject());
          return redisUtil.getValidSession(sid, SessionDTO.class)
              .switchIfEmpty(Mono.error(new ThrowException("session.missing")))
              .flatMap(session -> authorizeSessionById(session, token, exchange, chain, path, lang, agent));
        });
  }

  // ── Authenticate & continue ───────────────────────────────────────────────────

  protected Mono<Void> authenticateAndContinue(SessionDTO session,
                                               String token,
                                               ServerWebExchange exchange,
                                               WebFilterChain chain,
                                               String lang,
                                               AgentDTO agent) {
    ServerHttpRequest request = exchange.getRequest();

    MemberPrincipal principal = new MemberPrincipal(
        false,
        session.getMemberId(),
        resolveDepartmentId(request, session),
        session.getId(),
        session.getRoleType(),
        module(),
        lang,
        agent.getIp(),
        agent.getBrowser(),
        agent.getDeviceType()
    );
    List<GrantedAuthority> authorities =
        AuthorityUtils.createAuthorityList(session.getRoleType());

    return chain.filter(exchange)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
            new UsernamePasswordAuthenticationToken(principal, token, authorities)
        ));
  }

  private Mono<Void> continueAsExternal(ServerWebExchange exchange, WebFilterChain chain,
                                        DecodedJWT jwt, String token,
                                        String lang, AgentDTO agent) {
    Long memberId     = jwt.getClaim("memberId").asLong();
    Long departmentId = jwt.getClaim("departmentId").asLong();

    MemberPrincipal principal = new MemberPrincipal(
        true,
        memberId,
        departmentId,
        null,
        RoleType.DEVELOPER.toString(),
        Modules.API.toString(),
        lang,
        agent.getIp(),
        agent.getBrowser(),
        agent.getDeviceType()
    );
    List<GrantedAuthority> authorities =
        AuthorityUtils.createAuthorityList(RoleType.DEVELOPER.toString());

    return chain.filter(exchange)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
            new UsernamePasswordAuthenticationToken(principal, token, authorities)
        ));
  }

  // ── Permission gate ───────────────────────────────────────────────────────────

  protected Mono<Void> checkPermission(SessionDTO session,
                                       String token,
                                       ServerWebExchange exchange,
                                       WebFilterChain chain,
                                       String lang,
                                       AgentDTO agent) {
    String roleType = session.getRoleType();

    if (isPrivilegedRole(roleType)) {
      return authenticateAndContinue(session, token, exchange, chain, lang, agent);
    }

    PermissionElementDTO perm = resolvePermission(session);
    if (perm == null) {
      log.warn("No permission record: memberId={} module={} roleType={}",
          session.getMemberId(), module(), roleType);
      return sendErrorResponse(exchange,
          msgUtil.filter("error.permission.no.module", lang),
          HttpStatus.FORBIDDEN);
    }

    ServerHttpRequest             request = exchange.getRequest();
    String                        path    = request.getPath().value();
    HttpMethod method  = request.getMethod();
    MultiValueMap<String, String> query   = request.getQueryParams();

    String deniedKey = resolveOperationDeniedKey(path, method, query, perm);
    if (deniedKey != null) {
      log.warn("Operation denied: memberId={} module={} roleType={} path={} method={} key={}",
          session.getMemberId(), module(), roleType, path, method, deniedKey);
      return sendErrorResponse(exchange,
          msgUtil.filter(deniedKey, lang),
          HttpStatus.FORBIDDEN);
    }

    return authenticateAndContinue(session, token, exchange, chain, lang, agent);
  }

  private boolean isPrivilegedRole(String roleType) {
    if (RoleType.ADMINISTRATOR.toString().equals(roleType)
        || RoleType.DEVELOPER.toString().equals(roleType)) return true;
    return bypassRoles().contains(roleType);
  }

  private PermissionElementDTO resolvePermission(SessionDTO session) {
    return Optional.ofNullable(session.getPermissions())
        .flatMap(perms -> perms.stream()
            .filter(p -> module().equalsIgnoreCase(String.valueOf(p.getModule())))
            .findFirst())
        .orElse(null);
  }

  /** Returns a message key, or null if the operation is allowed. */
  private String resolveOperationDeniedKey(String path,
                                           HttpMethod method,
                                           MultiValueMap<String, String> query,
                                           PermissionElementDTO perm) {
    if (isGetOperation(path, method, query))                        return null;
    if (path.contains("/sync")     || query.containsKey("sync"))
      return !Boolean.TRUE.equals(perm.getCanSync())     ? "error.permission.sync"     : null;
    if (isAddOperation(path, method, query))
      return !Boolean.TRUE.equals(perm.getCanAdd())      ? "error.permission.add"      : null;
    if (isEditOperation(path, method, query))
      return !Boolean.TRUE.equals(perm.getCanEdit())     ? "error.permission.edit"     : null;
    if (isDeleteOperation(path, method, query))
      return !Boolean.TRUE.equals(perm.getCanDelete())   ? "error.permission.delete"   : null;
    if (path.contains("/import")   || query.containsKey("import"))
      return !Boolean.TRUE.equals(perm.getCanImport())   ? "error.permission.import"   : null;
    if (path.contains("/export")   || query.containsKey("export"))
      return !Boolean.TRUE.equals(perm.getCanExport())   ? "error.permission.export"   : null;
    if (path.contains("/download") || query.containsKey("download"))
      return !Boolean.TRUE.equals(perm.getCanDownload()) ? "error.permission.download" : null;
    if (path.contains("/comment")  || query.containsKey("comment"))
      return !Boolean.TRUE.equals(perm.getCanComment())  ? "error.permission.comment"  : null;
    return null;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  protected Long resolveDepartmentId(ServerHttpRequest request, SessionDTO session) {
    String deptHeader = request.getHeaders().getFirst("X-Department-Id");
    if (deptHeader != null && !deptHeader.isBlank()) {
      try { return Long.parseLong(deptHeader); }
      catch (NumberFormatException e) {
        log.warn("Invalid X-Department-Id header value: {}", deptHeader);
      }
    }
    if (session.getDepartments().isEmpty()) return 0L;
    HttpCookie cookie = request.getCookies().getFirst("DKEY");
    if (cookie != null) {
      try { return Long.parseLong(encryption.decodeKey(cookie.getValue())); }
      catch (Exception e) { log.warn("Failed to decode DKEY cookie: {}", e.getMessage()); }
    }
    return session.getDepartments().getFirst().getId();
  }

  protected Mono<Void> sendError(ServerWebExchange exchange, String lang) {
    return sendErrorResponse(exchange,
        msgUtil.filter("error.unexpected", lang),
        HttpStatus.INTERNAL_SERVER_ERROR);
  }

  private boolean isExcluded(String path) {
    return excludedPaths().stream().anyMatch(path::startsWith);
  }

  private boolean isGeoExcluded(String path) {
    return geoExcludedPaths().stream().anyMatch(path::startsWith);
  }

  private Mono<Void> handleError(Throwable ex, ServerWebExchange exchange,
                                 String path, String lang) {
    log.error("Unexpected security error: path={} error={}", path, ex.getMessage(), ex);
    return sendError(exchange, lang);
  }

  private boolean isGetOperation(String path, HttpMethod method,
                                 MultiValueMap<String, String> query) {
    return method == HttpMethod.GET
        || path.contains("/get")
        || query.containsKey("dashboard");
  }

  private boolean isAddOperation(String path, HttpMethod method,
                                 MultiValueMap<String, String> query) {
    return method == HttpMethod.POST
        || path.contains("/add")         || path.contains("/create")
        || path.contains("/generate")    || path.contains("/send")
        || query.containsKey("add")      || query.containsKey("create")
        || query.containsKey("generate") || query.containsKey("send");
  }

  private boolean isEditOperation(String path, HttpMethod method,
                                  MultiValueMap<String, String> query) {
    return method == HttpMethod.PUT || method == HttpMethod.PATCH
        || path.contains("/edit")    || path.contains("/update")
        || query.containsKey("edit") || query.containsKey("update");
  }

  private boolean isDeleteOperation(String path, HttpMethod method,
                                    MultiValueMap<String, String> query) {
    return method == HttpMethod.DELETE
        || path.contains("/delete")    || path.contains("/remove")
        || query.containsKey("delete") || query.containsKey("remove");
  }

  // ── Token extraction ─────────────────────────────────────────────────────────

  public Mono<String> extractAccessToken(ServerHttpRequest request) {
    return Mono.defer(() -> {
      String token = Optional.ofNullable(request.getCookies().getFirst(ACCESS_TOKEN_COOKIE_KEY))
          .map(HttpCookie::getValue)
          .filter(v -> !v.isBlank())
          .orElse(null);
      if (token == null) {
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
          token = authHeader.substring(BEARER_PREFIX.length()).trim();
        }
      }
      return Mono.justOrEmpty(token);
    });
  }

  public Mono<String> extractRefreshToken(ServerHttpRequest request) {
    return Mono.fromCallable(() -> {
      HttpCookie cookie = request.getCookies().getFirst(REFRESH_TOKEN_COOKIE_KEY);
      return cookie != null ? cookie.getValue() : null;
    });
  }

  // ── Error response ────────────────────────────────────────────────────────────
  public Mono<Void> sendErrorResponse(ServerWebExchange exchange,
                                      String message,
                                      HttpStatus status) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success",   false);
    body.put("message",   message);
    body.put("timestamp", Instant.now().toString());

    return Mono.fromCallable(() -> MAPPER.writeValueAsBytes(body))
        .flatMap(bytes -> {
          DataBuffer buffer = response.bufferFactory().wrap(bytes);
          return response.writeWith(Mono.just(buffer));
        })
        .onErrorResume(e -> {
          log.error("Failed to write error response: {}", e.getMessage());
          return response.setComplete();
        });
  }
}