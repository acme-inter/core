package com.acme.core.payload.agent;

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
public class AgentDTO {

  private String  ip;

  // ── Geo ───────────────────────────────────────────────────
  private GeoDTO  geo;

  // ── Browser / client ──────────────────────────────────────
  private String  browser;
  private String  browserVersion;
  private String  browserVersionMajor;

  // ── Operating system ──────────────────────────────────────
  private String  os;
  private String  osVersion;
  private String  osVersionMajor;

  // ── Device ────────────────────────────────────────────────
  private String  deviceType;
  private String  deviceBrand;

  // ── Classification flags ──────────────────────────────────
  private boolean isBot;
  private boolean isSuspicious;
  private boolean isApiClient;
  private boolean isHacker;

  // ── Threat detail (non-null only when isHacker = true) ────
  private String  hackerAttackVector;
  private String  hackerToolkit;
}