package com.travelos.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可信代理模式下的真实 HTTP 限流验收：
 * 配置 {@code security.trusted-proxies=127.0.0.1,10.0.0.0/8} 后，
 * 按约定从 X-Forwarded-For 解析客户端地址（右起第一个非受信段），
 * 不同来源的限流相互隔离；非法转发头回退远端地址且绝不 500。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(testsupport.MutableClockConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.trusted-proxies=127.0.0.1,10.0.0.0/8",
    "security.rate-limit.login-window-seconds=60",
    "security.rate-limit.login-ip-max-attempts=100",
    "security.rate-limit.login-account-ip-max-attempts=3",
    "security.rate-limit.login-global-max-attempts=500",
    "security.rate-limit.register-window-seconds=60",
    "security.rate-limit.register-ip-max-attempts=5",
    "security.rate-limit.max-tracked-keys=2000"
})
class AuthRateLimitProxyWebTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";

  @LocalServerPort
  int port;

  @Autowired
  MutableClock clock;

  private HttpClient http;
  private String fixtureUser;
  private String fixtureToken;

  @BeforeAll
  void createFixture() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    fixtureUser = "rlproxy" + suffix;
    HttpResponse<String> res = post("/api/auth/register", null,
        "{\"username\":\"" + fixtureUser + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, res.statusCode(), res.body());
    fixtureToken = JSON.readTree(res.body()).path("token").asText();
  }

  @AfterAll
  void cleanUp() {
    try {
      post("/api/auth/deactivate", fixtureToken,
          "{\"password\":\"" + PASSWORD + "\"}");
    } catch (RuntimeException ignored) {
      // 兜底由最终 SQL 处理
    }
  }

  @BeforeEach
  void freshWindow() {
    clock.advance(Duration.ofSeconds(120));
  }

  // ==================== 请求工具 ====================

  private HttpResponse<String> send(String method, String path, String token, String body,
      String... headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    for (String h : headers) {
      int colon = h.indexOf(':');
      b.header(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
    }
    try {
      if (body != null) {
        b.header("Content-Type", "application/json");
        b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      } else {
        b.method(method, HttpRequest.BodyPublishers.noBody());
      }
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private HttpResponse<String> post(String path, String token, String body, String... headers) {
    return send("POST", path, token, body, headers);
  }

  private String loginBody(String password) {
    return "{\"username\":\"" + fixtureUser + "\",\"password\":\"" + password + "\"}";
  }

  private HttpResponse<String> loginVia(String forwarded, String password) {
    String[] headers = forwarded == null ? new String[0]
        : new String[] {"X-Forwarded-For: " + forwarded};
    return post("/api/auth/login", null, loginBody(password), headers);
  }

  private void assertUnified429(HttpResponse<String> res) throws Exception {
    assertEquals(429, res.statusCode(), "应为 429，实际 " + res.statusCode() + " body=" + res.body());
    String retryAfter = res.headers().firstValue("Retry-After").orElse(null);
    assertNotNull(retryAfter, "429 必须携带 Retry-After");
    int seconds = Integer.parseInt(retryAfter);
    assertTrue(seconds >= 1 && seconds <= 60, "Retry-After 应在 1..60 秒: " + seconds);
    JsonNode body = JSON.readTree(res.body());
    assertEquals(429, body.path("statusCode").asInt());
    assertEquals("请求过于频繁，请稍后再试", body.path("message").asText());
    assertFalse(res.body().contains(fixtureUser), "429 不得泄露账号");
  }

  private void exhaustFrom(String forwarded) {
    for (int i = 0; i < 3; i++) { // 账号+IP 上限 3
      HttpResponse<String> res = loginVia(forwarded, "Wrong-Pass-" + i);
      assertEquals(400, res.statusCode(), "阈值内应为 400: " + res.body());
    }
  }

  // ==================== 用例 ====================

  @Test
  void limitsAreKeyedByForwardedClientIp() throws Exception {
    exhaustFrom("11.11.11.11");
    assertUnified429(loginVia("11.11.11.11", "Wrong-Pass-again"));

    // 换一个来源（同一远端、不同转发地址）：账号限制不跨来源
    HttpResponse<String> otherSource = loginVia("22.22.22.22", "Wrong-Pass-x");
    assertEquals(400, otherSource.statusCode(), otherSource.body());
    // 该来源可正常登录：一个来源受限不导致账号在所有来源无法登录
    HttpResponse<String> ok = loginVia("22.22.22.22", PASSWORD);
    assertEquals(200, ok.statusCode(), ok.body());
  }

  @Test
  void rightmostUntrustedHopDeterminesClientIdentity() throws Exception {
    exhaustFrom("11.11.11.11");
    assertUnified429(loginVia("11.11.11.11", "Wrong-Pass-a"));
    // 右起第一个非受信段 = 11.11.11.11（前段 9.9.9.9 忽略）→ 命中同一把锁
    assertUnified429(loginVia("9.9.9.9, 11.11.11.11", "Wrong-Pass-b"));
    // 链中混入可信代理段（10.0.0.0/8）：跳过受信段后仍取 11.11.11.11
    assertUnified429(loginVia("10.0.0.7, 11.11.11.11", "Wrong-Pass-c"));
    // 整条链都是受信代理：取最左合法段 10.0.0.7（全新来源）→ 正常 400
    HttpResponse<String> allTrusted = loginVia("10.0.0.7, 10.0.0.8", "Wrong-Pass-d");
    assertEquals(400, allTrusted.statusCode(), allTrusted.body());
  }

  @Test
  void xRealIpUsedWhenRemoteIsTrusted() throws Exception {
    for (int i = 0; i < 3; i++) {
      HttpResponse<String> res = send("POST", "/api/auth/login", null, loginBody("Wrong-Pass-" + i),
          "X-Real-IP: 31.31.31.31");
      assertEquals(400, res.statusCode(), res.body());
    }
    HttpResponse<String> blocked = send("POST", "/api/auth/login", null, loginBody("Wrong-Pass-x"),
        "X-Real-IP: 31.31.31.31");
    assertUnified429(blocked);
  }

  @Test
  void invalidForwardedContentFallsBackWithoutServer500() throws Exception {
    // 转发头全是垃圾：回退到远端地址（127.0.0.1），不是 500、也不是凭空放行
    HttpResponse<String> garbage = loginVia("not-an-ip, , ::::bad", "Wrong-Pass-g");
    assertEquals(400, garbage.statusCode(), "非法转发头不得 500: " + garbage.body());
    assertEquals("用户名或密码错误",
        JSON.readTree(garbage.body()).path("message").asText());

    // 远端键（127.0.0.1）独立：连续失败仍按同一身份计数
    for (int i = 0; i < 2; i++) {
      assertEquals(400, loginVia(null, "Wrong-Pass-r" + i).statusCode());
    }
    HttpResponse<String> again = loginVia(null, "Wrong-Pass-r2");
    // 账号+IP(127.0.0.1) 已有 1（垃圾那次）+2 = 3 → 触顶
    assertUnified429(again);
  }
}
