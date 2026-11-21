package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelos.common.EnvLoader;
import com.travelos.entity.AiRequestKey;
import com.travelos.mapper.AiRequestKeyMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等并发接管 + UTC 跨日预算的<b>真实 HTTP 验收</b>（完整 Spring Boot + 随机端口
 * + 真实 Postgres + 内嵌模拟模型，不调用付费模型）。
 *
 * <p>覆盖：failed 键 20 并发只有一个真实生成、过期 running 键 20 并发接管只有一个生成、
 * 同键请求得到“进行中”且不删执行者占位、用户并发 429 只清自己的占位、
 * 普通/流式同键回放同一结果、UTC 跨日预算恢复（可控准入时钟）。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "llm.base-url=${ai.test.mock-url}",
    "llm.api-key=mock-key-for-test",
    "llm.model=mock-model",
    "llm.connect-timeout-ms=2000",
    "llm.read-timeout-ms=8000",
    "ai.task.timeout-ms=30000",
    "ai.concurrency.user-max=1",
    "ai.concurrency.global-max=4",
    "ai.concurrency.queue-max=4",
    "ai.rate-limit.user-max-per-window=200",
    "ai.rate-limit.user-window-seconds=600",
    "ai.budget.daily-calls-per-user=2",
    "ai.idempotency.stale-running-ms=300000",
    "security.rate-limit.register-ip-max-attempts=50"
})
class AiIdempotencyRaceWebTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";

  /* ---------- 内嵌模拟模型（计数 + 慢速阻塞） ---------- */
  private static final AtomicInteger MODEL_REQUESTS = new AtomicInteger();
  private static HttpServer mockModel;
  private static String mockUrl;

  static {
    try {
      EnvLoader.loadDotEnv();
      mockModel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      mockUrl = "http://127.0.0.1:" + mockModel.getAddress().getPort();
      System.setProperty("ai.test.mock-url", mockUrl);
      mockModel.createContext("/chat/completions", AiIdempotencyRaceWebTest::handleModel);
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
        Thread.sleep(2600); // 占住用户并发槽，供同键/异键并发断言
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
  AiRequestKeyMapper requestKeyMapper;

  @Autowired
  AiTaskManager taskManager;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  private String userA;
  private String tokenA;
  private String convA;
  private String userB;
  private String tokenB;
  private String convB;
  private String userC;
  private String tokenC;
  private String convC;
  private String userD;
  private String tokenD;
  private String convD;
  private String userE;
  private String tokenE;
  private String convE;

  @BeforeAll
  void createFixtures() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    userA = "idmracea" + suffix;
    userB = "idmraceb" + suffix;
    userC = "idmracec" + suffix;
    userD = "idmraced" + suffix;
    userE = "idmracee" + suffix;
    tokenA = register(userA);
    tokenB = register(userB);
    tokenC = register(userC);
    tokenD = register(userD);
    tokenE = register(userE);
    // 幂等键的 userId 是用户 ID（非用户名）：夹具统一换算，保证种子行与请求行同一条
    userA = userIdOf(userA);
    userB = userIdOf(userB);
    userC = userIdOf(userC);
    userD = userIdOf(userD);
    userE = userIdOf(userE);
    convA = newConversation(tokenA);
    convB = newConversation(tokenB);
    convC = newConversation(tokenC);
    convD = newConversation(tokenD);
    convE = newConversation(tokenE);
  }

  private String userIdOf(String username) {
    return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", String.class, username);
  }

  @AfterAll
  void cleanUpTestData() {
    // 清理本测试的账号及其全部关联数据（真实库不留残留）
    String like = "'idmrace%'";
    String ids = "SELECT id FROM users WHERE username LIKE " + like;
    jdbc.execute("DELETE FROM ai_messages WHERE user_id IN (" + ids + ") "
        + "OR conversation_id IN (SELECT id FROM ai_conversations WHERE user_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM ai_conversations WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM ai_request_keys WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM ai_preferences WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM notifications WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM audit_logs WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM user_preferences WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM file_metadata WHERE owner_user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM user_friends WHERE user_id IN (" + ids + ") "
        + "OR friend_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM edit_locks WHERE holder_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM users WHERE username LIKE " + like);
  }

  /* ---------- HTTP 工具 ---------- */

  private String register(String username) throws Exception {
    HttpResponse<String> res = post("/api/auth/register", null,
        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, res.statusCode(), res.body());
    return JSON.readTree(res.body()).path("token").asText();
  }

  private String newConversation(String token) throws Exception {
    HttpResponse<String> res = post("/api/ai/conversations", token, "{}");
    assertEquals(200, res.statusCode(), res.body());
    return JSON.readTree(res.body()).path("conversation").path("id").asText();
  }

  private HttpRequest.Builder req(String path, String token) {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return b;
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    return http.send(req(path, token).method("POST",
            HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private CompletableFuture<HttpResponse<String>> asyncPost(String path, String token,
      String body) {
    return http.sendAsync(req(path, token).method("POST",
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
      Thread.sleep(25);
    }
    assertTrue(condition.getAsBoolean(), "条件未在 " + timeoutMs + "ms 内满足");
  }

  /** 同键 20 并发发送；返回全部响应。 */
  private List<HttpResponse<String>> fireConcurrent(String conv, String token, String content,
      String key, int threads) throws Exception {
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    try {
      java.util.List<CompletableFuture<HttpResponse<String>>> futures =
          new java.util.ArrayList<>();
      java.util.concurrent.CyclicBarrier barrier =
          new java.util.concurrent.CyclicBarrier(threads);
      String body = "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}";
      for (int i = 0; i < threads; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            return asyncPost("/api/ai/conversations/" + conv + "/messages", token, body).join();
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
        }, pool));
      }
      List<HttpResponse<String>> results = new java.util.ArrayList<>();
      for (CompletableFuture<HttpResponse<String>> f : futures) {
        results.add(f.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  /** 从响应集合中收集出现过的 assistantMessage id（去重）。 */
  private static Set<String> assistantIds(List<HttpResponse<String>> results, ObjectMapper json) {
    Set<String> ids = new HashSet<>();
    for (HttpResponse<String> res : results) {
      try {
        JsonNode node = json.readTree(res.body() == null ? "" : res.body());
        String id = node.path("assistantMessage").path("id").asText("");
        if (!id.isBlank()) {
          ids.add(id);
        }
      } catch (Exception ignored) {
        // 非 JSON / inProgress 响应：无 assistantMessage
      }
    }
    return ids;
  }

  private int countUserMessages(String token, String conv, String content) throws Exception {
    HttpRequest get = req("/api/ai/conversations/" + conv + "/messages?latest=true", token)
        .GET().build();
    HttpResponse<String> res = http.send(get,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, res.statusCode(), res.body());
    int count = 0;
    for (JsonNode m : json(res).path("messages")) {
      if ("user".equals(m.path("role").asText())
          && content.equals(m.path("content").asText())) {
        count++;
      }
    }
    return count;
  }

  // ==================== 1. failed 键 20 并发：只有一个真实生成 ====================

  @Test
  @Order(1)
  void concurrentBeginOnFailedKeyExecutesExactlyOnce() throws Exception {
    String key = "race-fail-00001";
    // 预置 failed 记录（真实库）
    AiRequestKey seed = new AiRequestKey();
    seed.setId(AiIdempotencyService.keyOf(userA, convA, key));
    seed.setUserId(userA);
    seed.setConversationId(convA);
    seed.setClientRequestId(key);
    seed.setStatus("failed");
    seed.setExecutionToken("legacy-fail-token");
    seed.setCreatedAt(new Date());
    seed.setUpdatedAt(new Date());
    requestKeyMapper.insert(seed);

    String content = "failed 键并发内容";
    int before = awaitMockBaseline();
    List<HttpResponse<String>> results = fireConcurrent(convA, tokenA, content, key, 20);

    // 所有响应 200（NEW 执行 / RUNNING 进行中 / SUCCEEDED 回放）
    for (HttpResponse<String> res : results) {
      assertEquals(200, res.statusCode(), "body=" + res.body());
    }
    // 模型只执行一次、用户消息只有一条
    waitFor(() -> {
      try {
        return mockCount() >= before + 1;
      } catch (Exception ex) {
        return false;
      }
    }, 5000);
    Thread.sleep(300); // 若有第二次执行，给它暴露窗口
    assertEquals(before + 1, mockCount(), "同键 20 并发只允许一次真实模型调用");
    assertEquals(1, countUserMessages(tokenA, convA, content), "只产生一条用户消息");
    // 出现过的 assistantMessage id 至多一个（回放共享同一 id）
    Set<String> ids = assistantIds(results, JSON);
    assertTrue(ids.size() <= 1, "执行结果与回放必须是同一消息: " + ids);
    // 最终状态为 succeeded，后续同键只回放
    waitStatus(userA, convA, key, "succeeded", 8000);
    int after = mockCount();
    HttpResponse<String> replay = post("/api/ai/conversations/" + convA + "/messages", tokenA,
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, replay.statusCode());
    assertEquals(after, mockCount(), "回放不得再次调用模型");
    assertEquals(1, countUserMessages(tokenA, convA, content));
  }

  // ==================== 2. 过期 running 键 20 并发接管 ====================

  @Test
  @Order(2)
  void concurrentTakeoverOfStaleRunningKeyExecutesExactlyOnce() throws Exception {
    String key = "race-stale-0001";
    AiRequestKey seed = new AiRequestKey();
    seed.setId(AiIdempotencyService.keyOf(userB, convB, key));
    seed.setUserId(userB);
    seed.setConversationId(convB);
    seed.setClientRequestId(key);
    seed.setStatus("running");
    seed.setExecutionToken("crashed-token");
    seed.setCreatedAt(new Date(System.currentTimeMillis() - 600_000L));
    seed.setUpdatedAt(new Date(System.currentTimeMillis() - 600_000L)); // 超过 stale 5min
    requestKeyMapper.insert(seed);

    String content = "过期键并发内容";
    int before = mockCount();
    List<HttpResponse<String>> results = fireConcurrent(convB, tokenB, content, key, 20);

    for (HttpResponse<String> res : results) {
      assertEquals(200, res.statusCode(), "body=" + res.body());
    }
    waitFor(() -> {
      try {
        return mockCount() >= before + 1;
      } catch (Exception ex) {
        return false;
      }
    }, 5000);
    Thread.sleep(300);
    assertEquals(before + 1, mockCount(), "过期 running 并发接管只允许一次真实生成");
    assertEquals(1, countUserMessages(tokenB, convB, content));
    Set<String> ids = assistantIds(results, JSON);
    assertTrue(ids.size() <= 1, "接管执行者的结果唯一: " + ids);

    // 旧执行者（crashed-token）晚到写入不得覆盖
    AiIdempotencyService.Begin legacy = new AiIdempotencyService.Begin(
        AiIdempotencyService.State.NEW, key, null, "crashed-token",
        AiIdempotencyService.Origin.STALE_TAKEOVER,
        new AiIdempotencyService.Restore("running", "crashed-token",
            new Date(System.currentTimeMillis() - 600_000L), null));
    waitStatus(userB, convB, key, "succeeded", 8000);
    String expectedId = ids.isEmpty() ? "" : ids.iterator().next();
    idempotency.markSucceeded(userB, convB, legacy, java.util.Map.of("hijack", true));
    assertEquals("succeeded", idempotency.statusOf(userB, convB, key),
        "旧执行者不得改写已成功状态");
    HttpResponse<String> replay = post("/api/ai/conversations/" + convB + "/messages", tokenB,
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    JsonNode replayBody = json(replay);
    assertEquals(200, replay.statusCode());
    assertEquals(expectedId, replayBody.path("assistantMessage").path("id").asText(),
        "回放必须是原执行者的结果，未被旧执行者覆盖");
  }

  // ==================== 3. 同键不删占位 + 429 只清自己的占位 ====================

  @Test
  @Order(3)
  void sameKeyGetsInProgressAnd429CleansOnlyOwnPlaceholder() throws Exception {
    String key1 = "race-hold-0001";
    String key2 = "race-429key-001";
    String content1 = "MOCK_BLOCK 执行者占位";

    int before = mockCount();
    CompletableFuture<HttpResponse<String>> holder = asyncPost(
        "/api/ai/conversations/" + convC + "/messages", tokenC,
        "{\"content\":\"" + content1 + "\",\"clientRequestId\":\"" + key1 + "\"}");
    waitFor(() -> {
      try {
        return mockCount() >= before + 1;
      } catch (Exception ex) {
        return false;
      }
    }, 5000);

    // 3.1 同键并发请求 → “进行中”（不是 429），且绝不删除执行者占位
    for (int i = 0; i < 3; i++) {
      HttpResponse<String> same = post("/api/ai/conversations/" + convC + "/messages", tokenC,
          "{\"content\":\"" + content1 + "\",\"clientRequestId\":\"" + key1 + "\"}");
      assertEquals(200, same.statusCode(), same.body());
      assertTrue(json(same).path("inProgress").asBoolean(false),
          "同键请求应得到“进行中”: " + same.body());
    }
    assertEquals("running", idempotency.statusOf(userC, convC, key1),
        "同键“进行中”返回不得删除执行者占位");
    assertNotNull(idempotency.tokenOf(userC, convC, key1), "执行归属必须还在");

    // 3.2 用户并发 429（异键）→ 只清自己的占位，执行者占位不受影响
    HttpResponse<String> rejected = post("/api/ai/conversations/" + convC + "/messages", tokenC,
        "{\"content\":\"另一条被限内容\",\"clientRequestId\":\"" + key2 + "\"}");
    assertEquals(429, rejected.statusCode(), rejected.body());
    assertNotNull(rejected.headers().firstValue("Retry-After").orElse(null));
    assertNull(idempotency.statusOf(userC, convC, key2),
        "429 请求必须清掉自己的占位（不残留）");
    assertEquals("running", idempotency.statusOf(userC, convC, key1),
        "429 请求不得删除执行者的占位");

    // 3.3 执行者正常完成 → 同键回放，不再调用模型
    HttpResponse<String> done = holder.get(30, TimeUnit.SECONDS);
    assertEquals(200, done.statusCode(), done.body());
    String doneId = json(done).path("assistantMessage").path("id").asText();
    assertTrue(!doneId.isBlank());
    waitStatus(userC, convC, key1, "succeeded", 8000);
    int afterHold = mockCount();
    HttpResponse<String> replay = post("/api/ai/conversations/" + convC + "/messages", tokenC,
        "{\"content\":\"" + content1 + "\",\"clientRequestId\":\"" + key1 + "\"}");
    assertEquals(200, replay.statusCode());
    assertEquals(doneId, json(replay).path("assistantMessage").path("id").asText(),
        "同键回放同一结果");
    assertEquals(afterHold, mockCount(), "回放不得再次调用模型");
    assertEquals(1, countUserMessages(tokenC, convC, content1));

    // 3.4 被 429 的键稍后可正常执行（占位已清，无永久残留）
    HttpResponse<String> retry2 = post("/api/ai/conversations/" + convC + "/messages", tokenC,
        "{\"content\":\"另一条被限内容\",\"clientRequestId\":\"" + key2 + "\"}");
    assertEquals(200, retry2.statusCode(), retry2.body());
    assertNotNull(json(retry2).path("assistantMessage").path("id").asText(""));
  }

  // ==================== 4. 普通/流式同键混合回放 ====================

  @Test
  @Order(4)
  void normalAndStreamShareOneStateMachine() throws Exception {
    String key = "race-mix-00001";
    String content = "混合回放内容";
    int before = mockCount();

    HttpResponse<String> normal = post("/api/ai/conversations/" + convD + "/messages", tokenD,
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, normal.statusCode(), normal.body());
    String firstId = json(normal).path("assistantMessage").path("id").asText();
    assertTrue(!firstId.isBlank());
    assertEquals(before + 1, mockCount());

    // 流式同键 → done 事件回放同一结果，不再调用模型
    HttpResponse<String> stream = post("/api/ai/conversations/" + convD + "/messages/stream",
        tokenD, "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, stream.statusCode(), stream.body());
    assertTrue(stream.body().contains("event:done"), "流式应回放 done: " + stream.body());
    assertTrue(stream.body().contains(firstId), "流式回放必须是同一 assistantMessage");
    assertEquals(before + 1, mockCount(), "流式同键重试不得再次调用模型");

    // 普通再次重试 → 仍是同一结果
    HttpResponse<String> again = post("/api/ai/conversations/" + convD + "/messages", tokenD,
        "{\"content\":\"" + content + "\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, again.statusCode());
    assertEquals(firstId, json(again).path("assistantMessage").path("id").asText());
    assertEquals(before + 1, mockCount());
    assertEquals(1, countUserMessages(tokenD, convD, content));
  }

  // ==================== 5. UTC 跨日预算恢复（可控准入时钟，真实 HTTP） ====================

  @Test
  @Order(5)
  void dailyBudgetRecoversAtUtcMidnightOverHttp() throws Exception {
    long realNow = System.currentTimeMillis();
    long todayStart = realNow - Math.floorMod(realNow, 86_400_000L);
    long nextMidnight = todayStart + 86_400_000L;
    // 今天 23:59:50（UTC）——距零点 10 秒
    AtomicLong clock = new AtomicLong(nextMidnight - 10_000L);
    taskManager.setAdmissionClockForTest(clock::get);
    try {
      // 当天预算内（budget=2）：两条正常执行
      assertEquals(200, post("/api/ai/conversations/" + convE + "/messages", tokenE,
          "{\"content\":\"预算第 1 条\",\"clientRequestId\":\"budget-key-0001\"}").statusCode());
      assertEquals(200, post("/api/ai/conversations/" + convE + "/messages", tokenE,
          "{\"content\":\"预算第 2 条\",\"clientRequestId\":\"budget-key-0002\"}").statusCode());

      // 第 3 条 → 429：文案“今日”、Retry-After = 到零点的秒数（时钟冻结 = 恰好 10）
      HttpResponse<String> limited = post("/api/ai/conversations/" + convE + "/messages", tokenE,
          "{\"content\":\"预算第 3 条\",\"clientRequestId\":\"budget-key-0003\"}");
      assertEquals(429, limited.statusCode(), limited.body());
      assertTrue(json(limited).path("message").asText().contains("今日"),
          json(limited).path("message").asText());
      int retryAfter = Integer.parseInt(limited.headers().firstValue("Retry-After").orElse("0"));
      assertEquals(10, retryAfter, "Retry-After 必须精确等于到下一个 UTC 零点的秒数");

      // 零点前不能提前恢复
      HttpResponse<String> still = post("/api/ai/conversations/" + convE + "/messages", tokenE,
          "{\"content\":\"预算第 3 条\",\"clientRequestId\":\"budget-key-0003\"}");
      assertEquals(429, still.statusCode(), "零点前不得提前恢复");

      // 推进到 UTC 零点之后 → 新自然日窗口 → 恢复
      clock.set(nextMidnight + 500L);
      HttpResponse<String> nextDay = post("/api/ai/conversations/" + convE + "/messages", tokenE,
          "{\"content\":\"预算第 3 条\",\"clientRequestId\":\"budget-key-0003\"}");
      assertEquals(200, nextDay.statusCode(),
          "UTC 零点后必须恢复（新日期键新窗口）: " + nextDay.body());
      assertNotNull(json(nextDay).path("assistantMessage").path("id").asText(""));
    } finally {
      taskManager.setAdmissionClockForTest(null); // 恢复真实时钟
    }
  }

  /** 等待键状态；失败时输出实际状态与令牌（便于定位）。 */
  private void waitStatus(String user, String conv, String key, String expected, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    String actual = idempotency.statusOf(user, conv, key);
    while (System.currentTimeMillis() < deadline && !expected.equals(actual)) {
      Thread.sleep(25);
      actual = idempotency.statusOf(user, conv, key);
    }
    assertEquals(expected, actual, "键 " + key + " 最终状态（token="
        + idempotency.tokenOf(user, conv, key) + "）");
  }

  private int awaitMockBaseline() {
    return MODEL_REQUESTS.get();
  }
}
