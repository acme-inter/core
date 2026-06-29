package com.acme.core.query;

import com.acme.core.payload.export.ExportFilter;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

public class ExportQueryBuilder {

  private static final Set<String> PRIVILEGED_ROLES =
      Set.of("ADMINISTRATOR", "DEVELOPER", "MANAGEMENT");

  private final List<String> clauses = new ArrayList<>();
  private final Map<String, Object> binds   = new LinkedHashMap<>();

  private ExportQueryBuilder() {}

  // ── audit ────────────────────────────────────────────────────────────────
  public Mono<ExportQueryBuilder> withAudit() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(auth -> {
          Object p = auth.getPrincipal();
          try {
            assert p != null;
            String roleType   = (String) p.getClass().getMethod("roleType").invoke(p);
            Long   deptId     = (Long)   p.getClass().getMethod("departmentId").invoke(p);
            if (!PRIVILEGED_ROLES.contains(roleType) && deptId != null) {
              clauses.add("created_department = :audit_dept_id");
              binds.put("audit_dept_id", deptId);
            }
          } catch (Exception ignored) {
            // principal shape not as expected — skip audit filter
          }
          return this;
        })
        .defaultIfEmpty(this)
        .onErrorReturn(this);
  }

  // ── entry points ──────────────────────────────────────────────────────────

  public static ExportQueryBuilder builder() {
    return new ExportQueryBuilder();
  }

  /** Build from ExportFilter — all fields optional. */
  public static ExportQueryBuilder from(ExportFilter filter) {
    ExportQueryBuilder b = new ExportQueryBuilder();
    if (filter == null) return b;

    // soft-delete
    if (!filter.isIncludeDeleted()) b.excludeDeleted();

    if (filter.getFilters() != null) {
      filter.getFilters().forEach((col, val) -> { if (val != null) b.eq(col, val); });
    }

    if (filter.getInFilters() != null) {
      filter.getInFilters().forEach((col, vals) -> {
        if (vals == null || vals.isEmpty()) return;
        if (vals.getFirst() instanceof Number) {
          b.inLongs(col, vals.stream().map(v -> ((Number) v).longValue()).toList());
        } else {
          b.inStrings(col, vals.stream().map(Object::toString).toList());
        }
      });
    }

    if (filter.getDateRanges() != null) {
      filter.getDateRanges().forEach(dr -> {
        if (dr.getField() != null) b.dateRange(dr.getField(), dr.getFrom(), dr.getTo());
      });
    }

    if (hasText(filter.getKeyword()) && notEmpty(filter.getKeywordFields()))
      b.keyword(filter.getKeyword(), filter.getKeywordFields());

    return b;
  }

  // ── fluent API ────────────────────────────────────────────────────────────
  public ExportQueryBuilder eq(String column, Object value) {
    if (value == null) return this;
    String p = param(column);
    clauses.add(column + " = :" + p);
    binds.put(p, value);
    return this;
  }

  public ExportQueryBuilder neq(String column, Object value) {
    if (value == null) return this;
    String p = param(column) + "_neq";
    clauses.add(column + " != :" + p);
    binds.put(p, value);
    return this;
  }

  public ExportQueryBuilder inLongs(String column, List<Long> values) {
    if (notEmpty(values)) clauses.add(column + " IN (" + joinLongs(values) + ")");
    return this;
  }

  public ExportQueryBuilder inStrings(String column, List<String> values) {
    if (notEmpty(values)) clauses.add(column + " IN (" + joinStrings(values) + ")");
    return this;
  }

  public ExportQueryBuilder dateRange(String column, Instant from, Instant to) {
    if (from == null && to == null) return this;
    String s = sanitize(column);
    if (from != null && to != null) {
      clauses.add(column + " >= :dr_from_" + s + " AND " + column + " <= :dr_to_" + s);
      binds.put("dr_from_" + s, from);
      binds.put("dr_to_"   + s, to);
    } else if (from != null) {
      clauses.add(column + " >= :dr_from_" + s);
      binds.put("dr_from_" + s, from);
    } else {
      clauses.add(column + " <= :dr_to_" + s);
      binds.put("dr_to_" + s, to);
    }
    return this;
  }

  public ExportQueryBuilder excludeDeleted() {
    clauses.add("(is_deleted IS NULL OR is_deleted = false)");
    return this;
  }

  public ExportQueryBuilder keyword(String value, String... columns) {
    if (!hasText(value) || columns.length == 0) return this;
    List<String> parts = new ArrayList<>();
    for (String col : columns) parts.add("LOWER(" + col + ") LIKE :kw_value");
    clauses.add("(" + String.join(" OR ", parts) + ")");
    binds.put("kw_value", "%" + value.toLowerCase().trim() + "%");
    return this;
  }

  public ExportQueryBuilder keyword(String value, List<String> columns) {
    return keyword(value, columns.toArray(String[]::new));
  }

  public ExportQueryBuilder raw(String sqlFragment) {
    if (hasText(sqlFragment)) clauses.add(sqlFragment);
    return this;
  }

  public ExportQueryBuilder bindRaw(String paramName, Object value) {
    binds.put(paramName, value);
    return this;
  }

  // ── output ────────────────────────────────────────────────────────────────

  /** Returns {@code ""} when no conditions, or {@code " WHERE cond1 AND cond2 …"}. */
  public String whereClause() {
    if (clauses.isEmpty()) return "";
    return " WHERE " + String.join(" AND ", clauses);
  }

  /** Binds all accumulated parameters onto the spec. */
  public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec) {
    for (Map.Entry<String, Object> e : binds.entrySet()) {
      spec = spec.bind(e.getKey(), e.getValue());
    }
    return spec;
  }

  // ── private ───────────────────────────────────────────────────────────────

  private String param(String column)    { return "p_" + sanitize(column); }
  private String sanitize(String col)    { return col.replaceAll("\\W", "_"); }

  private String joinLongs(List<Long> ids) {
    StringJoiner sj = new StringJoiner(",");
    ids.forEach(id -> sj.add(String.valueOf(id)));
    return sj.toString();
  }

  private String joinStrings(List<String> values) {
    StringJoiner sj = new StringJoiner(",");
    values.forEach(v -> sj.add("'" + v.replace("'", "''") + "'"));
    return sj.toString();
  }

  private static boolean hasText(String s)      { return s != null && !s.isBlank(); }
  private static boolean notEmpty(List<?> list) { return list != null && !list.isEmpty(); }
}