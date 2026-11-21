package com.travelos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
import com.travelos.entity.Trip;
import com.travelos.mapper.TripMapper;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首页 Dashboard 数据正确性回归（真实 HTTP + 真实 PostgreSQL，不依赖个人账号/固定行程ID）：
 *
 * <ul>
 *   <li><b>费用币种口径</b>：stats.totalExpenseAmount 仅统计 currency='CNY'（与
 *       TravelStatsService.PRIMARY_CURRENCY 同口径），外币/空币种不折算不混入；
 *       金额走 BigDecimal 精确分为单位累加（不经 doubleValue*100）；
 *       最近费用明细保留各自真实金额与币种；deleted 行程费用不计入；按用户隔离。</li>
 *   <li><b>最近行程排序</b>：在用户全部可访问（未删除）行程中排序后取3——修复原
 *       LIMIT 8 预截断导致最新行程可能落选；收藏优先 → updatedAt 降序（空=最早）→
 *       行程 id 降序稳定兜底；归档行程保持既有（不排除）规则；deleted 不进结果。</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=50"
})
class DashboardCorrectnessTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";
  /** 排序夹具基准时间（固定，断言与运行日期无关）。 */
  private static final Date BASE = Date.from(Instant.parse("2026-06-01T00:00:00Z"));

  /** 本次运行实际创建的用户 ID（注册成功即记录；清理只按这些准确 ID，绝不使用用户名前缀）。 */
  private final List<String> createdUserIds = new ArrayList<>();
  /** 本次运行实际创建的行程 ID（创建成功即记录）。 */
  private final List<String> createdTripIds = new ArrayList<>();

  @LocalServerPort
  int port;

  @Autowired
  TripMapper tripMapper;

  @Autowired
  JdbcTemplate jdbc;

  private HttpClient http;
  /** 注册用户名后缀（保证同批次用户名唯一；清理仍只按准确 ID，与名字无关）。 */
  private String suffix;
  private String ownerUserId;
  private String tokenA;
  private String tokenB;
  private String tokenC;
  /** userA 的行程 id（按标题索引）。 */
  private final java.util.Map<String, String> tripsA = new java.util.LinkedHashMap<>();

  @BeforeAll
  void registerUsers() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    suffix = String.format("%09d",
        Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    tokenA = register("dbdsha" + suffix);
    ownerUserId = createdUserIds.get(0); // dbdsha 的准确用户 ID（好友接受方）
    tokenB = register("dbdshb" + suffix);
    tokenC = register("dbdshc" + suffix);
  }

  /**
   * 按外键依赖顺序清理【仅本次运行】创建的数据（用户/行程均按准确 ID，不用前缀、
   * 不动其他批次或个人数据）。每条语句独立执行并收集失败，最后统一以断言失败暴露——
   * 不静默吞异常；成功标准是本次 ID 的残留核验为 0。
   */
  @AfterAll
  void cleanUpTestData() {
    if (createdUserIds.isEmpty() && createdTripIds.isEmpty()) {
      return; // 本次未创建任何夹具
    }
    // 雪花 ID 为纯数字串：格式不符直接暴露（防止拼出错误 SQL 而静默清错范围）
    for (String id : createdUserIds) {
      if (!id.matches("[0-9]+")) {
        throw new AssertionError("非法用户 ID，拒绝清理: " + id);
      }
    }
    for (String id : createdTripIds) {
      if (!id.matches("[0-9]+")) {
        throw new AssertionError("非法行程 ID，拒绝清理: " + id);
      }
    }
    String users = "('" + String.join("','", createdUserIds) + "')";
    String trips = "('" + String.join("','", createdTripIds) + "')";
    // 外键依赖顺序：子表 → 父表（users 最后）
    String[] statements = {
        "DELETE FROM trip_expense_shares WHERE expense_id IN "
            + "(SELECT id FROM trip_expenses WHERE trip_id IN " + trips + ")",
        "DELETE FROM trip_expenses WHERE trip_id IN " + trips,
        "DELETE FROM trip_places WHERE trip_id IN " + trips,      // 防御（本测试不建地点）
        "DELETE FROM trip_days WHERE trip_id IN " + trips,         // 防御（本测试不建行程天）
        "DELETE FROM user_friends WHERE user_id IN " + users + " OR friend_id IN " + users,
        "DELETE FROM audit_logs WHERE user_id IN " + users + " OR trip_id IN " + trips,
        "DELETE FROM notifications WHERE user_id IN " + users,
        "DELETE FROM trip_members WHERE user_id IN " + users + " OR trip_id IN " + trips,
        "DELETE FROM trips WHERE id IN " + trips,
        "DELETE FROM users WHERE id IN " + users,
    };
    List<String> failures = new ArrayList<>();
    for (String sql : statements) {
      try {
        jdbc.execute(sql);
      } catch (RuntimeException ex) {
        failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }
    }
    // 残留核验：本次 ID 范围必须归零（任何残留都视为清理失败）
    try {
      int leftTrips = count("SELECT count(*) FROM trips WHERE id IN " + trips);
      int leftUsers = count("SELECT count(*) FROM users WHERE id IN " + users);
      int leftMembers = count("SELECT count(*) FROM trip_members WHERE user_id IN " + users
          + " OR trip_id IN " + trips);
      int leftExpenses = count("SELECT count(*) FROM trip_expenses WHERE trip_id IN " + trips);
      int leftFriends = count("SELECT count(*) FROM user_friends WHERE user_id IN " + users
          + " OR friend_id IN " + users);
      int leftNotifications = count("SELECT count(*) FROM notifications WHERE user_id IN " + users);
      int leftAudit = count("SELECT count(*) FROM audit_logs WHERE user_id IN " + users
          + " OR trip_id IN " + trips);
      if (leftTrips + leftUsers + leftMembers + leftExpenses + leftFriends
          + leftNotifications + leftAudit != 0) {
        failures.add("残留: trips=" + leftTrips + " users=" + leftUsers + " members=" + leftMembers
            + " expenses=" + leftExpenses + " friends=" + leftFriends
            + " notifications=" + leftNotifications + " audit=" + leftAudit);
      }
    } catch (RuntimeException ex) {
      failures.add("残留核验异常: " + ex);
    }
    if (!failures.isEmpty()) {
      throw new AssertionError("Dashboard 测试数据清理失败（范围=本次 " + createdUserIds.size()
          + " 用户/" + createdTripIds.size() + " 行程）: " + String.join(" | ", failures));
    }
  }

  private int count(String sql) {
    Integer n = jdbc.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }

  // ==================== HTTP 工具 ====================

  private String register(String username) throws Exception {
    HttpResponse<String> res = post("/api/auth/register", null,
        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, res.statusCode(), res.body());
    JsonNode body = JSON.readTree(res.body());
    String userId = body.path("user").path("id").asText("");
    if (userId.isBlank()) {
      // 注册成功但拿不到 ID：立即暴露（否则该用户会成为无法按 ID 清理的残留）
      throw new IllegalStateException("注册响应缺少 user.id，无法记录清理范围: " + res.body());
    }
    createdUserIds.add(userId); // 成功即记录 → 初始化/中途失败时 @AfterAll 仍按准确 ID 清理
    return body.path("token").asText();
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + token)
        .GET()
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String createTrip(String token, String title) throws Exception {
    HttpResponse<String> res = post("/api/trips", token,
        "{\"title\":\"" + title + "\",\"destination\":\"t\","
            + "\"startDate\":\"2026-12-20\",\"endDate\":\"2026-12-24\"}");
    assertTrue(res.statusCode() >= 200 && res.statusCode() < 300, res.body());
    String tripId = JSON.readTree(res.body()).path("trip").path("id").asText();
    createdTripIds.add(tripId); // 创建成功即记录 → 中途失败也能按准确 ID 清理
    return tripId;
  }

  private void addExpense(String token, String tripId, String amount, String currency)
      throws Exception {
    Map<String, Object> dto = new java.util.LinkedHashMap<>();
    dto.put("amount", amount);
    if (currency != null) { // null=不传（后端默认 CNY）；""=显式空币种（兼容规则用例）
      dto.put("currency", currency);
    }
    dto.put("category", "other");
    dto.put("title", "验收");
    HttpResponse<String> res = post("/api/trips/" + tripId + "/expenses", token,
        JSON.writeValueAsString(dto));
    assertTrue(res.statusCode() >= 200 && res.statusCode() < 300,
        "创建费用失败: " + res.statusCode() + " " + res.body());
  }

  private JsonNode dashboard(String token) throws Exception {
    HttpResponse<String> res = get("/api/dashboard", token);
    assertEquals(200, res.statusCode(), res.body());
    return JSON.readTree(res.body());
  }

  private String total(JsonNode body) {
    return body.path("stats").path("totalExpenseAmount").asText();
  }

  private List<String> recentTripTitles(JsonNode body) {
    List<String> titles = new ArrayList<>();
    for (JsonNode row : body.path("recentTrips")) {
      titles.add(row.path("title").asText());
    }
    return titles;
  }

  private String recentTripId(JsonNode body, String title) {
    for (JsonNode row : body.path("recentTrips")) {
      if (title.equals(row.path("title").asText())) {
        return row.path("id").asText();
      }
    }
    return null;
  }

  // ==================== 费用币种 ====================

  @Test
  @Order(1)
  void onlyCnyCountedAndForeignKeptInDetail() throws Exception {
    String trip = createTrip(tokenB, "币种隔离行程");
    addExpense(tokenB, trip, "100.00", "CNY");
    addExpense(tokenB, trip, "100.00", "USD"); // 同额外币

    JsonNode body = dashboard(tokenB);
    assertEquals("100.00", total(body),
        "100 CNY + 100 USD 首页累计必须仅为 100.00（外币不折算不混入）");
    assertEquals(1, body.path("recentTrips").size());

    // 最近费用明细保留真实币种与金额（按 spentAt 倒序，USD 后建应在列）
    boolean usdSeen = false;
    for (JsonNode row : body.path("recentExpenses")) {
      if ("USD".equals(row.path("currency").asText())) {
        usdSeen = true;
        assertEquals("100.00", row.path("amount").asText(), "明细保留外币原币金额");
      }
    }
    assertTrue(usdSeen, "明细必须保留 USD 费用行（原币透出，不被吞掉）");

    // 响应字段兼容（前端 stSpent 依赖 stats.totalExpenseAmount 字符串）
    for (String key : List.of("tripCount", "totalExpenseAmount", "totalDays",
        "pendingPackingItemCount", "unreadNotificationCount")) {
      assertTrue(body.path("stats").has(key), "stats 缺少字段 " + key);
    }
    assertTrue(body.path("recentTrips").get(0).has("isFavorite"), "recentTrips 结构兼容");
  }

  @Test
  @Order(2)
  void cnyDecimalAccumulationExactAndEmptyCurrencyExcluded() throws Exception {
    String trip = createTrip(tokenA, "小数累加行程");
    tripsA.put("小数累加行程", trip); // 供排序场景固定其 updatedAt（真实 now 会干扰断言）
    addExpense(tokenA, trip, "50.10", "CNY");
    addExpense(tokenA, trip, "49.90", "CNY");
    addExpense(tokenA, trip, "0.10", "CNY");
    addExpense(tokenA, trip, "0.20", "CNY");
    JsonNode body = dashboard(tokenA);
    assertEquals("100.30", total(body),
        "多笔 CNY 小数金额必须精确累计（0.10+0.20 不得出现浮点尾巴）");

    // 空币种（兼容规则：仅精确 'CNY' 计入——空串不视为 CNY）：金额巨大也不得计入
    addExpense(tokenA, trip, "999.99", "");
    JsonNode after = dashboard(tokenA);
    assertEquals("100.30", total(after), "空币种费用不计入人民币累计（不擅自视为 CNY）");

    // 数据库约束核对：currency 为 NOT NULL DEFAULT 'CNY' → 合法数据不可能出现 NULL，
    // 空串是唯一可构造的"空币种"（上面已断言）；NULL 分支属服务层防御，
    // 由 DashboardServiceBoundaryTest 覆盖（明确区分"合法数据库场景"与"防御场景"）。
    // 断言此约束 = 防止后续有人放宽约束而测试不知情。
    assertEquals("NO", jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='trip_expenses' AND column_name='currency'",
            String.class),
        "trip_expenses.currency 必须保持 NOT NULL，不得放宽数据库约束");
  }

  @Test
  @Order(3)
  void deletedTripExpenseExcludedAndRecentAbsent() throws Exception {
    String tripX = createTrip(tokenA, "将删除行程");
    addExpense(tokenA, tripX, "100.00", "CNY");
    assertEquals("200.30", total(dashboard(tokenA)), "删除前应计入");

    HttpResponse<String> del = sendDelete("/api/trips/" + tripX, tokenA);
    assertEquals(200, del.statusCode(), del.body());

    JsonNode after = dashboard(tokenA);
    assertEquals("100.30", total(after), "已删除行程的费用不得混入累计");
    assertEquals(null, recentTripId(after, "将删除行程"), "已删除行程不得进入 recentTrips");
  }

  @Test
  @Order(4)
  void zeroAndOnlyForeignStateAndArchivedRule() throws Exception {
    // 无行程、无费用 → 既有空态数值
    JsonNode zero = dashboard(tokenC);
    assertEquals("0.00", total(zero), "没有费用时人民币累计为 0.00");
    assertEquals(0, zero.path("recentTrips").size(), "没有行程时 recentTrips 为空数组");

    // 只有外币：仍为 0.00；归档行程保持现有规则（不被排除出 recentTrips）
    String archived = createTrip(tokenC, "归档行程");
    addExpense(tokenC, archived, "88.00", "USD");
    Trip row = tripMapper.selectById(archived);
    row.setArchivedAt(new Date());
    tripMapper.updateById(row);

    JsonNode body = dashboard(tokenC);
    assertEquals("0.00", total(body), "只有外币时人民币累计仍为 0.00");
    assertEquals(1, body.path("recentTrips").size());
    assertEquals(archived, body.path("recentTrips").get(0).path("id").asText(),
        "归档行程保持现有展示规则（不排除）");
  }

  @Test
  @Order(5)
  void recentTripsSortedOverAllTripsWithStableTieBreak() throws Exception {
    // 数据库约束核对：trips.updated_at 为 NOT NULL → 合法数据不会出现空更新时间；
    // 「更新时间为空」的排序分支属服务层防御，由 DashboardServiceBoundaryTest 用 mock 实体
    // 覆盖（不修改数据库约束来构造非法数据）。
    assertEquals("NO", jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='trips' AND column_name='updated_at'",
            String.class),
        "trips.updated_at 必须保持 NOT NULL，不得放宽数据库约束");
    // 10 个行程（>原 LIMIT 8）：断言最新更新的行程仍能进入结果
    for (int i = 1; i <= 10; i++) {
      tripsA.put("近旅T" + i, createTrip(tokenA, "近旅T" + i));
    }
    // 既有行程的 updatedAt 也固定到基准（真实 now 会排在最前干扰 top3 断言）
    setUpdatedAt(tripsA.get("小数累加行程"), BASE.getTime() - 30 * 60_000L);
    // 固定 updatedAt：T1..T5 较旧；T6=+5m；T7=+1m（收藏）；T8=+3m；T9/T10 同为 +10m（平局）
    for (int i = 1; i <= 5; i++) {
      setUpdatedAt(tripsA.get("近旅T" + i), BASE.getTime() - (10 - i) * 60_000L);
    }
    setUpdatedAt(tripsA.get("近旅T6"), BASE.getTime() + 5 * 60_000L);
    setUpdatedAt(tripsA.get("近旅T7"), BASE.getTime() + 60_000L);
    setUpdatedAt(tripsA.get("近旅T8"), BASE.getTime() + 3 * 60_000L);
    setUpdatedAt(tripsA.get("近旅T9"), BASE.getTime() + 10 * 60_000L);
    setUpdatedAt(tripsA.get("近旅T10"), BASE.getTime() + 10 * 60_000L); // 与 T9 同一时刻
    // T7 收藏（更新时间较旧也必须排第一）
    Trip fav = tripMapper.selectById(tripsA.get("近旅T7"));
    fav.setIsFavorite(true);
    tripMapper.updateById(fav);

    // 断言排序依赖的雪花 id 单调（T10 晚于 T9 创建 → id 更大 → 平局按 id 降序 T10 在前）
    assertTrue(tripsA.get("近旅T10").compareTo(tripsA.get("近旅T9")) > 0,
        "夹具假设：雪花 id 随创建时间递增");

    JsonNode body = dashboard(tokenA);
    List<String> titles = recentTripTitles(body);
    assertEquals(3, titles.size(), "recentTrips 固定展示3条");
    assertEquals(List.of("近旅T7", "近旅T10", "近旅T9"), titles,
        "收藏优先 → 更新时间降序 → 同时刻按 id 降序；且>8 行程时最新(T10)必须入选");

    // 稳定性：重复请求顺序完全一致（平局不随机）
    JsonNode again = dashboard(tokenA);
    assertEquals(titles, recentTripTitles(again), "重复刷新顺序必须稳定");

    // 排序修复不改变金额口径（前面场景设置的累计值仍成立）
    assertEquals("100.30", total(body), "recentTrips 修复不影响费用统计");
  }

  // ==================== 成员权限撤销 ====================

  @Test
  @Order(6)
  void memberRevocationRemovesTripExpenseAndDetailFromMemberDashboard() throws Exception {
    // 独立用户（不依赖真实账号/固定行程 ID）：行程所有者 + 将被撤销的成员
    int base = createdUserIds.size();
    String ownerName = "dbdsho" + suffix;
    String memberName = "dbdshm" + suffix;
    String ownerTok = register(ownerName);
    String ownerUid = createdUserIds.get(base);
    String memberTok = register(memberName);
    String memberUid = createdUserIds.get(base + 1);

    // 项目正常流程建立有效成员关系：互为好友（所有者添加 → 成员接受）→ 行程 + CNY 费用 → 邀请
    HttpResponse<String> addFriend = post("/api/friends", ownerTok,
        "{\"username\":\"" + memberName + "\"}");
    assertTrue(addFriend.statusCode() >= 200 && addFriend.statusCode() < 300, addFriend.body());
    HttpResponse<String> accept = post("/api/friends/" + ownerUid + "/accept", memberTok, "{}");
    assertEquals(200, accept.statusCode(), accept.body());

    String trip = createTrip(ownerTok, "成员撤销行程");
    addExpense(ownerTok, trip, "66.60", "CNY");
    HttpResponse<String> invite = post("/api/trips/" + trip + "/members", ownerTok,
        "{\"username\":\"" + memberName + "\",\"role\":\"member\"}");
    assertEquals(200, invite.statusCode(), invite.body());

    // 成员可访问时：行程及其 CNY 费用进入其 Dashboard，最近费用明细可见
    JsonNode memberBefore = dashboard(memberTok);
    assertEquals("66.60", total(memberBefore), "成员有效期内可见该行程的 CNY 费用");
    assertEquals(trip, recentTripId(memberBefore, "成员撤销行程"), "成员可见该行程");
    boolean detailSeen = false;
    for (JsonNode row : memberBefore.path("recentExpenses")) {
      if ("66.60".equals(row.path("amount").asText())
          && "CNY".equals(row.path("currency").asText())) {
        detailSeen = true;
      }
    }
    assertTrue(detailSeen, "成员可见该行程的最近费用明细");

    JsonNode ownerBefore = dashboard(ownerTok);

    // 按正常流程撤销成员关系（所有者移除成员）
    HttpResponse<String> membersRes = get("/api/trips/" + trip + "/members", ownerTok);
    assertEquals(200, membersRes.statusCode(), membersRes.body());
    String memberId = null;
    for (JsonNode row : JSON.readTree(membersRes.body()).path("members")) {
      if (memberUid.equals(row.path("userId").asText())) {
        memberId = row.path("id").asText();
      }
    }
    assertNotNull(memberId, "应能按 userId 定位成员记录");
    HttpResponse<String> remove =
        sendDelete("/api/trips/" + trip + "/members/" + memberId, ownerTok);
    assertEquals(200, remove.statusCode(), remove.body());

    // 撤销后：该行程、累计费用、最近费用明细均不再出现
    JsonNode memberAfter = dashboard(memberTok);
    assertEquals("0.00", total(memberAfter), "撤销后成员累计费用归零");
    assertEquals(null, recentTripId(memberAfter, "成员撤销行程"), "撤销后行程不再出现");
    assertEquals(0, memberAfter.path("recentExpenses").size(), "撤销后最近费用明细为空");

    // 行程所有者的结果不受影响
    JsonNode ownerAfter = dashboard(ownerTok);
    assertEquals(total(ownerBefore), total(ownerAfter), "所有者累计费用不受撤销影响");
    assertEquals(trip, recentTripId(ownerAfter, "成员撤销行程"), "所有者仍可见该行程");
  }

  // ==================== 工具 ====================

  private void setUpdatedAt(String tripId, long epochMs) {
    Trip trip = tripMapper.selectById(tripId);
    trip.setUpdatedAt(new Date(epochMs));
    tripMapper.updateById(trip);
  }

  private HttpResponse<String> sendDelete(String path, String token) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + token)
        .DELETE()
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
