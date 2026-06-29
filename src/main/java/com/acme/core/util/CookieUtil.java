package com.acme.core.util;

import com.acme.core.constant.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class CookieUtil {

  private final Encryption encryption;
  private final Properties properties;

  private static final String STRICT_SITE = "Strict";
  private static final String MEMBER_ID_COOKIE_KEY = "UID";
  private static final String SESSIONS_ID_COOKIE_KEY = "SID";
  private static final String DEPARTMENT_ID_COOKIE_KEY = "DKEY";
  private static final String REFRESH_TOKEN_COOKIE_KEY = "refresh";
  private static final String ACCESS_TOKEN_COOKIE_KEY = "authorize";

  public void setAccessToken(ServerHttpResponse response, String value, int minutes) {
    addCookie(response,ACCESS_TOKEN_COOKIE_KEY, value,
        Duration.ofMinutes(minutes), true);
  }
  public void setRefreshToken(ServerHttpResponse response, String value, int hours) {
    addCookie(response, REFRESH_TOKEN_COOKIE_KEY, value,
        Duration.ofHours(hours), false);
  }
  public void setSession(ServerHttpResponse response, Long sessionId, int hours) {
    String encoded = encryption.encodeKey(sessionId.toString());
    addCookie(response, SESSIONS_ID_COOKIE_KEY, encoded,
        Duration.ofHours(hours), false);
  }
  public void setMember(ServerHttpResponse response, Long memberId, int minutes) {
    String encoded = encryption.encodeKey(memberId.toString());
    addCookie(response, MEMBER_ID_COOKIE_KEY, encoded,
        Duration.ofHours(minutes), false);
  }
  public void setDepartment(ServerHttpResponse response, Long departmentId, int minutes) {
    String encoded = encryption.encodeKey(departmentId.toString());
    addCookie(response, DEPARTMENT_ID_COOKIE_KEY, encoded,
        Duration.ofHours(minutes), false);
  }

  // ─── Generic setters ─────────────────────────────────────────────────────
  public void set(ServerHttpResponse response, String key, String value, Duration maxAge) {
    addCookie(response, key, value, maxAge, false);
  }
  public void set(ServerHttpResponse response, String key, String value,
                  Duration maxAge, boolean httpOnly) {
    addCookie(response, key, value, maxAge, httpOnly);
  }

  // ─── Clear ───────────────────────────────────────────────────────────────

  public void clear(ServerHttpResponse response, String key) {
    addCookie(response, key, "", Duration.ZERO, false);
  }
  public void clear(ServerHttpResponse response, String... keys) {
    for (String key : keys) clear(response, key);
  }


  public void setAuthCookies(ServerHttpResponse response,
                             Long memberId, Long departmentId, Long sessionId,
                             String accessToken, String refreshToken,
                             int accessMinutes, int refreshHours) {
    if (memberId != null)     setMember(response, memberId, accessMinutes);
    if (departmentId != null) setDepartment(response, departmentId, accessMinutes);
    if (sessionId != null)    setSession(response, sessionId, refreshHours);
    if (accessToken != null)  setAccessToken(response, accessToken, accessMinutes);
    if (refreshToken != null) setRefreshToken(response, refreshToken, refreshHours);
  }

  public Mono<Void> clearAll(ServerHttpResponse response, String... extraKeys) {
    return Mono.fromRunnable(() -> {
      clear(response,
          MEMBER_ID_COOKIE_KEY,
          DEPARTMENT_ID_COOKIE_KEY,
          SESSIONS_ID_COOKIE_KEY,
          ACCESS_TOKEN_COOKIE_KEY,
          REFRESH_TOKEN_COOKIE_KEY);
      clear(response, extraKeys);
    });
  }

  private void addCookie(ServerHttpResponse response, String key, String value,
                         Duration maxAge, boolean httpOnly) {
    ResponseCookie cookie = ResponseCookie.from(key, value)
        .httpOnly(httpOnly)
        .secure(properties.getSecurity().isSecure())
        .sameSite(STRICT_SITE)
        .maxAge(maxAge)
        .path("/")
        .build();
    response.addCookie(cookie);
  }
}