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
public class ListResponse<T> {

  @Builder.Default
  private boolean success = false;
  private String message;
  private String code;
  private T data;
  private String error;
  private Boolean hasMore;

  public static <T> ListResponse<T> success(String message, Boolean hasMore, T data) {
    return ListResponse.<T>builder()
        .success(true)
        .message(message)
        .data(data)
        .hasMore(hasMore)
        .build();
  }

  public static <T> ListResponse<T> error(String message, String error) {
    return ListResponse.<T>builder()
        .success(false)
        .message(message)
        .error(error)
        .hasMore(false)
        .build();
  }

  public static <T> ListResponse<T> success(String message) {
    return ListResponse.<T>builder()
        .success(true)
        .message(message)
        .hasMore(false)
        .build();
  }
}