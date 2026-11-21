package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelos.common.EnvLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>本次修复目标竞态的可重复真实 HTTP 复现</b>（完整 Spring Boot + 随机端口 +
 * 真实 Postgres + 内嵌模拟模型，不调用付费模型）：
 *
 * <p>配置：stale 阈值1s &lt; 模型阻塞2.6s &lt; 任务总超时5s；原任务持有旧令牌阻塞在模型中，
 * 执行约1.2s后同键并发20路。断言链证明确实进入
 * 「stale 接管 → 用户并发429 → abandon 条件恢复 → 旧任务晚完成保存」路径：</p>
 * <ol>
 *   <li>≥1 路竞争请求返回429且文案为用户并发（只有 NEW（=stale 接管）才会走到准入）；</li>
 *   <li>接管者放弃后记录被<b>恢复</b>为原令牌（而非删除）——中间态/终态令牌 == 原任务令牌；</li>
 *   <li>原任务晚完成返回200，终态 status=succeeded 且令牌仍为原令牌；</li>
 *   <li>模型总调用恰好1次（竞争期间与最终都不重复执行）；</li>
 *   <li>用户消息1条、助手回复1条；同键普通/流式重试只回放同一结果。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "llm.base-url=${ai.test.mock-url}",
    "llm.api-key=mock-key-for-test",
    "llm.model=mock-model",
    "llm.connect-timeout-ms=2000",
    "llm.read-timeout-ms=8000",
    "ai.task.timeout-ms=5000",
    "ai.idempotency.stale-running-ms=1000",
    "ai.concurrency.user-max=1",
    "ai.concurrency.global-max=4",
    "ai.concurrency.queue-max=4",
    "ai.rate-limit.user-max-per-window=300",
    "ai.rate-limit.user-window-seconds=600",
    "ai.budget.daily-calls-per-user=200",
    "security.rate-limit.register-ip-max-attempts=50"
})
class AiStaleTakeoverRaceWebTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";

  /* ---------- 内嵌模拟模型：MOCK_BLOCK 阻塞2.6s（stale1s <2.6s < 任务5s） ---------- */
  private static final AtomicInteger MODEL_REQUESTS = new AtomicInteger();
  private static HttpServer mockModel;
  private static String mockUrl;

  static {
    try {
      EnvLoader.loadDotEnv();
      mockModel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      mockUrl = "http://127.0.0.1:" + mockModel.getAddress().getPort();
      System.setProperty("ai.test.mock-url", mockUrl);
      mockModel.createContext("/chat/completions", AiStaleTakeoverRaceWebTest::handleModel);
      mockModel.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
      mockModel.start();
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @AfterAll
  static void stopMockModel() {
    if (mockModel != null) {
      mockModel.stop(0);
    }
    System.clearProperty("ai.test.mock-url");
  }

  private static void handleModel(HttpExchange exchange) throws IOException {
    MODEL_REQUESTS.incrementAndGet();
    try {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      JsonNode root = JSON.readTree(body);
      boolean stream = root.path("stream").asBoolean(false);
      StringBuilder all = new StringBuilder();
      for (JsonNode msg : root.path("messages")) {
        String c = msg.path("content").asText("");
        if (!c.isBlank()) {
          all.append(c).append('\n');
        }
      }
      String prompt = all.toString();
      if (prompt.contains("MOCK_BLOCK")) {
        Thread.sleep(2600); // 2.6s：超过 stale(1s)、低于任务总时限(5s)
      }
      String content = "mock-reply:" + Integer.toHexString(prompt.hashCode());
      if (stream) {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream os = exchange.getResponseBody();
        os.write(("data: " + JSON.writeValueAsString(
            java.util.Map.of("choices", java.util.List.of(
                java.util.Map.of("delta", java.util.Map.of("content", content)))))
            + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        os.close();
        return;
      }
      String json = JSON.writeValueAsString(java.util.Map.of("choices",
          java.util.List.of(java.util.Map.of("message",
              java.util.Map.of("role", "assistant", "content", content)))));
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /* ---------- 应用侧夹具 ---------- */

  @LocalServerPort
  int port;

  @Autowired
  AiIdempotencyService idempotency;

  @Autowired
  AiTaskManager taskManager;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  private String username;
  private String token;
  private String conv;

  @BeforeAll
  void createFixture() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    username = "idmrt" + suffix;
    HttpResponse<String> reg = post("/api/auth/register",
        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, reg.statusCode(), reg.body());
    token = JSON.readTree(reg.body()).path("token").asText();
    HttpResponse<String> c = post("/api/ai/conversations", "{}");
    assertEquals(200, c.statusCode(), c.body());
    conv = JSON.readTree(c.body()).path("conversation").path("id").asText();
  }

  @AfterAll
  void cleanUpTestData() {
    String ids = "SELECT id FROM users WHERE username LIKE 'idmrt%'";
    jdbc.execute("DELETE FROM ai_messages WHERE user_id IN (" + ids + ") "
        + "OR conversation_id IN (SELECT id FROM ai_conversations WHERE user_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM ai_conversations WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM ai_request_keys WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM ai_preferences WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM notifications WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM audit_logs WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM user_preferences WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM file_metadata WHERE owner_user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM edit_locks WHERE holder_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM users WHERE username LIKE 'idmrt%'");
  }

  /* ---------- HTTP 工具 ---------- */

  private HttpRequest.Builder req(String path) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token);
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(req(path).method("POST",
            HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private CompletableFuture<HttpResponse<String>> asyncPost(String path, String body) {
    return http.sendAsync(req(path).method("POST",
            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private JsonNode json(HttpResponse<String> res) {
    try {
      return JSON.readTree(res.body() == null ? "" : res.body());
    } catch (Exception ex) {
      throw new IllegalStateException("非 JSON: " + res.body(), ex);
    }
  }

  private int mockCount() {
    return MODEL_REQUESTS.get();
  }

  private void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "条件未在 " + timeoutMs + "ms 内满足");
  }

  private int countUserMessages(String content) throws Exception {
    HttpResponse<String> res = http.send(req(
            "/api/ai/conversations/" + conv + "/messages?latest=true").GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, res.statusCode(), res.body());
    int user = 0;
    for (JsonNode m : json(res).path("messages")) {
      if ("user".equals(m.path("role").asText()) && content.equals(m.path("content").asText())) {
        user++;
      }
    }
    return user;
  }

  private int countAssistants() throws Exception {
    HttpResponse<String> res = http.send(req(
            "/api/ai/conversations/" + conv + "/messages?latest=true").GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    int n = 0;
    for (JsonNode m : json(res).path("messages")) {
      if ("assistant".equals(m.path("role").asText())) {
        n++;
      }
    }
    return n;
  }

  // ==================== §5.1 目标竞态：接管+429+恢复+晚完成，绝不重复执行 ====================

  @Test
  void staleTakeover429RestoreAndLateCompletionNeverReExecutes() throws Exception {
    String key = "rt-race-000001";
    String content = "MOCK_BLOCK 竞态原任务";
    int before = mockCount();

    // 1) 原任务异步发起（持有旧令牌，阻塞在模型2.6s）
    CompletableFuture<HttpResponse<String>> original = asyncPost(
        "/api/ai/conversations/" + conv + "/messages",
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    waitFor(() -> mockCount() >= before + 1, 5000); // 模型已开始（原任务执行中）

    // 捕获原任务的执行令牌（INSERT 占位在模型调用前完成）
    waitFor(() -> idempotency.tokenOf(userId(), conv, key) != null, 3000);
    String originalToken = idempotency.tokenOf(userId(), conv, key);
    assertNotNull(originalToken, "原任务必须先占位 running");
    assertEquals("running", idempotency.statusOf(userId(), conv, key));

    // 2) 原任务执行约1.2s后（> stale 1s）发起20路同键竞争
    long modelStart = System.currentTimeMillis();
    long targetFire = modelStart + 1150; // age≈1.2s（插入略早于模型调用）
    long wait = targetFire - System.currentTimeMillis();
    if (wait > 0) {
      Thread.sleep(wait);
    }

    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    java.util.List<HttpResponse<String>> racers = new java.util.ArrayList<>();
    try {
      CyclicBarrier barrier = new CyclicBarrier(threads);
      String body = "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}";
      java.util.List<CompletableFuture<HttpResponse<String>>> futures = new java.util.ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            return asyncPost("/api/ai/conversations/" + conv + "/messages", body).join();
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
        }, pool));
      }
      for (CompletableFuture<HttpResponse<String>> f : futures) {
        racers.add(f.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    // 3) 状态码分布 + 接管/429 证据
    int ok = 0;
    int limited = 0;
    int inProgress = 0;
    for (HttpResponse<String> r : racers) {
      if (r.statusCode() == 429) {
        limited++;
        assertTrue(json(r).path("message").asText().contains("生成中"),
            "429 必须来自用户并发准入: " + r.body());
      } else if (r.statusCode() == 200) {
        ok++;
        if (json(r).path("inProgress").asBoolean(false)) {
          inProgress++;
        }
      } else {
        assertEquals(200, r.statusCode(), "竞争请求只允许 200/429，实际 body=" + r.body());
      }
    }
    assertTrue(limited >= 1,
        "必须至少一路真实完成 stale 接管并因用户并发429（不能全部只读到 RUNNING），"
            + "实际分布200=" + ok + "(inProgress=" + inProgress + ") 429=" + limited);
    // 429 的每一路都是 NEW（行已存在 → 只能来自 stale 接管）→ 接管次数 ==429 次数
    int takeoverCount = limited;

    // 4) 接管者放弃后的中间态：记录被恢复为原令牌（尚未删除、尚未完成）
    String midStatus = idempotency.statusOf(userId(), conv, key);
    String midToken = idempotency.tokenOf(userId(), conv, key);
    assertTrue("running".equals(midStatus) || "succeeded".equals(midStatus),
        "恢复路径绝不删除记录，实际状态=" + midStatus);
    assertEquals(originalToken, midToken,
        "接管者429放弃后必须恢复原任务令牌（而非保留接管令牌或删除记录）");

    // 5) 原任务晚完成 → 200 且结果可保存
    HttpResponse<String> originalRes = original.get(30, TimeUnit.SECONDS);
    assertEquals(200, originalRes.statusCode(), originalRes.body());
    String originalAssistantId = json(originalRes).path("assistantMessage").path("id").asText();
    assertTrue(!originalAssistantId.isBlank(), "原任务必须正常返回助手回复");

    // 终态：succeeded + 原令牌（证明旧任务晚完成按旧令牌保存成功）
    waitFor(() -> "succeeded".equals(idempotency.statusOf(userId(), conv, key)), 8000);
    assertEquals("succeeded", idempotency.statusOf(userId(), conv, key));
    assertEquals(originalToken, idempotency.tokenOf(userId(), conv, key),
        "终态必须由原任务令牌保存（恢复→晚完成链路完整）");

    // 6) 模型总调用恰好1次（竞争期间与竞争后都没有第二次执行）
    Thread.sleep(400); // 给潜在的重复执行暴露窗口
    assertEquals(before + 1, mockCount(),
        "同键全程只允许1次模型调用（接管次数=" + takeoverCount + "）");

    // 7) 消息计数：用户1条、助手1条
    assertEquals(1, countUserMessages(content), "只产生一条用户消息");
    assertEquals(1, countAssistants(), "只产生一条助手回复");

    // 8) 同键普通/流式重试只回放同一结果，不再执行
    HttpResponse<String> retry = post("/api/ai/conversations/" + conv + "/messages",
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, retry.statusCode(), retry.body());
    assertEquals(originalAssistantId, json(retry).path("assistantMessage").path("id").asText(),
        "普通重试回放同一结果");
    HttpResponse<String> streamRetry = post("/api/ai/conversations/" + conv + "/messages/stream",
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, streamRetry.statusCode(), streamRetry.body());
    assertTrue(streamRetry.body().contains("event:done"), "流式重试回放 done");
    assertTrue(streamRetry.body().contains(originalAssistantId), "流式回放同一 assistantMessage");
    assertEquals(before + 1, mockCount(), "重试不得再次调用模型");
    assertEquals(1, countUserMessages(content), "重试后仍只有一条用户消息");
    assertEquals(1, countAssistants(), "重试后仍只有一条助手回复");

    // 报告分布（验收交付引用）
    System.out.println("[race-distribution] racers200=" + ok + " (inProgress=" + inProgress
        + ") 429=" + limited + " takeovers=" + takeoverCount
        + " modelCalls=" + (mockCount() - before)
        + " originalToken=" + originalToken + " finalToken="
        + idempotency.tokenOf(userId(), conv, key));
  }

  /** 用户 ID（幂等键与 HTTP归属都用 ID，夹具换算一次）。 */
  private String cachedUserId;

  private String userId() {
    if (cachedUserId == null) {
      cachedUserId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?",
          String.class, username);
    }
    return cachedUserId;
  }
}
