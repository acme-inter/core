package com.acme.core.payload.role;

import com.acme.core.enums.Modules;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PermissionElementDTO {
  private Long id;
  private Modules module;
  private Boolean canAdd;
  private Boolean canEdit;
  private Boolean canDelete;
  private Boolean canSync;
  private Boolean canImport;
  private Boolean canExport;
  private Boolean canDownload;
  private Boolean canComment;
}