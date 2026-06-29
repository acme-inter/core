package com.acme.core.payload.export;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ExportFilter {

  // ── generic exact filters — { "lead_id": 123, "activity_type": "CALL" } ──
  private Map<String, Object> filters = new LinkedHashMap<>();

  // ── generic IN filters — { "created_department": [1, 2, 3] } ─────────────
  private Map<String, List<Object>> inFilters = new LinkedHashMap<>();

  // ── date ranges ───────────────────────────────────────────────────────────
  private List<DateRangeEntry> dateRanges = new ArrayList<>();

  // ── keyword ───────────────────────────────────────────────────────────────
  private String       keyword;
  private List<String> keywordFields;

  // ── soft-delete ───────────────────────────────────────────────────────────
  private boolean includeDeleted = false;

  // ── nested ────────────────────────────────────────────────────────────────
  @Data
  public static class DateRangeEntry {
    private String  field;
    private Instant from;
    private Instant to;
  }
}