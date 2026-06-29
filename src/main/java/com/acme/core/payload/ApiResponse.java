package com.acme.core.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

  private Boolean success;
  private Boolean warning;
  private String  message;
  private String  error;
  private T       data;

  // ── Success ───────────────────────────────────────────────────────────────

  public static <T> ApiResponse<T> success(String message, T data) {
    return ApiResponse.<T>builder()
        .success(true)
        .message(message)
        .data(data)
        .build();
  }

  public static <T> ApiResponse<T> success(String message) {
    return ApiResponse.<T>builder()
        .success(true)
        .message(message)
        .build();
  }

  // ── Warning (success=true, warning=true) ──────────────────────────────────

  public static <T> ApiResponse<T> warn(String message, String warningDetails) {
    return ApiResponse.<T>builder()
        .success(true)
        .warning(true)
        .message(message)
        .error(warningDetails)
        .build();
  }

  public static <T> ApiResponse<T> warn(String message, String warningDetails, T data) {
    return ApiResponse.<T>builder()
        .success(true)
        .warning(true)
        .message(message)
        .error(warningDetails)
        .data(data)
        .build();
  }

  // ── Error ─────────────────────────────────────────────────────────────────

  public static <T> ApiResponse<T> error(String message) {
    return ApiResponse.<T>builder()
        .success(false)
        .message(message)
        .build();
  }

  public static <T> ApiResponse<T> error(String message, String errorDetails) {
    return ApiResponse.<T>builder()
        .success(false)
        .message(message)
        .error(errorDetails)
        .build();
  }
}
