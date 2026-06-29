package com.acme.core.util;

import com.acme.core.constant.RoutingKeys;
import com.acme.core.enums.Modules;
import com.acme.core.exception.ThrowException;
import com.acme.core.payload.audit.AuditDTO;
import com.acme.core.payload.message.LogMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;


@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitUtil {

  public static final String EXCHANGE = "acme.events";

  private final RabbitTemplate rabbitTemplate;
  private final ObjectMapper   objectMapper;

  // ══════════════════════════════════════════════════════════════════════════
  // CORE PUBLISH
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<Void> publish(String routingKey, T payload) {
    return Mono.fromRunnable(() -> {
          try {
            String json = objectMapper.writeValueAsString(payload);
            rabbitTemplate.convertAndSend(EXCHANGE, routingKey, json);
            log.debug("[RabbitMQ] published → key={}", routingKey);
          } catch (Exception e) {
            log.error("[RabbitMQ] publish failed → key={} error={}", routingKey, e.getMessage());
          }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  public <T> Mono<Void> publishOrLog(String routingKey, T payload) {
    return publish(routingKey, payload)
        .doOnError(e -> log.error("[RabbitMQ] dead-letter → key={} type={} error={}",
            routingKey, payload.getClass().getSimpleName(), e.getMessage()))
        .onErrorComplete();
  }

  // ══════════════════════════════════════════════════════════════════════════
  // DESERIALIZE
  // ══════════════════════════════════════════════════════════════════════════

  public <T> T deserialize(String json, Class<T> clazz) {
    try {
      return objectMapper.readValue(json, clazz);
    } catch (Exception e) {
      log.error("[RabbitMQ] deserialize failed → type={} error={}", clazz.getSimpleName(), e.getMessage());
      throw new ThrowException("rabbitmq.deserialize.failed");
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // LOG PUBLISH  — manual (for service-layer use)
  // ══════════════════════════════════════════════════════════════════════════

  /** CREATE or DELETE — no diff. */
  public Mono<Void> log(String service, AuditDTO audit, Modules module,
                        String action, String description, String clazz,
                        Long ownerId, Long recordId) {
    return publishLog(service, LogMsg.from(
        audit, module, action, description, clazz,
        ownerId, recordId, null, null));
  }

  /** UPDATE — with before/after diff. */
  public Mono<Void> log(String service, AuditDTO audit, Modules module,
                        String action, String description, String clazz,
                        Long ownerId, Long recordId,
                        String oldValue, String newValue) {
    return publishLog(service, LogMsg.from(
        audit, module, action, description, clazz,
        ownerId, recordId, oldValue, newValue));
  }

  /** Collaborate — with collaborator list. */
  public Mono<Void> log(String service, AuditDTO audit, Modules module,
                        String action, String description, String clazz,
                        Long ownerId, Long recordId,
                        String oldValue, String newValue,
                        Boolean isCollaborate, List<Long> collaborators) {
    return publishLog(service, LogMsg.from(
        audit, module, action, description, clazz,
        ownerId, recordId, oldValue, newValue,
        isCollaborate, collaborators));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // LOG ON SUCCESS — auto fire-and-forget after CRUD (used by DbUtil)
  //
  // Rule:
  //   source completes → result emitted to caller → THEN rabbit fires independently
  //   rabbit success/fail never affects the source result
  // ══════════════════════════════════════════════════════════════════════════

  /** CREATE / DELETE — no diff. */
  public <T> Mono<T> logOnSuccess(
      Mono<T> source,
      AuditDTO audit,
      String action,
      String entityName,
      ToLongFunction<T> ownerIdFn,
      ToLongFunction<T> recordIdFn) {
    return source.doOnSuccess(result -> {
      if (result == null) return;
      fireAndForget(audit, action, entityName,
          ownerIdFn.applyAsLong(result),
          recordIdFn.applyAsLong(result),
          null, null);
    });
  }

  /** UPDATE — with old/new diff. */
  public <T> Mono<T> logOnSuccess(
      Mono<T> source,
      AuditDTO audit,
      String action,
      String entityName,
      ToLongFunction<T> ownerIdFn,
      ToLongFunction<T> recordIdFn,
      String oldValue,
      Function<T, String> newValueFn) {
    return source.doOnSuccess(result -> {
      if (result == null) return;
      fireAndForget(audit, action, entityName,
          ownerIdFn.applyAsLong(result),
          recordIdFn.applyAsLong(result),
          oldValue,
          newValueFn.apply(result));
    });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // PRIVATE
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Derives service + module from audit.getModule() automatically.
   * Builds description from action + entityName + recordId.
   * Subscribes detached — never affects the calling chain.
   */
  private void fireAndForget(AuditDTO audit, String action, String entityName,
                             long ownerId, long recordId,
                             String oldValue, String newValue) {
    try {
      Modules module  = audit.getModule();
      String  service = module.getService();
      String  desc    = buildDescription(action, entityName, recordId);
      publishLog(service, LogMsg.from(
          audit, module, action, desc, entityName,
          ownerId == 0L ? null : ownerId,
          recordId == 0L ? null : recordId,
          oldValue, newValue))
          .onErrorComplete()   // swallow rabbit errors
          .subscribe();        // detached — CRUD already completed
    } catch (Exception e) {
      log.warn("[RabbitUtil] fireAndForget skipped → action={} error={}", action, e.getMessage());
    }
  }

  /**
   * Auto-generates description from action suffix + entityName + recordId.
   * "crm.lead.created" + "Lead" + 42 → "Created Lead #42"
   */
  private String buildDescription(String action, String entityName, long recordId) {
    String verb = action.contains(".")
        ? action.substring(action.lastIndexOf('.') + 1)
        : action;
    String capitalized = Character.toUpperCase(verb.charAt(0)) + verb.substring(1);
    return recordId > 0
        ? capitalized + " " + entityName + " #" + recordId
        : capitalized + " " + entityName;
  }

  private Mono<Void> publishLog(String service, LogMsg msg) {
    return publish(RoutingKeys.logKey(service), msg);
  }
}