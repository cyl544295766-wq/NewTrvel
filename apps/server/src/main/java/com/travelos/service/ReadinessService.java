package com.travelos.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布验收用的**就绪检查**（区别于 /api/health 的存活检查）：
 * 数据库可连接 + 本版本运行所需的关键表/列存在。任一不满足即「未就绪」，
 * 由 HealthController 以 503 对外表达（HTTP 层面可被脚本判定，而不是 200 JSON 里写 error）。
 *
 * <p>能力边界（如实声明）：本检查只证明「库可连 + 关键结构存在」，
 * 等价于对 schema 的抽查，**不是**业务健康验证（不校验数据正确性、行数、业务规则）。</p>
 *
 * <p>有界超时：对注入的 JdbcTemplate 统一设置 {@link #QUERY_TIMEOUT_SECONDS}；
 * 该 bean 当前仅健康检查使用（见 HealthController），不影响业务查询路径。
 * 检查项失败时不向外返回 SQL/异常细节（只返回分类原因），避免泄露内部结构或连接信息。</p>
 */
@Service
public class ReadinessService {

  /** 单次查询超时（秒）：发布验收需要快速失败，而非挂起等待。 */
  static final int QUERY_TIMEOUT_SECONDS = 3;

  /**
   * 本版本运行所需的关键表。与 db/migration 迁移集对齐（抽查代表性核心表，
   * 覆盖用户/行程/记账/通知/受保护文件/AI 治理与幂等）。
   */
  static final List<String> REQUIRED_TABLES = List.of(
      "users", "trips", "trip_days", "trip_places", "trip_expenses", "trip_members",
      "packing_lists", "photos", "notifications", "edit_locks", "file_metadata",
      "ai_conversations", "ai_messages", "ai_request_keys");

  /**
   * 关键列抽查：近期迁移新增、且运行路径直接依赖的列（草稿租约/幂等执行令牌/行级属主/软删）。
   * 缺列说明迁移没跑全，旧结构跑新 JAR 会在运行期出错。
   */
  static final List<String> REQUIRED_COLUMNS = List.of(
      "users.deleted_at",
      "file_metadata.owner_user_id",
      "ai_messages.draft_apply_token",
      "ai_messages.draft_apply_started_at",
      "ai_messages.draft_apply_result",
      "ai_request_keys.execution_token");

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ReadinessService.class);

  private final JdbcTemplate jdbc;

  public ReadinessService(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
    this.jdbc = jdbcTemplate;
    // 有界超时：只对该健康检查 bean 生效（避免某条检查把发布验收挂住）
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  /** 单项检查结果：ok=true 才算就绪；reason 为固定分类词，不含 SQL/异常细节。 */
  public record CheckResult(boolean ready, boolean databaseOk, boolean schemaOk, String reason) {
  }

  /** 执行就绪检查（每次调用实时查询）。 */
  public CheckResult check() {
    boolean dbOk;
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      dbOk = true;
    } catch (Exception dbEx) {
      // 服务端日志只记异常类名，不打印消息（消息可能含连接串）；对外响应不含任何细节
      log.warn("readiness: database unavailable ({})", dbEx.getClass().getSimpleName());
      return new CheckResult(false, false, false, "database_unavailable");
    }
    try {
      String tableList = joinQuoted(REQUIRED_TABLES);
      Integer tables = jdbc.queryForObject(
          "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
              + "AND table_name IN (" + tableList + ")", Integer.class);
      Integer columns = jdbc.queryForObject(
          "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
              + "AND table_name || '.' || column_name IN (" + quotedColumnList() + ")", Integer.class);
      boolean schemaOk = tables != null && tables == REQUIRED_TABLES.size()
          && columns != null && columns == REQUIRED_COLUMNS.size();
      if (!schemaOk) {
        log.warn("readiness: schema incomplete (tables={}, columns={}, expected {}/{})",
            tables, columns, REQUIRED_TABLES.size(), REQUIRED_COLUMNS.size());
        return new CheckResult(false, true, false, "schema_incomplete");
      }
      return new CheckResult(true, true, true, "ok");
    } catch (Exception schemaEx) {
      log.warn("readiness: schema check failed ({})", schemaEx.getClass().getSimpleName());
      return new CheckResult(false, true, false, "schema_check_failed");
    }
  }

  private static String joinQuoted(List<String> names) {
    StringBuilder sb = new StringBuilder();
    for (String n : names) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append('\'').append(n.replace("'", "''")).append('\'');
    }
    return sb.toString();
  }

  private static String quotedColumnList() {
    StringBuilder sb = new StringBuilder();
    for (String c : REQUIRED_COLUMNS) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append('\'').append(c.replace("'", "''")).append('\'');
    }
    return sb.toString();
  }

  /** 供日志/诊断使用（不含敏感信息）。 */
  public Map<String, Object> snapshot(CheckResult r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ready", r.ready());
    m.put("databaseOk", r.databaseOk());
    m.put("schemaOk", r.schemaOk());
    m.put("reason", r.reason());
    return m;
  }
}
