package com.travelos.common;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.TimeZone;

/**
 * Flexible date parsing that mirrors JavaScript's Date constructor for the shapes this API uses:
 * full ISO timestamps ("2026-07-24T11:00:00.000Z") and date-only strings ("2026-07-24",
 * interpreted as UTC midnight).
 */
public final class DateUtil {

  private DateUtil() {
  }

  public static Date parse(String value) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("日期格式不正确");
    }
    String input = value.trim();
    if (input.matches("\\d{4}-\\d{2}-\\d{2}")) {
      return utc("yyyy-MM-dd", input);
    }
    if (input.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")) {
      return utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", normalizeMillis(input));
    }
    if (input.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:?\\d{2})")) {
      try {
        return Date.from(OffsetDateTime.parse(input).toInstant());
      } catch (Exception ex) {
        throw ApiException.badRequest("日期格式不正确");
      }
    }
    // Fallback: let the lenient parser try (e.g. "2026-07-24 11:00:00")
    try {
      SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      format.setLenient(true);
      return format.parse(input);
    } catch (ParseException ex) {
      throw ApiException.badRequest("日期格式不正确");
    }
  }

  public static Date utcDay(Date date) {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return utc("yyyy-MM-dd", format.format(date));
  }

  private static String normalizeMillis(String input) {
    int dotIndex = input.indexOf('.');
    int zIndex = input.indexOf('Z');
    String millis = input.substring(dotIndex + 1, zIndex);
    if (millis.length() == 3) {
      return input;
    }
    if (millis.length() > 3) {
      millis = millis.substring(0, 3);
    } else {
      millis = (millis + "000").substring(0, 3);
    }
    return input.substring(0, dotIndex + 1) + millis + "Z";
  }

  private static Date utc(String pattern, String input) {
    SimpleDateFormat format = new SimpleDateFormat(pattern);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    format.setLenient(false);
    try {
      return format.parse(input);
    } catch (ParseException ex) {
      throw ApiException.badRequest("日期格式不正确");
    }
  }
}
