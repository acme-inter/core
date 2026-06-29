package com.acme.core.payload.file;

import io.minio.messages.Item;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.ZoneId;

@Getter
@Builder
public class FileItemDTO {

  private String        key;
  private String        name;
  private String        type;          // "file" | "folder"
  private Long          size;
  private OffsetDateTime lastModified;
  private String        contentType;
  private String        path;
  private String        etag;

  // ─── Static factories ─────────────────────────────────────────────────────

  /** Folder entry from a common-prefix listing. */
  public static FileItemDTO folder(String objectName, String parentPrefix) {
    String folderName = extractName(objectName.endsWith("/")
        ? objectName.substring(0, objectName.length() - 1)
        : objectName);
    return FileItemDTO.builder()
        .key(objectName)
        .name(folderName)
        .type("folder")
        .size(0L)
        .lastModified(OffsetDateTime.now(ZoneId.of("Asia/Bangkok")))
        .path(parentPrefix)
        .build();
  }

  /** File entry from a MinIO {@link Item}. */
  public static FileItemDTO file(Item item, String parentPrefix) {
    return FileItemDTO.builder()
        .key(item.objectName())
        .name(extractName(item.objectName()))
        .type("file")
        .size(item.size())
        .lastModified(item.lastModified() != null
            ? item.lastModified().toOffsetDateTime()
            : OffsetDateTime.now(ZoneId.of("Asia/Bangkok")))
        .path(parentPrefix)
        .etag(item.etag())
        .build();
  }

  // ─── Helper ───────────────────────────────────────────────────────────────

  private static String extractName(String objectName) {
    if (objectName == null) return "";
    String k   = objectName.endsWith("/") ? objectName.substring(0, objectName.length() - 1) : objectName;
    int    idx = k.lastIndexOf('/');
    return idx < 0 ? k : k.substring(idx + 1);
  }
}