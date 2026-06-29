package com.acme.core.audit;

import com.acme.core.annotation.CreatedDepartment;
import com.acme.core.annotation.UpdatedDepartment;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseAudit {

  @CreatedDate
  private Instant createdAt;

  @LastModifiedDate
  private Instant updatedAt;

  @CreatedBy
  private Long createdBy;

  @LastModifiedBy
  private Long updatedBy;

  @CreatedDepartment
  private Long createdDepartment;

  @UpdatedDepartment
  private Long updatedDepartment;

  private Boolean isDeleted;
  private Instant deletedAt;
  private Long deletedBy;
}