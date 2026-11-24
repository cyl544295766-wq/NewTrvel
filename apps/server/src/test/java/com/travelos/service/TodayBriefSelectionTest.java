package com.travelos.service;

import com.travelos.ai.LlmGatewayService;
import com.travelos.entity.Trip;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 首页「AI 今日简报」行程选择回归（修复 start_date=NULL 草稿触发的 NPE/500）：
 *
 * <ul>
 *   <li>无日期草稿（合法业务数据，trips.start_date 可空）不得进入 upcoming 候选、
 *       不得阻断其他有效行程的简报，更不得触发 {@code Fmt.dateKey(null)} 空指针；</li>
 *   <li>仅剩无日期草稿 / 完全没有行程 → 沿用既有 emptyBrief 空态契约（mode=none）；</li>
 *   <li>显式指定行程永远返回该行程本身（不静默切换），无日期行程按 modeOf 的
 *       null→pre-trip 约定返回其行前简报；</li>
 *   <li>正常有日期行程的选择优先级与响应结构保持不变。</li>
 * </ul>
 *
 * 夹具为真实可成立的 Trip 实体（相对服务默认时区 Asia/Shanghai 构造日期），
 * 摘要走模板降级（llm 关闭），不依赖个人账号、固定行程 ID 或真实模型服务。
 */
class TodayBriefSelectionTest {

  /** 与 TodayBriefService.DEFAULT_ZONE 一致，保证 modeOf 的日期比较口径相同。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final String USER = "u1";

  private TripService tripService;
  private TripDayMapper tripDayMapper;
  private TripPlaceMapper tripPlaceMapper;
  private TripMemberService tripMemberService;
  private UserPreferenceService userPreferenceService;
  private WeatherService weatherService;
  private BookingService bookingService;
  private TripExpenseService tripExpenseService;
  private PackingListService packingListService;
  private TravelDocumentService travelDocumentService;
  private LlmGatewayService llm;
  private TodayBriefService service;

  @BeforeEach
  void setUp() {
    tripService = mock(TripService.class);
    tripDayMapper = mock(TripDayMapper.class);
    tripPlaceMapper = mock(TripPlaceMapper.class);
    tripMemberService = mock(TripMemberService.class);
    userPreferenceService = mock(UserPreferenceService.class);
    weatherService = mock(WeatherService.class);
    bookingService = mock(BookingService.class);
    tripExpenseService = mock(TripExpenseService.class);
    packingListService = mock(PackingListService.class);
    travelDocumentService = mock(TravelDocumentService.class);
    llm = mock(LlmGatewayService.class);
    when(llm.isEnabled()).thenReturn(false);                  // 摘要走模板降级，不碰真实模型
    when(userPreferenceService.timeZone(USER)).thenReturn(ZONE);
    service = new TodayBriefService(tripService, tripDayMapper, tripPlaceMapper,
        tripMemberService, userPreferenceService, weatherService, bookingService,
        tripExpenseService, packingListService, travelDocumentService, llm,
        new TripJourneyService(tripDayMapper, tripPlaceMapper));
  }

  // ==================== 夹具 ====================

  private static Date day(LocalDate d) {
    return Date.from(d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
  }

  private static Trip trip(String id, String title, Date start, Date end) {
    Trip t = new Trip();
    t.setId(id);
    t.setTitle(title);
    t.setStartDate(start);
    t.setEndDate(end);
    t.setUpdatedAt(new Date());
    return t;
  }

  /** 注册用户可见行程（findAll 返回 id 列表、findLive 返回实体；无 archivedAt=未归档）。 */
  private void stubTrips(Trip... trips) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<String, Trip> byId = new HashMap<>();
    for (Trip t : trips) {
      rows.add(Map.of("id", t.getId()));
      byId.put(t.getId(), t);
    }
    when(tripService.findAll(USER)).thenReturn(Map.of("trips", rows));
    when(tripService.findLive(anyString()))
        .thenAnswer(inv -> byId.get(inv.getArgument(0, String.class)));
  }

  private Map<String, Object> autoBrief() {
    return service.brief(USER, null, null, true, false);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> tripOf(Map<String, Object> body) {
    return (Map<String, Object>) body.get("trip");
  }

  private static void assertEmptyState(Map<String, Object> body) {
    assertEquals("none", body.get("mode"), "空态契约：mode=none");
    assertNull(body.get("trip"), "空态契约：trip=null");
    assertNotNull(body.get("emptyState"), "空态契约：必须带 emptyState");
    assertEquals("还没有可以生成简报的旅行",
        ((Map<?, ?>) body.get("emptyState")).get("text"));
  }

  // ==================== 场景 ====================

  @Test
  void dateLessDraftPlusValidFutureTripSelectsFuture() {
    LocalDate today = LocalDate.now(ZONE);
    Trip dateless = trip("draft-no-date", "无日期草稿", null, null);
    Trip future = trip("future-1", "有效未来行程",
        day(today.plusDays(30)), day(today.plusDays(35)));
    stubTrips(dateless, future);

    // 修复前：此处 upcoming 排序对 null 起始日调用 Fmt.dateKey → NPE → 500
    Map<String, Object> body = autoBrief();

    assertEquals("pre-trip", body.get("mode"));
    assertNotNull(tripOf(body));
    assertEquals("future-1", tripOf(body).get("id"), "必须选择有效未来行程而非无日期草稿");
    assertNotNull(tripOf(body).get("startDate"), "所选行程必须有出发日");
    assertNull(body.get("emptyState"), "正常简报不携带空态");
  }

  @Test
  void dateLessDraftPlusInProgressKeepsOriginalPriority() {
    LocalDate today = LocalDate.now(ZONE);
    Trip dateless = trip("draft-no-date", "无日期草稿", null, null);
    Trip running = trip("running-1", "进行中行程",
        day(today.minusDays(1)), day(today.plusDays(1)));
    stubTrips(dateless, running);

    Map<String, Object> body = autoBrief();

    assertEquals("today", body.get("mode"), "进行中优先级保持不变");
    assertEquals("running-1", tripOf(body).get("id"), "必须选择进行中行程");
    assertNull(body.get("emptyState"));
  }

  @Test
  void onlyDateLessDraftsReturnsExistingEmptyState() {
    stubTrips(trip("draft-no-date", "无日期草稿", null, null),
        trip("draft-no-date-2", "另一张无日期草稿", null, null));

    Map<String, Object> body = autoBrief(); // 不得抛异常（修复前 NPE）

    assertEmptyState(body);
  }

  @Test
  void noTripsAtAllReturnsExistingEmptyState() {
    stubTrips(); // 空列表

    Map<String, Object> body = autoBrief();

    assertEmptyState(body);
  }

  @Test
  void explicitDateLessTripReturnsThatTripNotAnotherOne() {
    LocalDate today = LocalDate.now(ZONE);
    Trip dateless = trip("draft-no-date", "无日期草稿", null, null);
    Trip future = trip("future-1", "有效未来行程",
        day(today.plusDays(30)), day(today.plusDays(35)));
    stubTrips(dateless, future);

    // 显式指定无日期行程：不得 NPE，不得静默切换到 future-1，按 modeOf 约定返回其行前简报
    Map<String, Object> body = service.brief(USER, "draft-no-date", null, true, false);

    assertNotNull(tripOf(body), "显式行程必须返回该行程本身");
    assertEquals("draft-no-date", tripOf(body).get("id"), "绝不允许静默切换到其他行程");
    assertEquals("pre-trip", body.get("mode"), "无日期行程按约定判为行前模式");
    assertNull(tripOf(body).get("startDate"), "startDate 为 null 如实下发");
    assertNull(body.get("emptyState"), "显式行程不是空态");
    assertNotNull(body.get("summary"), "响应结构完整（summary 存在）");
  }

  @Test
  void normalDatedTripBriefUnaffected() {
    LocalDate today = LocalDate.now(ZONE);
    Trip future = trip("future-1", "有效未来行程",
        day(today.plusDays(30)), day(today.plusDays(35)));
    stubTrips(future);

    Map<String, Object> body = autoBrief();

    assertEquals("pre-trip", body.get("mode"));
    assertEquals("future-1", tripOf(body).get("id"));
    // 响应结构兼容：关键字段齐备（前端依赖）
    assertTrue(body.get("summary") instanceof String, "summary 必须存在");
    assertTrue(body.get("dataStatus") instanceof Map, "dataStatus 必须存在");
    assertTrue(body.get("actions") instanceof List, "actions 必须存在");
    assertNotNull(body.get("generatedAt"));
  }
}
