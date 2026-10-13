package com.travelos.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

  private final JdbcTemplate jdbcTemplate;

  public HealthController(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping
  public Map<String, Object> health() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ok");
    body.put("service", "travel-os-api");
    body.put("timestamp", now());
    return body;
  }

  @GetMapping("/database")
  public Map<String, Object> databaseHealth() {
    Map<String, Object> body = new LinkedHashMap<>();
    try {
      jdbcTemplate.queryForObject("SELECT 1", Integer.class);
      body.put("status", "ok");
      body.put("database", "connected");
      body.put("timestamp", now());
    } catch (Exception ex) {
      body.put("status", "error");
      body.put("database", "disconnected");
      body.put("message", "Database connection failed");
      body.put("timestamp", now());
    }
    return body;
  }

  private String now() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
  }
}
