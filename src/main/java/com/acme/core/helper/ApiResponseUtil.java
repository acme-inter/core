package com.acme.core.helper;

import com.acme.core.payload.ApiResponse;
import com.acme.core.payload.ListResponse;
import com.acme.core.payload.PagedResponse;
import com.acme.core.payload.page.FilterDTO;
import com.acme.core.payload.page.SortDTO;
import com.acme.core.util.MsgUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ApiResponseUtil {

  private final MsgUtil msgUtil;

  // ══════════════════════════════════════════════════════════════════════════
  // ApiResponse — SUCCESS
  // ══════════════════════════════════════════════════════════════════════════

  /** Success with data payload. */
  public <T> Mono<ApiResponse<T>> success(String msgCode, T data) {
    return msgUtil.get(msgCode)
        .map(msg -> ApiResponse.success(msg, data));
  }

  /** Success with data payload + message args. */
  public <T> Mono<ApiResponse<T>> success(String msgCode, T data, Object... args) {
    return msgUtil.get(msgCode, args)
        .map(msg -> ApiResponse.success(msg, data));
  }

  /** Success with data payload + named placeholders. */
  public <T> Mono<ApiResponse<T>> success(String msgCode, T data,
                                          java.util.Map<String, Object> params) {
    return msgUtil.get(msgCode, params)
        .map(msg -> ApiResponse.success(msg, data));
  }

  /** Success with no data. */
  public <T> Mono<ApiResponse<T>> success(String msgCode) {
    return msgUtil.get(msgCode)
        .map(ApiResponse::success);
  }

  /** Success with no data + message args. */
  public Mono<ApiResponse<Void>> successParam(String msgCode, Object... args) {
    return msgUtil.get(msgCode, args)
        .map(ApiResponse::success);
  }

  /** Success with auto-extracted @MsgParam DTO. */
  public <T> Mono<ApiResponse<T>> success(String msgCode, T data, Object msgDto) {
    return msgUtil.get(msgCode, MsgUtil.extract(msgDto))
        .map(msg -> ApiResponse.success(msg, data));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // ApiResponse — WARNING
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<ApiResponse<T>> warn(String msgCode, String warnMsgCode) {
    return Mono.zip(msgUtil.get(msgCode), msgUtil.get(warnMsgCode))
        .map(t -> ApiResponse.warn(t.getT1(), t.getT2()));
  }

  /** Warn with data payload. */
  public <T> Mono<ApiResponse<T>> warn(String msgCode, String warnMsgCode, T data) {
    return Mono.zip(msgUtil.get(msgCode), msgUtil.get(warnMsgCode))
        .map(t -> ApiResponse.warn(t.getT1(), t.getT2(), data));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // ApiResponse — ERROR
  // ══════════════════════════════════════════════════════════════════════════

  /** Error with message code only. */
  public Mono<ApiResponse<Void>> error(String msgCode) {
    return msgUtil.get(msgCode)
        .map(ApiResponse::error);
  }

  /** Error with message args. */
  public Mono<ApiResponse<Void>> errorParam(String msgCode, Object... args) {
    return msgUtil.get(msgCode, args)
        .map(ApiResponse::error);
  }

  /** Error with named placeholders. */
  public Mono<ApiResponse<Void>> error(String msgCode,
                                       java.util.Map<String, Object> params) {
    return msgUtil.get(msgCode, params)
        .map(ApiResponse::error);
  }

  /** Error with exception detail string. */
  public <T> Mono<ApiResponse<T>> error(String msgCode, String errorDetails) {
    return msgUtil.get(msgCode)
        .map(msg -> ApiResponse.error(msg, errorDetails));
  }

  /** Error with message args + exception detail string. */
  public Mono<ApiResponse<Void>> error(String msgCode, String errorDetails,
                                       Object... args) {
    return msgUtil.get(msgCode, args)
        .map(msg -> ApiResponse.error(msg, errorDetails));
  }

  /** Error from a Throwable — message code + e.getMessage() as detail. */
  public <T> Mono<ApiResponse<T>> errorThrow(String msgCode, Throwable e) {
    return msgUtil.get(msgCode)
        .map(msg -> ApiResponse.error(msg, e.getMessage()));
  }

  /** Error with auto-extracted @MsgParam DTO. */
  public Mono<ApiResponse<Void>> error(String msgCode, Object msgDto) {
    return msgUtil.get(msgCode, MsgUtil.extract(msgDto))
        .map(ApiResponse::error);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // PagedResponse — SUCCESS
  // ══════════════════════════════════════════════════════════════════════════

  /** Paged success — full result set. */
  public <T> Mono<PagedResponse<T>> paged(String msgCode,
                                          List<T> data,
                                          int totalPages,
                                          long totalElements,
                                          int index,
                                          int size) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.success(msg, data, totalPages,
            totalElements, index, size));
  }

  /** Paged success — with filters, sorts, viewId. */
  public <T> Mono<PagedResponse<T>> paged(String msgCode,
                                          List<T> data,
                                          int totalPages,
                                          long totalElements,
                                          int index,
                                          int size,
                                          List<FilterDTO> filters,
                                          List<SortDTO> sorts,
                                          String viewId) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.success(msg, data, totalPages, totalElements,
            index, size, filters, sorts, viewId));
  }

  /** Paged success — empty result set. */
  public <T> Mono<PagedResponse<T>> pagedEmpty(String msgCode, int index, int size) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.success(msg, index, size));
  }

  /** Cursor-based paged success. */
  public <T> Mono<PagedResponse<T>> cursor(String msgCode,
                                           List<T> data,
                                           Long nextCursor,
                                           boolean hasNext) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.cursorSuccess(msg, data, nextCursor, hasNext));
  }

  /** Cursor-based paged success — no nextCursor needed. */
  public <T> Mono<PagedResponse<T>> cursor(String msgCode,
                                           List<T> data,
                                           boolean hasNext) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.cursorSuccess(msg, data, hasNext));
  }

  /** Cursor-based empty success. */
  public <T> Mono<PagedResponse<T>> cursorEmpty(String msgCode, boolean hasNext) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.cursorEmptySuccess(msg, hasNext));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // PagedResponse — ERROR
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<PagedResponse<T>> pagedError(String msgCode, String errorDetails) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.error(msg, errorDetails));
  }

  public <T> Mono<PagedResponse<T>> pagedError(String msgCode, Throwable e) {
    return msgUtil.get(msgCode)
        .map(msg -> PagedResponse.error(msg, e.getMessage()));
  }

  // ══════════════════════════════════════════════════════════════════════════
  // ListResponse — SUCCESS
  // ══════════════════════════════════════════════════════════════════════════

  /** List success with data + hasMore flag. */
  public <T> Mono<ListResponse<T>> list(String msgCode, T data, Boolean hasMore) {
    return msgUtil.get(msgCode)
        .map(msg -> ListResponse.success(msg, hasMore, data));
  }

  /** List success — no data. */
  public <T> Mono<ListResponse<T>> list(String msgCode) {
    return msgUtil.get(msgCode)
        .map(ListResponse::success);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // ListResponse — ERROR
  // ══════════════════════════════════════════════════════════════════════════

  public <T> Mono<ListResponse<T>> listError(String msgCode, String errorDetails) {
    return msgUtil.get(msgCode)
        .map(msg -> ListResponse.error(msg, errorDetails));
  }

  public <T> Mono<ListResponse<T>> listError(String msgCode, Throwable e) {
    return msgUtil.get(msgCode)
        .map(msg -> ListResponse.error(msg, e.getMessage()));
  }

  public <T> Mono<ApiResponse<T>> success(String msgCode, T data, Mono<Locale> locale) {
    return msgUtil.get(msgCode, locale)
        .map(msg -> ApiResponse.success(msg, data));
  }

  public <T> Mono<ApiResponse<T>> error(String msgCode, Mono<Locale> locale) {
    return msgUtil.get(msgCode, locale)
        .map(ApiResponse::error);
  }

  public <T> Mono<PagedResponse<T>> paged(String msgCode,
                                          List<T> data,
                                          int totalPages,
                                          long totalElements,
                                          int index,
                                          int size,
                                          Mono<Locale> locale) {
    return msgUtil.get(msgCode, locale)
        .map(msg -> PagedResponse.success(msg, data, totalPages,
            totalElements, index, size));
  }
}