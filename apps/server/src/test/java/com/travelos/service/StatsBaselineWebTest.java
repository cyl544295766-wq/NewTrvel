package com.travelos.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
import org.junit.jupiter.api.AfterAll;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计接口查询基线与兼容性测试（真实 HTTP + 真实 PostgreSQL）。
 *
 * <p>双模式：
 * <ul>
 *   <li><b>record</b>（-Dstats.baseline.record=1）：把各端点归一化响应写入
 *       target/stats-baseline/，供人工复制到 src/test/resources/stats-baseline/ 作为基线
 *       （在查询优化<b>之前</b>录制）。</li>
 *   <li><b>verify</b>（默认）：以 classpath /stats-baseline/ 中的基线逐一比对当前响应
 *       （ID 归一化后逐字节相等），证明优化不改变统计结果；同时断言 SQL 次数上限
 *       （随分批数增长，消除逐行程/逐费用/逐成员的 N+1），并把测量值打印到日志供报告引用。</li>
 * </ul></p>
 *
 * <p>夹具（全部经真实 API 创建，注册成功即记录准确 ID，@AfterAll 按 ID 清理并核验残留）：
 * 用户 A（多行程所有者）、B（成员）、C（成员，后被撤销）、E（空态用户，无任何行程）；
 * 行程 T1（常规+外币+多分摊）、T2（2027 年份边界）、T3（跨年 12/30→1/2，按开始年份口径）、
 * T4（多行程多费用多成员放大场景，6 笔费用×3 人分摊）、T5（归档）、T6（删除）。</p>
 *
 * <p>SQL 计数方式：logback ListAppender 挂到 com.travelos.mapper 记录器，统计
 * "Preparing:" 事件数（每条 SQL 一次），仅在测量请求前后挂载，排除夹具创建与清理。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=50"
})
class StatsBaselineWebTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final boolean RECORD = "1".equals(System.getProperty("stats.baseline.record"));
  private static final String PASSWORD = "Statspass123";
  private static final int SQL_CAP_OVERVIEW = 10;
  private static final int SQL_CAP_FOOTPRINTS = 16;
  private static final int SQL_CAP_YEARLY = 10;
  private static final int SQL_CAP_MONTHLY = 10;
  private static final int SQL_CAP_CATEGORIES = 10;
  private static final int SQL_CAP_BUDGETS = 10;
  private static final int SQL_CAP_SETTLEMENT = 13;
  /** 空态（无成员资格）：仅 membership 查询即返回，其余全部短路。 */
  private static final int SQL_CAP_EMPTY = 3;
  private static final int TIMED_RUNS = 5;

  private final List<String> createdUserIds = new ArrayList<>();
  private final List<String> createdTripIds = new ArrayList<>();
  private final List<String> createdExpenseIds = new ArrayList<>();
  private final List<String> createdPlaceIds = new ArrayList<>();
  private final List<String> createdMemberIds = new ArrayList<>();

  @LocalServerPort
  int port;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  private String suffix;
  private String userIdA;
  private String userIdB;
  private String userIdC;
  private String userIdE;
  private String tokenA;
  private String tokenB;
  private String tokenC;
  private String tokenE;
  private String trip1;
  private String trip2;
  private String trip3;
  private String trip4;
  private String trip5;
  private String trip6;
  /** 归一化映射：真实 ID → 确定令牌（让基线跨运行可比）。 */
  private final Map<String, String> idTokens = new LinkedHashMap<>();

  private final List<long[]> timings = new ArrayList<>();

  @org.junit.jupiter.api.BeforeAll
  void setup() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    suffix = Long.toString(System.currentTimeMillis(), 36) + "stb";
    userIdE = register("stbe" + suffix, "E"); tokenE = lastToken;
    userIdA = register("stba" + suffix, "A"); tokenA = lastToken;
    userIdB = register("stbb" + suffix, "B"); tokenB = lastToken;
    userIdC = register("stbc" + suffix, "C"); tokenC = lastToken;
  }

  private String lastToken = "";

  private String register(String username, String tag) throws Exception {
    Map<String, Object> body = Map.of("username", username, "password", PASSWORD);
    HttpResponse<String> res = send("POST", "/api/auth/register", body, "");
    assertEquals(200, res.statusCode(), "注册失败: " + res.body());
    JsonNode node = JSON.readTree(res.body());
    String id = node.get("user").get("id").asText();
    createdUserIds.add(id);
    lastToken = node.get("token").asText();
    // 用户名会进入统计响应（display_name 缺省时回退 username），同样需要归一化
    idTokens.put(username, "@UN_" + tag + "@");
    return id;
  }

  private void buildIdTokens() {
    int n = 1;
    for (String id : List.of(userIdA, userIdB, userIdC, userIdE)) {
      idTokens.put(id, "@U" + (n++) + "@");
    }
    n = 1;
    List<String> trips = new ArrayList<>();
    for (String id : List.of(trip1, trip2, trip3, trip4, trip5, trip6)) {
      if (id != null) {
        trips.add(id);
      }
    }
    for (String id : trips) {
      idTokens.put(id, "@T" + (n++) + "@");
    }
  }

  private String normalize(String json) {
    String out = json;
    for (Map.Entry<String, String> e : idTokens.entrySet()) {
      out = out.replace(e.getKey(), e.getValue());
    }
    return out.replace("\r\n", "\n");
  }

  private void saveOrVerify(String name, String normalizedJson) throws Exception {
    if (RECORD) {
      Path dir = Path.of("target", "stats-baseline");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(name + ".json"), normalizedJson + "\n", StandardCharsets.UTF_8);
      System.out.println("[baseline] recorded " + name);
      return;
    }
    try (var in = getClass().getResourceAsStream("/stats-baseline/" + name + ".json")) {
      assertTrue(in != null, "缺少基线文件: " + name);
      String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
      String actual = normalizedJson.trim();
      assertEquals(expected, actual, "统计响应与优化前基线不一致: " + name);
    }
  }

  /* ---------------- HTTP 助手 ---------------- */

  private HttpResponse<String> send(String method, String path, Map<String, Object> body, String bearer)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20));
    if (body != null) {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    if (bearer != null && !bearer.isBlank()) {
      b.header("Authorization", "Bearer " + bearer);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private JsonNode get(String path, String bearer) throws Exception {
    HttpResponse<String> res = send("GET", path, null, bearer);
    assertEquals(200, res.statusCode(), "GET " + path + " -> " + res.body());
    return JSON.readTree(res.body());
  }

  /* ---------------- SQL 计数 ---------------- */

  private List<String> sqlLog = new ArrayList<>();

  private void sqlCountStart() {
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("com.travelos.mapper");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level old = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    appender.setName("stats-count-" + System.nanoTime());
    logger.addAppender(appender);
    sqlLog = List.of();
    sqlLogState = new Object[]{appender, old};
  }

  private Object[] sqlLogState;

  private int sqlCountEnd() {
    ListAppender<ILoggingEvent> appender = (ListAppender<ILoggingEvent>) sqlLogState[0];
    Level old = (Level) sqlLogState[1];
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("com.travelos.mapper");
    int count = 0;
    for (ILoggingEvent event : appender.list) {
      if (event.getFormattedMessage().contains("Preparing:")) {
        count++;
      }
    }
    logger.detachAppender(appender);
    logger.setLevel(old == null ? Level.DEBUG : old);
    appender.stop();
    return count;
  }

  /* ---------------- 夹具 ---------------- */

  private String createTrip(String token, String title, String dest, String start, String end)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("destination", dest);
    body.put("startDate", start);
    body.put("endDate", end);
    body.put("description", "stats baseline fixture");
    HttpResponse<String> res = send("POST", "/api/trips", body, token);
    assertEquals(200, res.statusCode(), "建行程失败: " + res.body());
    JsonNode trip = JSON.readTree(res.body()).get("trip");
    String id = trip.get("id").asText();
    createdTripIds.add(id);
    return id;
  }

  private void generateDays(String token, String tripId) throws Exception {
    HttpResponse<String> res = send("POST", "/api/trips/" + tripId + "/days/generate", Map.of(), token);
    assertEquals(200, res.statusCode(), "生成行程天失败: " + res.body());
  }

  private void addPlace(String token, String tripId, String name, double lng, double lat)
      throws Exception {
    JsonNode days = get("/api/trips/" + tripId + "/days", token);
    String dayId = days.get("days").get(0).get("id").asText();
    HttpResponse<String> lock = send("POST", "/api/trips/" + tripId + "/days/" + dayId + "/edit-lock",
        Map.of(), token);
    assertEquals(200, lock.statusCode(), "取编辑锁失败: " + lock.body());
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("tripDayId", dayId);
      body.put("name", name);
      body.put("type", "attraction");
      body.put("longitude", lng);
      body.put("latitude", lat);
      HttpResponse<String> res = send("POST", "/api/trips/" + tripId + "/places", body, token);
      assertEquals(200, res.statusCode(), "加点失败: " + res.body());
      JsonNode node = JSON.readTree(res.body());
      JsonNode place = node.has("place") ? node.get("place") : node;
      createdPlaceIds.add(place.get("id").asText());
    } finally {
      send("DELETE", "/api/trips/" + tripId + "/days/" + dayId + "/edit-lock", null, token);
    }
  }

  private String addExpense(String token, String tripId, double amount, String currency,
      String payer, List<String> shares) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amount", String.valueOf(amount));
    body.put("category", "food");
    body.put("title", "基线费用");
    body.put("payerUserId", payer);
    body.put("shareUserIds", shares);
    if (currency != null) {
      body.put("currency", currency);
    }
    HttpResponse<String> res = send("POST", "/api/trips/" + tripId + "/expenses", body, token);
    assertEquals(200, res.statusCode(), "记费用失败: " + res.body());
    JsonNode node = JSON.readTree(res.body());
    JsonNode expense = node.has("expense") ? node.get("expense") : node;
    String id = expense.get("id").asText();
    createdExpenseIds.add(id);
    return id;
  }

  /** 互为好友：A 添加对方 + 对方 accept（邀请成员的前提）。 */
  private void befriend(String requesterToken, String requesterAdds, String otherToken, String otherAddsBackId)
      throws Exception {
    HttpResponse<String> add = send("POST", "/api/friends", Map.of("userId", requesterAdds), requesterToken);
    assertEquals(200, add.statusCode(), "添加好友失败: " + add.body());
    HttpResponse<String> accept = send("POST", "/api/friends/" + otherAddsBackId + "/accept", null, otherToken);
    assertEquals(200, accept.statusCode(), "接受好友失败: " + accept.body());
  }

  private void addMember(String token, String tripId, String username, String invitedUserId)
      throws Exception {
    HttpResponse<String> res = send("POST", "/api/trips/" + tripId + "/members",
        Map.of("username", username, "role", "member"), token);
    assertEquals(200, res.statusCode(), "加成员失败: " + res.body());
    // invite 返回成员列表响应：从列表中按被邀请人 userId 提取成员记录 ID
    JsonNode list = JSON.readTree(res.body()).get("members");
    for (JsonNode m : list) {
      if (invitedUserId.equals(m.get("userId").asText())) {
        createdMemberIds.add(m.get("id").asText());
        return;
      }
    }
    org.junit.jupiter.api.Assertions.fail("成员列表中未找到被邀请人: " + username);
  }

  /* ---------------- 测试流程 ---------------- */

  @Test
  void baselineFlow() throws Exception {
    // ---- 空态（E 无任何行程）：7 端点全量 ----
    String[][] emptyEndpoints = {
        {"/api/stats/overview", "empty-overview"},
        {"/api/stats/footprints", "empty-footprints"},
        {"/api/stats/yearly?year=2026", "empty-yearly"},
        {"/api/stats/monthly?year=2026", "empty-monthly"},
        {"/api/stats/expense-categories", "empty-categories"},
        {"/api/stats/budgets", "empty-budgets"},
        {"/api/stats/settlement-suggestions", "empty-settlement"}
    };
    Map<String, Integer> emptyCounts = new LinkedHashMap<>();
    for (String[] ep : emptyEndpoints) {
      sqlCountStart();
      HttpResponse<String> res = send("GET", ep[0], null, tokenE);
      int sql = sqlCountEnd();
      assertEquals(200, res.statusCode(), "空态端点失败: " + ep[0] + " -> " + res.body());
      saveOrVerify(ep[1], normalize(res.body()));
      emptyCounts.put(ep[1], sql);
    }
    for (Map.Entry<String, Integer> e : emptyCounts.entrySet()) {
      if (!RECORD) {
        assertTrue(e.getValue() <= SQL_CAP_EMPTY,
            "空态 SQL 次数超上限 " + e.getKey() + ": " + e.getValue());
      }
      System.out.println("[sql] " + e.getKey() + " = " + e.getValue());
    }

    // ---- 数据夹具 ----
    // 邀请成员的前提：互为好友（A↔B、A↔C）
    befriend(tokenA, userIdB, tokenB, userIdA);
    befriend(tokenA, userIdC, tokenC, userIdA);

    trip1 = createTrip(tokenA, "基线行程一", "大连", "2026-06-10", "2026-06-11");
    generateDays(tokenA, trip1);
    addPlace(tokenA, trip1, "星海广场", 121.59, 38.88);
    addMember(tokenA, trip1, "stbb" + suffix, userIdB);
    addExpense(tokenA, trip1, 120.50, "CNY", userIdA, List.of(userIdA, userIdB));
    addExpense(tokenB, trip1, 80.00, "CNY", userIdB, List.of(userIdA, userIdB));
    addExpense(tokenA, trip1, 25.00, "USD", userIdA, List.of(userIdA));

    trip2 = createTrip(tokenA, "基线行程二", "上海", "2027-01-05", "2027-01-07");
    generateDays(tokenA, trip2);
    addPlace(tokenA, trip2, "外滩", 121.49, 31.24);
    addMember(tokenA, trip2, "stbb" + suffix, userIdB);
    addExpense(tokenA, trip2, 300.00, "CNY", userIdA, List.of(userIdA, userIdB));

    trip3 = createTrip(tokenA, "基线行程三", "大连", "2026-12-30", "2027-01-02");
    generateDays(tokenA, trip3);
    addMember(tokenA, trip3, "stbb" + suffix, userIdB);
    addExpense(tokenB, trip3, 45.00, "CNY", userIdB, List.of(userIdA, userIdB));

    trip4 = createTrip(tokenA, "基线行程四", "成都", "2026-08-01", "2026-08-03");
    generateDays(tokenA, trip4);
    addMember(tokenA, trip4, "stbb" + suffix, userIdB);
    addMember(tokenA, trip4, "stbc" + suffix, userIdC);
    String[][] t4Expenses = {
        {"100.00", userIdA}, {"60.00", userIdB}, {"45.50", userIdC},
        {"88.00", userIdA}, {"32.00", userIdB}, {"19.90", userIdC}
    };
    List<String> t4 = List.of(userIdA, userIdB, userIdC);
    for (String[] exp : t4Expenses) {
      addExpense(tokenA, trip4, Double.parseDouble(exp[0]), "CNY", exp[1], t4);
    }

    trip5 = createTrip(tokenA, "基线行程五", "西安", "2026-05-01", "2026-05-02");
    addExpense(tokenA, trip5, 66.00, "CNY", userIdA, List.of(userIdA));
    HttpResponse<String> arch = send("PATCH", "/api/trips/" + trip5 + "/archive", null, tokenA);
    assertEquals(200, arch.statusCode(), "归档失败: " + arch.body());

    trip6 = createTrip(tokenA, "基线行程六", "厦门", "2026-09-09", "2026-09-10");
    addExpense(tokenA, trip6, 77.00, "CNY", userIdA, List.of(userIdA));
    HttpResponse<String> del = send("DELETE", "/api/trips/" + trip6, null, tokenA);
    assertEquals(200, del.statusCode(), "删除失败: " + del.body());

    buildIdTokens();

    // ---- 撤销前：C 的统计包含 T4 ----
    HttpResponse<String> cBefore = send("GET", "/api/stats/overview", null, tokenC);
    assertEquals(200, cBefore.statusCode());
    saveOrVerify("c-overview-before-revoke", normalize(cBefore.body()));

    // ---- 撤销 C 对 T4 的成员资格（按成员 ID 精确删除） ----
    JsonNode members = get("/api/trips/" + trip4 + "/members", tokenA);
    String revokeId = null;
    for (JsonNode m : members.get("members")) {
      if (userIdC.equals(m.get("userId").asText())) {
        revokeId = m.get("id").asText();
      }
    }
    assertTrue(revokeId != null, "未找到 C 的成员记录");
    createdMemberIds.remove(revokeId);
    HttpResponse<String> rm = send("DELETE", "/api/trips/" + trip4 + "/members/" + revokeId, null, tokenA);
    assertEquals(200, rm.statusCode(), "撤销成员失败: " + rm.body());

    HttpResponse<String> cAfter = send("GET", "/api/stats/overview", null, tokenC);
    assertEquals(200, cAfter.statusCode());
    saveOrVerify("c-overview-after-revoke", normalize(cAfter.body()));
    // 撤销后 C 不再统计 T4：行程数必须减少（基线 equality 再叠加显式数值断言）
    int before = JSON.readTree(cBefore.body()).get("tripCount").asInt();
    int after = JSON.readTree(cAfter.body()).get("tripCount").asInt();
    assertTrue(after < before, "撤销成员后行程数应减少：before=" + before + " after=" + after);

    // ---- A 的全量测量（计数 1 次 + 计时 5 次取中位） ----
    String[][] dataEndpoints = {
        {"/api/stats/overview", "data-overview", String.valueOf(SQL_CAP_OVERVIEW)},
        {"/api/stats/footprints", "data-footprints", String.valueOf(SQL_CAP_FOOTPRINTS)},
        {"/api/stats/yearly?year=2026", "data-yearly", String.valueOf(SQL_CAP_YEARLY)},
        {"/api/stats/monthly?year=2026", "data-monthly", String.valueOf(SQL_CAP_MONTHLY)},
        {"/api/stats/expense-categories", "data-categories", String.valueOf(SQL_CAP_CATEGORIES)},
        {"/api/stats/budgets", "data-budgets", String.valueOf(SQL_CAP_BUDGETS)},
        {"/api/stats/settlement-suggestions", "data-settlement", String.valueOf(SQL_CAP_SETTLEMENT)}
    };
    for (String[] ep : dataEndpoints) {
      sqlCountStart();
      HttpResponse<String> res = send("GET", ep[0], null, tokenA);
      int sql = sqlCountEnd();
      assertEquals(200, res.statusCode(), "端点失败: " + ep[0] + " -> " + res.body());
      String normalized = normalize(res.body());
      saveOrVerify(ep[1], normalized);
      if (!RECORD) {
        assertTrue(sql <= Integer.parseInt(ep[2]),
            "SQL 次数超上限 " + ep[0] + ": " + sql + " > " + ep[2]);
      }

      List<Long> samples = new ArrayList<>();
      for (int run = 0; run < TIMED_RUNS; run++) {
        long t0 = System.nanoTime();
        HttpResponse<String> timed = send("GET", ep[0], null, tokenA);
        long cost = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(200, timed.statusCode());
        samples.add(cost);
      }
      samples.sort(Long::compareTo);
      long median = samples.get(samples.size() / 2);
      timings.add(new long[]{sql, median});
      System.out.println("[perf] " + ep[1] + " sql=" + sql + " medianMs=" + median
          + " samples=" + samples);
    }
  }

  @AfterAll
  void cleanup() {
    List<String> errors = new ArrayList<>();
    try {
      // 外键安全顺序：分摊 → 费用 → 地点 → 行程天 → 成员 → 行程 → 用户侧表 → 用户
      for (String expenseId : createdExpenseIds) {
        jdbc.update("DELETE FROM trip_expense_shares WHERE expense_id = ?", expenseId);
        jdbc.update("DELETE FROM trip_expenses WHERE id = ?", expenseId);
      }
      for (String placeId : createdPlaceIds) {
        jdbc.update("DELETE FROM trip_places WHERE id = ?", placeId);
      }
      for (String tripId : createdTripIds) {
        jdbc.update("DELETE FROM trip_days WHERE trip_id = ?", tripId);
        jdbc.update("DELETE FROM trip_places WHERE trip_id = ?", tripId);
        jdbc.update("DELETE FROM trip_expenses WHERE trip_id = ?", tripId);
        jdbc.update("DELETE FROM trip_expense_shares WHERE expense_id IN "
            + "(SELECT id FROM trip_expenses WHERE trip_id = ?)", tripId);
        jdbc.update("DELETE FROM trip_members WHERE trip_id = ?", tripId);
        jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
      }
      for (String memberId : createdMemberIds) {
        jdbc.update("DELETE FROM trip_members WHERE id = ?", memberId);
      }
      for (String userId : createdUserIds) {
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM audit_logs WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_preferences WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM trip_members WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
      }
    } catch (Exception ex) {
      errors.add("清理异常: " + ex.getClass().getSimpleName());
    }
    // 残留核验：本次 ID 范围必须归零（非 0 即测试失败）
    try {
      for (String tripId : createdTripIds) {
        Integer trips = jdbc.queryForObject(
            "SELECT count(*) FROM trips WHERE id = ?", Integer.class, tripId);
        if (trips != null && trips > 0) {
          errors.add("行程残留: " + tripId);
        }
      }
      for (String userId : createdUserIds) {
        Integer users = jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE id = ?", Integer.class, userId);
        if (users != null && users > 0) {
          errors.add("用户残留: " + userId);
        }
      }
    } catch (Exception ex) {
      errors.add("残留核验异常: " + ex.getClass().getSimpleName());
    }
    org.junit.jupiter.api.Assertions.assertTrue(errors.isEmpty(),
        "夹具清理失败（范围=本次准确 ID）: " + String.join(" | ", errors));
  }
}
