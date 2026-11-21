package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
import com.travelos.entity.AiMessage;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.service.TripService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>真实重叠场景</b>：旧执行者仍在跑业务时租约过期 → 新执行者条件接管 →
 * 旧执行者的最终落账被 fencing 拦截 → <b>其整个业务事务回滚（不产生业务副作用）</b>，
 * 新执行者正常提交。断言真实数据库中的行程点数量恰为“一次执行”的数量（绝非两次之和）。
 *
 * <p>配置：ai.draft.apply-stale-ms=300（租约远小于业务时长以制造重叠）；
 * 草稿为3000步 add（replaceDay 覆盖当天），业务时长 ≫300ms，
 * 保证接管发生在旧执行者落账之前。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ai.draft.apply-stale-ms=300",
    "security.rate-limit.register-ip-max-attempts=50"
})
class AiDraftApplyOverlapWebTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";
  private static final int CHANGES = 1200;

  @LocalServerPort
  int port;

  @Autowired
  AiMessageMapper messageMapper;

  @Autowired
  TripService tripService;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  private String token;
  private String conv;

  @BeforeAll
  void createFixture() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    HttpResponse<String> reg = post("/api/auth/register",
        "{\"username\":\"idmdo" + suffix + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, reg.statusCode(), reg.body());
    token = JSON.readTree(reg.body()).path("token").asText();
    HttpResponse<String> c = post("/api/ai/conversations", "{}");
    assertEquals(200, c.statusCode(), c.body());
    conv = JSON.readTree(c.body()).path("conversation").path("id").asText();
  }

  @AfterAll
  void cleanUpTestData() {
    String ids = "SELECT id FROM users WHERE username LIKE 'idmdo%'";
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
    jdbc.execute("DELETE FROM trip_places WHERE trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM trip_days WHERE trip_id IN "
        + "(SELECT id FROM trips WHERE owner_id IN (" + ids + "))");
    jdbc.execute("DELETE FROM trips WHERE owner_id IN (" + ids + ")");
    jdbc.execute("DELETE FROM users WHERE username LIKE 'idmdo%'");
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
            StandardCharsets.UTF_8))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private CompletableFuture<HttpResponse<String>> asyncApply(String path) {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
        .build();
    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "条件未在 " + timeoutMs + "ms 内满足");
  }

  private String draftStatus(String id) {
    return jdbc.queryForObject("SELECT draft_status FROM ai_messages WHERE id = ?",
        String.class, id);
  }

  private String draftToken(String id) {
    return jdbc.queryForObject("SELECT draft_apply_token FROM ai_messages WHERE id = ?",
        String.class, id);
  }

  private int placeCount(String tripId) {
    return jdbc.queryForObject("SELECT count(*) FROM trip_places WHERE trip_id = ?",
        Integer.class, tripId);
  }

  @Test
  void staleTakeoverDuringRealBusinessOldExecutorRollsBackBusiness() throws Exception {
    // 行程 + 1天（3000 步 add 全部落在 day1，replaceDay=true 明确覆盖当天）
    HttpResponse<String> tripRes = post("/api/trips",
        "{\"title\":\"重叠竞态行程\",\"destination\":\"t\","
            + "\"startDate\":\"2026-12-20\",\"endDate\":\"2026-12-20\"}");
    assertEquals(200, tripRes.statusCode(), tripRes.body());
    String tripId = JSON.readTree(tripRes.body()).path("trip").path("id").asText();
    tripService.syncDaysWithRange(tripId);
    assertEquals(1, jdbc.queryForObject(
        "SELECT count(*) FROM trip_days WHERE trip_id = ?", Integer.class, tripId));

    // 大草稿：3000 个唯一地点（业务时长 ≫ 租约300ms）
    List<Map<String, Object>> changes = new ArrayList<>();
    for (int i = 0; i < CHANGES; i++) {
      Map<String, Object> change = new LinkedHashMap<>();
      change.put("action", "add");
      change.put("dayIndex", 1);
      change.put("name", "点位-" + i);
      change.put("latitude", "31.2304");
      change.put("longitude", "121.4737");
      changes.add(change);
    }
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "itinerary_change");
    draft.put("tripId", tripId);
    draft.put("replaceDay", true); // 明确覆盖当天（clearDay 只执行一次/执行者）
    draft.put("changes", changes);

    AiMessage message = new AiMessage();
    message.setConversationId(conv);
    message.setUserId(jdbc.queryForObject(
        "SELECT user_id FROM ai_conversations WHERE id = ?", String.class, conv));
    message.setRole("assistant");
    message.setContent("【重叠竞态草稿】");
    message.setToolCalls(JSON.writeValueAsString(draft));
    message.setDraftStatus("pending");
    messageMapper.insert(message);
    String messageId = message.getId();
    String applyPath = "/api/ai/conversations/" + conv + "/apply/" + messageId;

    // 执行者 A：异步开始应用（持有令牌 T_A，业务进行中）
    CompletableFuture<HttpResponse<String>> executorA = asyncApply(applyPath);
    waitFor(() -> "applying".equals(draftStatus(messageId)), 5000);
    String tokenA = draftToken(messageId);
    assertNotNull(tokenA);
    Date leaseAt = jdbc.queryForObject(
        "SELECT draft_apply_started_at FROM ai_messages WHERE id = ?", Date.class, messageId);

    // 等租约过期（>300ms）后发起 B：条件接管必须成功（A 仍在业务中）
    long waitMs = (leaseAt.getTime() + 350) - System.currentTimeMillis();
    if (waitMs > 0) {
      Thread.sleep(waitMs);
    }
    assertEquals("applying", draftStatus(messageId), "A 业务未结束前状态必须仍为 applying");

    // 执行者 B：接管并应用
    HttpResponse<String> executorB = post(applyPath, "{}");

    HttpResponse<String> resA = executorA.get(60, TimeUnit.SECONDS);
    assertEquals(200, executorB.statusCode(), "接管执行者必须成功: " + executorB.body());

    // ---- 核心断言：业务副作用只有一次（A 被 fencing 后整体回滚，B 正常提交） ----
    assertEquals("applied", draftStatus(messageId));
    String finalToken = draftToken(messageId);
    assertNotEquals(tokenA, finalToken, "必须发生接管（新执行令牌）");
    assertEquals(CHANGES, placeCount(tripId),
        "行程点必须恰为一次执行的数量（旧执行者回滚、新执行者提交，绝非两倍）");
    assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE trip_id = ? AND action = 'ai_itinerary_applied'",
            Integer.class, tripId),
        "审计必须只有成功执行者的一次");

    // 旧执行者 A 的响应：被 fencing 拦截（409）或（极小概率）其提交先于接管 → 回放200；
    // 无论哪种，都不能出现“业务双写”
    assertTrue(resA.statusCode() == 409 || resA.statusCode() == 200,
        "旧执行者响应只能是409(被拦截)或200(其先完成)，实际=" + resA.statusCode()
            + " body=" + resA.body());

    // 重复请求回放一致结果，不再执行业务
    HttpResponse<String> replay = post(applyPath, "{}");
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(CHANGES, JSON.readTree(replay.body()).path("appliedCount").asInt(),
        "回放与首次结果一致");
    assertEquals(CHANGES, placeCount(tripId), "回放不得改变行程");

    System.out.println("[overlap] A=" + resA.statusCode() + " B=" + executorB.statusCode()
        + " takeover=" + tokenA + "→" + finalToken + " places=" + placeCount(tripId));
  }
}
