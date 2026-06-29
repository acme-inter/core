package com.acme.core.payload;

import com.acme.core.payload.page.FilterDTO;
import com.acme.core.payload.page.SortDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataReqDTO {
  private String keyword;
  private List<Long> ids;
  private List<FilterDTO> filters;
  private List<SortDTO> sorts;
  private int index;
  private int size;
}
