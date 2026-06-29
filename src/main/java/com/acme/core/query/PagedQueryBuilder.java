package com.acme.core.query;

import com.acme.core.enums.Modules;
import com.acme.core.enums.RoleType;
import com.acme.core.payload.ListResponse;
import com.acme.core.payload.MemberPrincipal;
import com.acme.core.payload.PagedResponse;
import com.acme.core.payload.audit.AuditDTO;
import com.acme.core.payload.page.FilterDTO;
import com.acme.core.payload.page.SortDTO;
import com.acme.core.util.GuardUtil;
import com.acme.core.util.MsgUtil;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PagedQueryBuilder<T, R> {

  private final DatabaseClient databaseClient;
  private final MsgUtil        msgUtil;
  private final GuardUtil guardUtil;
  private       String         baseQuery;
  private       String  countQuery;
  private Function<Row, T> rowMapper;
  private       Function<List<T>, Flux<R>> converter;
  private       Function<T, Long>          cursorExtractor;
  private       String                     joinClause;

  private BiFunction<Long, String, Mono<UserViewResult>> viewLoader;
  private Long    viewMemberId;
  private String  viewEntityClass;

  // ─── Guard ────────────────────────────────────────────────────────────────
  private boolean guardEnabled        = false;
  private String  guardDeniedMsgCode  = "page.response.access.denied";

  private static final ConcurrentHashMap<String, long[]> COUNT_CACHE  = new ConcurrentHashMap<>();
  private static final long                              CACHE_TTL_MS = 30_000L;

  private static final String LOAD_VIEW_SQL = """
      SELECT filters, sorts, view_id
      FROM   views
      WHERE  member_id = :memberId
      AND    entity    = :entity
      LIMIT  1
      """;

  private static final ObjectMapper MAPPER           = new ObjectMapper();
  private static final Set<String> PRIVILEGED_ROLES = Set.of("ADMINISTRATOR", "DEVELOPER", "MANAGEMENT");

  // ─── Audit context resolution ─────────────────────────────────────────────

  private Mono<AuditDTO> resolveAudit() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .map(auth -> {
          Object principal = auth.getPrincipal();
          if (principal instanceof MemberPrincipal(
              Boolean isApi, Long memberId, Long departmentId, Long sessionId,
              String roleType, String module, String lang,
              String ip, String browser, String deviceType
          )) {
            return AuditDTO.builder()
                .isApi(isApi).memberId(memberId).departmentId(departmentId)
                .sessionId(sessionId).lang(lang).ip(ip).browser(browser)
                .deviceType(deviceType)
                .module(Modules.valueOf(module))
                .roleType(RoleType.valueOf(roleType))
                .build();
          }
          return AuditDTO.builder().build();
        })
        .defaultIfEmpty(AuditDTO.builder().build());
  }

  // ─── UserViewResult ───────────────────────────────────────────────────────

  public record UserViewResult(String viewId, List<FilterDTO> filters, List<SortDTO> sorts) {
    public static UserViewResult empty() { return new UserViewResult(null, null, null); }
  }

  private List<FilterDTO> resolveColumnFilters(PageQueryParams params, AuditDTO audit) {
    if (!params.isAuditDeleteEnabled()) return params.getColumnFilters();
    boolean isAdmin = audit != null
        && audit.getRoleType() != null
        && audit.getRoleType().equals(RoleType.ADMINISTRATOR);
    if (isAdmin) return params.getColumnFilters();
    return params.getColumnFilters().stream()
        .filter(f -> !f.getField().equalsIgnoreCase("isDeleted")
            && !f.getField().equalsIgnoreCase("is_deleted"))
        .toList();
  }

  private String auditDeleteCondition(PageQueryParams params, AuditDTO audit) {
    if (!params.isAuditDeleteEnabled()) return null;
    boolean isAdmin = audit != null
        && audit.getRoleType() != null
        && audit.getRoleType().equals(RoleType.ADMINISTRATOR);
    if (isAdmin) return null;
    return "is_deleted = false";
  }
  // ─── Constructor ──────────────────────────────────────────────────────────

  public PagedQueryBuilder(DatabaseClient databaseClient, MsgUtil msgUtil, GuardUtil guardUtil) {
    this.databaseClient = databaseClient;
    this.msgUtil        = msgUtil;
    this.guardUtil      = guardUtil;
  }

  // ─── Builder methods ──────────────────────────────────────────────────────

  public PagedQueryBuilder<T, R> baseQuery(String query)              { this.baseQuery = query;   return this; }
  public PagedQueryBuilder<T, R> countQuery(String query)             { this.countQuery = query;  return this; }
  public PagedQueryBuilder<T, R> cursorExtractor(Function<T, Long> e) { this.cursorExtractor = e; return this; }

  public PagedQueryBuilder<T, R> joinQuery(String join) { this.joinClause = join; return this; }

  public void rowMapper(Function<Row, T> m)           { this.rowMapper = m; }
  public void converter(Function<List<T>, Flux<R>> c) { this.converter = c; }

  public PagedQueryBuilder<T, R> viewLoader(Long memberId, String entity,
                                            BiFunction<Long, String, Mono<UserViewResult>> loader) {
    this.viewMemberId    = memberId;
    this.viewEntityClass = entity;
    this.viewLoader      = loader;
    return this;
  }

  /**
   * Enable saved-view loading. {@code memberId} is resolved from the security
   * context automatically — no need to pass it from the service.
   *
   * <pre>{@code
   *   pagedQueryFactory.create(mapper, converter)
   *       .baseQuery("SELECT * FROM v_activity_page")
   *       .viewQuery("activity")   // ← just the key
   *       .execute(params);
   * }</pre>
   */
  public PagedQueryBuilder<T, R> viewQuery(String entity) {
    if (entity == null || entity.isBlank()) return this;
    this.viewEntityClass = entity;
    this.viewLoader      = (mid, ent) ->
        databaseClient.sql(LOAD_VIEW_SQL)
            .bind("memberId", mid)
            .bind("entity",   ent)
            .map((row, meta) -> new UserViewResult(
                row.get("view_id",  String.class),
                parseList(row.get("filters", String.class), FilterDTO.class),
                parseList(row.get("sorts",   String.class), SortDTO.class)
            ))
            .one()
            .defaultIfEmpty(UserViewResult.empty())
            .onErrorReturn(UserViewResult.empty());
    return this;
  }

  /**
   * Enable saved-view loading with an explicit memberId.
   * Prefer the no-arg {@link #viewQuery(String)} unless you already have the id.
   */
  public PagedQueryBuilder<T, R> viewQuery(Long memberId, String entity) {
    if (memberId == null || memberId <= 0 || entity == null || entity.isBlank()) return this;
    this.viewMemberId = memberId;
    return viewQuery(entity);
  }

  // ─── Guard builder methods ────────────────────────────────────────────────

  /**
   * Enable role guard using the default denied message key
   * ({@code page.response.access.denied}).
   * Non-privileged callers receive a paged error before any DB query fires.
   *
   * <pre>{@code
   *   pagedQueryFactory.create(mapper, converter)
   *       .baseQuery("SELECT * FROM v_activity_page")
   *       .viewQuery("activity")
   *       .guard()          // enable with default message
   *       .execute(params);
   * }</pre>
   */
  public PagedQueryBuilder<T, R> guard() {
    this.guardEnabled = true;
    return this;
  }

  /**
   * Enable role guard with a custom denied message key.
   *
   * <pre>{@code .guard("activity.access.denied") }</pre>
   */
  public PagedQueryBuilder<T, R> guard(String deniedMsgCode) {
    this.guardEnabled       = true;
    this.guardDeniedMsgCode = deniedMsgCode;
    return this;
  }

  /**
   * Conditionally enable or disable the guard.
   * Useful when the guard state comes from a config flag.
   *
   * <pre>{@code .guard(props.isGuardEnabled()) }</pre>
   */
  public PagedQueryBuilder<T, R> guard(boolean enabled) {
    this.guardEnabled = enabled;
    return this;
  }

  /**
   * Conditionally enable or disable the guard with a custom denied message key.
   */
  public PagedQueryBuilder<T, R> guard(boolean enabled, String deniedMsgCode) {
    this.guardEnabled       = enabled;
    this.guardDeniedMsgCode = deniedMsgCode;
    return this;
  }

  // ─── Execute ──────────────────────────────────────────────────────────────

  public Mono<PagedResponse<R>> execute(PageQueryParams params) {
    return params.isCursorMode() ? executeCursor(params) : executeOffset(params);
  }

  // ─── Offset pagination ────────────────────────────────────────────────────
  public Mono<PagedResponse<R>> executeOffset(PageQueryParams params) {
    Mono<Void> guardCheck = (guardEnabled && guardUtil != null)
        ? guardUtil.requireRole(null, guardDeniedMsgCode).then()
        : Mono.empty();

    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    Long resolvedViewMemberId = viewMemberId != null ? viewMemberId : 0L;

    Mono<Long> memberIdMono;
    if (viewLoader != null && viewMemberId == null && guardUtil != null) {
      memberIdMono = guardUtil.memberId().onErrorReturn(0L);
    } else {
      memberIdMono = Mono.just(resolvedViewMemberId);
    }

    return guardCheck
        .then(Mono.zip(
            auditMono.defaultIfEmpty(AuditDTO.builder().build()),
            memberIdMono
        ))
        .flatMap(tuple -> {
          AuditDTO audit    = tuple.getT1();
          Long     memberId = tuple.getT2();

          String dataQuery = buildDataQuery(params, audit);
          String cQuery    = countQuery != null
              ? buildCountQueryCustom(params, audit)
              : buildCountQueryAuto(params, audit);
          String cacheKey  = cQuery + "|" + buildWhereClause(params, audit);

          DatabaseClient.GenericExecuteSpec dataSpec  = bindParameters(databaseClient.sql(dataQuery),  params, audit);
          DatabaseClient.GenericExecuteSpec countSpec = bindParameters(databaseClient.sql(cQuery), params, audit);

          Flux<T>              dataFlux  = dataSpec.map((row, meta) -> rowMapper.apply(row)).all();
          Mono<Long>           countMono = getCachedCount(cacheKey, countSpec);
          Mono<UserViewResult> viewMono  = (viewLoader != null && memberId > 0)
              ? viewLoader.apply(memberId, viewEntityClass).onErrorReturn(UserViewResult.empty())
              : Mono.just(UserViewResult.empty());

          return Mono.zip(dataFlux.collectList(), countMono, viewMono)
              .flatMap(t -> {
                List<T>        results    = t.getT1();
                long           total      = t.getT2();
                UserViewResult view       = t.getT3();
                int            totalPages = (int) Math.ceil((double) total / params.getSize());

                if (results.isEmpty()) {
                  return msgUtil.get("page.response.empty")
                      .map(msg -> PagedResponse.<R>success(msg, params.getIndex(), params.getSize()));
                }
                return converter.apply(results).collectList()
                    .flatMap(dtoList -> msgUtil.get("page.response.fetch.success")
                        .map(msg -> PagedResponse.<R>success(
                            msg, dtoList, totalPages, total,
                            params.getIndex(), params.getSize(),
                            view.filters(), view.sorts(), view.viewId())));
              });
        })
        .onErrorResume(e -> msgUtil.get("page.response.failed")
            .map(msg -> PagedResponse.<R>error(msg, e.getMessage())));
  }

  // ─── List / infinite-scroll ───────────────────────────────────────────────

  public Mono<ListResponse<List<R>>> executeList(PageQueryParams params) {
    if (params.hasSelectIds() && params.getIndex() == 0
        && (params.getKeyword() == null || params.getKeyword().isBlank())) {
      return executeListWithSelectIds(params);
    }
    return executeListNormal(params);
  }

  private Mono<ListResponse<List<R>>> executeListNormal(PageQueryParams params) {
    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return auditMono.defaultIfEmpty(AuditDTO.builder().build())
        .flatMap(audit -> {
          long limit  = params.getSize() + 1L;
          long offset = (long) params.getIndex() * params.getSize();
          DatabaseClient.GenericExecuteSpec spec = bindParameters(databaseClient.sql(buildDataQuery(params, audit, limit, offset)), params, audit);

          return spec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
              .flatMap(results -> {
                boolean hasMore = results.size() > params.getSize();
                List<T> actual  = hasMore ? results.subList(0, params.getSize()) : results;
                if (actual.isEmpty()) {
                  return msgUtil.get("list.response.empty").map(ListResponse::<List<R>>success);
                }
                return converter.apply(actual).collectList()
                    .flatMap(dtoList -> msgUtil.get("list.response.fetch.success")
                        .map(msg -> ListResponse.success(msg, hasMore, dtoList)));
              });
        })
        .onErrorResume(e -> msgUtil.get("list.response.failed")
            .map(msg -> ListResponse.error(msg, e.getMessage())));
  }

  private Mono<ListResponse<List<R>>> executeListWithSelectIds(PageQueryParams params) {
    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return auditMono.defaultIfEmpty(AuditDTO.builder().build())
        .flatMap(audit -> {
          DatabaseClient.GenericExecuteSpec selectSpec = databaseClient.sql(buildSelectIdsQuery(params)); // Long values inlined — no bind needed

          return selectSpec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
              .flatMap(pinned -> {
                int fetchSize = params.getSize() + params.getSelectIds().size() + 1;
                DatabaseClient.GenericExecuteSpec dataSpec =
                    bindParameters(databaseClient.sql(buildDataQuery(params, audit, fetchSize, 0L)), params, audit);

                return dataSpec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
                    .flatMap(rest -> {
                      Map<Object, T> seen = new LinkedHashMap<>();
                      for (T item : pinned) { Object id = extractId(item); if (id != null) seen.put(id, item); }
                      for (T item : rest)   { Object id = extractId(item); seen.putIfAbsent(id, item); }

                      List<T> merged  = new ArrayList<>(seen.values());
                      boolean hasMore = merged.size() > params.getSize();
                      List<T> actual  = hasMore ? merged.subList(0, params.getSize()) : merged;

                      if (actual.isEmpty()) {
                        return msgUtil.get("list.response.empty").map(ListResponse::<List<R>>success);
                      }
                      return converter.apply(actual).collectList()
                          .flatMap(dtoList -> msgUtil.get("list.response.fetch.success")
                              .map(msg -> ListResponse.success(msg, hasMore, dtoList)));
                    });
              });
        })
        .onErrorResume(e -> msgUtil.get("list.response.failed")
            .map(msg -> ListResponse.error(msg, e.getMessage())));
  }

  // ─── Cursor pagination ────────────────────────────────────────────────────

  private Mono<PagedResponse<R>> executeCursor(PageQueryParams params) {
    Mono<Void> guardCheck = (guardEnabled && guardUtil != null)
        ? guardUtil.requireRole(null, guardDeniedMsgCode).then()
        : Mono.empty();

    Mono<AuditDTO> auditMono = (params.isAuditEnabled() || params.isCheckDeleteEnabled())
        ? resolveAudit() : Mono.empty();

    return guardCheck
        .then(auditMono.defaultIfEmpty(AuditDTO.builder().build()))
        .flatMap(audit -> {
          DatabaseClient.GenericExecuteSpec dataSpec =
              bindParameters(databaseClient.sql(buildCursorDataQuery(params, audit)), params, audit);
          if (params.hasCursor()) dataSpec = dataSpec.bind("cursor", params.getCursor());

          return dataSpec.map((row, meta) -> rowMapper.apply(row)).all().collectList()
              .flatMap(results -> {
                boolean hasNext     = results.size() > params.getSize();
                List<T> pageResults = hasNext ? results.subList(0, params.getSize()) : results;

                if (pageResults.isEmpty()) {
                  return msgUtil.get("page.response.empty")
                      .map(msg -> PagedResponse.<R>cursorEmptySuccess(msg, false));
                }
                Long nextCursor = cursorExtractor != null
                    ? cursorExtractor.apply(pageResults.getLast()) : null;

                return converter.apply(pageResults).collectList()
                    .flatMap(dtoList -> msgUtil.get("page.response.fetch.success")
                        .map(msg -> PagedResponse.<R>cursorSuccess(msg, dtoList, nextCursor, hasNext)));
              });
        })
        .onErrorResume(e -> msgUtil.get("page.response.failed")
            .map(msg -> PagedResponse.<R>error(msg, e.getMessage())));
  }

  // ─── Query builders ───────────────────────────────────────────────────────

  private String buildDataQuery(PageQueryParams params, AuditDTO audit) {
    return buildDataQuery(params, audit, params.getSize(), (long) params.getIndex() * params.getSize());
  }

  private String buildDataQuery(PageQueryParams params, AuditDTO audit, long limit, long offset) {
    StringBuilder q        = new StringBuilder(baseQuery);
    if (joinClause != null) q.append(" ").append(joinClause);
    String        where    = buildWhereClause(params, audit);
    boolean       hasWhere = baseQuery.toUpperCase().contains(" WHERE ");

    if (!where.isEmpty()) q.append(hasWhere ? " AND " : " WHERE ").append(where);

    q.append(" ORDER BY ");
    if (params.hasPinnedIds()) q.append(buildPinnedCase(params.getPinnedIds())).append(", ");

    q.append(params.getOrderBy() != null ? params.getOrderBy() : "id")
        .append(params.isDescending() ? " DESC" : " ASC");

    if (params.hasThenOrderBy()) {
      q.append(", ").append(params.getThenOrderBy())
          .append(params.isThenDescending() ? " DESC" : " ASC");
    }
    q.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
    return q.toString();
  }

  private String buildSelectIdsQuery(PageQueryParams params) {
    List<Long> ids  = params.getSelectIds();
    String     list = ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    return baseQuery
        + " WHERE id IN (" + list + ")"
        + " ORDER BY " + buildPinnedCase(ids);
  }

  private String buildCursorDataQuery(PageQueryParams params, AuditDTO audit) {
    StringBuilder q          = new StringBuilder(baseQuery);
    if (joinClause != null) q.append(" ").append(joinClause);
    List<String>  conditions = new ArrayList<>();
    String        where      = buildWhereClause(params, audit);

    if (!where.isEmpty()) conditions.add(where);
    if (params.hasCursor()) {
      conditions.add(params.getCursorField() + (params.isDescending() ? " < " : " > ") + ":cursor");
    }
    boolean hasWhere = baseQuery.toUpperCase().contains(" WHERE ");
    if (!conditions.isEmpty()) {
      q.append(hasWhere ? " AND " : " WHERE ").append(String.join(" AND ", conditions));
    }
    String orderField = params.getOrderBy() != null ? params.getOrderBy() : params.getCursorField();
    q.append(" ORDER BY ").append(orderField).append(params.isDescending() ? " DESC" : " ASC");
    if (params.hasThenOrderBy()) {
      q.append(", ").append(params.getThenOrderBy())
          .append(params.isThenDescending() ? " DESC" : " ASC");
    }
    q.append(" LIMIT ").append((long) params.getSize() + 1);
    return q.toString();
  }

  private String buildCountQueryAuto(PageQueryParams params, AuditDTO audit) {
    StringBuilder inner    = new StringBuilder(baseQuery);
    if (joinClause != null) inner.append(" ").append(joinClause);
    String        where    = buildWhereClause(params, audit);
    boolean       hasWhere = baseQuery.toUpperCase().contains(" WHERE ");
    if (!where.isEmpty()) inner.append(hasWhere ? " AND " : " WHERE ").append(where);
    return "SELECT COUNT(*) FROM (" + inner + ") AS _count_subquery";
  }

  private String buildCountQueryCustom(PageQueryParams params, AuditDTO audit) {
    StringBuilder q     = new StringBuilder(countQuery);
    String        where = buildWhereClause(params, audit);
    if (!where.isEmpty()) q.append(" WHERE ").append(where);
    return q.toString();
  }

  private String buildWhereClause(PageQueryParams params, AuditDTO audit) {
    List<String> conditions = new ArrayList<>();
    addIfPresent(conditions, auditCondition(params, audit));
    addIfPresent(conditions, keywordCondition(params));
    addIfPresent(conditions, ftsCondition(params));
    exactFilterConditions(params, conditions);
    inFilterConditions(params, conditions);
    dateRangeConditions(params, conditions);
    addIfPresent(conditions, columnFilterCondition(params, audit));
    addIfPresent(conditions, auditDeleteCondition(params, audit));
    return String.join(" AND ", conditions);
  }

  // ─── WHERE fragment builders ──────────────────────────────────────────────

  private String auditCondition(PageQueryParams params, AuditDTO audit) {
    if (!params.isAuditEnabled() || isPrivileged(audit)) return null;
    return params.getAuditDepartmentField() + " = :audit_department_id";
  }

  /** Text LIKE across searchFields + jsonSearchFields, OR-joined with idField when present. */
  private String keywordCondition(PageQueryParams params) {
    boolean hasText   = params.hasKeywordSearch() || params.hasJsonKeywordSearch();
    boolean hasNumber = params.hasNumberKeywordSearch();
    boolean hasId     = params.hasIdSearch();

    if (!hasText && !hasNumber && !hasId) return null;

    List<String> parts = new ArrayList<>();

    if (params.hasKeywordSearch()) {
      for (String field : params.getSearchFields())
        parts.add("LOWER(" + field + ") LIKE :search_" + sanitize(field));
    }
    if (params.hasJsonKeywordSearch()) {
      for (String field : params.getJsonSearchFields())
        parts.add("LOWER(" + field + ") LIKE :search_" + sanitize(field));
    }
    if (hasNumber) {
      for (String field : params.getNumberSearchFields()) {
        if (params.isKeywordNumeric())
          parts.add(field + " = :num_exact_" + sanitize(field));
        parts.add("CAST(" + field + " AS TEXT) LIKE :num_like_" + sanitize(field));
      }
    }
    if (hasId) {
      if (params.isKeywordNumeric()) parts.add(params.getIdField() + " = :id_exact");
      parts.add("CAST(" + params.getIdField() + " AS TEXT) LIKE :id_like");
    }

    return "(" + String.join(" OR ", parts) + ")";
  }

  private String ftsCondition(PageQueryParams params) {
    if (!params.hasFtsSearch()) return null;
    return params.getFtsField() + " @@ plainto_tsquery('simple', :fts_query)";
  }

  private void exactFilterConditions(PageQueryParams params, List<String> out) {
    params.getFilters().keySet().forEach(f -> out.add(f + " = :filter_" + f));
  }

  private void inFilterConditions(PageQueryParams params, List<String> out) {
    params.getInFilters().forEach((field, values) -> {
      if (values == null || values.isEmpty()) return;
      List<String> ph = new ArrayList<>();
      for (int i = 0; i < values.size(); i++) ph.add(":in_" + field + "_" + i);
      out.add(field + " IN (" + String.join(", ", ph) + ")");
    });
  }

  private void dateRangeConditions(PageQueryParams params, List<String> out) {
    params.getDateRanges().forEach((field, range) -> {
      if (range.getStartDate() != null && range.getEndDate() != null)
        out.add(field + " BETWEEN :start_" + field + " AND :end_" + field);
      else if (range.getStartDate() != null)
        out.add(field + " >= :start_" + field);
      else if (range.getEndDate() != null)
        out.add(field + " <= :end_" + field);
    });
  }

  private String columnFilterCondition(PageQueryParams params, AuditDTO audit) {
    List<FilterDTO> filters = resolveColumnFilters(params, audit);
    if (filters.isEmpty()) return null;
    ColumnFilterClause clause = new ColumnFilterClause(filters, params.getAllowedFilterFields());
    String sql = clause.toSql();
    return sql.isEmpty() ? null : sql;
  }

  // ─── Parameter binding ────────────────────────────────────────────────────
  private DatabaseClient.GenericExecuteSpec bindParameters(
      DatabaseClient.GenericExecuteSpec spec, PageQueryParams params, AuditDTO audit) {

    if (params.isAuditEnabled() && !isPrivileged(audit)) {
      Long deptId = audit.getDepartmentId() != null ? audit.getDepartmentId() : 0L;
      spec = spec.bind("audit_department_id", deptId);
    }

    if (params.hasKeywordSearch() || params.hasJsonKeywordSearch()) {
      String pattern = "%" + params.getKeyword().toLowerCase() + "%";
      for (String field : params.getSearchFields())
        spec = spec.bind("search_" + sanitize(field), pattern);
      for (String field : params.getJsonSearchFields())
        spec = spec.bind("search_" + sanitize(field), pattern);
    }

    if (params.hasIdSearch()) {
      if (params.isKeywordNumeric())
        spec = spec.bind("id_exact", Long.parseLong(params.getKeyword().trim()));
      spec = spec.bind("id_like", "%" + params.getKeyword().trim() + "%");
    }

    if (params.hasNumberKeywordSearch()) {
      String like = "%" + params.getKeyword().trim() + "%";
      for (String field : params.getNumberSearchFields()) {
        if (params.isKeywordNumeric())
          spec = spec.bind("num_exact_" + sanitize(field), Long.parseLong(params.getKeyword().trim()));
        spec = spec.bind("num_like_" + sanitize(field), like);
      }
    }

    if (params.hasFtsSearch())
      spec = spec.bind("fts_query", params.getFtsQuery());

    for (Map.Entry<String, Object> e : params.getFilters().entrySet())
      spec = spec.bind("filter_" + e.getKey(), e.getValue());

    for (Map.Entry<String, Collection<?>> e : params.getInFilters().entrySet()) {
      int i = 0;
      for (Object v : e.getValue()) spec = spec.bind("in_" + e.getKey() + "_" + i++, v);
    }

    for (Map.Entry<String, DateRange> e : params.getDateRanges().entrySet()) {
      DateRange range = e.getValue();
      if (range.getStartDate() != null) spec = spec.bind("start_" + e.getKey(), range.getStartDate());
      if (range.getEndDate()   != null) spec = spec.bind("end_"   + e.getKey(), range.getEndDate());
    }

    if (params.hasColumnFilters()) {
      List<FilterDTO> filters = resolveColumnFilters(params, audit);
      ColumnFilterClause clause = new ColumnFilterClause(filters, params.getAllowedFilterFields());
      spec = clause.bind(spec);
    }
    return spec;
  }

  // ─── Shared helpers ───────────────────────────────────────────────────────

  /** True when audit is null, role is null, or role is in PRIVILEGED_ROLES. */
  private boolean isPrivileged(AuditDTO audit) {
    return audit == null
        || audit.getRoleType() == null
        || PRIVILEGED_ROLES.contains(audit.getRoleType().name());
  }

  /** Adds value to list only when non-null and non-blank. */
  private void addIfPresent(List<String> list, String value) {
    if (value != null && !value.isBlank()) list.add(value);
  }

  /**
   * Builds a complete {@code CASE id WHEN … THEN … ELSE N END} expression
   * for floating pinned rows first in ORDER BY. Long values are inlined —
   * no injection risk.
   */
  private String buildPinnedCase(List<Long> ids) {
    StringBuilder sb = new StringBuilder("CASE id ");
    for (int i = 0; i < ids.size(); i++)
      sb.append("WHEN ").append(ids.get(i)).append(" THEN ").append(i).append(" ");
    sb.append("ELSE ").append(ids.size()).append(" END");
    return sb.toString();
  }

  private Mono<Long> getCachedCount(String cacheKey, DatabaseClient.GenericExecuteSpec countSpec) {
    long[] cached = COUNT_CACHE.get(cacheKey);
    if (cached != null && System.currentTimeMillis() < cached[1]) return Mono.just(cached[0]);
    return countSpec.map((row, meta) -> row.get(0, Long.class)).one().defaultIfEmpty(0L)
        .doOnNext(count -> COUNT_CACHE.put(cacheKey,
            new long[]{count, System.currentTimeMillis() + CACHE_TTL_MS}));
  }

  private Object extractId(T item) {
    try { return item.getClass().getMethod("getId").invoke(item); }
    catch (Exception e) { return null; }
  }

  private String sanitize(String fieldName) {
    return fieldName.replaceAll("\\W", "_");
  }

  private <E> List<E> parseList(String json, Class<E> type) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return MAPPER.readValue(
          json,
          MAPPER.getTypeFactory().constructCollectionType(List.class, type)
      );
    } catch (Exception e) {
      return List.of();
    }
  }
}