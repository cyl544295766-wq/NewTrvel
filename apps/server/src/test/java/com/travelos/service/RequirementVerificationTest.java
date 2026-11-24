package com.travelos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.ai.LlmGatewayService;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

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

  @Autowired
  com.travelos.config.BookingReminderScheduler bookingReminders;

  @Autowired
  com.travelos.config.DocumentReminderScheduler documentReminders;

  @Autowired
  FileStorageService fileStorage;

  @MockBean
  LlmGatewayService llm;

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
      jdbc.update("DELETE FROM edit_locks WHERE scope_id IN (SELECT id FROM trip_days WHERE trip_id=?)"
          + " OR scope_id IN (SELECT 'journal:' || id FROM trip_journals WHERE trip_id=?)", tid, tid);
      jdbc.update("DELETE FROM trip_journal_audience WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM trip_journal_photos WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM trip_journals WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM trip_expenses WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM bookings WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_places WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_legs WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_days WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM photos WHERE trip_id=?", tid);
      jdbc.queryForList("SELECT url FROM travel_documents WHERE trip_id=? AND url LIKE '/files/%'", String.class, tid)
          .forEach(fileStorage::deleteIfManaged);
      jdbc.update("DELETE FROM travel_documents WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM packing_items WHERE packing_list_id IN (SELECT id FROM packing_lists WHERE trip_id=?)", tid);
      jdbc.update("DELETE FROM packing_lists WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_members WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_settlements WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trip_budgets WHERE trip_id=?", tid);
      jdbc.update("DELETE FROM trips WHERE id=?", tid);
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM trips WHERE id=?", Integer.class, tid));
    }
    for (String uid : createdUserIds) {
      jdbc.update("DELETE FROM notifications WHERE user_id=?", uid);
      jdbc.update("DELETE FROM user_friends WHERE user_id=? OR friend_id=?", uid, uid);
      jdbc.update("DELETE FROM users WHERE id=?", uid);
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, uid));
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
  void tr01b_scansPastFirstBatchAndBreaksTimestampTies() throws Exception {
    String owner = register("tr01buser" + suffix);
    String ownerId = createdUserIds.get(createdUserIds.size() - 1);
    String reader = register("tr01bmem" + suffix);
    String tripId = apiPost(owner, "/api/trips", Map.of("title", "游记过滤测试"))
        .path("trip").path("id").asText();
    createdTripIds.add(tripId);
    String journals = "/api/trips/" + tripId + "/journals";
    invite(owner, ownerId, reader, "tr01bmem" + suffix, tripId);

    for (int i = 0; i < 22; i++) {
      apiPost(owner, journals, Map.of("title", "不可见标题-" + i, "content", "不可见内容-" + i,
          "isDraft", false, "visibility", "private"));
    }
    assertEquals(0, apiGet(reader, "/api/dashboard").path("recentJournals").size());
    List<String> visible = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      visible.add(apiPost(owner, journals, Map.of("title", "可见游记-" + i, "content", "公开内容",
          "isDraft", false, "visibility", "public")).path("journal").path("id").asText());
    }
    jdbc.update("UPDATE trip_journals SET created_at='2026-08-01' WHERE trip_id=? AND visibility='public'", tripId);
    jdbc.update("UPDATE trip_journals SET created_at='2026-08-02' WHERE id=?", visible.get(0));
    // 保证分页边界上所有不可见记录都具有相同时间。
    jdbc.update("UPDATE trip_journals SET created_at='2026-08-03' WHERE trip_id=? AND visibility='private'", tripId);

    JsonNode dashboard = apiGet(reader, "/api/dashboard");
    List<String> expected = new ArrayList<>(visible.subList(1, 4));
    expected.sort(java.util.Comparator.reverseOrder());
    expected.add(0, visible.get(0));
    List<String> actual = new ArrayList<>();
    dashboard.path("recentJournals").forEach(j -> actual.add(j.path("id").asText()));
    assertEquals(expected.subList(0, 3), actual);
    assertFalse(dashboard.toString().contains("不可见标题"));
    assertFalse(dashboard.toString().contains("不可见内容"));
    jdbc.update("UPDATE trip_journals SET is_draft=true WHERE trip_id=? AND id<>?", tripId, visible.get(0));
    JsonNode one = apiGet(reader, "/api/dashboard").path("recentJournals");
    assertEquals(1, one.size());
    assertEquals(visible.get(0), one.get(0).path("id").asText());
  }

  @Test
  @Order(20)
  void tr02_multiCurrency() throws Exception {
    String owner = register("tr02user" + suffix);
    String ownerId = createdUserIds.get(createdUserIds.size() - 1);
    String member = register("tr02member" + suffix);
    String memberId = createdUserIds.get(createdUserIds.size() - 1);
    String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    currencyTripId = apiPost(owner, "/api/trips", Map.of(
        "title", "TR-02 币种测试", "startDate", today, "endDate", today, "budget", 500))
        .path("trip").path("id").asText();
    invite(owner, ownerId, member, "tr02member" + suffix, currencyTripId);
    String path = "/api/trips/" + currencyTripId;
    String expenses = path + "/expenses";
    apiPost(owner, path + "/days/generate", null);
    apiPost(owner, expenses, Map.of("title", "人民币费用", "amount", 100, "currency", "CNY",
        "category", "food", "spentAt", today, "shareUserIds", List.of(ownerId, memberId)));
    String confirm = expenses + "/settlement/confirm";
    JsonNode snapshot = apiPost(owner, confirm, null).path("settlement");
    assertEquals("CNY", snapshot.path("snapshot").path("currency").asText());
    assertEquals("50.00", snapshot.path("snapshot").path("transfers").get(0).path("amount").asText());

    apiPost(owner, expenses, Map.of("title", "美元费用", "amount", 100, "currency", "USD",
        "category", "food", "spentAt", today, "shareUserIds", List.of(ownerId, memberId)));
    JsonNode wallet = apiGet(owner, expenses + "/summary");
    JsonNode totalByCurrency = wallet.path("totalByCurrency");
    assertEquals("100.00", totalByCurrency.path("CNY").asText());
    assertEquals("100.00", totalByCurrency.path("USD").asText());
    assertNotEquals("200.00", wallet.path("totalExpenseAmount").asText());
    JsonNode report = apiGet(owner, expenses + "/report");
    assertEquals("100.00", report.path("totalAmount").asText());
    assertEquals("100.00", report.path("otherCurrencies")
        .path("USD".equals(report.path("currency").asText()) ? "CNY" : "USD").asText());

    String bookingId = apiPost(owner, path + "/bookings", Map.of("title", "外币预订",
        "type", "hotel", "amount", "125.43", "currency", "USD"))
        .path("booking").path("id").asText();
    JsonNode budget = apiGet(owner, expenses + "/budget");
    assertEquals("CNY", budget.path("budgetCurrency").asText());
    assertEquals("100.00", budget.path("totalSpent").asText());
    assertEquals("0.00", budget.path("totalEstimated").asText());
    assertEquals("400.00", budget.path("totalRemaining").asText());
    assertEquals("400.00", wallet.path("remainingBudget").asText());
    assertEquals("100.00", budget.path("foreignCurrencySpent").path("USD").asText());
    assertEquals("125.43", budget.path("foreignCurrencyEstimated").path("USD").asText());

    JsonNode plan = apiGet(owner, expenses + "/settlement");
    assertFalse(plan.path("canConfirm").asBoolean(true));
    assertTrue(plan.path("confirmBlockedReason").asText().contains("多种币种"));
    JsonNode cny = plan.path("byCurrency").path("CNY");
    assertEquals("0.00", cny.path("totalAmount").asText());
    assertEquals(0, cny.path("transfers").size());
    JsonNode usd = plan.path("byCurrency").path("USD");
    assertEquals("100.00", usd.path("totalAmount").asText());
    assertEquals("50.00", usd.path("transfers").get(0).path("amount").asText());
    assertEquals(snapshot.path("id"), plan.path("latestSettlement").path("id"));
    HttpResponse<String> rejected = httpRequest("POST", confirm, owner, null);
    assertEquals(400, rejected.statusCode());
    assertTrue(rejected.body().contains("多种币种"));

    JsonNode brief = apiPost(owner, "/api/ai/today-brief",
        Map.of("tripId", currencyTripId, "date", today, "refresh", true));
    JsonNode briefAmounts = brief.path("budget").path("todaySpentByCurrency");
    assertEquals("100.00", briefAmounts.path("CNY").asText());
    assertEquals("100.00", briefAmounts.path("USD").asText());
    assertEquals("fallback", brief.path("summarySource").asText());
    assertTrue(brief.path("summary").asText().contains("100.00 CNY"));
    assertTrue(brief.path("summary").asText().contains("100.00 USD"));
    when(llm.isEnabled()).thenReturn(true);
    when(llm.chat(anyList(), isNull())).thenReturn(Map.of("content", "今日安排可在时间轴查看。"));
    JsonNode aiBrief = apiPost(owner, "/api/ai/today-brief",
        Map.of("tripId", currencyTripId, "date", today, "refresh", true));
    assertEquals("ai", aiBrief.path("summarySource").asText());
    assertTrue(aiBrief.path("summary").asText().contains("100.00 CNY"));
    assertTrue(aiBrief.path("summary").asText().contains("100.00 USD"));

    apiPost(owner, path + "/bookings/" + bookingId + "/convert-expense", null);
    JsonNode converted = apiGet(owner, expenses + "/budget");
    assertEquals("100.00", converted.path("totalSpent").asText());
    assertEquals("0.00", converted.path("totalEstimated").asText());
    assertEquals(0, converted.path("foreignCurrencyEstimated").size());
    assertEquals("225.43", converted.path("foreignCurrencySpent").path("USD").asText());

    String foreignTrip = apiPost(owner, "/api/trips", Map.of("title", "仅外币"))
        .path("trip").path("id").asText();
    createdTripIds.add(foreignTrip);
    apiPost(owner, "/api/trips/" + foreignTrip + "/members",
        Map.of("username", "tr02member" + suffix, "role", "member"));
    String foreignExpenses = "/api/trips/" + foreignTrip + "/expenses";
    JsonNode expense = apiPost(owner, foreignExpenses, Map.of("amount", "0.03", "currency", "USD",
        "shareUserIds", List.of(ownerId, memberId))).path("expense");
    java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
    for (JsonNode share : expense.path("shares")) {
      sum = sum.add(new java.math.BigDecimal(share.path("shareAmount").asText()));
    }
    assertEquals(new java.math.BigDecimal("0.03"), sum);
    assertTrue(apiGet(owner, foreignExpenses + "/settlement").path("canConfirm").asBoolean());
    assertEquals("USD", apiPost(owner, foreignExpenses + "/settlement/confirm", null)
        .path("settlement").path("snapshot").path("currency").asText());
    assertEquals("0.00", apiGet(owner, foreignExpenses + "/budget").path("totalSpent").asText());
  }


  @Test
  @Order(30)
  void tr03_previewRejectsStalePlansAndPreservesEveryDayReference() throws Exception {
    String owner = register("tr03user" + suffix);
    String ownerId = createdUserIds.get(createdUserIds.size() - 1);
    String member = register("tr03member" + suffix);
    dateTripId = apiPost(owner, "/api/trips", Map.of("title", "TR-03 改期",
        "startDate", "2026-09-01", "endDate", "2026-09-10")).path("trip").path("id").asText();
    invite(owner, ownerId, member, "tr03member" + suffix, dateTripId);
    String path = "/api/trips/" + dateTripId;
    String daysPath = path + "/days";
    apiPost(owner, daysPath + "/generate", null);
    assertEquals(400, httpRequest("PATCH", path, owner, Map.of("startDate", "2026-09-15")).statusCode());
    assertEquals(400, httpRequest("PATCH", path, owner, Map.of("endDate", "2026-08-01")).statusCode());
    assertEquals(400, httpRequest("GET", path + "/date-change-preview?endDate=2026-12-01", owner, null).statusCode());

    JsonNode expansion = apiGet(owner, path + "/date-change-preview?endDate=2026-09-15");
    assertEquals(5, expansion.path("additions").size());
    apiPatch(owner, path, Map.of("endDate", "2026-09-15", "previewToken", expansion.path("previewToken").asText()));
    List<String> dayIds = new ArrayList<>();
    apiGet(owner, daysPath).path("days").forEach(day -> dayIds.add(day.path("id").asText()));
    assertEquals(15, dayIds.size());

    String previewPath = path + "/date-change-preview?endDate=2026-09-03";
    JsonNode stale = apiGet(owner, previewPath);
    assertEquals(12, stale.path("removed").size());
    assertEquals("2026-09-15", apiGet(owner, path).path("trip").path("endDate").asText().substring(0, 10));
    String mutableDay = daysPath + "/" + dayIds.get(3);
    apiPost(member, mutableDay + "/edit-lock", null);
    apiPatch(member, mutableDay, Map.of("title", "协作者保留的第 4 天"));
    assertEquals(409, httpRequest("PATCH", path, owner,
        Map.of("endDate", "2026-09-03", "previewToken", stale.path("previewToken").asText())).statusCode());

    JsonNode staleAfterTitle = apiGet(owner, previewPath);
    apiPost(member, path + "/expenses", Map.of("title", "第 5 天费用", "amount", 25,
        "tripDayId", dayIds.get(4), "spentAt", "2026-09-05"));
    assertEquals(409, httpRequest("PATCH", path, owner, Map.of("endDate", "2026-09-03",
        "previewToken", staleAfterTitle.path("previewToken").asText())).statusCode());
    assertEquals(15, apiGet(owner, daysPath).path("days").size());

    jdbc.update("INSERT INTO photos(id,trip_id,trip_day_id,url,updated_at) "
        + "VALUES (gen_random_uuid()::text,?,?, 'https://example.invalid/photo.jpg',now())", dateTripId, dayIds.get(5));
    apiPost(member, path + "/journals", Map.of("title", "第 7 天游记", "content", "行程记录",
        "isDraft", true, "tripDayId", dayIds.get(6)));
    jdbc.update("INSERT INTO trip_places(id,trip_id,trip_day_id,name,updated_at) "
        + "VALUES (gen_random_uuid()::text,?,?, '第 8 天地点',now())", dateTripId, dayIds.get(7));
    apiPost(member, path + "/bookings", Map.of("title", "第 9 天预订", "type", "hotel", "tripDayId", dayIds.get(8)));
    jdbc.update("INSERT INTO travel_documents(id,trip_id,trip_day_id,type,title,url,updated_at) "
        + "VALUES (gen_random_uuid()::text,?,?,'other','第 10 天文档','https://example.invalid/doc',now())",
        dateTripId, dayIds.get(9));
    jdbc.update("INSERT INTO trip_legs(id,trip_id,trip_day_id) VALUES (gen_random_uuid()::text,?,?)",
        dateTripId, dayIds.get(10));

    JsonNode fresh = apiGet(owner, previewPath);
    List<String> plannedPreserved = new ArrayList<>();
    fresh.path("preserved").forEach(day -> plannedPreserved.add(day.path("dayId").asText()));
    assertEquals(dayIds.subList(3, 11), plannedPreserved);
    assertEquals(4, fresh.path("removed").size());
    HttpResponse<String> saved = httpRequest("PATCH", path, owner, Map.of("endDate", "2026-09-03",
        "previewToken", fresh.path("previewToken").asText()));
    assertEquals(200, saved.statusCode(), saved.body());
    JsonNode sync = JSON.readTree(saved.body()).path("daySync");
    assertEquals(fresh.path("preserved"), sync.path("preserved"));
    assertEquals(fresh.path("removed"), sync.path("removedDays"));
    assertEquals(11, apiGet(owner, daysPath).path("days").size());

    List<String> tables = List.of("trip_expenses", "photos", "trip_journals", "trip_places",
        "bookings", "travel_documents", "trip_legs");
    for (int i = 0; i < tables.size(); i++) {
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + tables.get(i)
          + " WHERE trip_id=? AND trip_day_id=?", Integer.class, dateTripId, dayIds.get(4 + i)));
    }

    String collision = path + "/date-change-preview?startDate=2026-09-08&endDate=2026-09-10";
    JsonNode blocked = apiGet(owner, collision);
    assertFalse(blocked.path("canSave").asBoolean(true));
    assertTrue(blocked.path("blockedReason").asText().contains("重叠"));
    assertEquals(409, httpRequest("PATCH", path, owner, Map.of("title", "不应保存",
        "startDate", "2026-09-08", "endDate", "2026-09-10",
        "previewToken", blocked.path("previewToken").asText())).statusCode());
    JsonNode afterCollision = apiGet(owner, path).path("trip");
    assertEquals("TR-03 改期", afterCollision.path("title").asText());
    assertEquals("2026-09-03", afterCollision.path("endDate").asText().substring(0, 10));

    JsonNode shift = apiGet(owner, path + "/date-change-preview?startDate=2026-08-30&endDate=2026-09-01");
    assertEquals(3, shift.path("moves").size());
    apiPatch(owner, path, Map.of("startDate", "2026-08-30", "endDate", "2026-09-01",
        "previewToken", shift.path("previewToken").asText()));
    JsonNode first = apiGet(owner, daysPath).path("days").get(0);
    assertEquals(dayIds.get(0), first.path("id").asText());
    assertEquals("2026-08-30", first.path("date").asText().substring(0, 10));

    JsonNode clear = apiGet(owner, path + "/date-change-preview?endDate=");
    assertTrue(clear.path("newRange").path("endDate").isNull());
    assertEquals(11, clear.path("preserved").size());
    Map<String, Object> clearPayload = new java.util.HashMap<>();
    clearPayload.put("endDate", null);
    clearPayload.put("previewToken", clear.path("previewToken").asText());
    apiPatch(owner, path, clearPayload);
    assertTrue(apiGet(owner, path).path("trip").path("endDate").isNull());
    apiPatch(owner, path, Map.of("title", "日期待定"));
    assertEquals(11, apiGet(owner, daysPath).path("days").size());

    String draftId = apiPost(owner, "/api/trips", Map.of("title", "无日期草稿"))
        .path("trip").path("id").asText();
    createdTripIds.add(draftId);
    String draft = "/api/trips/" + draftId;
    apiPatch(owner, draft, Map.of("title", "无日期草稿可编辑"));
    assertTrue(apiGet(owner, draft).path("trip").path("startDate").isNull());
    JsonNode fill = apiGet(owner, draft + "/date-change-preview?startDate=2026-09-01&endDate=2026-09-03");
    assertEquals(3, fill.path("createCount").asInt());
    apiPatch(owner, draft, Map.of("startDate", "2026-09-01", "endDate", "2026-09-03",
        "previewToken", fill.path("previewToken").asText()));
    assertEquals(3, apiGet(owner, draft + "/days").path("days").size());
  }

  @Test
  @Order(40)
  void tr10And11_bookingCreditsAndSettlementDeltas() throws Exception {
    String owner = register("tr10owner" + suffix);
    String ownerId = createdUserIds.get(createdUserIds.size() - 1);
    String guest = register("tr10guest" + suffix);
    String guestId = createdUserIds.get(createdUserIds.size() - 1);
    String tripId = apiPost(owner, "/api/trips", Map.of("title", "预订退款与结算", "budget", 500))
        .path("trip").path("id").asText();
    createdTripIds.add(tripId);
    invite(owner, ownerId, guest, "tr10guest" + suffix, tripId);
    String path = "/api/trips/" + tripId;
    String expenses = path + "/expenses";
    String bookings = path + "/bookings";
    String confirm = expenses + "/settlement/confirm";

    JsonNode cancelled = apiPost(owner, bookings, Map.of("title", "取消行程", "type", "hotel", "amount", "20",
        "currency", "USD")).path("booking");
    assertEquals("20.00", apiGet(owner, expenses + "/budget").path("foreignCurrencyEstimated").path("USD").asText());
    apiPost(owner, bookings + "/" + cancelled.path("id").asText() + "/cancel", null);
    assertTrue(apiGet(owner, expenses + "/budget").path("foreignCurrencyEstimated").isEmpty());

    JsonNode booking = apiPost(owner, bookings, Map.of("title", "美元住宿", "type", "hotel", "amount", "120",
        "currency", "USD")).path("booking");
    String bookingId = booking.path("id").asText();
    String entry = bookings + "/" + bookingId;
    Map<String, Object> payment = Map.of("operationKey", "booking-payment", "amount", "100.03",
        "currency", "USD", "payerUserId", guestId, "shareUserIds", List.of(ownerId, guestId));
    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    JsonNode booked;
    try {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      java.util.concurrent.Callable<JsonNode> send = () -> {
        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
        return apiPost(owner, entry + "/convert-expense", payment);
      };
      var first = executor.submit(send);
      var second = executor.submit(send);
      booked = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(booked.path("expense").path("id"), second.get(20, java.util.concurrent.TimeUnit.SECONDS)
          .path("expense").path("id"));
    } finally {
      executor.shutdownNow();
    }
    String paidId = booked.path("expense").path("id").asText();
    assertEquals(1, apiGet(owner, expenses).path("expenses").size());
    assertEquals("100.03", booked.path("booking").path("paidAmount").asText());
    assertTrue(apiGet(owner, expenses + "/budget").path("foreignCurrencyEstimated").isEmpty());
    assertEquals(409, httpRequest("DELETE", expenses + "/" + paidId, owner, null).statusCode());

    apiPost(owner, expenses, Map.of("amount", "60.00", "currency", "CNY",
        "shareUserIds", List.of(ownerId, guestId)));
    JsonNode firstPlan = apiGet(owner, expenses + "/settlement");
    String usdToken = firstPlan.path("byCurrency").path("USD").path("previewToken").asText();
    apiPost(owner, entry + "/cancel", null);
    assertEquals("100.03", apiGet(owner, expenses + "/budget").path("foreignCurrencySpent").path("USD").asText());
    Map<String, Object> refundBody = Map.of("operationKey", "refund-one", "amount", "10.01");
    JsonNode refund = apiPost(owner, entry + "/refund", refundBody);
    assertEquals("partial_refund", refund.path("booking").path("paymentStatus").asText());
    assertEquals("-10.01", refund.path("expense").path("amount").asText());
    assertEquals(refund.path("expense").path("id"), apiPost(owner, entry + "/refund", refundBody).path("expense").path("id"));
    assertEquals(409, httpRequest("POST", entry + "/refund", owner,
        Map.of("operationKey", "refund-one", "amount", "9")).statusCode());
    assertEquals(400, httpRequest("POST", entry + "/refund", owner,
        Map.of("operationKey", "too-much", "amount", "90.03")).statusCode());
    assertEquals(409, httpRequest("POST", confirm, owner,
        Map.of("currency", "USD", "previewToken", usdToken)).statusCode());

    JsonNode choices = apiGet(owner, expenses + "/settlement").path("byCurrency");
    JsonNode cny = apiPost(owner, confirm, confirmation("CNY", choices.path("CNY"))).path("settlement");
    JsonNode usd = apiPost(owner, confirm, confirmation("USD", choices.path("USD"))).path("settlement");
    assertEquals("60.00", cny.path("snapshot").path("totalAmount").asText());
    assertEquals("90.02", usd.path("snapshot").path("totalAmount").asText());
    assertEquals(usd.path("id"), apiPost(owner, confirm, confirmation("USD", choices.path("USD")))
        .path("settlement").path("id"));

    String membership = "";
    for (JsonNode member : apiGet(owner, path + "/members").path("members")) {
      if (guestId.equals(member.path("userId").asText())) membership = member.path("id").asText();
    }
    assertFalse(membership.isEmpty());
    assertEquals(200, httpRequest("DELETE", path + "/members/" + membership, owner, null).statusCode());
    assertTrue(httpRequest("GET", expenses + "/settlement", guest, null).statusCode() >= 400);
    JsonNode lastRefund = apiPost(owner, entry + "/refund", Map.of("operationKey", "refund-two", "amount", "90.02"));
    assertEquals("refunded", lastRefund.path("booking").path("paymentStatus").asText());
    assertEquals("0.00", apiGet(owner, expenses + "/budget").path("foreignCurrencySpent").path("USD").asText());

    apiPost(owner, expenses, Map.of("amount", "20.00", "currency", "USD", "shareUserIds", List.of(ownerId)));
    JsonNode delta = apiGet(owner, expenses + "/settlement").path("byCurrency").path("USD");
    assertEquals("-70.02", delta.path("totalAmount").asText());
    assertEquals(usd.path("id").asText(), delta.path("baseSettlementId").asText());
    boolean formerMemberVisible = false;
    long paid = 0, owed = 0;
    for (JsonNode net : delta.path("nets")) {
      if (guestId.equals(net.path("userId").asText())) formerMemberVisible = net.path("formerMember").asBoolean();
      paid += new java.math.BigDecimal(net.path("paid").asText()).movePointRight(2).longValueExact();
      owed += new java.math.BigDecimal(net.path("owed").asText()).movePointRight(2).longValueExact();
    }
    assertTrue(formerMemberVisible);
    assertEquals(paid, owed);
    assertEquals("-70.02", apiPost(owner, confirm, confirmation("USD", delta)).path("settlement")
        .path("snapshot").path("totalAmount").asText());
    JsonNode history = apiGet(owner, expenses + "/settlement").path("history");
    assertEquals(3, history.size());
    assertTrue(java.util.stream.StreamSupport.stream(history.spliterator(), false)
        .anyMatch(row -> row.path("id").equals(usd.path("id")) && row.path("snapshot").equals(usd.path("snapshot"))));

    String reversedBooking = apiPost(owner, bookings, Map.of("title", "误入账", "type", "hotel", "amount", "15"))
        .path("booking").path("id").asText();
    String reversePath = bookings + "/" + reversedBooking;
    apiPost(owner, reversePath + "/convert-expense", Map.of("operationKey", "reversible-payment"));
    JsonNode reversed = apiPost(owner, reversePath + "/reverse-expense", Map.of("operationKey", "reversal-one"));
    assertEquals("pending", reversed.path("booking").path("paymentStatus").asText());
    assertTrue(reversed.path("booking").path("expenseId").isNull());
    assertEquals("reversal", reversed.path("expense").path("adjustmentKind").asText());
    assertEquals("15.00", apiGet(owner, expenses + "/budget").path("totalEstimated").asText());
    assertEquals("60.00", apiGet(owner, expenses + "/budget").path("totalSpent").asText());
  }

  @Test
  @Order(41)
  void tr11_legacyCurrencySnapshotRequiresAcknowledgedBaseline() throws Exception {
    String owner = register("tr11owner" + suffix);
    String ownerId = createdUserIds.get(createdUserIds.size() - 1);
    String guest = register("tr11guest" + suffix);
    String guestId = createdUserIds.get(createdUserIds.size() - 1);
    String tripId = apiPost(owner, "/api/trips", Map.of("title", "历史结算兼容"))
        .path("trip").path("id").asText();
    createdTripIds.add(tripId);
    invite(owner, ownerId, guest, "tr11guest" + suffix, tripId);
    String legacyId = java.util.UUID.randomUUID().toString();
    Map<String, Object> legacy = Map.of("currency", "CNY", "totalAmount", "100.00", "nets", List.of(
        Map.of("userId", ownerId, "paid", "100.00", "owed", "50.00"),
        Map.of("userId", guestId, "paid", "0.00", "owed", "50.00")));
    jdbc.update("INSERT INTO trip_settlements (id, trip_id, snapshot, status, confirmed_by) VALUES (?, ?, ?::jsonb, 'confirmed', ?)",
        legacyId, tripId, JSON.writeValueAsString(legacy), ownerId);
    String expenses = "/api/trips/" + tripId + "/expenses";
    apiPost(owner, expenses, Map.of("amount", "130.00", "shareUserIds", List.of(ownerId, guestId)));
    JsonNode plan = apiGet(owner, expenses + "/settlement").path("byCurrency").path("CNY");
    assertTrue(plan.path("legacyBaseline").asBoolean());
    assertEquals(legacyId, plan.path("baseSettlementId").asText());
    assertEquals("30.00", plan.path("totalAmount").asText());
    String confirm = expenses + "/settlement/confirm";
    assertEquals(409, httpRequest("POST", confirm, owner, confirmation("CNY", plan)).statusCode());
    Map<String, Object> acknowledged = new java.util.LinkedHashMap<>(confirmation("CNY", plan));
    acknowledged.put("acceptLegacyBaseline", true);
    assertEquals("30.00", apiPost(owner, confirm, acknowledged).path("settlement").path("snapshot").path("totalAmount").asText());
    assertEquals(2, apiGet(owner, expenses + "/settlement").path("history").size());
  }

  private Map<String, Object> confirmation(String currency, JsonNode group) {
    return Map.of("currency", currency, "previewToken", group.path("previewToken").asText());
  }


  @Test
  @Order(50)
  void tr05And06_shareTripChoiceAndPreserveAppointmentInstants() throws Exception {
    String owner = register("tr0506" + suffix);
    apiPut(owner, "/api/auth/preferences", Map.of("timezone", "America/Los_Angeles"));
    java.time.ZoneId zone = java.time.ZoneId.of("America/Los_Angeles");
    java.time.LocalDate today = java.time.LocalDate.now(zone);
    JsonNode empty = apiGet(owner, "/api/dashboard").path("journey");
    assertEquals("/trip-form/", empty.path("primary").path("url").asText());

    String draft = apiPost(owner, "/api/trips", Map.of("title", "日期待定"))
        .path("trip").path("id").asText();
    createdTripIds.add(draft);
    JsonNode draftContext = apiGet(owner, "/api/dashboard").path("journey");
    assertEquals("draft", draftContext.path("phase").asText());
    assertEquals("/trip-form/?id=" + draft, draftContext.path("primary").path("url").asText());
    assertTrue(draftContext.path("daysUntilStart").isMissingNode());

    String active = apiPost(owner, "/api/trips", Map.of("title", "进行中的行程",
        "startDate", today.toString(), "endDate", today.plusDays(1).toString()))
        .path("trip").path("id").asText();
    createdTripIds.add(active);
    String trip = "/api/trips/" + active;
    String dayId = apiPost(owner, trip + "/days/generate", null).path("days").get(0).path("id").asText();
    JsonNode home = apiGet(owner, "/api/dashboard").path("journey");
    JsonNode detail = apiGet(owner, trip + "/next-action");
    JsonNode brief = apiPost(owner, "/api/ai/today-brief", Map.of("refresh", true));
    assertEquals("today", home.path("phase").asText());
    assertEquals("America/Los_Angeles", home.path("timeZone").asText());
    assertEquals(today.toString(), home.path("dateKey").asText());
    assertEquals(home.path("primary"), detail.path("primary"));
    assertTrue(home.path("primary").path("url").asText().contains("day=" + dayId));
    assertEquals(active, brief.path("trip").path("id").asText());
    assertEquals(dayId, brief.path("day").path("id").asText());
    assertEquals(today.toString(), brief.path("dateKey").asText());

    JsonNode booking = apiPost(owner, trip + "/bookings", Map.of("title", "跨时区预约", "type", "flight",
        "startTime", "2026-01-18T09:00", "startTimezone", "America/Los_Angeles",
        "endTime", "2026-01-19T08:00", "endTimezone", "Asia/Tokyo")).path("booking");
    assertTrue(booking.path("startTime").asText().startsWith("2026-01-18T17:00"));
    assertTrue(booking.path("endTime").asText().startsWith("2026-01-18T23:00"));
    assertEquals("Asia/Tokyo", booking.path("endTimezone").asText());
    String bookingId = booking.path("id").asText();
    apiPut(owner, "/api/auth/preferences", Map.of("timezone", "Asia/Tokyo"));
    JsonNode changedZone = apiGet(owner, trip + "/bookings").path("bookings").get(0);
    assertEquals(booking.path("startTime"), changedZone.path("startTime"));
    assertEquals("America/Los_Angeles", changedZone.path("startTimezone").asText());
    assertEquals(today.toString(), apiGet(owner, trip).path("trip").path("startDate").asText().substring(0, 10));
    assertEquals(400, httpRequest("PATCH", trip + "/bookings/" + bookingId, owner,
        Map.of("startTime", "2026-03-08T02:30", "startTimezone", "America/Los_Angeles")).statusCode());

    apiPut(owner, "/api/auth/preferences", Map.of("timezone", zone.getId()));
    String overlap = apiPost(owner, "/api/trips", Map.of("title", "并行行程",
        "startDate", today.toString(), "endDate", today.plusDays(1).toString()))
        .path("trip").path("id").asText();
    createdTripIds.add(overlap);
    jdbc.update("UPDATE trips SET updated_at='2026-01-01' WHERE id IN (?, ?)", active, overlap);
    assertEquals("choose", apiGet(owner, "/api/dashboard").path("journey").path("phase").asText());
    assertEquals("choose", apiPost(owner, "/api/ai/today-brief", Map.of("refresh", true)).path("mode").asText());
    assertEquals(overlap, apiGet(owner, "/api/dashboard?tripId=" + overlap).path("journey").path("trip").path("id").asText());
    assertEquals(overlap, apiPost(owner, "/api/ai/today-brief", Map.of("tripId", overlap))
        .path("trip").path("id").asText());
    apiPatch(owner, "/api/trips/" + overlap + "/archive", Map.of());
    assertEquals("archived", apiGet(owner, "/api/trips/" + overlap + "/next-action").path("phase").asText());
    assertEquals(active, apiGet(owner, "/api/dashboard").path("journey").path("trip").path("id").asText());

    String upcoming = apiPost(owner, "/api/trips", Map.of("title", "即将出发行程",
        "startDate", today.plusDays(1).toString(), "endDate", today.plusDays(2).toString()))
        .path("trip").path("id").asText();
    createdTripIds.add(upcoming);
    assertEquals("/trip-detail/?id=" + upcoming + "#journey-preparation",
        apiGet(owner, "/api/trips/" + upcoming + "/next-action").path("primary").path("url").asText());

    String past = apiPost(owner, "/api/trips", Map.of("title", "已经结束的行程",
        "startDate", today.minusDays(2).toString(), "endDate", today.minusDays(1).toString()))
        .path("trip").path("id").asText();
    createdTripIds.add(past);
    assertEquals("/trip-detail/?id=" + past + "#recapModule",
        apiGet(owner, "/api/trips/" + past + "/next-action").path("primary").path("url").asText());
    apiPost(owner, "/api/trips/" + past + "/expenses", Map.of("title", "旅后补记", "amount", "42", "currency", "CNY"));
    assertEquals("/expenses/?trip=" + past + "&tab=settlement",
        apiGet(owner, "/api/trips/" + past + "/next-action").path("primary").path("url").asText());
  }

  @Test
  @Order(51)
  void tr14_notificationsRespectPreferencesAndClaimEachEventOnce() throws Exception {
    String owner = register("tr14owner" + suffix);
    String ownerId = createdUserIds.get(createdUserIds.size() - 1);
    String guest = register("tr14guest" + suffix);
    String guestId = createdUserIds.get(createdUserIds.size() - 1);
    apiPut(guest, "/api/auth/preferences", Map.of("notifyTrip", false, "notifyDocument", false,
        "notifyMember", false, "notifySummary", false));
    String tripStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1).toString();
    String tripId = apiPost(owner, "/api/trips", Map.of("title", "提醒偏好验收", "startDate", tripStart,
        "endDate", tripStart)).path("trip").path("id").asText();
    createdTripIds.add(tripId);
    invite(owner, ownerId, guest, "tr14guest" + suffix, tripId);
    String trip = "/api/trips/" + tripId;
    assertEquals(tripId, apiGet(guest, trip).path("trip").path("id").asText());
    assertEquals(0, notificationCount(guestId, tripId, "member_invited"));

    String start = java.time.Instant.now().plusSeconds(900).toString();
    String expiry = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(3).toString();
    String pdf = "data:application/pdf;base64,JVBERi0xLjQKJSVFT0Y=";
    String bookingId = apiPost(owner, trip + "/bookings", Map.of("title", "即将出行", "type", "flight",
        "startTime", start, "reminderEnabled", true, "reminderMinutes", 60)).path("booking").path("id").asText();
    String document = apiPost(owner, trip + "/documents", Map.of("title", "即将到期", "type", "passport",
        "url", pdf, "expiredAt", expiry, "isReminder", true))
        .path("document").path("id").asText();
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<Void> reminders = () -> {
        bookingReminders.scanReminders();
        documentReminders.scan();
        bookingReminders.sendPrecheckSummaries();
        return null;
      };
      var first = executor.submit(reminders);
      var second = executor.submit(reminders);
      first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      second.get(30, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, notificationCount(ownerId, tripId, "booking_reminder"));
    assertEquals(1, notificationCount(ownerId, tripId, "document_expiry"));
    assertEquals(1, notificationCount(ownerId, tripId, "trip_precheck"));
    assertEquals(0, notificationCount(guestId, tripId, "booking_reminder"));
    assertEquals(0, notificationCount(guestId, tripId, "document_expiry"));
    assertEquals(0, notificationCount(guestId, tripId, "trip_precheck"));
    JsonNode notices = apiGet(owner, "/api/notifications?size=50").path("notifications");
    assertTrue(notices.toString().contains("/bookings/?trip=" + tripId + "&booking=" + bookingId));
    assertTrue(notices.toString().contains("/documents/?trip=" + tripId + "&document=" + document));

    apiPut(guest, "/api/auth/preferences", Map.of("notifyTrip", true, "notifyDocument", true, "notifyMember", true));
    bookingReminders.scanReminders();
    documentReminders.scan();
    bookingReminders.sendPrecheckSummaries();
    assertEquals(0, notificationCount(guestId, tripId, "booking_reminder"));
    assertEquals(0, notificationCount(guestId, tripId, "document_expiry"));
    assertEquals(0, notificationCount(guestId, tripId, "trip_precheck"));
    apiPost(owner, trip + "/bookings", Map.of("title", "另一笔即将出行", "type", "flight",
        "startTime", start, "reminderEnabled", true, "reminderMinutes", 60));
    apiPost(owner, trip + "/documents", Map.of("title", "另一份证件", "type", "passport",
        "url", pdf, "expiredAt", expiry, "isReminder", true));
    bookingReminders.scanReminders();
    documentReminders.scan();
    assertEquals(1, notificationCount(guestId, tripId, "booking_reminder"));
    assertEquals(1, notificationCount(guestId, tripId, "document_expiry"));
    JsonNode member = java.util.stream.StreamSupport.stream(apiGet(owner, trip + "/members").path("members").spliterator(), false)
        .filter(row -> guestId.equals(row.path("userId").asText())).findFirst().orElseThrow();
    String memberId = member.path("id").asText();
    assertEquals(200, httpRequest("DELETE", trip + "/members/" + memberId, owner, null).statusCode());
    apiPost(owner, trip + "/members", Map.of("username", "tr14guest" + suffix, "role", "member"));
    assertEquals(1, notificationCount(guestId, tripId, "member_invited"));
  }

  private int notificationCount(String userId, String tripId, String kind) {
    return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND metadata->>'tripId'=? "
        + "AND (type::text=? OR metadata->>'kind'=?)", Integer.class, userId, tripId, kind, kind);
  }

  private void apiPut(String token, String url, Map<String, Object> body) throws Exception {
    HttpResponse<String> response = httpRequest("PUT", url, token, body);
    assertEquals(200, response.statusCode(), response.body());
  }


  private void invite(String owner, String ownerId, String member, String username, String tripId)
      throws Exception {
    apiPost(owner, "/api/friends", Map.of("username", username));
    apiPost(member, "/api/friends/" + ownerId + "/accept", null);
    apiPost(owner, "/api/trips/" + tripId + "/members", Map.of("username", username, "role", "member"));
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
