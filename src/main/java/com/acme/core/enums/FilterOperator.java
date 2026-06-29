package com.acme.core.enums;

import com.acme.core.payload.page.FilterDTO;
import lombok.Getter;

import java.util.List;

@Getter
public enum FilterOperator {

  // ── Text operators ────────────────────────────────────────────────────────
  CONTAINS     ("field LIKE CONCAT('%', :v, '%')",  1, false),
  NOT_CONTAINS ("field NOT LIKE CONCAT('%', :v, '%')", 1, false),
  STARTS_WITH  ("field LIKE CONCAT(:v, '%')",       1, false),
  ENDS_WITH    ("field LIKE CONCAT('%', :v)",        1, false),

  // ── Equality ─────────────────────────────────────────────────────────────
  EQ           ("field = :v",                       1, false),
  NEQ          ("field != :v",                      1, false),

  // ── Null checks (no value needed) ────────────────────────────────────────
  IS_NULL      ("field IS NULL",                    0, false),
  IS_NOT_NULL  ("field IS NOT NULL",                0, false),

  // ── Numeric / date comparisons ───────────────────────────────────────────
  GT           ("field > :v",                       1, false),
  GTE          ("field >= :v",                      1, false),
  LT           ("field < :v",                       1, false),
  LTE          ("field <= :v",                      1, false),
  BETWEEN      ("field BETWEEN :v0 AND :v1",        2, true),

  // ── Collection operators ─────────────────────────────────────────────────
  IN           ("field IN (:values)",               -1, true),  // -1 = 1 or more
  NOT_IN       ("field NOT IN (:values)",           -1, true);


  private final int expectedValues;
  private final boolean useValuesList;
  private final String sqlTemplate;

  FilterOperator(String sqlTemplate, int expectedValues, boolean useValuesList) {
    this.sqlTemplate   = sqlTemplate;
    this.expectedValues = expectedValues;
    this.useValuesList  = useValuesList;
  }

  public void validate(FilterDTO filter) {
    List<?> vals = filter.getValues();
    String field = filter.getField();

    if (expectedValues == 0 && (filter.getValue() != null || (vals != null && !vals.isEmpty()))) {
      throw new IllegalArgumentException("Operator " + name() + " on field '" + field + "' must not have a value.");
    }
    if (expectedValues == 1 && filter.getValue() == null) {
      throw new IllegalArgumentException("Operator " + name() + " on field '" + field + "' requires exactly one value.");
    }
    if (expectedValues == 2 && (vals == null || vals.size() != 2)) {
      throw new IllegalArgumentException("Operator " + name() + " on field '" + field + "' requires exactly 2 values in the 'values' array.");
    }
    if (expectedValues == -1 && (vals == null || vals.isEmpty())) {
      throw new IllegalArgumentException("Operator " + name() + " on field '" + field + "' requires at least one value in the 'values' array.");
    }
  }
}