package com.travelos.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 就绪检查单测：正常 / 数据库不可用 / 关键结构缺失 / 结构检查异常。
 * JdbcTemplate 以 Mockito 模拟，不依赖真实数据库；行为契约与 HealthController 的 503 语义对应。
 */
class ReadinessServiceTest {

  private static final String TABLES_SQL_PART = "information_schema.tables";
  private static final String COLUMNS_SQL_PART = "information_schema.columns";

  /** 按被查 SQL（SELECT 1 / tables / columns）单桩分派：避免 later-stub 覆盖精确打桩。 */
  private JdbcTemplate templateWith(Integer selectOne, Integer tables, Integer columns) {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(inv -> {
      String sql = inv.getArgument(0);
      if ("SELECT 1".equals(sql)) {
        if (selectOne == null) {
          throw new DataAccessResourceFailureException("simulated db down");
        }
        return selectOne;
      }
      Integer v = sql.contains(TABLES_SQL_PART) ? tables : sql.contains(COLUMNS_SQL_PART) ? columns : null;
      if (v == null) {
        throw new QueryTimeoutException("simulated timeout");
      }
      return v;
    });
    return jdbc;
  }

  @Test
  void readyWhenDatabaseUpAndSchemaComplete() {
    ReadinessService s = new ReadinessService(
        templateWith(1, ReadinessService.REQUIRED_TABLES.size(), ReadinessService.REQUIRED_COLUMNS.size()));
    ReadinessService.CheckResult r = s.check();
    assertTrue(r.ready(), "库可连+结构齐 → ready");
    assertTrue(r.databaseOk());
    assertTrue(r.schemaOk());
    assertEquals("ok", r.reason());
  }

  @Test
  void downWhenDatabaseUnavailable() {
    ReadinessService s = new ReadinessService(templateWith(null, null, null));
    ReadinessService.CheckResult r = s.check();
    assertFalse(r.ready());
    assertFalse(r.databaseOk());
    assertFalse(r.schemaOk());
    assertEquals("database_unavailable", r.reason());
    // 对外 reason 必须是固定分类词，不携带异常细节/SQL
    assertFalse(r.reason().contains("SELECT"));
  }

  @Test
  void schemaIncompleteWhenRequiredTablesMissing() {
    ReadinessService s = new ReadinessService(
        templateWith(1, ReadinessService.REQUIRED_TABLES.size() - 2, ReadinessService.REQUIRED_COLUMNS.size()));
    ReadinessService.CheckResult r = s.check();
    assertFalse(r.ready(), "关键表缺失 → 未就绪");
    assertTrue(r.databaseOk(), "库本身可连");
    assertFalse(r.schemaOk());
    assertEquals("schema_incomplete", r.reason());
  }

  @Test
  void schemaIncompleteWhenRequiredColumnsMissing() {
    ReadinessService s = new ReadinessService(
        templateWith(1, ReadinessService.REQUIRED_TABLES.size(), ReadinessService.REQUIRED_COLUMNS.size() - 1));
    ReadinessService.CheckResult r = s.check();
    assertFalse(r.ready());
    assertFalse(r.schemaOk());
    assertEquals("schema_incomplete", r.reason());
  }

  @Test
  void schemaCheckFailureMapsToFixedReason() {
    ReadinessService s = new ReadinessService(templateWith(1, null, null));
    ReadinessService.CheckResult r = s.check();
    assertFalse(r.ready());
    assertTrue(r.databaseOk());
    assertEquals("schema_check_failed", r.reason());
  }

  @Test
  void queriesAreBoundedByTimeout() {
    JdbcTemplate jdbc = templateWith(1, ReadinessService.REQUIRED_TABLES.size(), ReadinessService.REQUIRED_COLUMNS.size());
    new ReadinessService(jdbc);
    verify(jdbc).setQueryTimeout(ReadinessService.QUERY_TIMEOUT_SECONDS);
  }
}
