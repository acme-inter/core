package com.acme.core.enums;

import lombok.Getter;

@Getter
public enum Modules {
  API("api"),
  USER("user"),
  ADMIN("admin"),       // same service as USER
  ACCOUNT("account"),
  ERP("erp"),
  CRM("crm"),
  CHECKLIST("checklist"),
  DOOR_RECORD("door_record"),
  SYSTEM("system"),      // system events go to user service
  CAR_CHECKLIST("car_checklist"),
  HR("hr"),
  CASE("case"),
  VEHICLE("vehicle"),
  MANAGEMENT("management");

  private final String service;
  Modules(String service) { this.service = service; }
}