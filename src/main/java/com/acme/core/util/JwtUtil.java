package com.acme.core.util;

import com.acme.core.constant.Properties;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtUtil {

  private final Properties properties;
  private final Encryption encryption;

  public Mono<String> generateAccessToken(Long id, int durationMins) {
    return Mono.fromCallable(() -> {
      Algorithm algorithm = Algorithm.HMAC256(properties.getSecurity().getSecret());
      Date now      = new Date();
      Date validity = new Date(now.getTime() + 1000L * 60 * durationMins);
      String raw = JWT.create()
          .withSubject(id.toString())
          .withClaim("external", false)
          .withIssuedAt(now)
          .withExpiresAt(validity)
          .sign(algorithm);
      return encryption.encodeKey(raw);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<String> generateRefreshToken(Long id, int durationHours) {
    return Mono.fromCallable(() -> {
      Algorithm algorithm = Algorithm.HMAC256(properties.getSecurity().getSecret());
      Date now     = new Date();
      Date expired = new Date(now.getTime() + 1000L * 3600 * durationHours);
      String raw = JWT.create()
          .withSubject(id.toString())
          .withIssuedAt(now)
          .withExpiresAt(expired)
          .sign(algorithm);
      return encryption.encodeKey(raw);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<String> generateApiAccessToken(
      Long memberId,
      Long departmentId,
      int durationHours
  ) {
    return Mono.defer(() -> {
      Algorithm algorithm = Algorithm.HMAC256(properties.getSecurity().getSecret());

      Date now = new Date();
      Date validity = new Date(now.getTime() + 1000L * 3600 * durationHours);
      String raw = JWT.create()
          .withClaim("external", true)
          .withClaim("member_id", memberId)
          .withClaim("department_id", departmentId)
          .withIssuedAt(now)
          .withExpiresAt(validity)
          .sign(algorithm);

      return Mono.just(encryption.encodeKey(raw));
    }).subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<DecodedJWT> decode(String token) {
    return Mono.fromCallable(() -> {
      Algorithm algorithm = Algorithm.HMAC256(properties.getSecurity().getSecret());
      JWTVerifier verifier = JWT.require(algorithm).build();
      return verifier.verify(encryption.decodeKey(token));
    }).subscribeOn(Schedulers.boundedElastic());
  }

  public Mono<Boolean> isInvalid(String token) {
    return decode(token)
        .map(this::isExpired)
        .onErrorReturn(true);
  }

  /** Returns {@code true} if the token's expiry is before now. */
  public Mono<Boolean> isTokenExpired(String token) {
    return decode(token)
        .map(jwt -> {
          Date expiresAt = jwt.getExpiresAt();
          return expiresAt == null || expiresAt.before(new Date());
        })
        .onErrorReturn(true);
  }

  /** Synchronous expiry check on an already-decoded JWT. */
  public boolean isExpired(DecodedJWT jwt) {
    return jwt.getExpiresAt().before(new Date());
  }

  // ─── Claim extraction ─────────────────────────────────────────────────────

  /** Returns {@code true} when the {@code external} claim is {@code true}. */
  public Mono<Boolean> isExternal(String token) {
    return decode(token)
        .map(jwt -> Boolean.TRUE.equals(jwt.getClaim("external").asBoolean()));
  }

  /** Extracts the subject claim as a {@code Long} session / member ID. */
  public Mono<Long> getSessionId(String token) {
    return decode(token)
        .map(jwt -> Long.valueOf(jwt.getSubject()));
  }
}
