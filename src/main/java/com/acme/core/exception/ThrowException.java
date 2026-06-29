package com.acme.core.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThrowException extends RuntimeException {

  private final Boolean    success;
  private final String     messageCode;
  private final String     errorDetails;
  private final transient Object[] args;

  public ThrowException(String messageCode) {
    this(false, messageCode, null, (Object) null);
  }

  public ThrowException(String messageCode, String errorDetails) {
    this(false, messageCode, errorDetails, (Object[]) null);
  }

  public ThrowException(Boolean status, String messageCode, Object... args) {
    this(status, messageCode, null, args);
  }

  public ThrowException(Boolean status, String messageCode, String errorDetails, Object... args) {
    super(messageCode);
    this.success      = status;
    this.messageCode  = messageCode;
    this.errorDetails = errorDetails;
    this.args         = args;
  }
}