package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelos.common.ApiException;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ReceiptFields {
  private static final Set<String> KINDS = Set.of("expense", "flight", "hotel", "train", "ticket", "other");
  private static final DateTimeFormatter LOCAL_MINUTES = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");

  private ReceiptFields() {}

  static Map<String, Object> review(JsonNode detected) {
    Map<String, String> fields = new LinkedHashMap<>();
    List<String> uncertain = new ArrayList<>();
    for (String key : List.of("kind", "title", "date", "startTime", "endTime", "startTimezone", "endTimezone",
        "amount", "currency", "subtotal", "tax")) {
      String value = detected == null ? null : sanitize(key, detected.path(key).asText(null));
      boolean flagged = false;
      if (detected != null && detected.path("uncertain").isArray()) {
        for (JsonNode item : detected.path("uncertain")) {
          flagged |= key.equals(item.asText());
        }
      }
      if (value != null) {
        fields.put(key, value);
      }
      if (flagged || value == null) {
        uncertain.add(key);
      }
    }
    if ("expense".equals(fields.get("kind")) && fields.containsKey("subtotal") && fields.containsKey("tax")
        && fields.containsKey("amount") && new BigDecimal(fields.get("subtotal")).add(new BigDecimal(fields.get("tax")))
            .subtract(new BigDecimal(fields.get("amount"))).abs().compareTo(new BigDecimal("0.02")) > 0
        && !uncertain.contains("amount")) {
      uncertain.add("amount");
    }
    return Map.of("fields", fields, "uncertain", uncertain, "recognition",
        fields.isEmpty() ? "manual" : "review");
  }

  static String required(Map<String, Object> input, String key, String label) {
    String value = optional(input, key, label);
    if (value == null) {
      throw ApiException.badRequest("请核对并填写" + label);
    }
    return value;
  }

  static String optional(Map<String, Object> input, String key, String label) {
    Object raw = input.get(key);
    if (raw == null || raw instanceof String text && text.isBlank()) {
      return null;
    }
    if (raw instanceof String text) {
      String value = sanitize(key, text);
      if (value != null) {
        return value;
      }
    }
    throw ApiException.badRequest(label + "格式有误，请核对");
  }

  static String timestamp(String value, ZoneId zone) {
    try {
      LocalDateTime local = value.length() == 10 ? LocalDate.parse(value).atStartOfDay() : LocalDateTime.parse(value);
      var offsets = zone.getRules().getValidOffsets(local);
      if (offsets.size() == 1) {
        return local.atOffset(offsets.get(0)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      }
    } catch (DateTimeException ignored) {
      // 缺失或重复的本地时间不能静默转换。
    }
    throw ApiException.badRequest("请核对日期、时间和行程时区");
  }

  private static String sanitize(String field, String raw) {
    if (raw == null || raw.length() > 200) {
      return null;
    }
    String text = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return switch (field) {
        case "kind" -> KINDS.contains(text) ? text : null;
        case "title" -> text.length() <= 120 && !text.matches("(?s).*(?:[0-9]{6,}|[\\p{Cntrl}]|https?://).*")
            ? text : null;
        case "currency" -> currency(text);
        case "startTimezone", "endTimezone" -> ZoneId.of(text).getId();
        case "amount", "subtotal", "tax" -> text.matches("[0-9]{1,9}(?:\\.[0-9]{1,2})?") ? text : null;
        case "date" -> text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? LocalDate.parse(text).toString() : null;
        case "startTime", "endTime" -> text.length() == 10 ? sanitize("date", text)
            : text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[T ][0-9]{2}:[0-9]{2}")
                ? LocalDateTime.parse(text.replace(' ', 'T')).format(LOCAL_MINUTES) : null;
        default -> null;
      };
    } catch (IllegalArgumentException | DateTimeException ex) {
      return null;
    }
  }

  private static String currency(String value) {
    String code = value.toUpperCase(Locale.ROOT);
    return code.matches("[A-Z]{3}") && Currency.getInstance(code).getDefaultFractionDigits() >= 0 ? code : null;
  }
}
