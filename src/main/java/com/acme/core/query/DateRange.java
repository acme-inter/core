package com.acme.core.query;

import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
public class DateRange {

  private final Object startDate;
  private final Object endDate;

  private DateRange(Object startDate, Object endDate) {
    this.startDate = startDate;
    this.endDate   = endDate;
  }

  // ─── LocalDate ───────────────────────────────────────────────────────────
  public static DateRange between(LocalDate start, LocalDate end) { return new DateRange(start, end); }
  public static DateRange from(LocalDate start)                   { return new DateRange(start, null); }
  public static DateRange until(LocalDate end)                    { return new DateRange(null, end); }

  // ─── LocalDateTime ───────────────────────────────────────────────────────
  public static DateRange between(LocalDateTime start, LocalDateTime end) { return new DateRange(start, end); }
  public static DateRange from(LocalDateTime start)                       { return new DateRange(start, null); }
  public static DateRange until(LocalDateTime end)                        { return new DateRange(null, end); }

  // ─── Instant ─────────────────────────────────────────────────────────────
  public static DateRange between(Instant start, Instant end) { return new DateRange(start, end); }
  public static DateRange from(Instant start)                 { return new DateRange(start, null); }
  public static DateRange until(Instant end)                  { return new DateRange(null, end); }
}