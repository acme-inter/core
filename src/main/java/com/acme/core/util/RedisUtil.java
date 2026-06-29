package com.acme.core.util;

import com.acme.core.exception.ThrowException;
import com.acme.core.payload.session.SessionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Slf4j
@Service
public class RedisUtil {

  private final ReactiveRedisTemplate<String, String> redisTemplate;
  private final ObjectMapper objectMapper;

  public RedisUtil(
      @Qualifier("reactiveStringRedisTemplate")
      ReactiveRedisTemplate<String, String> redisTemplate,
      ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper  = objectMapper;
  }

  // ── Key helpers ───────────────────────────────────────────────────────────

  private static String sid(Long sessionId)  { return "SID:"    + sessionId; }
  private static String nid(Long memberId)   { return "NOTIFY:" + memberId;  }

  // ── Session Read ──────────────────────────────────────────────────────────

  public Mono<SessionDTO> getSession(Long sessionId) {
    return get(sid(sessionId), SessionDTO.class);
  }

  public <T> Mono<T> getValidSession(Long sessionId, Class<T> clazz) {
    return get(sid(sessionId), clazz);
  }

  public Mono<SessionDTO> sessionContext(Long sessionId) {
    if (sessionId == null) return Mono.empty();
    return getSession(sessionId)
        .onErrorResume(e -> {
          log.warn("sessionContext: could not load session {}: {}", sessionId, e.getMessage());
          return Mono.empty();
        });
  }

  // ── Session Write ─────────────────────────────────────────────────────────

  public <T> Mono<Void> store(Long sessionId, Integer durationMin, T data) {
    return set(sid(sessionId), durationMin, data);
  }

  public <T> Mono<Void> update(Long sessionId, Integer durationMin, T data) {
    return set(sid(sessionId), durationMin, data); // SET overwrites — no need to delete first
  }

  public Mono<Void> delete(Long sessionId) {
    return redisTemplate.delete(sid(sessionId)).then();
  }

  // ── Session Invalidation (permission change) ──────────────────────────────

  public Mono<Void> evictSession(Long sessionId) {
    return delete(sessionId)
        .doOnSuccess(v -> log.info("Evicted session: {}", sessionId));
  }

  // ── Notification Settings ─────────────────────────────────────────────────

  public <T> Mono<T> getNotifySettings(Long memberId, Class<T> clazz) {
    return get(nid(memberId), clazz);
  }

  public <T> Mono<Void> storeNotifySettings(Long memberId, Integer durationMin, T data) {
    return set(nid(memberId), durationMin, data);
  }

  public Mono<Void> evictNotifySettings(Long memberId) {
    return redisTemplate.delete(nid(memberId)).then();
  }

  // ── Generic ───────────────────────────────────────────────────────────────

  public <T> Mono<T> get(String key, Class<T> clazz) {
    return redisTemplate.opsForValue()
        .get(key)
        .flatMap(json -> {
          try {
            return Mono.just(objectMapper.readValue(json, clazz));
          } catch (Exception e) {
            log.error("Failed to deserialize key {}: {}", key, e.getMessage());
            return Mono.error(new ThrowException("redis.deserialize.failed"));
          }
        });
  }

  public <T> Mono<Void> set(String key, Integer durationMin, T data) {
    return Mono.fromCallable(() -> {
          try {
            return objectMapper.writeValueAsString(data);
          } catch (Exception e) {
            log.error("Failed to serialize key {}: {}", key, e.getMessage());
            throw new ThrowException("redis.serialize.failed");
          }
        })
        .flatMap(json -> redisTemplate.opsForValue()
            .set(key, json, Duration.ofMinutes(durationMin)))
        .then();
  }

  public Mono<Void> evict(String key) {
    return redisTemplate.delete(key).then();
  }
}