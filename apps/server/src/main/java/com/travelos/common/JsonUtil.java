package com.travelos.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Shared Jackson mapper for service-level JSON handling (metadata, upstream APIs). */
public final class JsonUtil {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonUtil() {
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static Map<String, Object> readObject(String json) {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
      });
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
