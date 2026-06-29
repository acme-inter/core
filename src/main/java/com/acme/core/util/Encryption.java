package com.acme.core.util;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class Encryption {

  public String encodeKey(String input) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
  }

  public String decodeKey(String input) {
    byte[] decodedBytes = Base64.getUrlDecoder().decode(input);
    return new String(decodedBytes, StandardCharsets.UTF_8);
  }
}