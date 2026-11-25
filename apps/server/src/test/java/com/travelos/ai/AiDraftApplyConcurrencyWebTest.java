package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.service.TripService;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 草稿并发应用幂等的<b>真实 HTTP + 真实数据库</b>验收
 * （完整 Spring Boot、随机端口、真实 Postgres 条件更新与行锁语义，无内存替身）。
 *
 * <p>状态机：pending → applying → applied；pending → discarded（互斥）。
 * 覆盖：同稿20路并发仅一个业务执行者（费用/预订/行程三类）、apply/discard 竞争合法终态、
 * 业务失败回滚无半应用且立即可重试、stale applying 接管（未过期不可抢、恢复者唯一）、
 * 旧执行者晚完成被 fencing 拦截、授权隔离、重复成功响应一致且业务实体不增加。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ai.draft.apply-stale-ms=4000",
    "security.rate-limit.register-ip-max-attempts=50"
})
class AiDraftApplyConcurrencyWebTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";
  private static final int CONCURRENCY = 20;

  @LocalServerPort
  int port;

  @Autowired
  AiMessageMapper messageMapper;

  @Autowired
  AiToolRegistry toolRegistry;

  @Autowired
  TripService tripService;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  private String userA;
  private String tokenA;
  private String userB;
  private String tokenB;
  private String convA;
  private String convA2;
  private String convB;

  @BeforeAll
  void createFixtures() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    userA = "idmda" + suffix; //11+9=20 ✓（用户名 ≤20）
    userB = "idmdb" + suffix;
    tokenA = register(userA);
    tokenB = register(userB);
    convA = newConversation(tokenA);
    convA2 = newConversation(tokenA);
    convB = newConversation(tokenB);
  }

  @AfterAll
  void cleanUpTestData() {
    String ids = "SELECT id FROM users WHERE username LIKE 'idmda%' OR username LIKE 'idmdb%'";
    jdbc.execute("DELETE FROM ai_messages WHERE user_id IN (" + ids + ") "
        + "OR conversation_id IN (SELECT id FROM ai_conversations WHERE user_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM ai_conversations WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM ai_request_keys WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM ai_preferences WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM notifications WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM audit_logs WHERE user_id IN (" + ids + ") OR trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM user_preferences WHERE user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM file_metadata WHERE owner_user_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM edit_locks WHERE holder_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM trip_members WHERE user_id IN (" + ids + ") OR trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses "
        + "WHERE trip_id IN (SELECT id FROM trips WHERE owner_id IN (" + ids + ")))");
    jdbc.execute("DELETE FROM trip_expenses WHERE trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM bookings WHERE trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM trip_places WHERE trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM trip_days WHERE trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM trips WHERE owner_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM users WHERE username LIKE 'idmda%' OR username LIKE 'idmdb%'");
  }

  // ==================== HTTP/DB 工具 ====================

  private HttpRequest.Builder req(String path, String token) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token);
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

  private String register(String username) throws Exception {
    HttpResponse<String> res = post("/api/auth/register", null,
        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, res.statusCode(), res.body());
    return json(res).path("token").asText();
  }

  private String newConversation(String token) throws Exception {
    HttpResponse<String> res = post("/api/ai/conversations", token, "{}");
    assertEquals(200, res.statusCode(), res.body());
    return json(res).path("conversation").path("id").asText();
  }

  /** 创建3天行程并生成 trip_days（应用行程草稿需要真实天）。 */
  private String newTrip() throws Exception {
    HttpResponse<String> res = post("/api/trips", tokenA,
        "{\"title\":\"草稿并发测试\",\"destination\":\"t\","
            + "\"startDate\":\"2026-12-20\",\"endDate\":\"2026-12-22\"}");
    assertEquals(200, res.statusCode(), res.body());
    String tripId = json(res).path("trip").path("id").asText();
    tripService.syncDaysWithRange(tripId);
    Integer days = jdbc.queryForObject(
        "SELECT count(*) FROM trip_days WHERE trip_id = ?", Integer.class, tripId);
    assertEquals(3, days, "夹具必须生成3天");
    return tripId;
  }

  /** 直接种一条 pending 草稿消息（夹具；草稿生成链路不是被测对象）。属主从会话解析（外键是用户 ID）。 */
  private String seedDraft(String convId, String toolCallsJson) {
    String ownerId = jdbc.queryForObject(
        "SELECT user_id FROM ai_conversations WHERE id = ?", String.class, convId);
    AiMessage message = new AiMessage();
    message.setConversationId(convId);
    message.setUserId(ownerId);
    message.setRole("assistant");
    message.setContent("【测试草稿卡片】");
    message.setToolCalls(toolCallsJson);
    message.setDraftStatus("pending");
    messageMapper.insert(message);
    assertNotNull(message.getId());
    return message.getId();
  }

  private String expenseDraftJson(String tripId) throws Exception {
    Map<String, Object> expense = new LinkedHashMap<>();
    expense.put("amount", "88.50");
    expense.put("category", "food");
    expense.put("title", "并发测试餐饮");
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "expense");
    draft.put("tripId", tripId);
    draft.put("expense", expense);
    return JSON.writeValueAsString(draft);
  }

  private AiConversation expenseConversation(String tripId, String username, String token) throws Exception {
    var response = post("/api/ai/conversations", token, JSON.writeValueAsString(Map.of("tripId", tripId)));
    assertEquals(200, response.statusCode(), response.body());
    AiConversation conversation = new AiConversation();
    conversation.setId(json(response).path("conversation").path("id").asText());
    conversation.setUserId(fixtureUserId(username));
    conversation.setTripId(tripId);
    return conversation;
  }

  private String fixtureUserId(String username) {
    return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", String.class, username);
  }

  private void addExpenseMember(String tripId, String userId) {
    jdbc.update("""
        INSERT INTO trip_members (id, trip_id, user_id, role, created_at, updated_at)
        VALUES (?, ?, ?, 'member', now(), now())
        """, "expense-fixture-" + tripId + "-" + userId, tripId, userId);
  }

  private JsonNode expenseTool(AiConversation conversation, Map<String, Object> args) throws Exception {
    return JSON.readTree(toolRegistry.executeTool(Map.of("name", "add_expense",
        "arguments", JSON.writeValueAsString(args)), conversation, conversation.getUserId()));
  }

  @Test
  @Order(20)
  void expenseDraftPreservesOriginalCurrencyAndAllThreeSplitModes() throws Exception {
    String tripId = newTrip();
    String owner = fixtureUserId(userA);
    String payer = fixtureUserId(userB);
    String thirdUsername = "idmdbx" + userA.substring(5);
    register(thirdUsername);
    String third = fixtureUserId(thirdUsername);
    addExpenseMember(tripId, payer);
    addExpenseMember(tripId, third);
    AiConversation conversation = expenseConversation(tripId, userA, tokenA);

    List<Map<String, Object>> cases = List.of(
        Map.of("title", "美元均分", "amount", "80", "currency", "usd", "payer", userB,
            "participantCount", 3, "participants", List.of(
                Map.of("member", userB), Map.of("member", thirdUsername), Map.of("member", "我"))),
        Map.of("title", "欧元比例", "amount", "100.01", "currency", "EUR", "payer", userB,
            "splitType", "ratio", "participants", List.of(
                Map.of("member", "我", "ratio", "1"), Map.of("member", userB, "ratio", "2"))),
        Map.of("title", "日元金额", "amount", "25.02", "currency", "JPY", "payer", userB,
            "splitType", "amount", "participants", List.of(
                Map.of("member", "我", "shareAmount", "10.00"), Map.of("member", userB, "shareAmount", "15.01"))));
    List<Map<String, String>> expected = List.of(
        Map.of(payer, "26.66", third, "26.66", owner, "26.68"),
        Map.of(owner, "33.34", payer, "66.67"),
        Map.of(owner, "10.00", payer, "15.02"));

    for (int i = 0; i < cases.size(); i++) {
      Map<String, Object> args = cases.get(i);
      JsonNode tool = expenseTool(conversation, args);
      assertTrue(tool.path("draftCreated").asBoolean(), tool.toString());
      JsonNode preview = tool.path("expensePreview");
      assertEquals(payer, preview.path("payerUserId").asText());
      Map<String, String> shares = new LinkedHashMap<>();
      preview.path("shares").forEach(row -> shares.put(row.path("userId").asText(), row.path("shareAmount").asText()));
      assertEquals(expected.get(i), shares);
      assertEquals(i, expenseCount(tripId), "预览阶段不得入账");
      String messageId = tool.path("messageId").asText();
      var applied = post(applyPath(conversation.getId(), messageId), tokenA, "{}");
      assertEquals(200, applied.statusCode(), applied.body());
      assertEquals(200, post(applyPath(conversation.getId(), messageId), tokenA, "{}").statusCode());
      assertEquals(i + 1, expenseCount(tripId));

      String expense = jdbc.queryForObject("SELECT id FROM trip_expenses WHERE trip_id = ? AND title = ?",
          String.class, tripId, args.get("title"));
      assertEquals(preview.path("currency").asText(),
          jdbc.queryForObject("SELECT currency FROM trip_expenses WHERE id = ?", String.class, expense));
      assertEquals(payer, jdbc.queryForObject("SELECT payer_user_id FROM trip_expenses WHERE id = ?", String.class, expense));
      assertEquals(preview.path("splitType").asText(),
          jdbc.queryForObject("SELECT split_type FROM trip_expenses WHERE id = ?", String.class, expense));
      Map<String, String> persisted = new LinkedHashMap<>();
      jdbc.query("SELECT user_id, share_amount FROM trip_expense_shares WHERE expense_id = ?",
          rs -> { persisted.put(rs.getString(1), rs.getBigDecimal(2).setScale(2).toPlainString()); }, expense);
      assertEquals(shares, persisted);
    }
  }

  @Test
  @Order(21)
  void ambiguousExpenseDraftAndChangedExpenseScopeNeverWrite() throws Exception {
    String tripId = newTrip();
    String owner = fixtureUserId(userA);
    String other = fixtureUserId(userB);
    addExpenseMember(tripId, other);
    jdbc.update("UPDATE users SET display_name = '同名成员' WHERE id IN (?, ?)", owner, other);
    AiConversation conversation = expenseConversation(tripId, userA, tokenA);
    assertTrue(expenseTool(conversation, Map.of("amount", "80", "currency", "¥")).path("error").asText().contains("原币"));
    assertTrue(expenseTool(conversation, Map.of("amount", "80", "participantCount", 3)).path("error").asText().contains("成员"));
    assertTrue(expenseTool(conversation, Map.of("amount", "80", "payer", "同名成员")).path("error").asText().contains("重名"));
    assertTrue(expenseTool(conversation, Map.of("amount", "80", "participants", List.of(Map.of("member", "行程外成员"))))
        .path("error").asText().contains("当前行程"));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ai_messages WHERE conversation_id = ?", Integer.class, conversation.getId()));
    assertEquals(0, expenseCount(tripId));

    var own = expenseTool(conversation, Map.of("amount", "136", "category", "food"));
    assertEquals("CNY", own.path("expensePreview").path("currency").asText());
    AiConversation otherConversation = expenseConversation(tripId, userB, tokenB);
    String otherDraft = expenseTool(otherConversation, Map.of("amount", "80", "currency", "USD")).path("messageId").asText();
    jdbc.update("UPDATE trip_members SET role = 'viewer' WHERE trip_id = ? AND user_id = ?", tripId, other);
    var forbidden = post(applyPath(otherConversation.getId(), otherDraft), tokenB, "{}");
    assertEquals(403, forbidden.statusCode(), forbidden.body());
    assertTrue(expenseTool(otherConversation, Map.of("amount", "80")).path("error").asText().contains("权限"));
    jdbc.update("DELETE FROM trip_members WHERE trip_id = ? AND user_id = ?", tripId, other);
    var stale = post(applyPath(conversation.getId(), own.path("messageId").asText()), tokenA, "{}");
    assertEquals(409, stale.statusCode(), stale.body());
    assertTrue(stale.body().contains("重新生成"));
    assertEquals(200, post(discardPath(conversation.getId(), own.path("messageId").asText()), tokenA, "{}").statusCode());
    assertEquals(0, expenseCount(tripId));
  }

  private String bookingDraftJson(String tripId) throws Exception {
    Map<String, Object> booking = new LinkedHashMap<>();
    booking.put("type", "hotel");
    booking.put("title", "并发测试酒店");
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "booking");
    draft.put("tripId", tripId);
    draft.put("booking", booking);
    return JSON.writeValueAsString(draft);
  }

  private String itineraryDraftJson(String tripId, List<Map<String, Object>> changes)
      throws Exception {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "itinerary_change");
    draft.put("tripId", tripId);
    draft.put("changes", changes);
    return JSON.writeValueAsString(draft);
  }

  private Map<String, Object> addChange(int dayIndex, String name) {
    Map<String, Object> change = new LinkedHashMap<>();
    change.put("action", "add");
    change.put("dayIndex", dayIndex);
    change.put("name", name);
    change.put("latitude", "31.2304");
    change.put("longitude", "121.4737");
    return change;
  }

  /** 并发发 n 路相同请求（屏障同步，确保真正竞争）。 */
  private List<HttpResponse<String>> concurrent(int n, String path, String token, String body)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      CyclicBarrier barrier = new CyclicBarrier(n);
      List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
          return asyncPost(path, token, body).join();
        }, pool));
      }
      List<HttpResponse<String>> out = new ArrayList<>();
      for (CompletableFuture<HttpResponse<String>> future : futures) {
        out.add(future.get(30, TimeUnit.SECONDS));
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  private static int countStatus(List<HttpResponse<String>> results, int code) {
    int n = 0;
    for (HttpResponse<String> res : results) {
      if (res.statusCode() == code) {
        n++;
      }
    }
    return n;
  }

  /** 并发结果只允许200（成功或回放）/409（进行中），且至少一个200 执行者。 */
  private String assertOnlySuccessOrConflict(List<HttpResponse<String>> results, String label) {
    int ok = countStatus(results, 200);
    int conflict = countStatus(results, 409);
    StringBuilder others = new StringBuilder();
    for (HttpResponse<String> res : results) {
      if (res.statusCode() != 200 && res.statusCode() != 409) {
        others.append(res.statusCode()).append(':').append(res.body()).append(" | ");
      }
    }
    assertTrue(others.length() == 0, label + " 只允许200/409，实际其他响应: " + others);
    assertTrue(ok >= 1, label + " 必须有至少一个成功执行者，实际200=" + ok + "409=" + conflict);
    return "200=" + ok + ",409=" + conflict;
  }

  private String draftStatus(String messageId) {
    return jdbc.queryForObject("SELECT draft_status FROM ai_messages WHERE id = ?",
        String.class, messageId);
  }

  private String draftToken(String messageId) {
    return jdbc.queryForObject(
        "SELECT draft_apply_token FROM ai_messages WHERE id = ?", String.class, messageId);
  }

  private String draftResult(String messageId) {
    return jdbc.queryForObject(
        "SELECT draft_apply_result FROM ai_messages WHERE id = ?", String.class, messageId);
  }

  private int expenseCount(String tripId) {
    return jdbc.queryForObject("SELECT count(*) FROM trip_expenses WHERE trip_id = ?",
        Integer.class, tripId);
  }

  private int bookingCount(String tripId) {
    return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE trip_id = ?",
        Integer.class, tripId);
  }

  private int placeCount(String tripId) {
    return jdbc.queryForObject("SELECT count(*) FROM trip_places WHERE trip_id = ?",
        Integer.class, tripId);
  }

  private int auditCount(String tripId, String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_logs WHERE trip_id = ? AND action = ?",
        Integer.class, tripId, action);
  }

  private void seedPlace(String tripId, String dayId, String name) {
    String id = "tpl" + Long.toHexString(System.nanoTime());
    jdbc.update("INSERT INTO trip_places (id, trip_id, trip_day_id, name, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, now(), now())",
        id, tripId, dayId, name);
  }

  private String dayId(String tripId, int dayIndex) {
    return jdbc.queryForObject(
        "SELECT id FROM trip_days WHERE trip_id = ? AND day_index = ?",
        String.class, tripId, dayIndex);
  }

  private String applyPath(String conv, String messageId) {
    return "/api/ai/conversations/" + conv + "/apply/" + messageId;
  }

  private String discardPath(String conv, String messageId) {
    return "/api/ai/conversations/" + conv + "/discard/" + messageId;
  }

  private void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(25);
    }
    assertTrue(condition.getAsBoolean(), "条件未在 " + timeoutMs + "ms 内满足");
  }

  // ==================== 1. 同稿20路并发应用（三类草稿各一组） ====================

  @Test
  @Order(1)
  void concurrentExpenseApplyTwentyRoutesSingleExecutor() throws Exception {
    String tripId = newTrip();
    String messageId = seedDraft(convA, expenseDraftJson(tripId));

    List<HttpResponse<String>> results = concurrent(CONCURRENCY,
        applyPath(convA, messageId), tokenA, "{}");
    String dist = assertOnlySuccessOrConflict(results, "费用草稿并发");

    assertEquals(1, expenseCount(tripId), "费用必须只记一次（真实库断言）");
    assertEquals(1, auditCount(tripId, "ai_expense_applied"), "审计必须只有一次");
    assertEquals("applied", draftStatus(messageId), "终态必须为 applied");
    assertNotNull(draftToken(messageId), "applied 必须带执行令牌");
    JsonNode saved = JSON.readTree(draftResult(messageId));
    assertEquals(1, saved.path("appliedCount").asInt(), "持久化结果必须含 appliedCount=1");

    // 重复成功响应：一致结果 + 业务实体不增加
    HttpResponse<String> again = post(applyPath(convA, messageId), tokenA, "{}");
    assertEquals(200, again.statusCode(), again.body());
    assertEquals("applied", json(again).path("status").asText());
    assertEquals(1, json(again).path("appliedCount").asInt(), "回放结果必须与首次一致");
    assertEquals(1, expenseCount(tripId), "重复请求不得再记账");
    assertEquals(1, auditCount(tripId, "ai_expense_applied"), "重复请求不得再审计");

    // 已应用 → 放弃被拒
    HttpResponse<String> discard = post(discardPath(convA, messageId), tokenA, "{}");
    assertEquals(400, discard.statusCode(), "已应用草稿不得放弃");
    assertEquals("applied", draftStatus(messageId));
    System.out.println("[expense-concurrency] " + dist + " expenses=1 audits=1");
  }

  @Test
  @Order(2)
  void concurrentBookingApplyTwentyRoutesSingleExecutor() throws Exception {
    String tripId = newTrip();
    String messageId = seedDraft(convA, bookingDraftJson(tripId));

    List<HttpResponse<String>> results = concurrent(CONCURRENCY,
        applyPath(convA, messageId), tokenA, "{}");
    String dist = assertOnlySuccessOrConflict(results, "预订草稿并发");

    assertEquals(1, bookingCount(tripId), "预订必须只建一次（真实库断言）");
    assertEquals(1, auditCount(tripId, "ai_booking_applied"), "审计必须只有一次");
    assertEquals("applied", draftStatus(messageId));

    HttpResponse<String> again = post(applyPath(convA, messageId), tokenA, "{}");
    assertEquals(200, again.statusCode(), again.body());
    assertEquals(1, json(again).path("appliedCount").asInt(), "回放结果一致");
    assertEquals(1, bookingCount(tripId), "重复请求不得再建预订");
    System.out.println("[booking-concurrency] " + dist + " bookings=1 audits=1");
  }

  @Test
  @Order(3)
  void concurrentItineraryApplyTwentyRoutesSingleExecutor() throws Exception {
    String tripId = newTrip();
    // 种两个既有点：一个用于 update、一个用于 remove（多步骤组合）
    seedPlace(tripId, dayId(tripId, 1), "待更新景点");
    seedPlace(tripId, dayId(tripId, 2), "待删除景点");
    assertEquals(2, placeCount(tripId));

    List<Map<String, Object>> changes = new ArrayList<>();
    changes.add(addChange(1, "新景点A"));
    changes.add(addChange(2, "新景点B"));
    changes.add(addChange(3, "新景点C"));
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("action", "update");
    update.put("placeId", placeIdByName(tripId, "待更新景点"));
    update.put("name", "已更新景点");
    update.put("dayIndex", 1);
    changes.add(update);
    Map<String, Object> remove = new LinkedHashMap<>();
    remove.put("action", "remove");
    remove.put("placeId", placeIdByName(tripId, "待删除景点"));
    changes.add(remove);
    String messageId = seedDraft(convA, itineraryDraftJson(tripId, changes));

    List<HttpResponse<String>> results = concurrent(CONCURRENCY,
        applyPath(convA, messageId), tokenA, "{}");
    String dist = assertOnlySuccessOrConflict(results, "行程草稿并发");

    // 期望：2 种子 -1删除 +3 新增 =4；update 只生效一次；无重复/半应用
    assertEquals(4, placeCount(tripId), "行程只应用一次（无重复 add、无半应用）");
    assertEquals(0, countPlacesNamed(tripId, "待更新景点"), "update 必须生效");
    assertEquals(1, countPlacesNamed(tripId, "已更新景点"), "update 不得重复产生新点");
    assertEquals(0, countPlacesNamed(tripId, "待删除景点"), "remove 必须生效");
    assertEquals(1, countPlacesNamed(tripId, "新景点A"));
    assertEquals(1, countPlacesNamed(tripId, "新景点B"));
    assertEquals(1, countPlacesNamed(tripId, "新景点C"));
    assertEquals(1, auditCount(tripId, "ai_itinerary_applied"), "审计必须只有一次");
    assertEquals("applied", draftStatus(messageId));

    HttpResponse<String> again = post(applyPath(convA, messageId), tokenA, "{}");
    assertEquals(200, again.statusCode(), again.body());
    assertEquals(5, json(again).path("appliedCount").asInt(),
        "回放与首次一致（3 add +1 update +1 remove）");
    assertEquals(4, placeCount(tripId), "重复请求不得改变行程");
    System.out.println("[itinerary-concurrency] " + dist + " places=4 audits=1");
  }

  private String placeIdByName(String tripId, String name) {
    return jdbc.queryForObject(
        "SELECT id FROM trip_places WHERE trip_id = ? AND name = ?", String.class, tripId, name);
  }

  private int countPlacesNamed(String tripId, String name) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trip_places WHERE trip_id = ? AND name = ?",
        Integer.class, tripId, name);
  }

  // ==================== 2. apply/discard 竞争（只允许合法终态） ====================

  @Test
  @Order(4)
  void applyDiscardRaceOnlyLegalOutcomes() throws Exception {
    String tripId = newTrip();
    int pairs = 6;
    int applyWins = 0;
    for (int i = 0; i < pairs; i++) {
      String messageId = seedDraft(convA, expenseDraftJson(tripId));
      CyclicBarrier barrier = new CyclicBarrier(2);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        CompletableFuture<HttpResponse<String>> applyF = CompletableFuture.supplyAsync(() -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            return asyncPost(applyPath(convA, messageId), tokenA, "{}").join();
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
        }, pool);
        CompletableFuture<HttpResponse<String>> discardF = CompletableFuture.supplyAsync(() -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            return asyncPost(discardPath(convA, messageId), tokenA, "{}").join();
          } catch (Exception ex) {
            throw new IllegalStateException(ex);
          }
        }, pool);
        HttpResponse<String> applyRes = applyF.get(30, TimeUnit.SECONDS);
        HttpResponse<String> discardRes = discardF.get(30, TimeUnit.SECONDS);

        boolean applyOk = applyRes.statusCode() == 200;
        boolean discardOk = discardRes.statusCode() == 200;
        String finalStatus = draftStatus(messageId);

        // 合法终态：二者恰有一个成功；状态与业务结果一致
        assertTrue(applyOk ^ discardOk,
            "apply/discard 恰好一个成功：apply=" + applyRes.statusCode()
                + " discard=" + discardRes.statusCode() + " status=" + finalStatus);
        if (applyOk) {
          applyWins++;
          assertEquals("applied", finalStatus, "apply 成功 → 终态必须 applied");
          assertTrue(discardRes.statusCode() == 409 || discardRes.statusCode() == 400,
              "apply 抢先后 discard 必须被拒，实际=" + discardRes.statusCode());
        } else {
          assertEquals(400, applyRes.statusCode(), "discard 成功 → apply 必须400: "
              + applyRes.body());
          assertEquals("discarded", finalStatus, "discard 成功 → 终态必须 discarded");
        }
      } finally {
        pool.shutdownNow();
      }
    }
    // ---- B. apply 先抢：discard 不得把已进入应用/已应用的草稿改成 discarded ----
    String applyFirstId = seedDraft(convA, expenseDraftJson(tripId));
    CompletableFuture<HttpResponse<String>> applyFirst = asyncPost(
        applyPath(convA, applyFirstId), tokenA, "{}");
    waitFor(() -> !"pending".equals(draftStatus(applyFirstId)), 10_000); // 已被抢占或已完成
    HttpResponse<String> discardLate = post(discardPath(convA, applyFirstId), tokenA, "{}");
    assertTrue(discardLate.statusCode() == 409 || discardLate.statusCode() == 400,
        "apply 抢先后 discard 必须失败，实际=" + discardLate.statusCode()
            + " body=" + discardLate.body());
    HttpResponse<String> applyDone = applyFirst.get(30, TimeUnit.SECONDS);
    assertEquals(200, applyDone.statusCode(), applyDone.body());
    assertEquals("applied", draftStatus(applyFirstId),
        "apply 抢先后终态必须是 applied（绝不出现业务已应用却标 discarded）");

    // ---- C. discard 先行：apply 必须400 且无业务写入 ----
    String discardFirstId = seedDraft(convA, expenseDraftJson(tripId));
    HttpResponse<String> discardWin = post(discardPath(convA, discardFirstId), tokenA, "{}");
    assertEquals(200, discardWin.statusCode(), discardWin.body());
    HttpResponse<String> applyLate = post(applyPath(convA, discardFirstId), tokenA, "{}");
    assertEquals(400, applyLate.statusCode(), "discarded 草稿不得应用: " + applyLate.body());
    assertEquals("discarded", draftStatus(discardFirstId));

    // 业务写入数量 = apply 获胜数（A 组）+ B 组1；被放弃的草稿绝不产生费用/审计
    int expectedExpenses = applyWins + 1;
    assertEquals(expectedExpenses, expenseCount(tripId), "费用数必须等于 apply 获胜数（无幽灵写入）");
    assertEquals(expectedExpenses, auditCount(tripId, "ai_expense_applied"),
        "审计数必须等于 apply 获胜数");
    System.out.println("[apply-discard-race] barrierPairs=" + pairs + " applyWins=" + applyWins
        + " applyFirst→discard=" + discardLate.statusCode()
        + " discardFirst→apply=400 expenses=" + expenseCount(tripId));
  }

  // ==================== 3. 业务失败回滚（无半应用）+ 修复后重试只执行一次 ====================

  @Test
  @Order(5)
  void businessFailureRollsBackWithoutPartialApplyThenRetrySucceedsOnce() throws Exception {
    String tripId = newTrip();
    int baseline = placeCount(tripId);

    // 第一步合法（add day1）、第二步非法（day99 不存在）→ 业务中途失败
    List<Map<String, Object>> badChanges = new ArrayList<>();
    badChanges.add(addChange(1, "回滚测试景点"));
    badChanges.add(addChange(99, "不存在的天"));
    String messageId = seedDraft(convA, itineraryDraftJson(tripId, badChanges));

    HttpResponse<String> failed = post(applyPath(convA, messageId), tokenA, "{}");
    assertEquals(400, failed.statusCode(), "非法天数必须被业务校验拒绝: " + failed.body());
    assertEquals("pending", draftStatus(messageId),
        "业务失败必须释放占位回到 pending（可立即重试，不留 applying）");
    assertEquals(baseline, placeCount(tripId),
        "事务回滚必须撤销第一步写入（无半应用行程）");
    assertEquals(0, countPlacesNamed(tripId, "回滚测试景点"), "失败后不得留下已插入的景点");
    assertEquals(0, auditCount(tripId, "ai_itinerary_applied"), "失败不得写审计");

    // 修复草稿内容（等价于重新生成合法草稿）→ 重试：最终只成功执行一次
    List<Map<String, Object>> goodChanges = new ArrayList<>();
    goodChanges.add(addChange(1, "回滚测试景点"));
    jdbc.update("UPDATE ai_messages SET tool_calls = ? WHERE id = ?",
        itineraryDraftJson(tripId, goodChanges), messageId);

    HttpResponse<String> retried = post(applyPath(convA, messageId), tokenA, "{}");
    assertEquals(200, retried.statusCode(), retried.body());
    assertEquals("applied", draftStatus(messageId));
    assertEquals(1, countPlacesNamed(tripId, "回滚测试景点"), "重试成功只执行一次业务");
    assertEquals(baseline + 1, placeCount(tripId));
    assertEquals(1, auditCount(tripId, "ai_itinerary_applied"), "只有成功的那次写审计");
    System.out.println("[failure-retry] rollback-ok places=" + placeCount(tripId));
  }

  // ==================== 4. stale applying：接管唯一、未过期不可抢 ====================

  @Test
  @Order(6)
  void staleApplyingTakeoverSingleExecutorAndFreshNotStealable() throws Exception {
    String tripId = newTrip();

    // 4.1 过期 applying（租约10s 前 > 阈值4s）：20 路并发只有一个恢复执行者
    String staleId = seedDraft(convA, expenseDraftJson(tripId));
    Date staleAt = new Date(System.currentTimeMillis() - 10_000L);
    jdbc.update("UPDATE ai_messages SET draft_status = 'applying', draft_apply_token = ?, "
            + "draft_apply_started_at = ? WHERE id = ?",
        "legacy-tok", staleAt, staleId);
    List<HttpResponse<String>> results = concurrent(CONCURRENCY,
        applyPath(convA, staleId), tokenA, "{}");
    String dist = assertOnlySuccessOrConflict(results, "stale applying 接管");

    assertEquals(1, expenseCount(tripId), "接管后也只执行一次业务");
    assertEquals(1, auditCount(tripId, "ai_expense_applied"));
    assertEquals("applied", draftStatus(staleId));
    assertNotEquals("legacy-tok", draftToken(staleId), "接管必须换入新执行令牌（fencing）");
    assertNotNull(draftResult(staleId), "接管执行者必须持久化结果");

    // 4.2 未过期 applying：不可被抢走（apply 拒绝、discard 也拒绝，状态与令牌不动）
    String freshId = seedDraft(convA, expenseDraftJson(tripId));
    Date freshAt = new Date(); // 刚进入 applying（租约未过期）
    jdbc.update("UPDATE ai_messages SET draft_status = 'applying', draft_apply_token = ?, "
            + "draft_apply_started_at = ? WHERE id = ?",
        "fresh-tok", freshAt, freshId);
    HttpResponse<String> applyFresh = post(applyPath(convA, freshId), tokenA, "{}");
    assertEquals(409, applyFresh.statusCode(), "未过期 applying 不可被抢走: " + applyFresh.body());
    HttpResponse<String> discardFresh = post(discardPath(convA, freshId), tokenA, "{}");
    assertEquals(409, discardFresh.statusCode(), "applying 不可被普通 discard 放弃");
    assertEquals("applying", draftStatus(freshId), "状态不得被改变");
    assertEquals("fresh-tok", draftToken(freshId), "令牌不得被改变");
    assertEquals(1, expenseCount(tripId), "未过期记录被拒后不得产生业务写入");
    System.out.println("[stale-applying] " + dist + " takeover-ok; fresh=409 untouched");
  }

  // ==================== 5. 旧执行者晚完成被 fencing 拦截 ====================

  @Test
  @Order(7)
  void oldExecutorLateFinishCannotOverwriteNewState() throws Exception {
    String tripId = newTrip();
    String messageId = seedDraft(convA, expenseDraftJson(tripId));
    Date legacyAt = new Date(System.currentTimeMillis() - 10_000L);
    jdbc.update("UPDATE ai_messages SET draft_status = 'applying', draft_apply_token = ?, "
            + "draft_apply_started_at = ? WHERE id = ?",
        "old-executor-tok", legacyAt, messageId);

    // 新执行者接管并成功
    HttpResponse<String> taken = post(applyPath(convA, messageId), tokenA, "{}");
    assertEquals(200, taken.statusCode(), taken.body());
    String newToken = draftToken(messageId);
    String newResult = draftResult(messageId);
    assertNotEquals("old-executor-tok", newToken);

    // 旧执行者的晚到落账/释放/接管：全部影响行数 = 0，状态与结果不被覆盖
    int fin = messageMapper.finishDraftApply(messageId, "old-executor-tok",
        "{\"success\":true,\"status\":\"applied\",\"appliedCount\":999}", new Date());
    assertEquals(0, fin, "旧执行者不得完成落账（fencing）");
    int rel = messageMapper.releaseDraftApply(messageId, "old-executor-tok");
    assertEquals(0, rel, "旧执行者不得释放占位");
    int take = messageMapper.takeoverDraftApply(messageId, "old-executor-tok", legacyAt,
        "another-tok", new Date());
    assertEquals(0, take, "已 applied 的记录不得再被接管");

    assertEquals("applied", draftStatus(messageId), "状态不被旧执行者覆盖");
    assertEquals(newToken, draftToken(messageId), "执行令牌不被旧执行者覆盖");
    assertEquals(newResult, draftResult(messageId), "成功结果不被旧执行者覆盖");
    assertEquals(1, expenseCount(tripId), "旧执行者不得产生任何业务副作用");
    System.out.println("[old-executor-fencing] finish/release/takeover all=0, expenses=1");
  }

  // ==================== 6. 授权与隔离 ====================

  @Test
  @Order(8)
  void authorizationAndDraftIsolationHold() throws Exception {
    String tripId = newTrip();
    String mineId = seedDraft(convA, expenseDraftJson(tripId));

    // 6.1 其他用户不能应用/放弃他人草稿（沿用既有404语义，不暴露存在性）
    HttpResponse<String> otherApply = post(applyPath(convA, mineId), tokenB, "{}");
    assertEquals(404, otherApply.statusCode(), "跨用户必须404");
    HttpResponse<String> otherDiscard = post(discardPath(convA, mineId), tokenB, "{}");
    assertEquals(404, otherDiscard.statusCode(), "跨用户放弃必须404");
    assertEquals("pending", draftStatus(mineId), "他人请求不得改变状态");
    assertEquals(0, expenseCount(tripId), "他人请求不得产生业务写入");

    // 6.2 同一用户跨会话的消息不可混用（会话归属校验保持）
    String otherConvId = seedDraft(convA2, expenseDraftJson(tripId));
    HttpResponse<String> crossConv = post(applyPath(convA, otherConvId), tokenA, "{}");
    assertEquals(404, crossConv.statusCode(), "非本会话消息必须404");
    assertEquals("pending", draftStatus(otherConvId));

    // 6.3 同会话不同草稿互不影响
    HttpResponse<String> first = post(applyPath(convA, mineId), tokenA, "{}");
    assertEquals(200, first.statusCode(), first.body());
    assertEquals("applied", draftStatus(mineId));
    assertEquals("pending", draftStatus(otherConvId), "其他草稿必须仍为 pending");
    assertEquals(1, expenseCount(tripId), "只应用了目标草稿");
    System.out.println("[auth-isolation] cross-user=404 cross-conv=404 others-untouched");
  }

  // ==================== 7. 行程/费用/预订三类的重复成功响应 ====================

  @Test
  @Order(9)
  void repeatedSuccessReturnsConsistentResultWithoutNewEntities() throws Exception {
    String tripId = newTrip();
    String eId = seedDraft(convA, expenseDraftJson(tripId));
    String bId = seedDraft(convA, bookingDraftJson(tripId));

    HttpResponse<String> e1 = post(applyPath(convA, eId), tokenA, "{}");
    HttpResponse<String> e2 = post(applyPath(convA, eId), tokenA, "{}");
    HttpResponse<String> b1 = post(applyPath(convA, bId), tokenA, "{}");
    HttpResponse<String> b2 = post(applyPath(convA, bId), tokenA, "{}");

    assertEquals(200, e1.statusCode());
    assertEquals(200, e2.statusCode());
    assertEquals(json(e1).path("status").asText(), json(e2).path("status").asText());
    assertEquals(json(e1).path("appliedCount").asInt(), json(e2).path("appliedCount").asInt(),
        "重复成功响应必须与首次一致");
    assertEquals(200, b1.statusCode());
    assertEquals(200, b2.statusCode());
    assertEquals(json(b1).path("appliedCount").asInt(), json(b2).path("appliedCount").asInt());

    assertEquals(1, expenseCount(tripId), "费用实体数量不增加");
    assertEquals(1, bookingCount(tripId), "预订实体数量不增加");
    assertEquals(1, auditCount(tripId, "ai_expense_applied"));
    assertEquals(1, auditCount(tripId, "ai_booking_applied"));
    System.out.println("[repeat-success] expense=1 booking=1 consistent-replay");
  }
}
