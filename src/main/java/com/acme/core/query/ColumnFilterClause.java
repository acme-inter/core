package com.acme.core.query;

import com.acme.core.enums.FilterOperator;
import com.acme.core.payload.page.FilterDTO;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class ColumnFilterClause {

  private final List<FilterDTO> filters;
  private final Set<String> allowedFields;

  private static final List<String> DATE_FIELD_SUFFIXES = List.of(
      "_at", "_date", "_time", "_on"
  );

  public ColumnFilterClause(List<FilterDTO> filters, Set<String> allowedFields) {
    this.filters       = filters       == null ? List.of() : filters;
    this.allowedFields = allowedFields == null ? Set.of()  : allowedFields;
  }

  public boolean isEmpty() { return filters.isEmpty(); }

  public String toSql() {
    if (filters.isEmpty()) return "";
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < filters.size(); i++) {
      parts.add(buildFragment(filters.get(i), i));
    }
    return String.join(" AND ", parts);
  }

  public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec) {
    for (int i = 0; i < filters.size(); i++) {
      spec = bindFilter(spec, filters.get(i), i);
    }
    return spec;
  }

  // ─── SQL fragment builder (pure string — no binding here) ─────────────────
  private String buildFragment(FilterDTO filter, int idx) {
    String field = toSnakeCase(filter.getField());
    validateField(field);

    FilterOperator op     = filter.operator();
    op.validate(filter);
    String  prefix = "cf_" + idx + "_";
    boolean isDate = isDateField(field);

    return switch (op) {
      case CONTAINS     -> "(" + lower(field) + " LIKE CONCAT('%', LOWER(:" + prefix + "v), '%'))";
      case NOT_CONTAINS -> "(" + lower(field) + " NOT LIKE CONCAT('%', LOWER(:" + prefix + "v), '%'))";
      case STARTS_WITH  -> "(" + lower(field) + " LIKE CONCAT(LOWER(:" + prefix + "v), '%'))";
      case ENDS_WITH    -> "(" + lower(field) + " LIKE CONCAT('%', LOWER(:" + prefix + "v)))";
      case EQ           -> "(" + field + " = :"  + prefix + "v)";
      case NEQ          -> "(" + field + " != :" + prefix + "v)";
      case IS_NULL      -> "(" + field + " IS NULL)";
      case IS_NOT_NULL  -> "(" + field + " IS NOT NULL)";
      case GT           -> isDate
          ? "(" + field + " > (:"  + prefix + "v)::timestamptz)"
          : "(" + field + " > :"   + prefix + "v)";
      case GTE          -> isDate
          ? "(" + field + " >= (:" + prefix + "v)::timestamptz)"
          : "(" + field + " >= :"  + prefix + "v)";
      case LT           -> isDate
          ? "(" + field + " < (:"  + prefix + "v)::timestamptz)"
          : "(" + field + " < :"   + prefix + "v)";
      case LTE          -> isDate
          ? "(" + field + " <= (:" + prefix + "v)::timestamptz)"
          : "(" + field + " <= :"  + prefix + "v)";
      case BETWEEN      -> isDate
          ? "(" + field + " BETWEEN (:" + prefix + "v0)::timestamptz"
          + " AND (:"     + prefix + "v1)::timestamptz)"
          : "(" + field + " BETWEEN :"  + prefix + "v0 AND :" + prefix + "v1)";
      case IN           -> "(" + field + " IN ("     + inPlaceholders(prefix, normalizeValues(filter).size()) + "))";
      case NOT_IN       -> "(" + field + " NOT IN (" + inPlaceholders(prefix, normalizeValues(filter).size()) + "))";
    };
  }

  // ─── Parameter binder ─────────────────────────────────────────────────────

  private DatabaseClient.GenericExecuteSpec bindFilter(
      DatabaseClient.GenericExecuteSpec spec, FilterDTO filter, int idx) {

    FilterOperator op     = filter.operator();
    String         prefix = "cf_" + idx + "_";

    switch (op) {
      case IS_NULL, IS_NOT_NULL:
        break;
      case CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, EQ, NEQ, GT, GTE, LT, LTE:
        spec = spec.bind(prefix + "v", coerceValue(filter.getValue()));
        break;
      case BETWEEN:
        spec = spec.bind(prefix + "v0", toInstantIfNeeded(filter.getValues().get(0)));
        spec = spec.bind(prefix + "v1", toInstantIfNeeded(filter.getValues().get(1)));
        break;
      case IN, NOT_IN: {
        List<Object> vals = normalizeValues(filter);
        for (int j = 0; j < vals.size(); j++) {
          spec = spec.bind(prefix + "v" + j, coerceValue(vals.get(j)));
        }
        break;
      }
    }
    return spec;
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────
  private List<Object> normalizeValues(FilterDTO filter) {
    List<Object> raw = filter.getValues();
    if (raw == null) return List.of();
    if (raw.size() == 1 && raw.getFirst() instanceof String s && s.contains(",")) {
      return Arrays.stream(s.split(","))
          .map(String::trim)
          .filter(v -> !v.isEmpty())
          .map(v -> (Object) v)
          .toList();
    }
    return raw;
  }

  private Object coerceValue(Object v) {
    if (v instanceof String s) {
      try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) {
        //
      }
    }
    return v;
  }

  /**
   * Parses an ISO-8601 string to Instant for BETWEEN bindings on timestamptz
   * columns. R2DBC rejects raw String bindings on timestamp columns.
   */
  private Object toInstantIfNeeded(Object value) {
    if (!(value instanceof String s)) return value;
    try { return Instant.parse(s); } catch (Exception e) {
      //
    }
    try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception e) {
      //
    }
    return value;
  }

  private void validateField(String field) {
    if (field == null || field.isBlank())
      throw new IllegalArgumentException("Filter field must not be blank.");
    if (!allowedFields.isEmpty() && !allowedFields.contains(field))
      throw new IllegalArgumentException(
          "Filter field '" + field + "' is not permitted. Allowed: " + allowedFields);
  }

  private String toSnakeCase(String field) {
    if (field == null) return null;
    return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
  }

  private boolean isDateField(String snakeField) {
    if (snakeField == null) return false;
    for (String suffix : DATE_FIELD_SUFFIXES) {
      if (snakeField.endsWith(suffix)) return true;
    }
    return false;
  }

  private String inPlaceholders(String prefix, int count) {
    List<String> ph = new ArrayList<>();
    for (int j = 0; j < count; j++) ph.add(":" + prefix + "v" + j);
    return String.join(", ", ph);
  }

  private String lower(String field) { return "LOWER(" + field + ")"; }
}