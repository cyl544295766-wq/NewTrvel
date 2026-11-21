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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录/注册限流的**真实 HTTP 验收**（完整 Spring Boot + 随机端口 + 真实 HTTP 请求）。
 *
 * <p>阈值通过测试配置缩小；窗口过期用 {@link MutableClock} 推进（CI 不真实睡眠）。
 * 配置为「未配置可信代理」（默认）：伪造 X-Forwarded-For 不得绕过。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(testsupport.MutableClockConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.trusted-proxies=",
    "security.rate-limit.login-window-seconds=60",
    "security.rate-limit.login-ip-max-attempts=10",
    "security.rate-limit.login-account-ip-max-attempts=4",
    "security.rate-limit.login-global-max-attempts=500",
    "security.rate-limit.register-window-seconds=60",
    "security.rate-limit.register-ip-max-attempts=4",
    "security.rate-limit.max-tracked-keys=2000"
})
class AuthRateLimitWebTest {

  static {
    EnvLoader.loadDotEnv(); // 与 TravelOsApplication.main 相同的数据源引导
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";

  @LocalServerPort
  int port;

  @Autowired
  MutableClock clock;

  @Autowired
  AuthRateLimiter limiter;

  private HttpClient http;
  private String fixtureUser;
  private String fixtureToken;
  private String tripId;
  private String photoId;
  private String photoUrl;
  /** 本测试创建的账号（用户名 + token），结束时注销清理。 */
  private final List<String[]> createdAccounts = new ArrayList<>();

  @BeforeAll
  void createFixtures() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    fixtureUser = named("rlfix");
    fixtureToken = register(fixtureUser);

    // 造一张可校验的照片文件（供「限流不影响已登录文件访问」用）
    tripId = text(post("/api/trips", fixtureToken,
        "{\"title\":\"rl fixture\",\"destination\":\"t\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-02\"}"))
        .path("trip").path("id").asText();
    byte[] payload = "RL-WEB-PAYLOAD-1234567890".getBytes(StandardCharsets.UTF_8);
    String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(payload);
    var photo = text(post("/api/trips/" + tripId + "/photos", fixtureToken,
        "{\"url\":\"" + dataUrl + "\",\"caption\":\"rl\"}")).path("photo");
    photoId = photo.path("id").asText();
    photoUrl = photo.path("url").asText();
  }

  @AfterAll
  void cleanUp() {
    // 先删行程与照片（需 fixture 会话），再注销全部账号；失败由最终 SQL 兜底
    try {
      if (tripId != null && photoId != null) {
        call("DELETE", "/api/trips/" + tripId + "/photos/" + photoId, fixtureToken, null);
      }
      if (tripId != null) {
        call("DELETE", "/api/trips/" + tripId, fixtureToken, null);
      }
    } catch (RuntimeException ignored) {
      // 忽略
    }
    for (String[] account : createdAccounts) {
      try {
        call("POST", "/api/auth/deactivate", account[1],
            "{\"password\":\"" + PASSWORD + "\"}");
      } catch (RuntimeException ignored) {
        // 忽略
      }
    }
  }

  @BeforeEach
  void freshWindow() {
    clock.advance(Duration.ofSeconds(120)); // 跨过登录(60s)与注册(60s)窗口：每个用例从干净计数开始
  }

  // ==================== 请求工具 ====================

  private HttpRequest.Builder req(String path, String token) {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return b;
  }

  private HttpResponse<String> send(HttpRequest.Builder builder, String method, String body) {
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private HttpResponse<String> post(String path, String token, String body) {
    return send(req(path, token), "POST", body);
  }

  private HttpResponse<String> get(String path, String token) {
    return send(req(path, token), "GET", null);
  }

  private HttpResponse<String> call(String method, String path, String token, String body) {
    return send(req(path, token), method, body);
  }

  private HttpResponse<String> head(String path, String token) {
    return send(req(path, token), "HEAD", null);
  }

  private JsonNode text(HttpResponse<String> res) {
    try {
      return JSON.readTree(res.body() == null ? "" : res.body());
    } catch (Exception ex) {
      throw new IllegalStateException("非 JSON 响应: " + res.body(), ex);
    }
  }

  private String register(String username) throws Exception {
    HttpResponse<String> res = post("/api/auth/register", null,
        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, res.statusCode(), "注册失败: " + res.body());
    String token = text(res).path("token").asText();
    createdAccounts.add(new String[] {username, token});
    return token;
  }

  private HttpResponse<String> login(String username, String password, String... extraHeaders) {
    HttpRequest.Builder b = req("/api/auth/login", null);
    for (String h : extraHeaders) {
      b.header(h.substring(0, h.indexOf(':')), h.substring(h.indexOf(':') + 1).trim());
    }
    return send(b, "POST", "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
  }

  private String named(String prefix) {
    String suffix = String.format("%09d",
        Math.abs(ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    String name = prefix + suffix;
    return name.length() > 18 ? name.substring(0, 18) : name;
  }

  private void assertUnified429(HttpResponse<String> res) {
    assertEquals(429, res.statusCode(), "应为 429，实际 " + res.statusCode() + " body=" + res.body());
    String retryAfter = res.headers().firstValue("Retry-After").orElse(null);
    assertNotNull(retryAfter, "429 必须携带 Retry-After");
    int seconds = Integer.parseInt(retryAfter);
    assertTrue(seconds >= 1 && seconds <= 60, "Retry-After 应在 1..60 秒: " + seconds);
    JsonNode body = text(res);
    assertEquals(429, body.path("statusCode").asInt());
    assertEquals("Too Many Requests", body.path("error").asText());
    assertEquals("请求过于频繁，请稍后再试", body.path("message").asText());
    assertFalse(body.path("message").asText().contains("rlfix"), "429 不得泄露账号");
  }

  private void exhaustFixtureAccount() {
    // 账号+IP 上限 4：4 次失败后第 5 次请求 429
    for (int i = 0; i < 4; i++) {
      HttpResponse<String> res = login(fixtureUser, "Wrong-Pass-" + i + "-x");
      assertEquals(400, res.statusCode(), "阈值内应为正常认证失败: " + res.body());
    }
  }

  // ==================== 1. 统一失败 + 429 + 恢复 ====================

  @Test
  void loginFailuresUnifiedThen429ThenRecoversAfterWindow() {
    // 不存在用户 vs 口令错误：状态码与响应体逐字一致
    HttpResponse<String> ghost = login(named("ghost"), "whatever-123");
    HttpResponse<String> wrongPw = login(fixtureUser, "Wrong-Pass-123");
    assertEquals(400, ghost.statusCode());
    assertEquals(400, wrongPw.statusCode());
    assertEquals(ghost.body(), wrongPw.body(), "两种失败响应必须完全一致");
    JsonNode body = text(ghost);
    assertEquals("用户名或密码错误", body.path("message").asText());
    assertEquals(400, body.path("statusCode").asInt());
    assertEquals("Bad Request", body.path("error").asText());

    // 继续失败直到阈值（账号+IP=4；上面已 1 次）
    for (int i = 0; i < 3; i++) {
      assertEquals(400, login(fixtureUser, "Wrong-Pass-" + i).statusCode());
    }
    HttpResponse<String> limited = login(fixtureUser, "Wrong-Pass-again");
    assertUnified429(limited);

    // 被限流期间：口令正确也不能登录
    HttpResponse<String> correctWhileLimited = login(fixtureUser, PASSWORD);
    assertUnified429(correctWhileLimited);

    // 窗口过期（可控时钟推进）→ 恢复正常登录
    clock.advance(Duration.ofSeconds(61));
    HttpResponse<String> recovered = login(fixtureUser, PASSWORD);
    assertEquals(200, recovered.statusCode(), "窗口过期后应恢复: " + recovered.body());
    assertNotNull(text(recovered).path("token").asText());
  }

  // ==================== 2. 维度隔离 ====================

  @Test
  void lockedAccountAndSourceDoNotLockOthers() {
    exhaustFixtureAccount();
    assertUnified429(login(fixtureUser, PASSWORD));

    // 同一 IP 上的其他账号不受影响（账号+IP 维度隔离）
    String other = named("other");
    HttpResponse<String> otherRegister = post("/api/auth/register", null,
        "{\"username\":\"" + other + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, otherRegister.statusCode(), otherRegister.body());
    String otherToken = text(otherRegister).path("token").asText();
    createdAccounts.add(new String[] {other, otherToken});
    assertEquals(400, login(other, "Wrong-Pass-x").statusCode(), "其他账号仍应为正常 400 而非 429");
    assertEquals(200, login(other, PASSWORD).statusCode(), "其他账号可正常登录");
  }

  @Test
  void ipDimensionBlocksCrossAccountTraversal() {
    // IP 上限 10：分散在 10 个不同账号上的失败 → 第 11 次（账号全新）按 IP 维度 429
    for (int i = 0; i < 10; i++) {
      HttpResponse<String> res = login(named("trav"), "Wrong-Pass-" + i);
      assertEquals(400, res.statusCode(), "阈值内应为 400: " + res.body());
    }
    assertUnified429(login(named("trav"), "Wrong-Pass-final"));
  }

  // ==================== 3. 注册限流 ====================

  @Test
  void registerRateLimitedPerSource() {
    for (int i = 0; i < 4; i++) { // 注册上限 4：阈值内正常
      String user = named("rlreg");
      HttpResponse<String> res = post("/api/auth/register", null,
          "{\"username\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}");
      assertEquals(200, res.statusCode(), "阈值内注册应成功: " + res.body());
      createdAccounts.add(new String[] {user, text(res).path("token").asText()});
    }
    HttpResponse<String> blocked = post("/api/auth/register", null,
        "{\"username\":\"" + named("rlreg") + "\",\"password\":\"" + PASSWORD + "\"}");
    assertUnified429(blocked);
    // 注册限流不影响已有账号登录
    assertEquals(200, login(fixtureUser, PASSWORD).statusCode());
  }

  // ==================== 4. 伪造代理头无效（未配置可信代理） ====================

  @Test
  void spoofedForwardedHeadersCannotBypassLimit() {
    exhaustFixtureAccount();
    assertUnified429(login(fixtureUser, "Wrong-Pass-x",
        "X-Forwarded-For: 9.9.9.9", "X-Real-IP: 8.8.8.8"));
    assertUnified429(login(fixtureUser, "Wrong-Pass-y",
        "X-Forwarded-For: 1.2.3.4, 5.6.7.8"));
    assertUnified429(login(fixtureUser, PASSWORD, "X-Forwarded-For: 11.22.33.44"));
  }

  // ==================== 5. 误伤防护 ====================

  @Test
  void healthAndLoggedInApisAreNotAffectedByLoginLimit() {
    exhaustFixtureAccount();
    assertUnified429(login(fixtureUser, PASSWORD));

    // 健康检查
    assertEquals(200, get("/api/health", null).statusCode());

    // 已登录普通 API（会话接口、偏好、通知）
    assertEquals(200, get("/api/auth/me", fixtureToken).statusCode());
    assertEquals(200, get("/api/auth/preferences", fixtureToken).statusCode());
    assertEquals(200, get("/api/notifications", fixtureToken).statusCode());

    // 文件 GET / HEAD
    HttpResponse<String> file = get(photoUrl, fixtureToken);
    assertEquals(200, file.statusCode(), "文件 GET 不应被限流: " + file.body());
    assertTrue(file.headers().firstValue("Content-Type").orElse("").startsWith("image/png"));
    HttpResponse<String> fileHead = head(photoUrl, fixtureToken);
    assertEquals(200, fileHead.statusCode(), "文件 HEAD 不应被限流");
    assertTrue(fileHead.body() == null || fileHead.body().isEmpty(), "HEAD 无响应体");
  }

  @Test
  void nonLoginApiErrorsKeep400WhileLoginLimited() {
    exhaustFixtureAccount();
    assertUnified429(login(fixtureUser, PASSWORD));

    HttpResponse<String> bad = post("/api/trips", fixtureToken, "{\"title\":\"\"}");
    assertEquals(400, bad.statusCode(), "非登录接口的参数错误仍应 400，而非 429: " + bad.body());
    JsonNode body = text(bad);
    assertEquals(400, body.path("statusCode").asInt());
    assertEquals("Bad Request", body.path("error").asText());
  }
}
