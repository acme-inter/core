package com.acme.core.payload;

public record GuestPrincipal(
    String ip,
    String countryCode,
    String countryName,
    String city
) {
  public boolean hasGeo() {
    return countryCode != null;
  }
}