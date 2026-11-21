package com.travelos.controller;

import com.travelos.common.EnvLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康端点契约（真实 Spring 上下文 + 随机端口 + 真实库，随本地 .env / CI 环境变量接入数据源）：
 * - /api/health：200 + 服务标识（存活检查不探库）；
 * - /api/health/ready：库可连 + 关键结构齐 → 200；release 标识经 travelos.release.id 注入时回显。
 * 「库不可用 / 结构缺失 → 503」的状态机分支由 ReadinessServiceTest 以模拟数据源覆盖
 * （避免为模拟故障破坏共享数据源），此处验证真实链路的 200 语义与字段契约。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "travelos.release.id=release-test-001"
})
@AutoConfigureMockMvc
class ReadinessEndpointWebTest {

  static {
    // 与 TravelOsApplication.main 相同的数据源引导（本地 .env / CI 环境变量二选一生效）
    EnvLoader.loadDotEnv();
  }

  @Autowired
  private MockMvc mockMvc;

  @Test
  void livenessReturnsServiceIdentityAndRelease() throws Exception {
    String body = mockMvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.service").value("travel-os-api"))
        .andExpect(jsonPath("$.release").value("release-test-001"))
        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(body.contains("travel-os-api"), "响应含服务标识");
  }

  @Test
  void readinessReturnsOkAgainstPreparedDatabase() throws Exception {
    mockMvc.perform(get("/api/health/ready"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.service").value("travel-os-api"))
        .andExpect(jsonPath("$.database").value("ok"))
        .andExpect(jsonPath("$.schema").value("ok"))
        .andExpect(jsonPath("$.release").value("release-test-001"));
  }

  @Test
  void responsesCarryNoConnectionDetails() throws Exception {
    for (String path : new String[]{"/api/health", "/api/health/ready"}) {
      String body = mockMvc.perform(get(path)).andReturn().getResponse()
          .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      assertFalse(body.contains("jdbc:"), "健康响应不得包含连接串");
      assertFalse(body.toLowerCase().contains("password"), "健康响应不得包含口令字段");
      assertFalse(body.contains("SELECT"), "健康响应不得包含 SQL");
    }
  }

  private static void assertTrue(boolean cond, String msg) {
    org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
  }

  private static void assertFalse(boolean cond, String msg) {
    org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
  }
}
