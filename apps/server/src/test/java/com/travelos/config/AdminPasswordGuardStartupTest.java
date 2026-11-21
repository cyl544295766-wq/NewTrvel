package com.travelos.config;

import com.travelos.TravelOsApplication;
import com.travelos.common.EnvLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产配置下管理员口令守卫的**真实启动**行为（SpringApplicationBuilder 全量上下文）。
 *
 * <p>测试覆盖值通过 {@code addFirst} 属性源注入——优先级高于 application.yml 与
 * 环境变量（SpringApplicationBuilder.properties() 落在 defaultProperties，会被
 * application.yml 覆盖，不能用于本测试）。使用独立探针管理员用户名，避免依赖共享
 * 数据库中 admin 账户的实际口令状态；失败方向不产生写入，成功方向当场删除探针账户。</p>
 */
class AdminPasswordGuardStartupTest {

  static {
    // 与 TravelOsApplication.main 相同的数据源引导（不读取/输出内容）
    EnvLoader.loadDotEnv();
  }

  private static final List<String> CREATED_USERS = new ArrayList<>();
  private static final String SUFFIX =
      String.format("%09d", Math.abs(ThreadLocalRandom.current().nextLong(1_000_000_000L)));

  @AfterAll
  static void cleanup() {
    if (CREATED_USERS.isEmpty()) {
      return;
    }
    Map<String, Object> overrides = new HashMap<>();
    overrides.put("seed.allow-default-admin-password", "true"); // 清理上下文放行弱口令告警
    overrides.put("jwt.allow-default-secret", "true");
    try (ConfigurableApplicationContext ctx = newSpring(overrides).run()) {
      JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
      for (String username : CREATED_USERS) {
        jdbc.update("DELETE FROM users WHERE username = ?", username);
      }
    } catch (RuntimeException ignored) {
      // 清理失败由最终 SQL 兜底
    }
  }

  /** 起一个不起端口的完整应用上下文（Runner 顺序与生产一致），覆盖值以最高优先级注入。 */
  private static SpringApplicationBuilder newSpring(Map<String, Object> overrides) {
    StandardEnvironment environment = new StandardEnvironment();
    Map<String, Object> props = new HashMap<>(overrides);
    props.put("server.port", "0");
    props.put("jwt.allow-default-secret", "true");
    environment.getPropertySources().addFirst(new MapPropertySource("guardTestOverrides", props));
    return new SpringApplicationBuilder(TravelOsApplication.class)
        .web(WebApplicationType.NONE) // 不起端口，只跑启动链路
        .environment(environment);
  }

  private static String describe(Throwable ex) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = ex; t != null && sb.length() < 4000; t = t.getCause()) {
      sb.append(t.getClass().getName()).append(':').append(t.getMessage()).append('\n');
    }
    return sb.toString();
  }

  @Test
  void productionStartupRejectsDefaultAdminPassword() {
    String probe = ("guardweak" + SUFFIX).substring(0, 18);
    Map<String, Object> overrides = Map.of(
        "seed.admin-username", probe,
        "seed.admin-password", "admin123",
        "seed.allow-default-admin-password", "false");
    Throwable thrown = assertThrows(Throwable.class, () -> newSpring(overrides).run());
    String chain = describe(thrown);
    assertTrue(chain.contains("SEED_ADMIN_PASSWORD"),
        "启动失败提示应指向环境变量，实际：" + chain);
    assertFalse(chain.contains("admin123"), "启动失败信息不得包含口令：" + chain);
    assertFalse(CREATED_USERS.contains(probe));
  }

  @Test
  void productionStartupSucceedsWithStrongAdminPassword() {
    String probe = ("guardok" + SUFFIX).substring(0, 16);
    CREATED_USERS.add(probe);
    Map<String, Object> overrides = Map.of(
        "seed.admin-username", probe,
        "seed.admin-password", "Str0ng-Probe-2026-x9",
        "seed.allow-default-admin-password", "false");
    ConfigurableApplicationContext ctx = newSpring(overrides).run();
    assertNotNull(ctx, "生产 + 强口令应启动成功");
    try {
      JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
      Integer count = jdbc.queryForObject(
          "SELECT count(*) FROM users WHERE username = ?", Integer.class, probe);
      assertEquals(1, count, "生产 + 强口令应正常初始化管理员");
      String hash = jdbc.queryForObject(
          "SELECT password_hash FROM users WHERE username = ?", String.class, probe);
      assertNotNull(hash);
      assertFalse(hash.contains("Str0ng-Probe"), "口令必须以哈希存储");
    } finally {
      CREATED_USERS.remove(probe);
      JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
      jdbc.update("DELETE FROM users WHERE username = ?", probe);
      ctx.close();
    }
  }
}
