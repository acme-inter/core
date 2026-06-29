package com.acme.core.util;

import com.acme.core.exception.ThrowException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.relational.core.query.Query;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.*;

@Slf4j
public class ValidUtil<T> {

  private final List<Runnable> warnRules = new ArrayList<>();

  private final T dto;
  private final List<Supplier<Optional<Mono<T>>>> syncRules = new ArrayList<>();
  private final List<Mono<Void>> asyncChecks = new ArrayList<>();         // ← new

  private ValidUtil(T dto) {
    this.dto = dto;
  }

  public static <T> ValidUtil<T> of(T dto, String nullErrorKey) {
    if (dto == null) throw new ThrowException(nullErrorKey);
    return new ValidUtil<>(dto);
  }

  public ValidUtil<T> trim(Supplier<String> getter, Consumer<String> setter) {
    String val = getter.get();
    if (val != null) setter.accept(val.trim());
    return this;
  }

  public ValidUtil<T> trimLower(Supplier<String> getter, Consumer<String> setter) {
    String val = getter.get();
    if (val != null) setter.accept(val.trim().toLowerCase());
    return this;
  }

  public ValidUtil<T> rejectIf(BooleanSupplier condition, String errorKey) {
    syncRules.add(() -> condition.getAsBoolean()
        ? Optional.of(Mono.error(new ThrowException(errorKey)))
        : Optional.empty());
    return this;
  }

  public ValidUtil<T> requireText(Supplier<String> getter, String errorKey) {
    return rejectIf(() -> !StringUtils.hasText(getter.get()), errorKey);
  }

  public ValidUtil<T> requireNonNull(Supplier<?> getter, String errorKey) {
    return rejectIf(() -> getter.get() == null, errorKey);
  }

  public <E> ValidUtil<T> unique(
      DbUtil dbUtil,
      Query query,
      Class<E> entityClass,
      boolean isEdit,
      LongSupplier dtoIdGetter,
      ToLongFunction<E> existingIdGetter,
      String errorKey) {

    asyncChecks.add(
        dbUtil.getOneByQuery(query, entityClass)
            .flatMap(existing -> {
              long existingId = existingIdGetter.applyAsLong(existing);
              long dtoId      = dtoIdGetter.getAsLong();
              if (isEdit && existingId == dtoId) return Mono.empty();
              return Mono.<Void>error(new ThrowException(errorKey));
            })
            .then()
    );
    return this;
  }

  public <E> ValidUtil<T> uniqueIf(
      BooleanSupplier runCondition,
      DbUtil dbUtil,
      Query query,
      Class<E> entityClass,
      boolean isEdit,
      LongSupplier dtoIdGetter,
      ToLongFunction<E> existingIdGetter,
      String errorKey) {

    if (runCondition.getAsBoolean()) {
      unique(dbUtil, query, entityClass, isEdit, dtoIdGetter, existingIdGetter, errorKey);
    }
    return this;
  }

  public ValidUtil<T> warnIfNull(Supplier<?> getter, String warnMsg) {
    warnRules.add(() -> {
      if (getter.get() == null) {
        log.warn("[ValidUtil] missing field — {}", warnMsg);
      }
    });
    return this;
  }

  public ValidUtil<T> warnIfBlank(Supplier<String> getter, String warnMsg) {
    warnRules.add(() -> {
      if (!StringUtils.hasText(getter.get())) {
        log.warn("[ValidUtil] missing field — {}", warnMsg);
      }
    });
    return this;
  }

  public Mono<T> build() {
    // Run all warn-only checks first — never stops processing
    warnRules.forEach(Runnable::run);

    // Hard sync rules — first failure short-circuits with an error Mono
    for (Supplier<Optional<Mono<T>>> rule : syncRules) {
      Optional<Mono<T>> result = rule.get();
      if (result.isPresent()) return result.get();
    }

    if (asyncChecks.isEmpty()) return Mono.just(dto);
    return Mono.when(asyncChecks).thenReturn(dto);
  }
}