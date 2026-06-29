package com.acme.core.util;

import com.acme.core.annotation.MsgParam;
import com.acme.core.payload.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class MsgUtil {

  private final MessageSource messageSource;

  // {key} placeholder pattern
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

  // ══════════════════════════════════════════════════════════════════════════
  // LOCALE
  // ══════════════════════════════════════════════════════════════════════════
  public Mono<Locale> currentLocale() {
    return ReactiveSecurityContextHolder.getContext()
        .mapNotNull(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .mapNotNull(auth -> {
          Object principal = auth.getPrincipal();
          if (principal instanceof MemberPrincipal mp) {
            String lang = mp.lang();
            if (lang != null && !lang.isBlank()) return buildLocale(lang);
          }
          return null;
        })
        .defaultIfEmpty(Locale.ENGLISH);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // GET — positional args  (existing behaviour, keep for DbUtil etc.)
  // ══════════════════════════════════════════════════════════════════════════

  /** {@code msgUtil.get("delete.success")} */
  public Mono<String> get(String code) {
    return currentLocale().map(locale -> resolve(code, locale));
  }

  /** {@code msgUtil.get("delete.success", entityName)} */
  public Mono<String> get(String code, Object... args) {
    return currentLocale().map(locale -> resolveArgs(code, args, locale));
  }

  /** Synchronous — when locale is already known. */
  public String get(String code, Locale locale) {
    return resolve(code, locale);
  }

  /** Synchronous with args — when locale is already known. */
  public String get(String code, Locale locale, Object... args) {
    return resolveArgs(code, args, locale);
  }

  public Mono<String> get(String code, Mono<Locale> localeMono) {
    return localeMono.map(locale -> resolve(code, locale));
  }

  /** Pre-cached locale + positional args. */
  public Mono<String> get(String code, Mono<Locale> localeMono, Object... args) {
    return localeMono.map(locale -> resolveArgs(code, args, locale));
  }

  public Mono<String> get(String code, Map<String, Object> params) {
    return currentLocale().map(locale -> interpolate(resolve(code, locale), params));
  }

  /** Named placeholders with a pre-cached locale. */
  public Mono<String> get(String code, Mono<Locale> localeMono, Map<String, Object> params) {
    return localeMono.map(locale -> interpolate(resolve(code, locale), params));
  }

  /** Synchronous named placeholders — when locale is already known. */
  public String get(String code, Locale locale, Map<String, Object> params) {
    return interpolate(resolve(code, locale), params);
  }

  public String filter(String code, String lang) {
    return resolve(code, lang != null && !lang.isBlank()
        ? buildLocale(lang) : Locale.ENGLISH);
  }

  /** Filter-layer message with positional args. */
  public String filter(String code, String lang, Object... args) {
    return resolveArgs(code, args, lang != null && !lang.isBlank()
        ? buildLocale(lang) : Locale.ENGLISH);
  }

  /** Filter-layer message with named placeholders. */
  public String filter(String code, String lang, Map<String, Object> params) {
    String template = resolve(code, lang != null && !lang.isBlank()
        ? buildLocale(lang) : Locale.ENGLISH);
    return interpolate(template, params);
  }

  public Mono<String> get(String code, Object dto) {
    return currentLocale()
        .map(locale -> interpolate(resolve(code, locale), extract(dto)));
  }

  /** Auto-extract with pre-cached locale. */
  public Mono<String> get(String code, Mono<Locale> localeMono, Object dto) {
    return localeMono
        .map(locale -> interpolate(resolve(code, locale), extract(dto)));
  }

  /** Synchronous auto-extract — when locale is already known. */
  public String get(String code, Locale locale, Object dto) {
    return interpolate(resolve(code, locale), extract(dto));
  }

  public static Map<String, Object> extract(Object dto) {
    if (dto == null) return Map.of();
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    Class<?> clazz = dto.getClass();
    while (clazz != null && clazz != Object.class) {
      for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
        MsgParam ann = field.getAnnotation(MsgParam.class);
        if (ann == null) continue;
        String key = ann.value().isBlank() ? field.getName() : ann.value();
        field.setAccessible(true);
        try {
          Object value = field.get(dto);
          params.put(key, value != null ? value : "");
        } catch (IllegalAccessException e) {
          //
        }
      }
      clazz = clazz.getSuperclass();
    }
    return params;
  }

  private static String interpolate(String template, Map<String, Object> params) {
    if (template == null || params == null || params.isEmpty()) return template;
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String key         = m.group(1);
      Object replacement = params.get(key);
      m.appendReplacement(sb,
          replacement != null
              ? Matcher.quoteReplacement(replacement.toString())
              : m.group(0));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private String resolve(String code, Locale locale) {
    try {
      return messageSource.getMessage(code, null, normalizeLocale(locale));
    } catch (Exception e) {
      return code;
    }
  }

  private String resolveArgs(String code, Object[] args, Locale locale) {
    try {
      return messageSource.getMessage(
          new DefaultMessageSourceResolvable(new String[]{code}, args),
          normalizeLocale(locale));
    } catch (Exception e) {
      return code;
    }
  }

  private static Locale buildLocale(String lang) {
    try {
      return new Locale.Builder().setLanguage(lang.toLowerCase()).build();
    } catch (Exception e) {
      return Locale.ENGLISH;
    }
  }

  private static Locale normalizeLocale(Locale locale) {
    if (locale == null) return Locale.ENGLISH;
    try {
      return new Locale.Builder().setLanguage(locale.getLanguage().toLowerCase()).build();
    } catch (Exception e) {
      return Locale.ENGLISH;
    }
  }
}