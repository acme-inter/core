package com.acme.core.payload;

import com.acme.core.payload.page.FilterDTO;
import com.acme.core.payload.page.SortDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PagedResponse<T> {

  @Builder.Default private boolean success      = false;
  private String        message;
  private List<T> data;
  private int           totalPages;
  private long          totalElements;
  private int           index;
  private int           size;
  private String        error;
  private List<FilterDTO> filters;
  private List<SortDTO>   sorts;
  private String        viewId;
  private Long          nextCursor;
  private boolean       hasNext;

  // ── Offset pagination ─────────────────────────────────────────────────────

  public static <T> PagedResponse<T> success(String message,
                                             List<T> data,
                                             int totalPages,
                                             long totalElements,
                                             int index,
                                             int size) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data)
        .totalPages(totalPages).totalElements(totalElements)
        .index(index).size(size)
        .build();
  }

  /** With filters, sorts, viewId (saved-view support). */
  public static <T> PagedResponse<T> success(String message,
                                             List<T> data,
                                             int totalPages,
                                             long totalElements,
                                             int index,
                                             int size,
                                             List<FilterDTO> filters,
                                             List<SortDTO> sorts,
                                             String viewId) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data)
        .totalPages(totalPages).totalElements(totalElements)
        .index(index).size(size)
        .filters(filters).sorts(sorts).viewId(viewId)
        .build();
  }

  /** Empty result set — no DB hit needed for data. */
  public static <T> PagedResponse<T> success(String message, int index, int size) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(List.of())
        .totalPages(0).totalElements(0)
        .index(index).size(size)
        .build();
  }

  // ── Cursor pagination ─────────────────────────────────────────────────────

  public static <T> PagedResponse<T> cursorSuccess(String message,
                                                   List<T> data,
                                                   Long nextCursor,
                                                   boolean hasNext) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data != null ? data : List.of())
        .nextCursor(nextCursor).hasNext(hasNext)
        .build();
  }

  /** Cursor success — no nextCursor (last page). */
  public static <T> PagedResponse<T> cursorSuccess(String message,
                                                   List<T> data,
                                                   boolean hasNext) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(data != null ? data : List.of())
        .hasNext(hasNext)
        .build();
  }

  /** Cursor empty — no records matched. */
  public static <T> PagedResponse<T> cursorEmptySuccess(String message, boolean hasNext) {
    return PagedResponse.<T>builder()
        .success(true).message(message)
        .data(List.of())
        .hasNext(hasNext)
        .build();
  }

  // ── Error ─────────────────────────────────────────────────────────────────

  public static <T> PagedResponse<T> error(String message, String errorDetails) {
    return PagedResponse.<T>builder()
        .success(false).message(message)
        .error(errorDetails)
        .data(List.of())
        .build();
  }
}