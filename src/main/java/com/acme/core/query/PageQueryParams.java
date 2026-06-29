package com.acme.core.query;

import com.acme.core.payload.page.FilterDTO;
import com.acme.core.payload.page.SortDTO;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Getter
public class PageQueryParams {

  private final String                     keyword;
  private final List<String> searchFields;
  private final List<String>               numberSearchFields;

  private final List<String>               jsonSearchFields;

  private final Map<String, Object> filters;
  private final Map<String, Collection<?>> inFilters;
  private final Map<String, DateRange>     dateRanges;
  private final String                     ftsField;
  private final String                     ftsQuery;
  private final int                        index;
  private final int                        size;
  private final String                     orderBy;
  private final boolean                    descending;
  private final Long                       cursor;
  private final String                     cursorField;
  private final boolean                    cursorMode;
  private final String                     idField;
  private final List<Long>                 selectIds;
  private final List<FilterDTO>            columnFilters;
  private final Set<String> allowedFilterFields;

  // ─── Pinned-ids-first ordering ────────────────────────────────────────────
  private final List<Long> pinnedIds;

  // ─── Audit-scope fields ───────────────────────────────────────────────────
  private final boolean auditEnabled;
  private final String  auditDepartmentField;
  private final boolean auditDeleteEnabled;

  // ─── Soft-delete visibility ───────────────────────────────────────────────
  private final boolean checkDeleteEnabled;
  private final String  deleteField;

  // ─── Secondary sort ───────────────────────────────────────────────────────
  /** Optional secondary sort column applied after the primary orderBy. */
  private final String  thenOrderBy;
  private final boolean thenDescending;

  private PageQueryParams(Builder b) {
    this.keyword              = b.keyword;
    this.searchFields         = List.copyOf(b.searchFields);
    this.numberSearchFields   = List.copyOf(b.numberSearchFields);
    this.jsonSearchFields     = List.copyOf(b.jsonSearchFields);
    this.filters              = Map.copyOf(b.filters);
    this.inFilters            = Map.copyOf(b.inFilters);
    this.dateRanges           = Map.copyOf(b.dateRanges);
    this.ftsField             = b.ftsField;
    this.ftsQuery             = b.ftsQuery;
    this.index                = Math.max(0, b.index);
    this.size                 = Math.clamp(b.size, 1, 1000);
    this.orderBy              = b.orderBy;
    this.descending           = b.descending;
    this.cursor               = b.cursor;
    this.cursorField          = b.cursorField != null ? b.cursorField : "id";
    this.cursorMode           = b.cursorMode;
    this.idField              = b.idField;
    this.selectIds            = b.selectIds != null ? List.copyOf(b.selectIds) : List.of();
    this.columnFilters        = b.columnFilters != null ? List.copyOf(b.columnFilters) : List.of();
    this.allowedFilterFields  = b.allowedFilterFields != null
        ? Set.copyOf(b.allowedFilterFields) : Set.of();
    this.pinnedIds            = b.pinnedIds != null ? List.copyOf(b.pinnedIds) : List.of();
    this.auditEnabled         = b.auditEnabled;
    this.auditDepartmentField = b.auditDepartmentField != null
        ? b.auditDepartmentField : "department_id";
    this.checkDeleteEnabled   = b.checkDeleteEnabled;
    this.deleteField          = b.deleteField != null ? b.deleteField : "is_deleted";
    this.thenOrderBy          = b.thenOrderBy;
    this.thenDescending       = b.thenDescending;
    this.auditDeleteEnabled   = b.auditDeleteEnabled;
  }

  // ─── Derived predicates ───────────────────────────────────────────────────

  public boolean hasPinnedIds()           { return !pinnedIds.isEmpty(); }
  public boolean hasColumnFilters()       { return !columnFilters.isEmpty(); }
  public boolean hasSelectIds()           { return !selectIds.isEmpty(); }
  public boolean hasCursor()              { return cursor != null && cursor > 0; }
  public boolean hasFtsSearch()           { return ftsField != null && ftsQuery != null && !ftsQuery.isBlank(); }
  public boolean hasThenOrderBy()         { return thenOrderBy != null && !thenOrderBy.isBlank(); }

  public boolean hasKeywordSearch() {
    return keyword != null && !keyword.trim().isEmpty() && !searchFields.isEmpty();
  }

  /** True when there are JSON text columns to search AND a keyword is present. */
  public boolean hasJsonKeywordSearch() {
    return keyword != null && !keyword.trim().isEmpty() && !jsonSearchFields.isEmpty();
  }

  /** True when there are numeric fields to search AND a keyword is present. */
  public boolean hasNumberKeywordSearch() {
    return keyword != null && !keyword.trim().isEmpty() && !numberSearchFields.isEmpty();
  }

  public boolean hasIdSearch() {
    return idField != null && keyword != null && !keyword.trim().isEmpty();
  }

  /** True when the keyword consists entirely of digits (safe to bind as Long). */
  public boolean isKeywordNumeric() {
    return keyword != null && keyword.trim().matches("\\d+");
  }

  public static Builder builder() { return new Builder(); }

  // ─── Builder ──────────────────────────────────────────────────────────────

  public static class Builder {
    private String                           keyword;
    private List<String>                     searchFields         = new ArrayList<>();
    private List<String>                     numberSearchFields   = new ArrayList<>();
    private List<String>                     jsonSearchFields     = new ArrayList<>();
    private final Map<String, Object>        filters              = new HashMap<>();
    private final Map<String, Collection<?>> inFilters            = new HashMap<>();
    private final Map<String, DateRange>     dateRanges           = new HashMap<>();
    private String                           ftsField;
    private String                           ftsQuery;
    private int                              index                = 0;
    private int                              size                 = 10;
    private String                           orderBy;
    private boolean                          descending           = true;
    private Long                             cursor;
    private String                           cursorField;
    private boolean                          cursorMode           = false;
    private String                           idField;
    private List<Long>                       selectIds            = new ArrayList<>();
    private List<FilterDTO>                  columnFilters        = new ArrayList<>();
    private Set<String>                      allowedFilterFields  = new HashSet<>();
    private boolean                          auditEnabled         = false;
    private String                           auditDepartmentField;
    private List<Long>                       pinnedIds            = new ArrayList<>();
    private boolean                          checkDeleteEnabled   = false;
    private String                           deleteField;
    private String                           thenOrderBy;
    private boolean                          thenDescending       = true;
    private boolean                          auditDeleteEnabled;

    // ── Basic ─────────────────────────────────────────────────────────────

    public Builder keyword(String keyword) { this.keyword = keyword; return this; }
    public Builder idField(String field)   { this.idField = field;   return this; }
    public Builder index(int index)        { this.index   = index;   return this; }
    public Builder size(int size)          { this.size    = size;    return this; }

    // ── Text search fields ────────────────────────────────────────────────

    public Builder searchFields(String... fields) {
      this.searchFields = new ArrayList<>(Arrays.asList(fields));
      return this;
    }

    public Builder searchFields(List<String> fields) {
      this.searchFields = new ArrayList<>(fields);
      return this;
    }

    // ── Numeric search fields (BIGINT exact + TEXT LIKE) ──────────────────
    public Builder numberSearchFields(String... fields) {
      this.numberSearchFields = new ArrayList<>(Arrays.asList(fields));
      return this;
    }

    public Builder numberSearchFields(List<String> fields) {
      this.numberSearchFields = new ArrayList<>(fields);
      return this;
    }

    // ── JSON text search fields ───────────────────────────────────────────
    public Builder jsonSearchFields(String... fields) {
      this.jsonSearchFields = new ArrayList<>(Arrays.asList(fields));
      return this;
    }

    public Builder jsonSearchFields(List<String> fields) {
      this.jsonSearchFields = new ArrayList<>(fields);
      return this;
    }

    // ── Sorting ───────────────────────────────────────────────────────────

    public Builder orderBy(String field) { this.orderBy    = field; return this; }
    public Builder ascending()           { this.descending = false; return this; }
    public Builder descending()          { this.descending = true;  return this; }

    /**
     * Secondary sort applied after the primary {@link #orderBy}.
     * Defaults to descending; use {@link #thenOrderByAsc(String)} for ascending.
     */
    public Builder thenOrderBy(String field) {
      this.thenOrderBy    = field;
      this.thenDescending = true;
      return this;
    }

    public Builder thenOrderByAsc(String field) {
      this.thenOrderBy    = field;
      this.thenDescending = false;
      return this;
    }


    public Builder sorts(List<SortDTO> sorts) {
      if (sorts == null || sorts.isEmpty()) return this;
      SortDTO primary = sorts.get(0);
      if (primary.getField() != null && !primary.getField().isBlank()) {
        this.orderBy    = primary.getField();
        this.descending = !"ASC".equalsIgnoreCase(primary.getDirection());
      }
      if (sorts.size() > 1) {
        SortDTO secondary = sorts.get(1);
        if (secondary.getField() != null && !secondary.getField().isBlank()) {
          this.thenOrderBy    = secondary.getField();
          this.thenDescending = !"ASC".equalsIgnoreCase(secondary.getDirection());
        }
      }
      return this;
    }

    // ── Cursor ────────────────────────────────────────────────────────────

    public Builder cursorMode()              { this.cursorMode  = true;  return this; }
    public Builder cursorField(String field) { this.cursorField = field;  return this; }

    public Builder cursor(Long cursor) {
      this.cursor     = cursor;
      this.cursorMode = true;
      return this;
    }

    // ── Pinned ids ────────────────────────────────────────────────────────

    public Builder pinnedIds(List<Long> ids) {
      if (ids != null && !ids.isEmpty()) this.pinnedIds = new ArrayList<>(ids);
      return this;
    }

    // ── Audit scope ───────────────────────────────────────────────────────

    public Builder auditEnable() {
      this.auditEnabled = true;
      return this;
    }

    public Builder auditDepartmentField(String field) {
      this.auditDepartmentField = field;
      this.auditEnabled         = true;
      return this;
    }

    public Builder auditDelete() {
      this.auditDeleteEnabled = true;
      this.auditEnabled       = true; // need audit resolved to know role
      return this;
    }

    // ── Soft-delete ───────────────────────────────────────────────────────

    /** Enable soft-delete filter; column defaults to {@code is_deleted}. */
    public Builder checkDelete() {
      this.checkDeleteEnabled = true;
      return this;
    }

    /** Enable soft-delete filter with a custom column name. */
    public Builder checkDelete(String field) {
      this.checkDeleteEnabled = true;
      this.deleteField        = field;
      return this;
    }

    // ── Exact / IN / date filters ─────────────────────────────────────────

    public Builder filter(String field, Object value) {
      if (value != null) this.filters.put(field, value);
      return this;
    }

    public Builder filters(Map<String, Object> filters) {
      if (filters != null) filters.forEach((k, v) -> { if (v != null) this.filters.put(k, v); });
      return this;
    }

    public Builder filterIn(String field, Object... values) {
      if (values != null && values.length > 0) this.inFilters.put(field, Arrays.asList(values));
      return this;
    }

    public void filterIn(String field, Collection<?> values) {
      if (values != null && !values.isEmpty()) this.inFilters.put(field, values);
    }

    public Builder dateRange(String field, DateRange range) {
      if (range != null) this.dateRanges.put(field, range);
      return this;
    }

    public Builder dateRange(String field, LocalDate start, LocalDate end) {
      if (start != null || end != null) this.dateRanges.put(field, DateRange.between(start, end));
      return this;
    }

    public Builder dateRange(String field, LocalDateTime start, LocalDateTime end) {
      if (start != null || end != null) this.dateRanges.put(field, DateRange.between(start, end));
      return this;
    }

    public Builder dateRange(String field, Instant start, Instant end) {
      if (start != null || end != null) this.dateRanges.put(field, DateRange.between(start, end));
      return this;
    }

    // ── FTS ───────────────────────────────────────────────────────────────

    public Builder ftsSearch(String field, String query) {
      if (field != null && query != null && !query.isBlank()) {
        this.ftsField = field;
        this.ftsQuery = query;
      }
      return this;
    }

    // ── Select / column filters ───────────────────────────────────────────

    public Builder selectIds(List<Long> ids) {
      if (ids != null && !ids.isEmpty()) this.selectIds = new ArrayList<>(ids);
      return this;
    }

    public Builder columnFilters(List<FilterDTO> filters) {
      if (filters != null) this.columnFilters = new ArrayList<>(filters);
      return this;
    }

    public Builder allowedFilterFields(String... fields) {
      this.allowedFilterFields = new HashSet<>(Arrays.asList(fields));
      return this;
    }

    public Builder allowedFilterFields(Set<String> fields) {
      this.allowedFilterFields = new HashSet<>(fields);
      return this;
    }

    public PageQueryParams build() { return new PageQueryParams(this); }
  }
}