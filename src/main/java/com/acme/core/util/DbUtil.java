package com.acme.core.util;

import com.acme.core.exception.ThrowException;
import com.acme.core.payload.ApiResponse;
import com.acme.core.payload.audit.AuditDTO;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

@Slf4j
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class DbUtil {

  private static final String REFRESH_TOKEN_COOKIE_KEY = "refresh";
  private static final String ACCESS_TOKEN_COOKIE_KEY  = "authorize";
  private static final String BEARER_PREFIX            = "Bearer ";

  public final  R2dbcEntityTemplate template;
  private final MsgUtil             msgUtil;
  private final GuardUtil           guardUtil;
  private final RabbitUtil          rabbitUtil;
  private final ObjectMapper        objectMapper;

  // ══════════════════════════════════════════════════════════════════════════
  // CONTEXT — delegate to GuardUtil, no duplication
  // ══════════════════════════════════════════════════════════════════════════

  public Mono<AuditDTO> auditContext() {
    return guardUtil.auditContext();
  }

  public Mono<Long> getMemberId() {
    return guardUtil.memberId()
        .switchIfEmpty(Mono.error(new ThrowException("auth.member.notfound")));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // QUERY
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<T> getById(Long id, Class<T> clazz) {
    return template.selectOne(Query.query(Criteria.where("id").is(id)), clazz);
  }

  public <T> Mono<List<T>> getByIds(List<Long> ids, Class<T> clazz) {
    if (ids == null || ids.isEmpty()) return Mono.just(Collections.emptyList());
    return template.select(Query.query(Criteria.where("id").in(ids)), clazz).collectList();
  }

  public <T> Mono<T> getOneByQuery(Query query, Class<T> clazz) {
    return template.selectOne(query, clazz);
  }

  public <T> Mono<T> getByQuery(Query query, Class<T> clazz) {
    return template.selectOne(query, clazz);
  }

  public <T> Mono<Long> countByQuery(Query query, Class<T> clazz) {
    return template.count(query, clazz);
  }

  public <T> Mono<Boolean> existsByQuery(Query query, Class<T> clazz) {
    return template.exists(query, clazz);
  }

  public Mono<Void> deleteByQuery(Query query, Class<?> clazz) {
    return template.delete(query, clazz).then();
  }

  public <T> Flux<T> getAllById(String field, Object value, Class<T> type) {
    return template.select(type)
        .matching(Query.query(Criteria.where(field).is(value)))
        .all();
  }
  public <T> Mono<Void> deleteBy(String field, Object value, Class<T> type) {
    return template.delete(type)
        .matching(Query.query(Criteria.where(field).is(value)))
        .all()
        .then();
  }

  public <T> Mono<T> getOneBy(String field, Object value, Class<T> type) {
    return template.select(type)
        .matching(Query.query(Criteria.where(field).is(value)).limit(1))
        .one();
  }

  public <T> Mono<T> getViewById(Long id, Class<T> clazz,
                                 String notFoundMsgCode,
                                 BiFunction<Row, RowMetadata, T> rowMapper) {
    return template.getDatabaseClient()
        .sql("SELECT * FROM " + resolveTableName(clazz) + " WHERE id = :id")
        .bind("id", id)
        .map(rowMapper)
        .one()
        .switchIfEmpty(Mono.error(new ThrowException(notFoundMsgCode)));
  }

  private String resolveTableName(Class<?> clazz) {
    Table annotation = clazz.getAnnotation(Table.class);
    return annotation != null ? annotation.value() : clazz.getSimpleName().toLowerCase();
  }

  public <T> Mono<Long> update(Object id, Map<String, Object> fields, Class<T> type) {
    if (fields == null || fields.isEmpty()) {
      return Mono.just(0L);
    }
    Update update = null;
    for (Map.Entry<String, Object> e : fields.entrySet()) {
      update = (update == null)
          ? Update.update(e.getKey(), e.getValue())
          : update.set(e.getKey(), e.getValue());
    }
    return template.update(type)
        .matching(Query.query(Criteria.where("id").is(id)))
        .apply(update);
  }
  // ══════════════════════════════════════════════════════════════════════════
  // SAVE
  // ══════════════════════════════════════════════════════════════════════════

  /** Simple insert — no guard, no log. */
  public <T> Mono<T> save(T entity, Class<T> clazz) {
    return template.insert(clazz).using(entity);
  }

  /** Insert with role guard. */
  public <T> Mono<T> save(T entity, Class<T> clazz, String deniedMessageKey) {
    return guard(deniedMessageKey)
        .flatMap(ignored -> template.insert(clazz).using(entity));
  }

  /**
   * Insert with role guard + auto log.
   * DB completes → response emitted → rabbit fires independently.
   */
  public <T> Mono<T> save(T entity, Class<T> clazz,
                          String deniedMessageKey,
                          String action,
                          ToLongFunction<T> ownerIdFn,
                          ToLongFunction<T> recordIdFn) {
    return auditContext().flatMap(audit ->
        rabbitUtil.logOnSuccess(
            guard(deniedMessageKey).flatMap(ignored -> template.insert(clazz).using(entity)),
            audit, action, clazz.getSimpleName(),
            ownerIdFn, recordIdFn
        )
    );
  }

  // ══════════════════════════════════════════════════════════════════════════
  // UPDATE
  // ══════════════════════════════════════════════════════════════════════════

  /** Simple update — no guard. */
  public <T> Mono<T> update(Long id, Update update, Class<T> clazz) {
    return applyUpdate(id, update, clazz);
  }

  /** Update with role guard. */
  public <T> Mono<T> update(Long id, Update update, Class<T> clazz,
                            String deniedMessageKey) {
    return guard(deniedMessageKey)
        .flatMap(ignored -> applyUpdate(id, update, clazz));
  }

  /** Update with not-found + owner/role guard. */
  public <T> Mono<T> update(Long id, Update update, Class<T> clazz,
                            String notFoundMsgCode,
                            ToLongFunction<T> ownerExtractor,
                            String deniedMessageCode) {
    return getById(id, clazz)
        .switchIfEmpty(Mono.error(new ThrowException(notFoundMsgCode)))
        .flatMap(existing -> {
          Mono<Long> g = (ownerExtractor != null && deniedMessageCode != null)
              ? guardUtil.requireRoleOrOwner(() -> ownerExtractor.applyAsLong(existing), deniedMessageCode)
              : Mono.just(0L);
          return g.flatMap(ignored -> applyUpdate(id, update, clazz));
        });
  }

  /**
   * Update with not-found + owner/role guard + auto log with old/new diff.
   * Captures old value BEFORE update, publishes AFTER update completes.
   * DB completes → response emitted → rabbit fires independently.
   */
  public <T> Mono<T> update(Long id, Update update, Class<T> clazz,
                            String notFoundMsgCode,
                            ToLongFunction<T> ownerExtractor,
                            String deniedMessageCode,
                            String action,
                            ToLongFunction<T> recordIdFn) {
    return auditContext().flatMap(audit ->
        getById(id, clazz)
            .switchIfEmpty(Mono.error(new ThrowException(notFoundMsgCode)))
            .flatMap(existing -> {
              String    oldJson = toJson(existing);                          // snapshot before
              Mono<Long> g      = (ownerExtractor != null && deniedMessageCode != null)
                  ? guardUtil.requireRoleOrOwner(() -> ownerExtractor.applyAsLong(existing), deniedMessageCode)
                  : Mono.just(0L);
              return rabbitUtil.logOnSuccess(
                  g.flatMap(ignored -> applyUpdate(id, update, clazz)),     // DB first
                  audit, action, clazz.getSimpleName(),
                  ownerExtractor != null ? ownerExtractor : e -> 0L,
                  recordIdFn,
                  oldJson,                                                   // before
                  this::toJson                                               // after — from result
              );
            })
    );
  }

  // ══════════════════════════════════════════════════════════════════════════
  // DELETE SOFT — single
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<ApiResponse<Void>> deleteById(
      Long id,
      String notFoundMessageCode,
      String successMessageCode,
      String failedMessageCode,
      String deniedMessageCode,
      String entityName,
      Class<T> clazz,
      Function<T, ?> nameExtractor,
      BiFunction<String, AuditDTO, Mono<Void>> postDeleteTask) {

    if (id == null) return Mono.error(new ThrowException(notFoundMessageCode));

    return auditContext()
        .flatMap(audit -> getById(id, clazz)
            .switchIfEmpty(Mono.error(new ThrowException(notFoundMessageCode)))
            .flatMap(entity -> guard(deniedMessageCode).flatMap(ignored -> {
              String name    = String.valueOf(nameExtractor.apply(entity));
              String oldJson = toJson(entity);
              return template.update(clazz)
                  .matching(Query.query(Criteria.where("id").is(id)))
                  .apply(softDeleteUpdate(audit.getMemberId()))
                  .publishOn(Schedulers.boundedElastic())
                  .doOnSuccess(r ->                                          // DB done, now rabbit
                      rabbitUtil.log(
                              audit.getModule().getService(), audit, audit.getModule(),
                              entityName.toLowerCase() + ".deleted",
                              "Deleted " + entityName + ": " + name,
                              entityName, null, id, oldJson, null)
                          .onErrorComplete().subscribe()
                  )
                  .then(Mono.defer(() -> postDeleteTask.apply(name, audit)))
                  .then(msgUtil.get(successMessageCode).map(ApiResponse::<Void>success));
            }))
        )
        .onErrorResume(ThrowException.class, e -> Mono.just(ApiResponse.error(e.getMessage())))
        .onErrorResume(e -> msgUtil.get(failedMessageCode, entityName)
            .map(msg -> ApiResponse.error(msg, e.getMessage())));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // DELETE SOFT — bulk
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<ApiResponse<Void>> deleteByIds(
      List<Long> ids,
      String emptyIdMessageCode,
      String successMessageCode,
      String failedMessageCode,
      String deniedMessageCode,
      String entityName,
      Class<T> clazz,
      Function<T, ?> nameExtractor,
      BiFunction<List<String>, AuditDTO, Mono<Void>> postDeleteTask) {

    if (ids == null || ids.isEmpty()) return Mono.error(new ThrowException(emptyIdMessageCode));

    return auditContext()
        .flatMap(audit -> getByIds(ids, clazz)
            .flatMap(existing -> {
              if (existing.size() < ids.size())
                return msgUtil.get("delete.failed.some.not.found", entityName)
                    .map(ApiResponse::<Void>error);
              return guard(deniedMessageCode).flatMap(ignored -> {
                List<String> names  = extractNames(existing, nameExtractor);
                String       joined = String.join(", ", names);
                return template.update(clazz)
                    .matching(Query.query(Criteria.where("id").in(ids)))
                    .apply(softDeleteUpdate(audit.getMemberId()))
                    .publishOn(Schedulers.boundedElastic())
                    .doOnSuccess(r ->                                        // DB done, now rabbit
                        rabbitUtil.log(
                                audit.getModule().getService(), audit, audit.getModule(),
                                entityName.toLowerCase() + ".deleted",
                                "Deleted " + ids.size() + " " + entityName + ": " + joined,
                                entityName, null, null, null, null)
                            .onErrorComplete().subscribe()
                    )
                    .then(Mono.defer(() -> postDeleteTask.apply(names, audit)))
                    .then(msgUtil.get(successMessageCode).map(ApiResponse::<Void>success));
              });
            })
        )
        .onErrorResume(ThrowException.class, e -> Mono.just(ApiResponse.error(e.getMessage())))
        .onErrorResume(e -> msgUtil.get(failedMessageCode, entityName)
            .map(msg -> ApiResponse.error(msg, e.getMessage())));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // DELETE PERMANENT — bulk
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<ApiResponse<Void>> deletePermanentByIds(
      List<Long> ids,
      String emptyIdMessageCode,
      String successMessageCode,
      String failedMessageCode,
      String deniedMessageCode,
      String entityName,
      Class<T> clazz,
      Function<T, ?> nameExtractor,
      BiFunction<List<String>, AuditDTO, Mono<Void>> postDeleteTask) {

    if (ids == null || ids.isEmpty()) return Mono.error(new ThrowException(emptyIdMessageCode));

    return auditContext()
        .flatMap(audit -> getByIds(ids, clazz)
            .flatMap(existing -> {
              if (existing.size() < ids.size())
                return msgUtil.get("delete.failed.some.not.found", entityName)
                    .map(ApiResponse::<Void>error);
              return guard(deniedMessageCode).flatMap(ignored -> {
                List<String> names  = extractNames(existing, nameExtractor);
                String       joined = String.join(", ", names);
                return deleteByQuery(Query.query(Criteria.where("id").in(ids)), clazz)
                    .publishOn(Schedulers.boundedElastic())
                    .doOnSuccess(r ->                                        // DB done, now rabbit
                        rabbitUtil.log(
                                audit.getModule().getService(), audit, audit.getModule(),
                                entityName.toLowerCase() + ".permanent_deleted",
                                "Permanently deleted " + ids.size() + " " + entityName + ": " + joined,
                                entityName, null, null, null, null)
                            .onErrorComplete().subscribe()
                    )
                    .then(Mono.defer(() -> postDeleteTask.apply(names, audit)))
                    .then(msgUtil.get(successMessageCode).map(ApiResponse::<Void>success));
              });
            })
        )
        .onErrorResume(ThrowException.class, e -> Mono.just(ApiResponse.error(e.getMessage())))
        .onErrorResume(e -> msgUtil.get(failedMessageCode, entityName)
            .map(msg -> ApiResponse.error(msg, e.getMessage())));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // JSON
  // ══════════════════════════════════════════════════════════════════════════

  public String toJson(Object obj) {
    if (obj == null) return null;
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      log.warn("[DbUtil] serialize failed: {}", e.getMessage());
      return obj.toString();
    }
  }

  public <T> T parseJson(String json, Class<T> clazz) {
    if (json == null) return null;
    try {
      return objectMapper.readValue(json, clazz);
    } catch (Exception e) {
      log.warn("[DbUtil] parse failed: {}", e.getMessage());
      return null;
    }
  }

  public <T> List<T> parseJsonList(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (Exception e) {
      throw new ThrowException("JSON list parse failed for " + type.getSimpleName());
    }
  }

  public <T> List<T> parseJsonListSafe(String json, Class<T> type) {
    if (json == null) return Collections.emptyList();
    return parseJsonList(json, type);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // TOKEN
  // ══════════════════════════════════════════════════════════════════════════

  public Mono<String> extractAccessToken(ServerHttpRequest request) {
    return Mono.defer(() -> {
      String token = Optional.ofNullable(request.getCookies().getFirst(ACCESS_TOKEN_COOKIE_KEY))
          .map(HttpCookie::getValue).filter(v -> !v.isBlank()).orElse(null);
      if (token == null) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX))
          token = header.substring(BEARER_PREFIX.length()).trim();
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

  // ══════════════════════════════════════════════════════════════════════════
  // ERROR RESPONSE
  // ══════════════════════════════════════════════════════════════════════════

  public Mono<Void> sendErrorResponse(ServerWebExchange exchange,
                                      String code, HttpStatus status) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return msgUtil.get(code).defaultIfEmpty(code).flatMap(message -> {
      try {
        byte[]     bytes  = objectMapper.writeValueAsBytes(Map.of(
            "success", false, "message", message,
            "timestamp", Instant.now().toString()));
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
      } catch (Exception e) {
        log.error("Failed to write error response: {}", e.getMessage());
        return response.setComplete();
      }
    });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // PRIVATE
  // ══════════════════════════════════════════════════════════════════════════

  private Mono<Long> guard(String deniedMessageKey) {
    return deniedMessageKey != null
        ? guardUtil.requireSuperOnly(deniedMessageKey)
        : Mono.just(0L);
  }

  private static Update softDeleteUpdate(Long deletedBy) {
    return Update.update("is_deleted", true)
        .set("deleted_at", Instant.now())
        .set("deleted_by", deletedBy);
  }

  private <T> Mono<T> applyUpdate(Long id, Update update, Class<T> clazz) {
    return template.update(clazz)
        .matching(Query.query(Criteria.where("id").is(id)))
        .apply(update)
        .then(getById(id, clazz));
  }

  private <T> List<String> extractNames(List<T> entities, Function<T, ?> nameExtractor) {
    if (nameExtractor == null) return new ArrayList<>();
    return entities.stream()
        .map(e -> Optional.ofNullable(nameExtractor.apply(e))
            .map(Object::toString).orElse(""))
        .toList();
  }
}