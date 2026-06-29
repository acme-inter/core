package com.acme.core.payload.file;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StorageStatsDTO {
  private long totalSizeBytes;
  private long totalFiles;
  private long totalFolders;
  private Map<String, Long> fileTypeBreakdown;
  private String path;

  public String getTotalSizeFormatted() {
    if (totalSizeBytes < 1024) return totalSizeBytes + " B";
    if (totalSizeBytes < 1024 * 1024) return String.format("%.1f KB", totalSizeBytes / 1024.0);
    if (totalSizeBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", totalSizeBytes / (1024.0 * 1024));
    return String.format("%.2f GB", totalSizeBytes / (1024.0 * 1024 * 1024));
  }
}
