package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.travelos.common.EnvLoader;
import com.travelos.common.JsonUtil;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AI 资源治理的**真实 HTTP 验收**：完整 Spring Boot + 随机端口 + 内嵌模拟模型服务
 * （不调用任何付费模型）。
 *
 * <p>覆盖：用户/全局并发上限与 429（统一 JSON + Retry-After）、幂等不重复写入、
 * 客户端提前断开后停止后续模型调用、总超时后资源释放、模型异常降级、
 * AI 限流不影响健康检查/通知/文件/登录、无残留执行线程。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "llm.base-url=${ai.test.mock-url}",
    "llm.api-key=mock-key-for-test",
    "llm.model=mock-model",
    "llm.connect-timeout-ms=2000",
    "llm.read-timeout-ms=3000",
    "ai.task.timeout-ms=1500",
    "ai.concurrency.user-max=1",
    "ai.concurrency.global-max=2",
    "ai.concurrency.queue-max=1",
    "ai.rate-limit.user-max-per-window=100",
    "ai.rate-limit.user-window-seconds=300",
    "ai.budget.daily-calls-per-user=1000",
    "ai.idempotency.stale-running-ms=300000"
})
class AiGuardWebTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";

  /* ---------- 内嵌模拟模型服务 ---------- */
  private static final AtomicInteger MODEL_REQUESTS = new AtomicInteger();
  private static final AtomicInteger ACTIVE_MODEL_CALLS = new AtomicInteger();
  private static final AtomicInteger ABORTED_STREAMS = new AtomicInteger();
  private static HttpServer mockModel;
  private static String mockUrl;

  static {
    try {
      EnvLoader.loadDotEnv();
      mockModel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      mockUrl = "http://127.0.0.1:" + mockModel.getAddress().getPort();
      System.setProperty("ai.test.mock-url", mockUrl);
      mockModel.createContext("/chat/completions", AiGuardWebTest::handleModelRequest);
      mockModel.setExecutor(Executors.newCachedThreadPool());
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

  /** 模拟模型：按提示词中的标记决定行为（阻塞/失败/工具轮/慢速流）。 */
  private static void handleModelRequest(HttpExchange exchange) throws IOException {
    MODEL_REQUESTS.incrementAndGet();
    ACTIVE_MODEL_CALLS.incrementAndGet();
    try {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      JsonNode root = JSON.readTree(body);
      boolean stream = root.path("stream").asBoolean(false);
      StringBuilder all = new StringBuilder();
      boolean hasToolRound = false;
      for (JsonNode msg : root.path("messages")) {
        if ("tool".equals(msg.path("role").asText())) {
          hasToolRound = true;
        }
        String c = msg.path("content").asText("");
        if (!c.isBlank()) {
          all.append(c).append('\n');
        }
      }
      String prompt = all.toString();

      if (prompt.contains("MOCK_FAIL")) {
        byte[] err = "{\"error\":\"mock model down\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(500, err.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(err);
        }
        return;
      }
      if (prompt.contains("MOCK_BLOCK")) {
        Thread.sleep(2600); // 超过 ai.task.timeout-ms(1500) 且低于 read-timeout(3000)
      }

      if (!stream) {
        String content = "mock-reply:" + prompt.trim().split("\n")[0];
        if (prompt.contains("MOCK_TOOL") && !hasToolRound) {
          content = "";
        }
        String json = hasToolRound || !prompt.contains("MOCK_TOOL")
            ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\"", "'") + "\"}}]}"
            : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\","
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"ask_options\",\"arguments\":"
                + "\"{\\\"question\\\":\\\"选哪个？\\\",\\\"options\\\":[\\\"选项A\\\",\\\"选项B\\\"]}\"}}]}}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(bytes);
        }
        return;
      }

      // SSE 流式
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0); // 0 = chunked
      OutputStream os = exchange.getResponseBody();
      boolean aborted = false;
      try {
        if (prompt.contains("MOCK_TOOL") && !hasToolRound) {
          // 先发正文增量：应用侧会把 content 转发给客户端（写失败即可检测客户端断开），
          // 保留可中断窗口后再补 tool_calls（若客户端仍在，本轮可正常进入工具轮）
          for (int i = 1; i <= 10 && !aborted; i++) {
            try {
              writeSse(os, "{\"choices\":[{\"delta\":{\"content\":\"思考" + i + " \"}}]}");
              Thread.sleep(60);
            } catch (IOException ex) {
              aborted = true;
              ABORTED_STREAMS.incrementAndGet();
            }
          }
          if (!aborted) {
            String toolChunk = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"ask_options\",\"arguments\":"
                + "\"{\\\"question\\\":\\\"选哪个？\\\",\\\"options\\\":[\\\"选项A\\\",\\\"选项B\\\"]}\"}}]}}]}";
            writeSse(os, toolChunk);
            writeSse(os, "[DONE]");
            os.flush();
          }
        } else {
          for (int i = 1; i <= 5 && !aborted; i++) {
            try {
              writeSse(os, "{\"choices\":[{\"delta\":{\"content\":\"chunk-" + i + " \"}}]}");
              Thread.sleep(20);
            } catch (IOException ex) {
              aborted = true;
              ABORTED_STREAMS.incrementAndGet();
            }
          }
        }
        if (!aborted) {
          writeSse(os, "[DONE]");
          os.flush();
        }
      } catch (IOException ex) {
        ABORTED_STREAMS.incrementAndGet();
      } finally {
        try {
          os.close();
        } catch (IOException ignored) {
          // 客户端已断开
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      ACTIVE_MODEL_CALLS.decrementAndGet();
      exchange.close();
    }
  }

  private static void writeSse(OutputStream os, String payload) throws IOException {
    os.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
    os.flush();
  }

  /* ---------- 应用侧夹具 ---------- */

  @LocalServerPort
  int port;

  @Autowired
  AiTaskManager manager;

  private HttpClient http;
  private String tokenA;
  private String tokenB;
  private String tokenC;
  private String tokenD;
  private String userA;
  private String convA;
  private String photoUrl;

  @BeforeAll
  void createFixtures() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    userA = "aiguard" + suffix;
    JsonNode regA = json(post("/api/auth/register", null,
        "{\"username\":\"" + userA + "\",\"password\":\"" + PASSWORD + "\"}"));
    tokenA = regA.path("token").asText();
    tokenB = registerUser("aigb" + suffix); // B/C/D：并发与登录误伤测试用
    tokenC = registerUser("aigc" + suffix);
    tokenD = registerUser("aigd" + suffix);

    convA = json(post("/api/ai/conversations", tokenA, "{}")).path("conversation").path("id").asText();
    assertNotNull(convA);

    // 文件夹具（限流不影响文件访问）
    String tripId = json(post("/api/trips", tokenA,
        "{\"title\":\"ai guard trip\",\"destination\":\"t\","
            + "\"startDate\":\"2026-12-25\",\"endDate\":\"2026-12-26\"}"))
        .path("trip").path("id").asText();
    String dataUrl = "data:image/png;base64,"
        + java.util.Base64.getEncoder().encodeToString("ai-guard-payload".getBytes(StandardCharsets.UTF_8));
    photoUrl = json(post("/api/trips/" + tripId + "/photos", tokenA,
        "{\"url\":\"" + dataUrl + "\",\"caption\":\"guard\"}")).path("photo").path("url").asText();
  }

  private String registerUser(String username) throws Exception {
    return json(post("/api/auth/register", null,
        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
        .path("token").asText();
  }

  /* ---------- HTTP 工具 ---------- */

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

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder b = req(path, token);
    if (body != null) {
      b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    return send("POST", path, token, body);
  }

  private JsonNode json(HttpResponse<String> res) {
    try {
      return JSON.readTree(res.body() == null ? "" : res.body());
    } catch (Exception ex) {
      throw new IllegalStateException("非 JSON: " + res.body(), ex);
    }
  }

  private String newConversation(String token) throws Exception {
    return json(post("/api/ai/conversations", token, "{}")).path("conversation").path("id").asText();
  }

  private CompletableFuture<HttpResponse<String>> asyncPost(String path, String token,
      String body) {
    HttpRequest.Builder b = req(path, token);
    return http.sendAsync(b.method("POST",
            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(25);
    }
    assertTrue(condition.getAsBoolean(), "条件未在 " + timeoutMs + "ms 内满足");
  }

  private void assert429(HttpResponse<String> res) throws Exception {
    assertEquals(429, res.statusCode(), "body=" + res.body());
    assertTrue(res.headers().firstValue("Retry-After").isPresent(), "429 必须带 Retry-After");
    JsonNode body = json(res);
    assertEquals(429, body.path("statusCode").asInt());
    assertEquals("Too Many Requests", body.path("error").asText());
    assertNotNull(body.path("message").asText(""));
    assertFalse(body.path("message").asText().contains("llm."), "429 不得泄露模型配置");
  }

  // ==================== 1/2 并发上限与 429 ====================

  @Test
  @Order(1)
  void sameUserSecondRequestRejectedWith429WhileFirstRunning() throws Exception {
    String conv = newConversation(tokenA);
    int before = MODEL_REQUESTS.get();
    CompletableFuture<HttpResponse<String>> first = asyncPost(
        "/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"MOCK_BLOCK 保持占用\"}");
    waitFor(() -> MODEL_REQUESTS.get() > before, 4000);
    try {
      HttpResponse<String> second = post("/api/ai/conversations/" + conv + "/messages", tokenA,
          "{\"content\":\"第二条同时发\"}");
      assert429(second);
      assertTrue(json(second).path("message").asText().contains("生成中")
          || json(second).path("message").asText().contains("繁忙"));
    } finally {
      HttpResponse<String> done = first.get(15, TimeUnit.SECONDS);
      assertEquals(200, done.statusCode(), "第一条必须正常完成并释放槽位");
    }
    // 释放后可再次执行（槽位真正回收）
    HttpResponse<String> again = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"恢复后的正常请求\"}");
    assertEquals(200, again.statusCode(), again.body());
  }

  @Test
  @Order(2)
  void globalConcurrencyLimitReturns429ForExtraUser() throws Exception {
    String convA = newConversation(tokenA);
    String convB = newConversation(tokenB());
    int before = MODEL_REQUESTS.get();
    CompletableFuture<HttpResponse<String>> hold1 = asyncPost(
        "/api/ai/conversations/" + convA + "/messages", tokenA,
        "{\"content\":\"MOCK_BLOCK 占位一\"}");
    CompletableFuture<HttpResponse<String>> hold2 = asyncPost(
        "/api/ai/conversations/" + convB + "/messages", tokenB(),
        "{\"content\":\"MOCK_BLOCK 占位二\"}");
    waitFor(() -> MODEL_REQUESTS.get() >= before + 2, 5000);
    // 前置条件：两个占位任务确实同时在执行（否则本用例的队列语义无法成立）
    waitFor(() -> ((Number) manager.stats().get("active")).intValue() == 2, 3000);
    assertEquals(2, ((Number) manager.stats().get("active")).intValue(),
        "两个占位任务应同时占住全局执行位，stats=" + manager.stats());
    try {
      String convC = newConversation(tokenC());
      String convD = newConversation(tokenD());
      // 全局 2 + 队列 1：先确保 C 已入队（异步请求到达并占住队列位），再发 D → 必须 429
      CompletableFuture<HttpResponse<String>> queued = asyncPost(
          "/api/ai/conversations/" + convC + "/messages", tokenC(),
          "{\"content\":\"MOCK_BLOCK 占位三\"}");
      waitFor(() -> ((Number) manager.stats().get("queue")).intValue() == 1, 3000);
      HttpResponse<String> extra = post("/api/ai/conversations/" + convD + "/messages", tokenD(),
          "{\"content\":\"MOCK_BLOCK 占位四\"}");
      try {
        assert429(extra);
      } catch (AssertionError err) {
        throw new AssertionError("提交时资源快照=" + manager.stats() + "；" + err.getMessage(), err);
      }
      int queuedStatus = queued.get(20, TimeUnit.SECONDS).statusCode();
      assertTrue(queuedStatus == 200 || queuedStatus == 429 || queuedStatus == 503,
          "入队请求允许 200（完成）/503（排队超时）/429：" + queuedStatus);
    } finally {
      assertEquals(200, hold1.get(20, TimeUnit.SECONDS).statusCode());
      assertEquals(200, hold2.get(20, TimeUnit.SECONDS).statusCode());
    }
    assertTrue(manager.awaitIdle(8000), "并发任务必须全部回收");
    assertEquals(0, manager.activeCount());
    assertEquals(0, manager.queueSize());
  }

  private String tokenB() {
    return tokenB;
  }

  private String tokenC() {
    return tokenC;
  }

  private String tokenD() {
    return tokenD;
  }

  // ==================== 3 幂等：不重复写入 ====================

  @Test
  @Order(3)
  void duplicateClientRequestIdExecutesOnceAcrossNormalAndStream() throws Exception {
    String conv = newConversation(tokenA);
    String key = "idem-key-000001";
    int before = MODEL_REQUESTS.get();

    // 普通发送 → 200
    HttpResponse<String> first = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"幂等测试内容\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, first.statusCode(), first.body());
    String firstAssistant = json(first).path("assistantMessage").path("id").asText();
    assertFalse(firstAssistant.isBlank());

    // 同键重试（普通）→ 回放同一结果，不重复执行
    HttpResponse<String> retry = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"幂等测试内容\",\"clientRequestId\":\"" + key + "\"}");
    assertEquals(200, retry.statusCode());
    assertEquals(firstAssistant, json(retry).path("assistantMessage").path("id").asText(),
        "同键重试必须回放同一结果");
    assertEquals(1, MODEL_REQUESTS.get() - before, "模型只应被调用一次");

    // 同键改走流式接口 → 仍不执行重复生成（共享幂等键，路径不同不能绕过）
    String key2 = "idem-stream-0001";
    String streamBody = rawStream("/api/ai/conversations/" + conv + "/messages/stream", tokenA,
        "{\"content\":\"幂等流式内容\",\"clientRequestId\":\"" + key2 + "\"}");
    assertTrue(streamBody.contains("event:done") || streamBody.contains("data:"),
        "流式应有事件：" + streamBody.substring(0, Math.min(200, streamBody.length())));
    // 普通 1 次 + 流式 1 次 = 两次真实执行
    assertEquals(2, MODEL_REQUESTS.get() - before, "普通与流式各执行一次，共两次模型调用");
    int afterFirstStream = MODEL_REQUESTS.get();
    // 同一个流式键再来一次 → 回放 done，不再调用模型
    HttpResponse<String> streamRetry = post(
        "/api/ai/conversations/" + conv + "/messages/stream", tokenA,
        "{\"content\":\"幂等流式内容\",\"clientRequestId\":\"" + key2 + "\"}");
    assertEquals(200, streamRetry.statusCode());
    assertEquals(afterFirstStream, MODEL_REQUESTS.get(), "流式同键重试不得再次调用模型");

    // 消息列表：普通与流式各自的用户消息都只有一条
    JsonNode msgs = json(get("/api/ai/conversations/" + conv + "/messages?latest=true", tokenA));
    int userSame = 0;
    int userStream = 0;
    for (JsonNode m : msgs.path("messages")) {
      if (!"user".equals(m.path("role").asText())) {
        continue;
      }
      if ("幂等测试内容".equals(m.path("content").asText())) {
        userSame++;
      }
      if ("幂等流式内容".equals(m.path("content").asText())) {
        userStream++;
      }
    }
    assertEquals(1, userSame, "同一幂等键只允许一条用户消息");
    assertEquals(1, userStream, "流式同键只允许一条用户消息");
  }

  // ==================== 4 客户端提前断开 ====================

  @Test
  @Order(4)
  void clientDisconnectStopsBeforeNextModelRound() throws Exception {
    String conv = newConversation(tokenA);
    int before = MODEL_REQUESTS.get();
    // 原始 Socket 发流式请求：读到第一个事件后立即关闭（模拟浏览器中断）
    String path = "/api/ai/conversations/" + conv + "/messages/stream";
    String auth = tokenA;
    java.util.concurrent.atomic.AtomicBoolean gotResponse =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    Thread client = new Thread(() -> {
      try (Socket socket = new Socket("127.0.0.1", port)) {
        socket.setSoTimeout(15000);
        String reqText = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
            + "\r\nConnection: close\r\nContent-Type: application/json\r\n"
            + "Authorization: Bearer " + auth + "\r\n"
            + "Content-Length: "
            + ("{\"content\":\"MOCK_TOOL 断开测试\"}").getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n{\"content\":\"MOCK_TOOL 断开测试\"}";
        socket.getOutputStream().write(reqText.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        // 读到第一个 data: 事件（正文增量已到达客户端）后立即关闭，模拟浏览器中断
        java.io.ByteArrayOutputStream seen = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int read;
        while ((read = socket.getInputStream().read(buf)) != -1) {
          seen.write(buf, 0, read);
          if (seen.toString("ISO-8859-1").contains("data:")) {
            break;
          }
        }
        gotResponse.set(seen.toString("ISO-8859-1").contains("data:"));
        // try-with-resources 关闭 socket = 断开
      } catch (IOException ex) {
        // 断开路径
      }
    }, "raw-sse-client");
    client.start();
    client.join(10000);
    assertTrue(gotResponse.get(), "客户端应先收到响应再断开");

    // 等待任务回收；断开发生在第 1 轮（正文增量窗口）→ 不再发起第 2 轮模型调用
    assertTrue(manager.awaitIdle(8000), "断开后任务必须回收");
    assertEquals(before + 1, MODEL_REQUESTS.get(),
        "断开后不得开始下一轮模型调用（模型请求应恰好 1 次）");
    assertEquals(0, manager.activeCount());
    // 模拟模型侧最终收敛：观察到写失败，或其处理已自然结束（有界等待）
    waitFor(() -> ABORTED_STREAMS.get() >= 1 || ACTIVE_MODEL_CALLS.get() == 0, 5000);

    // 取消语义：不写降级回复（保留已写入的用户消息）
    JsonNode msgs = json(get("/api/ai/conversations/" + conv + "/messages?latest=true", tokenA));
    int assistantCount = 0;
    for (JsonNode m : msgs.path("messages")) {
      if ("assistant".equals(m.path("role").asText())) {
        assistantCount++;
      }
    }
    assertEquals(0, assistantCount, "断开取消后不得追加降级/超时回复");

    // 槽位释放：普通请求可继续
    HttpResponse<String> next = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"断开后的下一条\"}");
    assertEquals(200, next.statusCode(), next.body());
  }

  // ==================== 5 总超时 ====================

  @Test
  @Order(5)
  void totalTimeoutWritesOneTimeoutReplyAndReleasesResources() throws Exception {
    String conv = newConversation(tokenA);
    int before = MODEL_REQUESTS.get();
    HttpResponse<String> blocked = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"MOCK_BLOCK 超时测试\"}");
    assertEquals(200, blocked.statusCode(), blocked.body());
    assertEquals(before + 1, MODEL_REQUESTS.get(), "超时后不得发起下一轮模型调用");
    String reply = json(blocked).path("assistantMessage").path("content").asText();
    assertTrue(reply.contains("超时"), "应返回超时提示：" + reply);

    // 恰好一条超时提示
    JsonNode msgs = json(get("/api/ai/conversations/" + conv + "/messages?latest=true", tokenA));
    int timeoutReplies = 0;
    for (JsonNode m : msgs.path("messages")) {
      if ("assistant".equals(m.path("role").asText()) && m.path("content").asText("").contains("超时")) {
        timeoutReplies++;
      }
    }
    assertEquals(1, timeoutReplies, "超时提示不得重复写入");

    // 资源释放：下一条正常请求成功
    HttpResponse<String> next = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"超时后的正常请求\"}");
    assertEquals(200, next.statusCode(), next.body());
    assertTrue(manager.awaitIdle(8000));
  }

  // ==================== 6 模型异常降级 ====================

  @Test
  @Order(6)
  void modelFailureDegradesOnceWithoutDuplicate() throws Exception {
    String conv = newConversation(tokenA);
    HttpResponse<String> failed = post("/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"MOCK_FAIL 触发模型故障\"}");
    assertEquals(200, failed.statusCode(), failed.body());
    String reply = json(failed).path("assistantMessage").path("content").asText();
    assertTrue(reply.contains("AI 服务暂时不可用"), reply);
    JsonNode msgs = json(get("/api/ai/conversations/" + conv + "/messages?latest=true", tokenA));
    int degraded = 0;
    for (JsonNode m : msgs.path("messages")) {
      if ("assistant".equals(m.path("role").asText())) {
        degraded++;
      }
    }
    assertEquals(1, degraded, "模型异常只落一条降级回复");
  }

  // ==================== 7 AI 限流不影响其他能力 ====================

  @Test
  @Order(7)
  void aiAdmissionDoesNotAffectHealthNotificationsFilesOrLogin() throws Exception {
    String conv = newConversation(tokenA);
    int before = MODEL_REQUESTS.get();
    CompletableFuture<HttpResponse<String>> hold = asyncPost(
        "/api/ai/conversations/" + conv + "/messages", tokenA,
        "{\"content\":\"MOCK_BLOCK 占用中\"}");
    waitFor(() -> MODEL_REQUESTS.get() > before, 4000);
    try {
      // AI 侧被限（单用户并发 1）
      HttpResponse<String> limited = post("/api/ai/conversations/" + conv + "/messages", tokenA,
          "{\"content\":\"同时再发\"}");
      assert429(limited);

      // 健康检查 / 通知 / 文件 / 登录 全部不受影响
      assertEquals(200, send("GET", "/api/health", null, null).statusCode());
      assertEquals(200, send("GET", "/api/notifications", tokenA, null).statusCode());
      HttpResponse<String> file = send("GET", photoUrl, tokenA, null);
      assertEquals(200, file.statusCode(), "文件 GET 不受影响");
      HttpResponse<String> fileHead = send("HEAD", photoUrl, tokenA, null);
      assertEquals(200, fileHead.statusCode(), "文件 HEAD 不受影响");
      HttpResponse<String> login = post("/api/auth/login", null,
          "{\"username\":\"" + userA + "\",\"password\":\"" + PASSWORD + "\"}");
      assertEquals(200, login.statusCode(), "登录不受 AI 限流影响");
      // 非生成类 AI 普通接口（会话列表）也不受影响
      assertEquals(200, send("GET", "/api/ai/conversations", tokenA, null).statusCode());
    } finally {
      assertEquals(200, hold.get(20, TimeUnit.SECONDS).statusCode());
      assertTrue(manager.awaitIdle(8000));
    }
  }

  // ==================== 8 无残留执行 ====================

  @Test
  @Order(8)
  void noExecutionLeftAfterSuite() throws Exception {
    assertTrue(manager.awaitIdle(8000), "全部任务应已结束");
    assertEquals(0, manager.activeCount(), "不得有仍在执行的任务");
    assertEquals(0, manager.queueSize(), "队列必须清空");
    waitFor(() -> ACTIVE_MODEL_CALLS.get() == 0, 5000);
    assertEquals(0, ACTIVE_MODEL_CALLS.get(), "模拟模型不得仍有在途调用");
    // 工作线程不得仍在执行生成逻辑（空闲等待的池线程不算）
    long executing = Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().startsWith("ai-gen-"))
        .filter(t -> t.isAlive())
        .filter(t -> {
          for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("com.travelos.ai.")
                && "apply".equals(frame.getMethodName())) {
              return true;
            }
          }
          return false;
        })
        .count();
    assertEquals(0, executing, "测试结束不得有工作线程继续执行生成逻辑");
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return send("GET", path, token, null);
  }

  /** 原始 Socket 发流式请求并读到结束（Connection: close）。 */
  private String rawStream(String path, String token, String body) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(20000);
      byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
      String head = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
          + "\r\nConnection: close\r\nContent-Type: application/json\r\n"
          + "Authorization: Bearer " + token + "\r\n"
          + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
      OutputStream out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.ISO_8859_1));
      out.write(bodyBytes);
      out.flush();
      java.io.ByteArrayInputStream in =
          new java.io.ByteArrayInputStream(socket.getInputStream().readAllBytes());
      byte[] data = in.readAllBytes();
      return new String(data, StandardCharsets.UTF_8);
    }
  }
}
