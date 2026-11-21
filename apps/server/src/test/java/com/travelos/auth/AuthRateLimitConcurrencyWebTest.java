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
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **真实 HTTP 并发**验收：同一账号、同来源、错误密码 20 路同时打进来，
 * 400 不得超过账号+IP 阈值（3），其余必须 429（统一 JSON + Retry-After）。
 *
 * <p>阈值经测试配置收窄；窗口用可控时钟推进（无固定睡眠）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(testsupport.MutableClockConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.trusted-proxies=",
    "security.rate-limit.login-window-seconds=60",
    "security.rate-limit.login-ip-max-attempts=50",
    "security.rate-limit.login-account-ip-max-attempts=3",
    "security.rate-limit.login-global-max-attempts=100",
    "security.rate-limit.register-window-seconds=60",
    "security.rate-limit.register-ip-max-attempts=10",
    "security.rate-limit.max-tracked-keys=2000"
})
class AuthRateLimitConcurrencyWebTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";
  private static final int ACCOUNT_IP_MAX = 3;
  private static final int CONCURRENCY = 20;

  @LocalServerPort
  int port;

  @Autowired
  MutableClock clock;

  private HttpClient http;
  private String fixtureUser;
  private String fixtureToken;

  /** 一次登录尝试的观测结果。 */
  private record Attempt(int status, String body, String retryAfter) {
  }

  @BeforeAll
  void createFixture() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    fixtureUser = "rlconc" + suffix;
    HttpResponse<String> res = send("POST", "/api/auth/register", null,
        "{\"username\":\"" + fixtureUser + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, res.statusCode(), res.body());
    fixtureToken = JSON.readTree(res.body()).path("token").asText();
  }

  @AfterAll
  void cleanUp() {
    try {
      send("POST", "/api/auth/deactivate", fixtureToken,
          "{\"password\":\"" + PASSWORD + "\"}");
    } catch (Exception ignored) {
      // 兜底由最终 SQL 处理
    }
  }

  @BeforeEach
  void freshWindow() {
    clock.advance(Duration.ofSeconds(120)); // 每个用例从干净计数开始（不睡眠）
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      b.header("Content-Type", "application/json");
      b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** 并发发起 n 次错误密码登录，返回每次的状态/响应体/Retry-After。 */
  private List<Attempt> concurrentWrongPasswords(int n) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Attempt>> futures = new ArrayList<>();
    String body = "{\"username\":\"" + fixtureUser + "\",\"password\":\"Wrong-Pass-9\"}";
    for (int i = 0; i < n; i++) {
      futures.add(pool.submit(() -> {
        barrier.await(10, TimeUnit.SECONDS);
        HttpResponse<String> res = send("POST", "/api/auth/login", null, body);
        String retry = res.headers().firstValue("Retry-After").orElse(null);
        return new Attempt(res.statusCode(), res.body(), retry);
      }));
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发登录应全部结束");
    List<Attempt> results = new ArrayList<>();
    for (Future<Attempt> f : futures) {
      results.add(f.get());
    }
    return results;
  }

  @Test
  void twentyConcurrentWrongPasswordsCannotExceedThreshold() throws Exception {
    List<Attempt> results = concurrentWrongPasswords(CONCURRENCY);

    long ok400 = results.stream().filter(a -> a.status() == 400).count();
    long blocked429 = results.stream().filter(a -> a.status() == 429).count();
    long other = results.size() - ok400 - blocked429;

    assertEquals(0, other, "只允许 400/429 两种状态，实际出现其他: "
        + results.stream().filter(a -> a.status() != 400 && a.status() != 429).toList());
    assertTrue(ok400 <= ACCOUNT_IP_MAX,
        "400 不得超过阈值 " + ACCOUNT_IP_MAX + "，实际 " + ok400);
    assertEquals(CONCURRENCY - ok400, blocked429, "其余必须全部 429");
    assertTrue(blocked429 >= CONCURRENCY - ACCOUNT_IP_MAX, "至少 17 路 429，实际 " + blocked429);

    // 每个 429：统一 JSON + 合法 Retry-After + 不泄露账号
    for (Attempt a : results) {
      if (a.status() != 429) {
        continue;
      }
        assertNotNull(a.retryAfter(), "429 必须携带 Retry-After");
        int seconds = Integer.parseInt(a.retryAfter());
        assertTrue(seconds >= 1 && seconds <= 60, "Retry-After 范围: " + seconds);
        JsonNode node = JSON.readTree(a.body());
        assertEquals(429, node.path("statusCode").asInt(), a.body());
        assertEquals("Too Many Requests", node.path("error").asText(), a.body());
        assertEquals("请求过于频繁，请稍后再试", node.path("message").asText(), a.body());
        assertTrue(!a.body().contains(fixtureUser), "429 不得泄露账号：" + a.body());
    }
    // 每个 400：统一防枚举文案
    for (Attempt a : results) {
      if (a.status() != 400) {
        continue;
      }
      assertEquals("用户名或密码错误", JSON.readTree(a.body()).path("message").asText(), a.body());
    }

    // 并发后账号仍处于限流态：限流期内正确密码同样被拒（同一窗口，不依赖任何睡眠）
    HttpResponse<String> correctRes = send("POST", "/api/auth/login", null,
        "{\"username\":\"" + fixtureUser + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(429, correctRes.statusCode(), "限流期内正确密码也必须被拒");
    assertTrue(correctRes.headers().firstValue("Retry-After").isPresent());
    assertEquals("请求过于频繁，请稍后再试",
        JSON.readTree(correctRes.body()).path("message").asText());
  }

  @Test
  void concurrentBurstOnFreshWindowStillEnforced() throws Exception {
    // 第二组并发（窗口已被 @BeforeEach 推进重置）：结果同样必须满足阈值约束
    List<Attempt> results = concurrentWrongPasswords(CONCURRENCY);
    long ok400 = results.stream().filter(a -> a.status() == 400).count();
    long blocked429 = results.stream().filter(a -> a.status() == 429).count();
    assertTrue(ok400 <= ACCOUNT_IP_MAX, "第二组 400 超阈值: " + ok400);
    assertEquals(CONCURRENCY - ok400, blocked429);
    assertEquals(0, results.size() - ok400 - blocked429);
  }
}
