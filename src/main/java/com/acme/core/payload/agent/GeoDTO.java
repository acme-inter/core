package com.acme.core.payload.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeoDTO {

  private String  countryCode;      // "TH"
  private String  countryName;      // "Thailand"
  private String  subdivision;      // "Bangkok", "Chiang Mai Province" …
  private String  city;             // "Bangkok"
  private String  timezone;         // "Asia/Bangkok"
  private Double  latitude;         // 13.7563
  private Double  longitude;        // 100.5018
  private Integer accuracyRadius;   // km — how precise the coords are

  public static GeoDTO empty() {
    return new GeoDTO();
  }

  public boolean hasCountry() {
    return countryCode != null;
  }
}