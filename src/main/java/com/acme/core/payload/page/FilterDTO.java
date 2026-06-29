package com.acme.core.payload.page;

import com.acme.core.enums.FilterOperator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterDTO {
  private String field;
  private String op;
  private Object value;
  private List<Object> values;

  public FilterOperator operator() {
    if (op == null) throw new IllegalArgumentException(
        "Filter on field '" + field + "' is missing the 'op' property.");
    try {
      return FilterOperator.valueOf(op.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown filter operator '" + op + "' on field '" + field + "'. "
              + "Valid operators: " + java.util.Arrays.toString(FilterOperator.values()));
    }
  }

  public boolean hasValue()  { return value  != null; }
  public boolean hasValues() { return values != null && !values.isEmpty(); }
}