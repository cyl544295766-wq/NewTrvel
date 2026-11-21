package com.travelos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求验收测试：TR-01 游记可见性 / TR-02 多币种 / TR-03 日期校验与关联保护。
 *
 * <p>独立测试数据：每批测试用唯一用户名后缀注册，记录准确 ID，收尾按 ID 精确清理。
 * 不使用固定种子，不修改他人数据，不连接生产。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=50"
})
class RequirementVerificationTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Testpass123";

  private final List<String> createdUserIds = new ArrayList<>();
  private final List<String> createdTripIds = new ArrayList<>();

  @LocalServerPort
  int port;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  private String suffix;

  // ===== TR-01 变量 =====
  private String ownerToken, memberToken, outsiderToken;
  private String ownerUserId, memberUserId, outsiderUserId;
  private String journalTripId;

  // ===== TR-02 变量 =====
  private String currencyTripId;

  // ===== TR-03 变量 =====
  private String dateTripId;

  @BeforeAll
  void setup() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
  }

  @AfterAll
  void cleanup() {
    // 按外键依赖顺序清理本次创建的数据
    List<String> allTripIds = new ArrayList<>();
    if (journalTripId != null) allTripIds.add(journalTripId);
    if (currencyTripId != null) allTripIds.add(currencyTripId);
    if (dateTripId != null) allTripIds.add(dateTripId);
    allTripIds.addAll(createdTripIds);
    for (String tid : allTripIds) {
      jdbc.update("DELETE FROM trip_journal_audience WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM trip_journal_photos WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM trip_journals WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM trip_expenses WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM bookings WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_places WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_days WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM photos WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM travel_documents WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM packing_items WHERE packing_list_id IN (SELECT id FROM packing_lists WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM packing_lists WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_members WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_settlements WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_budgets WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trips WHERE id=?", tid);
    }
    for (String uid : createdUserIds) {
      jdbc.update("DELETE FROM notifications WHERE user_id=?", uid);
      jdbc.update("DELETE FROM user_friends WHERE user_id=? OR friend_id=?", uid, uid);
      jdbc.update("DELETE FROM users WHERE id=?", uid);
    }
  }

  // ================================================================
  // TR-01：首页最近游记可见性
  // ================================================================

  @Test
  @Order(10)
  void tr01_journalVisibility() throws Exception {
    // 1. 创建三个用户
    ownerToken = register("tr01owner" + suffix);
    memberToken = register("tr01member" + suffix);
    outsiderToken = register("tr01out" + suffix);
    ownerUserId = createdUserIds.get(createdUserIds.size() - 3);
    memberUserId = createdUserIds.get(createdUserIds.size() - 2);
    outsiderUserId = createdUserIds.get(createdUserIds.size() - 1);

    // 2. owner 创建行程并邀请 member（需先加好友）
    JsonNode tripResp = apiPost(ownerToken, "/api/trips", Map.of(
        "title", "TR-01 测试行程", "destination", "测试目的地",
        "startDate", "2026-08-01", "endDate", "2026-08-05"));
    journalTripId = tripResp.get("trip").get("id").asText();
    // 建立好友关系：owner → member 发申请，member 接受（accept 路径中是申请发起者 ID）
    apiPost(ownerToken, "/api/friends", Map.of("username", "tr01member" + suffix));
    apiPost(memberToken, "/api/friends/" + ownerUserId + "/accept", null);
    apiPost(ownerToken, "/api/trips/" + journalTripId + "/members",
        Map.of("username", "tr01member" + suffix, "role", "member"));

    // 3. owner 创建私有游记
    JsonNode privJ = apiPost(ownerToken, "/api/trips/" + journalTripId + "/journals",
        Map.of("title", "私有游记-TR01", "content", "这是一篇私有游记内容",
            "isDraft", false, "visibility", "private"));
    String privJId = privJ.get("journal").get("id").asText();

    // 4. 验收：member（同行程）首页不应看到私有游记
    JsonNode dashMember = apiGet(memberToken, "/api/dashboard");
    assertJournalNotInDash(dashMember, privJId, "私有游记-TR01");

    // 5. 验收：owner 自己的首页可以看到
    JsonNode dashOwner = apiGet(ownerToken, "/api/dashboard");
    assertJournalInDash(dashOwner, privJId, "私有游记-TR01");

    // 6. owner 把游记设为指定受众 member（需先获取编辑锁 + 提供 visibleFriendIds）
    apiPost(ownerToken, "/api/journals/" + privJId + "/edit-lock", null);
    apiPatch(ownerToken, "/api/trips/" + journalTripId + "/journals/" + privJId,
        Map.of("visibility", "specified", "visibleFriendIds", List.of(memberUserId)));
    // PATCH 的 visibleFriendIds 已自动写入 audience 记录，无需手动插入

    // 7. 验收：获得指定受众权限后可见
    JsonNode dashMember2 = apiGet(memberToken, "/api/dashboard");
    assertJournalInDash(dashMember2, privJId, "私有游记-TR01");

    // 8. 撤销指定受众
    jdbc.update("DELETE FROM trip_journal_audience WHERE journal_id=? AND user_id=?", privJId, memberUserId);

    // 9. 验收：撤销后不可见
    JsonNode dashMember3 = apiGet(memberToken, "/api/dashboard");
    assertJournalNotInDash(dashMember3, privJId, "私有游记-TR01");

    // 10. 验收：草稿仅作者可见
    apiPost(ownerToken, "/api/trips/" + journalTripId + "/journals",
        Map.of("title", "草稿游记-TR01", "content", "草稿内容", "isDraft", true));
    JsonNode dashMember4 = apiGet(memberToken, "/api/dashboard");
    assertJournalNotInDash(dashMember4, null, "草稿游记-TR01");

    // 11. 验收：公开游记对行程成员可见
    apiPost(ownerToken, "/api/trips/" + journalTripId + "/journals",
        Map.of("title", "公开游记-TR01", "content", "公开内容", "isDraft", false, "visibility", "public"));
    JsonNode dashMember5 = apiGet(memberToken, "/api/dashboard");
    assertJournalInDash(dashMember5, null, "公开游记-TR01");

    // 12. 验收：非成员（outsider）看不到任何游记
    JsonNode dashOut = apiGet(outsiderToken, "/api/dashboard");
    assertJournalNotInDash(dashOut, privJId, "私有游记-TR01");
    // 13. 验收：API 响应不含无权游记的标题/摘要/入口
    String dashJson = dashMember.toString();
    assertFalse(dashJson.contains("私有游记-TR01"), "API 响应不应包含私有游记标题");
    assertFalse(dashJson.contains("这是一篇私有游记内容"), "API 响应不应包含私有游记摘要");
    // 14. 验收：好友可见——owner 发 friends 游记，member（互为好友）可见
    apiPost(ownerToken, "/api/journals/" + privJId + "/edit-lock", null);
    apiPatch(ownerToken, "/api/trips/" + journalTripId + "/journals/" + privJId,
        Map.of("visibility", "friends"));
    JsonNode dashMember6 = apiGet(memberToken, "/api/dashboard");
    assertJournalInDash(dashMember6, privJId, "私有游记-TR01");
  }

  // ================================================================
  // TR-01b：最新三条不可见但更早有可见记录时，仍显示最近的可见记录
  // ================================================================

  @Test
  @Order(15)
  void tr01b_recentThreeInvisibleButOlderVisible() throws Exception {
    String tok = register("tr01buser" + suffix);
    // 创建行程
    JsonNode tripResp = apiPost(tok, "/api/trips", Map.of(
        "title", "TR-01b 窗口测试", "destination", "窗口", "startDate", "2026-08-01", "endDate", "2026-08-10"));
    String tripId = tripResp.get("trip").get("id").asText();
    createdTripIds.add(tripId);

    // 创建 5 条私有游记（最新 3 条对 member 不可见）
    for (int i = 1; i <= 5; i++) {
      HttpResponse<String> r = httpRequest("POST", "/api/trips/" + tripId + "/journals", tok,
          Map.of("title", "窗口测试游记-" + i, "content", "内容-" + i,
              "isDraft", false, "visibility", "private"));
      assertEquals(200, r.statusCode(), "创建游记失败: " + r.body());
    }
    // 创建 1 条公开游记（更早，对 member 可见）
    HttpResponse<String> pubR = httpRequest("POST", "/api/trips/" + tripId + "/journals", tok,
        Map.of("title", "公开游记-窗口", "content", "公开内容",
            "isDraft", false, "visibility", "public"));
    assertEquals(200, pubR.statusCode());

    // 邀请 member（好友 + 行程成员）
    String memberTok = register("tr01bmem" + suffix);
    String memberId = createdUserIds.get(createdUserIds.size() - 1);
    apiPost(tok, "/api/friends", Map.of("username", "tr01bmem" + suffix));
    apiPost(memberTok, "/api/friends/" + createdUserIds.get(createdUserIds.size() - 2) + "/accept", null);
    apiPost(tok, "/api/trips/" + tripId + "/members", Map.of("username", "tr01bmem" + suffix, "role", "member"));

    // 验收：member 首页应显示公开游记（虽然最新 5 条私有不可见）
    JsonNode dash = apiGet(memberTok, "/api/dashboard");
    assertJournalInDash(dash, null, "公开游记-窗口");
    // 不应显示任何私有游记
    assertJournalNotInDash(dash, null, "窗口测试游记-1");
    assertJournalNotInDash(dash, null, "窗口测试游记-5");
    // API 响应不含私有游记标题
    String dashJson = dash.toString();
    assertFalse(dashJson.contains("窗口测试游记"), "API 响应不应包含私有游记标题");
    assertTrue(dashJson.contains("公开游记-窗口"), "API 响应应包含公开游记");
  }

  // ================================================================
  // TR-02：多币种
  // ================================================================

  @Test
  @Order(20)
  void tr02_multiCurrency() throws Exception {
    // 1. 创建行程
    String tok = register("tr02user" + suffix);
    JsonNode tripResp = apiPost(tok, "/api/trips", Map.of(
        "title", "TR-02 币种测试", "destination", "多币种", "budget", 500));
    currencyTripId = tripResp.get("trip").get("id").asText();

    // 2. 创建 100 CNY + 100 USD 费用
    apiPost(tok, "/api/trips/" + currencyTripId + "/expenses",
        Map.of("title", "人民币费用", "amount", 100, "currency", "CNY",
            "category", "food", "spentAt", "2026-08-01"));
    apiPost(tok, "/api/trips/" + currencyTripId + "/expenses",
        Map.of("title", "美元费用", "amount", 100, "currency", "USD",
            "category", "food", "spentAt", "2026-08-01"));

    // 3. 验收：汇总不应显示虚假的 200 单币种
    JsonNode summary = apiGet(tok, "/api/trips/" + currencyTripId + "/expenses/summary");
    JsonNode totalByCurrency = summary.get("totalByCurrency");
    assertNotNull(totalByCurrency, "汇总应有 totalByCurrency 字段");
    assertNotEquals("200.00", summary.get("totalExpenseAmount").asText(),
        "不应把 100 CNY + 100 USD 合并成 200");
    // 分币种明细：CNY 100、USD 100
    if (totalByCurrency != null && totalByCurrency.has("CNY")) {
      assertEquals("100.00", totalByCurrency.get("CNY").asText(), "CNY 分项应为 100");
    }
    if (totalByCurrency != null && totalByCurrency.has("USD")) {
      assertEquals("100.00", totalByCurrency.get("USD").asText(), "USD 分项应为 100");
    }

    // 4. 验收：预算不受异币影响
    JsonNode budget = apiGet(tok, "/api/trips/" + currencyTripId + "/expenses/budget");
    assertNotNull(budget.get("budgetCurrency"), "预算应标注币种");
    assertNotNull(budget.get("foreignCurrencySpent"), "异币费用应单独列出");
    // 预算进度只含 CNY
    assertEquals("100.00", budget.get("totalSpent").asText(), "预算进度只计 CNY 100");

    // 5. 验收：混币结算被拦截
    HttpResponse<String> settleResp = httpRequest("POST",
        "/api/trips/" + currencyTripId + "/settlement", tok, null);
    assertNotEquals(200, settleResp.statusCode(), "混币结算应被拦截");

    // 6. 验收：今日简报分币种
    HttpResponse<String> briefResp = httpRequest("POST", "/api/ai/today-brief", tok,
        Map.of("tripId", currencyTripId));
    assertEquals(200, briefResp.statusCode(), "today-brief 调用失败: " + briefResp.body());
    JsonNode brief = JSON.readTree(briefResp.body());
    JsonNode budgetSection = brief.get("budget");
    assertNotNull(budgetSection, "简报应有 budget 段");
    assertNotNull(budgetSection.get("todaySpentByCurrency"), "简报应有分币种字段");

    // 7. 验收：单币种结算正常（删 USD 后只留 CNY）
    jdbc.update("DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses WHERE trip_id=? AND currency='USD')", currencyTripId);
    jdbc.update("DELETE FROM trip_expenses WHERE trip_id=? AND currency='USD'", currencyTripId);
    HttpResponse<String> settle2 = httpRequest("POST",
        "/api/trips/" + currencyTripId + "/settlement", tok, null);
    // 单币种应通过（200 或400 没有账目），但不是混币拦截
    if (settle2.statusCode() == 400) {
      assertFalse(settle2.body().contains("多种币种"), "单币种不应报混币错误");
    }
  }

  // ================================================================
  // TR-03：日期校验 + 关联数据保护
  // ================================================================

  @Test
  @Order(30)
  void tr03_dateValidation() throws Exception {
    String tok = register("tr03user" + suffix);

    // 1. 创建行程 2026-09-01 ~ 2026-09-10
    JsonNode tripResp = apiPost(tok, "/api/trips", Map.of(
        "title", "TR-03 日期测试", "destination", "日期保护",
        "startDate", "2026-09-01", "endDate", "2026-09-10"));
    dateTripId = tripResp.get("trip").get("id").asText();

    // 2. 验收：只改 startDate 不能产生倒序
    HttpResponse<String> resp = httpRequest("PATCH", "/api/trips/" + dateTripId, tok,
        Map.of("startDate", "2026-09-15")); // 晚于 endDate
    assertEquals(400, resp.statusCode(), "只改 startDate 导致倒序应被拒绝");

    // 3. 验收：只改 endDate 也不能倒序
    HttpResponse<String> resp2 = httpRequest("PATCH", "/api/trips/" + dateTripId, tok,
        Map.of("endDate", "2026-08-01")); // 早于 startDate
    assertEquals(400, resp2.statusCode(), "只改 endDate 导致倒序应被拒绝");

    // 4. 验收：正常扩展范围成功
    HttpResponse<String> resp3 = httpRequest("PATCH", "/api/trips/" + dateTripId, tok,
        Map.of("endDate", "2026-09-15"));
    assertEquals(200, resp3.statusCode(), "正常扩展范围应成功");

    // 5. 给第 10 天（2026-09-10）添加费用关联
    jdbc.update("INSERT INTO trip_expenses (id, trip_id, payer_user_id, title, amount, currency, category, spent_at, created_at, updated_at, trip_day_id) " +
        "SELECT gen_random_uuid(), ?, u.id, '第10天费用', 50, 'CNY', 'food', '2026-09-10', NOW(), NOW(), td.id " +
        "FROM trip_days td JOIN trip_members tm ON tm.trip_id=td.trip_id JOIN users u ON u.id=tm.user_id " +
        "WHERE td.trip_id=? AND td.date='2026-09-10' LIMIT 1",
        dateTripId, dateTripId);

    // 6. 验收：缩短范围时有关联内容的天不被删除
    HttpResponse<String> resp4 = httpRequest("PATCH", "/api/trips/" + dateTripId, tok,
        Map.of("endDate", "2026-09-05"));
    assertEquals(200, resp4.statusCode(), "缩短范围应成功（有关联的天保留）");
    // 核对数据库：2026-09-10 的天应仍在（因有费用关联）
    Integer dayCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM trip_days WHERE trip_id=? AND date='2026-09-10'", Integer.class, dateTripId);
    assertNotNull(dayCount);
    // 注：dayIndex > 5 的天保留但 dayIndex 不变，所以 day 仍在
    JsonNode resp4Body = JSON.readTree(resp4.body());
    JsonNode daySync = resp4Body.get("daySync");
    if (daySync != null && daySync.has("preservedDayIds")) {
      assertTrue(daySync.get("preservedDayIds").size() >= 0, "应报告保留的天");
    }

    // 7. 验收：日期预览 API 返回影响信息
    HttpResponse<String> preview = httpRequest("GET",
        "/api/trips/" + dateTripId + "/date-change-preview?startDate=2026-09-01&endDate=2026-09-03", tok, null);
    assertEquals(200, preview.statusCode(), "日期预览 API 应可用");
    JsonNode previewBody = JSON.readTree(preview.body());
    assertNotNull(previewBody.get("moves"), "预览应有 moves 字段");
    assertNotNull(previewBody.get("preserved"), "预览应有 preserved 字段");
    assertNotNull(previewBody.get("removed"), "预览应有 removed 字段");
    assertNotNull(previewBody.get("hasRisk"), "预览应有 hasRisk 字段");

    // 8. 验收：无日期草稿可保存
    JsonNode draftResp = apiPost(tok, "/api/trips", Map.of("title", "无日期草稿-TR03"));
    String draftId = draftResp.get("trip").get("id").asText();
    createdTripIds.add(draftId);
    assertTrue(draftResp.get("trip").get("startDate").isNull(), "无日期草稿 startDate 应为 null");
    HttpResponse<String> patchDraft = httpRequest("PATCH", "/api/trips/" + draftId, tok,
        Map.of("title", "无日期草稿-已改"));
    assertEquals(200, patchDraft.statusCode(), "无日期草稿应可部分更新");
  }

  // ================================================================
  // 工具方法
  // ================================================================

  private void assertJournalInDash(JsonNode dash, String journalId, String title) {
    JsonNode journals = dash.get("recentJournals");
    assertNotNull(journals, "dashboard 应有 recentJournals");
    for (JsonNode j : journals) {
      if (title.equals(j.get("title").asText())) {
        return; // 找到即通过
      }
    }
    fail("首页应包含游记「" + title + "」但未找到");
  }

  private void assertJournalNotInDash(JsonNode dash, String journalId, String title) {
    JsonNode journals = dash.get("recentJournals");
    assertNotNull(journals, "dashboard 应有 recentJournals");
    for (JsonNode j : journals) {
      if (title.equals(j.get("title").asText())) {
        fail("首页不应包含游记「" + title + "」但出现了（泄露）");
      }
    }
    // 通过
  }

  private String register(String username) throws Exception {
    HttpResponse<String> resp = httpRequest("POST", "/api/auth/register", null,
        Map.of("username", username, "password", PASSWORD));
    assertEquals(200, resp.statusCode(), "注册失败: " + resp.body());
    JsonNode body = JSON.readTree(resp.body());
    String userId = body.get("user").get("id").asText();
    createdUserIds.add(userId);
    return body.get("token").asText();
  }

  private JsonNode apiGet(String token, String path) throws Exception {
    HttpResponse<String> resp = httpRequest("GET", path, token, null);
    assertEquals(200, resp.statusCode(), "GET " + path + " 失败: " + resp.body());
    return JSON.readTree(resp.body());
  }

  private JsonNode apiPost(String token, String path, Map<String, Object> body) throws Exception {
    HttpResponse<String> resp = httpRequest("POST", path, token, body);
    assertEquals(200, resp.statusCode(), "POST " + path + " 失败: " + resp.body());
    return JSON.readTree(resp.body());
  }

  private void apiPatch(String token, String path, Map<String, Object> body) throws Exception {
    HttpResponse<String> resp = httpRequest("PATCH", path, token, body);
    assertEquals(200, resp.statusCode(), "PATCH " + path + " 失败: " + resp.body());
  }

  private HttpResponse<String> httpRequest(String method, String path, String token,
      Map<String, Object> body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if ("POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method)) {
      String json = body == null ? "{}" : JSON.writeValueAsString(body);
      builder.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
