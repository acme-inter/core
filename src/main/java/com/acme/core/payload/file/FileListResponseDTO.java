package com.acme.core.payload.file;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileListResponseDTO {
  private List<FileItemDTO> items;
  private int totalItems;
  private int page;
  private int size;
  private int totalPages;
  private String currentPath;
}
