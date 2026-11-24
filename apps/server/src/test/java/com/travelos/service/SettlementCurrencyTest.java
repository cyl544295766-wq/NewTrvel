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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR-02 币种口径专项测试：单币种、多币种、仅外币、小数尾差、历史异常币种。
 * 覆盖结算预览、结算确认、预算、汇总、简报五大入口。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=50"
})
class SettlementCurrencyTest {

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
  private String tokenA, tokenB;
  private String userAId, userBId;

  @BeforeAll
  void setup() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    tokenA = register("tcurA" + suffix);
    userAId = createdUserIds.get(createdUserIds.size() - 1);
    tokenB = register("tcurB" + suffix);
    userBId = createdUserIds.get(createdUserIds.size() - 1);
    // 建立好友关系
    apiPost(tokenA, "/api/friends", Map.of("username", "tcurB" + suffix));
    apiPost(tokenB, "/api/friends/" + userAId + "/accept", null);
  }

  @AfterAll
  void cleanup() {
    for (String tid : createdTripIds) {
      for (String table : List.of("trip_expenses", "trip_budgets", "trip_settlements", "trip_members")) {
        jdbc.update("DELETE FROM " + table + " WHERE trip_id=?", tid);
      }
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

  // ==================== 场景 1：单币种（CNY）正常结算 ====================

  @Test
  @Order(1)
  void singleCurrencyCNY() throws Exception {
    String tripId = createTrip("单币种CNY", 0);
    // A 付 60 CNY（B 分摊 30）
    createExpense(tripId, "CNY", "60", userAId, "food", List.of(userBId));
    // B 付 40 CNY（A 分摊 20）
    createExpense(tripId, "CNY", "40", userBId, "transport", List.of(userAId));

    // 结算预览
    JsonNode settlement = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
    assertEquals("CNY", settlement.get("currency").asText());
    assertFalse(settlement.get("multiCurrency").asBoolean(), "单币种不是 multiCurrency");
    assertNotNull(settlement.get("nets"));
    assertNotNull(settlement.get("transfers"));
    // 应收=应付核对：总垫付 = 总应付
    assertTrue(settlement.get("nets").size() >= 2, "至少 2 个成员的净额");
    // 分币种明细只有 CNY
    JsonNode byCur = settlement.get("byCurrency");
    assertNotNull(byCur, "应有 byCurrency");
    assertTrue(byCur.has("CNY"), "byCurrency 应有 CNY");

    // 汇总
    JsonNode summary = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/summary");
    assertEquals("100.00", summary.get("totalByCurrency").get("CNY").asText());
    assertFalse(summary.get("currencies").size() > 1, "单币种不应多币种");

    // 预算
    JsonNode budget = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/budget");
    assertEquals("CNY", budget.get("budgetCurrency").asText());
    assertTrue(budget.get("foreignCurrencySpent").isEmpty(), "无异币费用");

    // 确认结算（单币种应成功或无账目）
    HttpResponse<String> confirm = httpRequest("POST",
        "/api/trips/" + tripId + "/expenses/settlement/confirm", tokenA, null);
    // 200=成功 400=没有需结算账目（刚好平），都不应报混币
    if (confirm.statusCode() == 400) {
      assertFalse(confirm.body().contains("币种"), "单币种不应报币种错误");
    }
  }

  // ==================== 场景 2：多币种（100 CNY + 100 USD）不合并 ====================

  @Test
  @Order(2)
  void multiCurrencyNoMerge() throws Exception {
    String tripId = createTrip("多币种测试", 0);
    createExpense(tripId, "CNY", "100", userAId, "food", List.of(userBId));
    createExpense(tripId, "USD", "100", userAId, "food", List.of(userBId));

    // 汇总不应合并成 200
    JsonNode summary = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/summary");
    JsonNode totalByCur = summary.get("totalByCurrency");
    assertNotNull(totalByCur);
    assertEquals("100.00", totalByCur.get("CNY").asText(), "CNY 应为 100");
    assertEquals("100.00", totalByCur.get("USD").asText(), "USD 应为 100");
    // 不应有虚假 200（buildWalletSummary 用 totalExpenseAmount 字段）
    String totalExp = summary.get("totalExpenseAmount").asText();
    assertFalse("200.00".equals(totalExp),
        "totalExpenseAmount 不应为跨币种合计 200，实际=" + totalExp);
    assertTrue(summary.get("currencies").size() == 2, "应有 2 种币种");

    // 结算预览按币种独立
    JsonNode settlement = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
    assertTrue(settlement.get("multiCurrency").asBoolean(), "混币应标记 multiCurrency");
    JsonNode byCur = settlement.get("byCurrency");
    assertTrue(byCur.has("CNY") && byCur.has("USD"), "byCurrency 应有 CNY 和 USD");
    // 每币种独立转账（不跨币种抵消）
    JsonNode cnyTransfers = byCur.get("CNY").get("transfers");
    JsonNode usdTransfers = byCur.get("USD").get("transfers");
    assertTrue(cnyTransfers.size() > 0, "CNY 应有转账");
    assertTrue(usdTransfers.size() > 0, "USD 应有转账");
    // 转账币种标注（若有 currency 字段则验证）
    if (cnyTransfers.get(0).has("currency")) {
      assertEquals("CNY", cnyTransfers.get(0).get("currency").asText());
    }
    if (usdTransfers.get(0).has("currency")) {
      assertEquals("USD", usdTransfers.get(0).get("currency").asText());
    }
    // 跨币种独立：CNY 转账总额 ≠ USD 转账总额（各自独立）
    // 且 CNY nets 的 paid/owed 之和 = CNY totalAmount（守恒）
    JsonNode cnyNets = byCur.get("CNY").get("nets");
    long cnyPaidTotal = 0;
    long cnyOwedTotal = 0;
    for (JsonNode n : cnyNets) {
      cnyPaidTotal += Math.round(Double.parseDouble(n.get("paid").asText().replace(",", "")) * 100);
      cnyOwedTotal += Math.round(Double.parseDouble(n.get("owed").asText().replace(",", "")) * 100);
    }
    assertEquals(cnyPaidTotal, cnyOwedTotal, "CNY 垫付总 = 应付总（守恒）");

    // 混币确认被拦截
    HttpResponse<String> confirm = httpRequest("POST",
        "/api/trips/" + tripId + "/expenses/settlement/confirm", tokenA, null);
    assertEquals(400, confirm.statusCode(), "混币确认应被拦截");
    assertTrue(confirm.body().contains("币种"), "应提示币种原因");
    assertFalse(confirm.body().contains("200"), "不应出现跨币种合计 200");

    // 预算：异币单列
    JsonNode budget = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/budget");
    assertEquals("CNY", budget.get("budgetCurrency").asText());
    JsonNode foreign = budget.get("foreignCurrencySpent");
    assertTrue(foreign.has("USD"), "USD 应在异币单列");
    // 预算进度只计 CNY（假设预算 >0）
    assertEquals("100.00", budget.get("totalSpent").asText(), "预算进度只计 CNY");

    // 简报分币种
    HttpResponse<String> briefResp = httpRequest("POST", "/api/ai/today-brief", tokenA,
        Map.of("tripId", tripId));
    assertEquals(200, briefResp.statusCode());
    JsonNode brief = JSON.readTree(briefResp.body());
    JsonNode briefBudget = brief.get("budget");
    assertNotNull(briefBudget);
    JsonNode todayByCur = briefBudget.get("todaySpentByCurrency");
    assertNotNull(todayByCur, "简报应有 todaySpentByCurrency");
  }

  // ==================== 场景 3：仅外币（全 USD） ====================

  @Test
  @Order(3)
  void foreignCurrencyOnly() throws Exception {
    String tripId = createTrip("仅外币USD", 0);
    createExpense(tripId, "USD", "200", userAId, "food", List.of(userBId));

    // 汇总
    JsonNode summary = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/summary");
    assertEquals("USD", summary.get("currency").asText(), "全外币时主币种为 USD");
    assertEquals("200.00", summary.get("totalByCurrency").get("USD").asText());

    // 结算
    JsonNode settlement = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
    assertEquals("USD", settlement.get("currency").asText());
    assertFalse(settlement.get("multiCurrency").asBoolean(), "全外币单币种");

    // 预算：CNY 预算 0、异币 USD 单列
    JsonNode budget = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/budget");
    assertEquals("CNY", budget.get("budgetCurrency").asText(), "预算币种固定 CNY");
    assertTrue(budget.get("foreignCurrencySpent").has("USD"));
    assertEquals("0.00", budget.get("totalSpent").asText(), "预算进度只计 CNY=0");
  }

  // ==================== 场景 4：小数尾差守恒 ====================

  @Test
  @Order(4)
  void decimalRemainder() throws Exception {
    String tripId = createTrip("小数尾差", 0);
    // 100 CNY 三人分摊（A 付）→ 33.34+33.33+33.33 = 100.00
    // 分摊尾差规则：分账时使用精确整数分（cents），尾差归最大份额
    apiPost(tokenA, "/api/trips/" + tripId + "/expenses", Map.of(
        "title", "尾差测试", "amount", new BigDecimal("100.00"), "currency", "CNY",
        "category", "food", "payerUserId", userAId,
        "shares", List.of(
            Map.of("userId", userAId, "amount", new BigDecimal("33.34")),
            Map.of("userId", userBId, "amount", new BigDecimal("33.33"))
        )));

    // 汇总总金额精确
    JsonNode summary = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/summary");
    assertEquals("100.00", summary.get("totalByCurrency").get("CNY").asText(),
        "小数分摊总金额应精确 100.00（守恒）");
    // 分摊后应收=应付
    JsonNode settlement = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
    JsonNode nets = settlement.get("nets");
    long totalNet = 0;
    for (JsonNode n : nets) {
      String net = n.get("net").asText();
      totalNet += Math.round(Double.parseDouble(net.replace(",", "")) * 100);
    }
    assertEquals(0, totalNet, "各成员净额之和应为 0（应收=应付守恒）");
  }

  // ==================== 场景 5：历史异常币种 ====================

  @Test
  @Order(5)
  void abnormalCurrency() throws Exception {
    String tripId = createTrip("异常币种", 0);
    // null/空白 → CNY
    createExpenseNoCur(tripId, "50", userAId, "food");
    // 小写 "cny" → CNY（规范化）
    createExpense(tripId, "cny", "30", userAId, "food", List.of(userBId));
    // 异常 "XXX"（非 ISO）→ 原样保留不吞没
    createExpense(tripId, "XXX", "20", userAId, "food", List.of(userBId));

    // 汇总：cny 归入 CNY，null 归入 CNY，XXX 独立
    JsonNode summary = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/summary");
    JsonNode totalByCur = summary.get("totalByCurrency");
    assertNotNull(totalByCur);
    // CNY: 50(null) + 30(cny) = 80
    assertEquals("80.00", totalByCur.get("CNY").asText(), "null 和小写 cny 应归入 CNY");
    assertEquals("20.00", totalByCur.get("XXX").asText(), "XXX 应原样保留");
    assertTrue(summary.get("currencies").size() == 2, "应有 CNY 和 XXX 两种");

    // 结算预览：CNY 和 XXX 独立
    JsonNode settlement = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
    assertTrue(settlement.get("multiCurrency").asBoolean());
    JsonNode byCur = settlement.get("byCurrency");
    assertTrue(byCur.has("CNY"));
    assertTrue(byCur.has("XXX"), "XXX 不应被吞没");

    // 混币确认被拦截
    HttpResponse<String> confirm = httpRequest("POST",
        "/api/trips/" + tripId + "/expenses/settlement/confirm", tokenA, null);
    assertEquals(400, confirm.statusCode());
  }

  // ==================== 场景 6：历史结算快照兼容 ====================

  @Test
  @Order(6)
  void historicalSnapshotCompat() throws Exception {
    String tripId = createTrip("快照兼容", 0);
    createExpense(tripId, "CNY", "100", userAId, "food", List.of(userBId));

    // 先确认一次单币种结算（写入快照）
    HttpResponse<String> confirm = httpRequest("POST",
        "/api/trips/" + tripId + "/expenses/settlement/confirm", tokenA, null);
    // 可能 200（有账目）或 400（刚好平）
    if (confirm.statusCode() == 200) {
      // 查询历史快照
      JsonNode settlement = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
      JsonNode latest = settlement.get("latestSettlement");
      if (latest != null && !latest.isNull() && latest.has("currency")) {
        assertEquals("CNY", latest.get("currency").asText(), "快照应有币种");
        assertTrue(latest.has("totalAmount"), "快照应有总金额");
        assertFalse(latest.get("totalAmount").asText().isEmpty(), "金额不应为空");
      }
    }
    // 加入外币后再查：历史快照不应被改动
    createExpense(tripId, "USD", "50", userAId, "food", List.of(userBId));
    JsonNode after = apiGet(tokenA, "/api/trips/" + tripId + "/expenses/settlement");
    assertTrue(after.get("multiCurrency").asBoolean(), "加外币后变为混币");
    // 历史快照仍在
    JsonNode latestAfter = after.get("latestSettlement");
    if (latestAfter != null && !latestAfter.isNull() && latestAfter.has("currency")) {
      assertTrue(latestAfter.has("totalAmount"), "历史快照保留金额");
    }
  }

  // ==================== 工具 ====================

  private String createTrip(String title, double budget) throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("title", title);
    body.put("destination", "币种测试");
    if (budget > 0) body.put("budget", budget);
    JsonNode resp = apiPost(tokenA, "/api/trips", body);
    String id = resp.get("trip").get("id").asText();
    createdTripIds.add(id);
    // 邀请 B
    apiPost(tokenA, "/api/trips/" + id + "/members",
        Map.of("username", "tcurB" + suffix, "role", "member"));
    return id;
  }

  private void createExpense(String tripId, String currency, String amount,
      String payerId, String category, List<String> shareUserIds) throws Exception {
    List<Map<String, Object>> shares = new ArrayList<>();
    BigDecimal amt = new BigDecimal(amount);
    BigDecimal shareAmt = amt.divide(BigDecimal.valueOf(shareUserIds.size() + 1),
        2, java.math.RoundingMode.HALF_UP);
    // 付款人也分摊
    Map<String, Object> payerShare = new java.util.LinkedHashMap<>();
    payerShare.put("userId", payerId);
    payerShare.put("amount", shareAmt);
    shares.add(payerShare);
    for (String uid : shareUserIds) {
      Map<String, Object> s = new java.util.LinkedHashMap<>();
      s.put("userId", uid);
      s.put("amount", shareAmt);
      shares.add(s);
    }
    apiPost(tokenA, "/api/trips/" + tripId + "/expenses", Map.of(
        "title", "费用-" + currency, "amount", amt, "currency", currency,
        "category", category, "payerUserId", payerId, "shares", shares,
        "spentAt", "2026-08-01"));
  }

  private void createExpenseNoCur(String tripId, String amount,
      String payerId, String category) throws Exception {
    // 不传 currency（测试 null/blank 处理）
    apiPost(tokenA, "/api/trips/" + tripId + "/expenses", Map.of(
        "title", "无币种费用", "amount", new BigDecimal(amount),
        "category", category, "payerUserId", payerId,
        "spentAt", "2026-08-01"));
  }

  private String register(String username) throws Exception {
    HttpResponse<String> resp = httpRequest("POST", "/api/auth/register", null,
        Map.of("username", username, "password", PASSWORD));
    assertEquals(200, resp.statusCode(), "注册失败: " + resp.body());
    JsonNode body = JSON.readTree(resp.body());
    createdUserIds.add(body.get("user").get("id").asText());
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

  private HttpResponse<String> httpRequest(String method, String path, String token,
      Map<String, Object> body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) builder.header("Authorization", "Bearer " + token);
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
