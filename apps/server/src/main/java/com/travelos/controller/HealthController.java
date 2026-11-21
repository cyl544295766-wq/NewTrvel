package com.travelos.controller;

import com.travelos.service.ReadinessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private final ReadinessService readinessService;

  /**
   * 发布版本标识：由 systemd 的 EnvironmentFile（/opt/travel-os/release.env，键
   * TRAVEL_OS_RELEASE_ID）在启动时注入（环境变量经 relaxed binding 映射到本属性）。
   * 环境变量在进程启动时固定——之后文件被改不会改变本进程上报值，因此
   * 「/api/health 的 release == 目标发布 ID」能证明当前运行的 JAR 就是刚发布的那个。
   * 本地/未配置时为空串，/api/health 不输出 release 字段。
   */
  private final String releaseId;

  public HealthController(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
      @Autowired ReadinessService readinessService,
      @Value("${travelos.release.id:}") String releaseId) {
    this.jdbcTemplate = jdbcTemplate;
    this.readinessService = readinessService;
    this.releaseId = releaseId == null ? "" : releaseId.trim();
  }

  /** 存活检查（liveness）：进程在、服务标识正确。始终 200，不探数据库。 */
  @GetMapping
  public Map<String, Object> health() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ok");
    body.put("service", "travel-os-api");
    if (!releaseId.isEmpty()) {
      body.put("release", releaseId);
    }
    body.put("timestamp", now());
    return body;
  }

  /**
   * 就绪检查（readiness，发布验收用）：数据库可连 + 关键表/列存在，任一不满足返回 503。
   * 响应只含状态分类（ok / database_unavailable / schema_incomplete / schema_check_failed），
   * 不包含连接串、账号、SQL 或异常详情。
   * 能力边界：只证明「可连 + 结构就绪」，不等价于业务健康验证。
   */
  @GetMapping("/ready")
  public ResponseEntity<Map<String, Object>> ready() {
    ReadinessService.CheckResult r = readinessService.check();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", r.ready() ? "ok" : "error");
    body.put("service", "travel-os-api");
    body.put("database", r.databaseOk() ? "ok" : "unavailable");
    body.put("schema", r.ready() ? "ok" : (r.databaseOk() ? "incomplete" : "unknown"));
    if (!releaseId.isEmpty()) {
      body.put("release", releaseId);
    }
    body.put("timestamp", now());
    return r.ready()
        ? ResponseEntity.ok(body)
        : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }

  /** 数据库连通性诊断（历史端点，保留兼容；诊断态用 JSON 表达，脚本判定请用 /ready）。 */
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
