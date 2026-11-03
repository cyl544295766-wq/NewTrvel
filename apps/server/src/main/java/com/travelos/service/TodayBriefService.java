package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.ai.LlmGatewayService;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.Fmt;
import com.travelos.entity.Booking;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * AI 今日简报：把当前用户当天的行程、天气、预订、预算、打包、证件聚合成一份可操作简报。
 *
 * <p>职责边界（本阶段只读）：读取数据 → 确定性规则发现问题 → LLM 只做语言摘要 → 前端跳转处理。
 * 本服务绝不写入任何业务数据；业务事实一律来自现有 Service/实体，模型不参与事实判断。
 *
 * <p>行程选择优先级：显式 tripId &gt; 进行中行程 &gt; 最近即将开始（行前简报）&gt; 空态；
 * 多个候选按「当天安排较多、最近更新」择优，无法区分时不静默猜测、返回候选让用户选。
 * 「今天」按用户账户时区判断（行程实体没有目的地时区字段，见交付说明）；行程天日期沿用全站
 * {@link Fmt#dateKey}（UTC yyyy-MM-dd）口径匹配。
 */
@Service
public class TodayBriefService {

  private static final Logger log = LoggerFactory.getLogger(TodayBriefService.class);

  /** 同一用户/行程/日期的简报短时缓存；refresh=true 时绕过。key 含 userId，绝不跨用户共享。 */
  private static final long CACHE_TTL_MS = 60_000;

  /** LLM 摘要预算：超过即降级模板，绝不拖垮整个接口。 */
  private static final long LLM_TIMEOUT_MS = 15_000;

  /** 证件到期预警窗口：与旅行证件页「7 天内到期」口径一致。 */
  private static final int DOC_EXPIRY_WINDOW_DAYS = 7;

  /** 无用户偏好时的默认时区（与账户页默认值一致）。 */
  private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

  /** 降雨概率达到该值且当天有室外安排时提示天气风险。 */
  private static final int RAIN_ALERT_THRESHOLD = 50;

  private static final Map<String, String> BOOKING_TYPE_LABELS = Map.of(
      "flight", "机票", "hotel", "酒店", "train", "火车票", "ticket", "门票", "other", "预订");

  private final TripService tripService;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;
  private final UserPreferenceService userPreferenceService;
  private final WeatherService weatherService;
  private final BookingService bookingService;
  private final TripExpenseService tripExpenseService;
  private final PackingListService packingListService;
  private final TravelDocumentService travelDocumentService;
  private final LlmGatewayService llm;

  private final Map<String, CacheEntry> briefCache = new ConcurrentHashMap<>();
  private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "today-brief-llm");
    thread.setDaemon(true);
    return thread;
  });

  public TodayBriefService(TripService tripService, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberService tripMemberService,
      UserPreferenceService userPreferenceService, WeatherService weatherService,
      BookingService bookingService, TripExpenseService tripExpenseService,
      PackingListService packingListService, TravelDocumentService travelDocumentService,
      LlmGatewayService llm) {
    this.tripService = tripService;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
    this.userPreferenceService = userPreferenceService;
    this.weatherService = weatherService;
    this.bookingService = bookingService;
    this.tripExpenseService = tripExpenseService;
    this.packingListService = packingListService;
    this.travelDocumentService = travelDocumentService;
    this.llm = llm;
  }

  // ==================== 对外入口 ====================

  /**
   * 生成今日简报。
   *
   * @param tripId     显式行程，可空（空则按规则自动选择）
   * @param date       目标日期 yyyy-MM-dd，可空（默认按用户时区的今天）
   * @param refresh    true 时绕过缓存重新生成
   * @param llmSummary 是否调用 LLM 生成摘要（AI 工具路径传 false，由对话模型自己组织语言）
   */
  public Map<String, Object> brief(String userId, String tripId, String date, boolean refresh,
      boolean llmSummary) {
    ZoneId zone = userZone(userId);
    LocalDate today = LocalDate.now(zone);
    LocalDate target = parseDateOrToday(date, today);

    Selection selection = selectTrip(userId, tripId, target);
    if (selection == null) {
      return emptyBrief();
    }
    if (selection.candidates() != null) {
      return chooseBrief(selection.candidates());
    }

    Trip trip = selection.trip();
    String mode = selection.mode();
    String dateKey = target.toString();
    String cacheKey = userId + "|" + trip.getId() + "|" + dateKey + "|" + mode;
    if (!refresh) {
      CacheEntry cached = briefCache.get(cacheKey);
      if (cached != null && System.currentTimeMillis() - cached.cachedAt() < CACHE_TTL_MS) {
        return cached.brief();
      }
    }
    Map<String, Object> brief = buildBrief(userId, trip, mode, target, zone, llmSummary);
    briefCache.put(cacheKey, new CacheEntry(brief, System.currentTimeMillis()));
    if (briefCache.size() > 200) {
      briefCache.clear();
    }
    return brief;
  }

  // ==================== 行程选择 ====================

  private record Selection(Trip trip, String mode, List<Trip> candidates) {
  }

  private Selection selectTrip(String userId, String explicitTripId, LocalDate target) {
    String dateKey = target.toString();
    if (explicitTripId != null && !explicitTripId.isBlank()) {
      tripMemberService.requireTripMember(explicitTripId.trim(), userId);
      Trip trip = tripService.findLive(explicitTripId.trim());
      if (trip == null) {
        throw ApiException.notFound("旅行不存在");
      }
      return new Selection(trip, modeOf(trip, dateKey), null);
    }

    List<Trip> mine = new ArrayList<>();
    try {
      if (tripService.findAll(userId).get("trips") instanceof List<?> trips) {
        for (Object item : trips) {
          if (!(item instanceof Map<?, ?> map) || map.get("id") == null) {
            continue;
          }
          if (map.get("archivedAt") != null) {
            continue;
          }
          Trip trip = tripService.findLive(map.get("id").toString());
          if (trip != null) {
            mine.add(trip);
          }
        }
      }
    } catch (Exception ex) {
      log.warn("today-brief: 读取用户行程失败 userId={}", userId, ex);
      return null;
    }

    List<Trip> inProgress = new ArrayList<>();
    List<Trip> upcoming = new ArrayList<>();
    for (Trip trip : mine) {
      String mode = modeOf(trip, dateKey);
      if ("today".equals(mode)) {
        inProgress.add(trip);
      } else if ("pre-trip".equals(mode)) {
        upcoming.add(trip);
      }
    }

    if (!inProgress.isEmpty()) {
      return pickByArrangement(inProgress, "today", dateKey);
    }
    if (!upcoming.isEmpty()) {
      String nearestStart = upcoming.stream()
          .map(trip -> Fmt.dateKey(trip.getStartDate()))
          .min(Comparator.naturalOrder()).orElse(null);
      List<Trip> nearest = upcoming.stream()
          .filter(trip -> nearestStart != null
              && nearestStart.equals(Fmt.dateKey(trip.getStartDate())))
          .toList();
      return pickByArrangement(nearest, "pre-trip", dateKey);
    }
    return null;
  }

  /** 「当天已有安排较多、最近更新」择优；两条打平则不静默猜测，返回候选。 */
  private Selection pickByArrangement(List<Trip> pool, String mode, String dateKey) {
    if (pool.size() == 1) {
      return new Selection(pool.get(0), mode, null);
    }
    record Scored(Trip trip, int places, Date updatedAt) {
    }
    List<Scored> scored = new ArrayList<>();
    for (Trip trip : pool) {
      scored.add(new Scored(trip, countPlacesOn(trip.getId(), mode, dateKey), trip.getUpdatedAt()));
    }
    scored.sort((left, right) -> {
      int byPlaces = Integer.compare(right.places(), left.places());
      if (byPlaces != 0) {
        return byPlaces;
      }
      long l = left.updatedAt() == null ? 0 : left.updatedAt().getTime();
      long r = right.updatedAt() == null ? 0 : right.updatedAt().getTime();
      return Long.compare(r, l);
    });
    Scored top = scored.get(0);
    Scored second = scored.get(1);
    boolean tied = top.places() == second.places()
        && (top.updatedAt() == null ? 0 : top.updatedAt().getTime())
        == (second.updatedAt() == null ? 0 : second.updatedAt().getTime());
    if (tied) {
      return new Selection(null, "choose", pool);
    }
    return new Selection(top.trip(), mode, null);
  }

  /** 行前模式数第 1 天安排、今日模式数当天安排。 */
  private int countPlacesOn(String tripId, String mode, String dateKey) {
    try {
      TripDay day = "pre-trip".equals(mode) ? firstDay(tripId) : findDayByDate(tripId, dateKey);
      return day == null ? 0 : placeCount(day.getId());
    } catch (Exception ex) {
      return 0;
    }
  }

  /** today=当天在行程日期内；pre-trip=尚未出发；past=显式请求过去的日期（如实标注）。 */
  private String modeOf(Trip trip, String dateKey) {
    if (trip.getStartDate() == null) {
      return "pre-trip";
    }
    String startKey = Fmt.dateKey(trip.getStartDate());
    String endKey = trip.getEndDate() == null ? startKey : Fmt.dateKey(trip.getEndDate());
    if (dateKey.compareTo(startKey) < 0) {
      return "pre-trip";
    }
    return dateKey.compareTo(endKey) > 0 ? "past" : "today";
  }

  // ==================== 简报构建 ====================

  private Map<String, Object> buildBrief(String userId, Trip trip, String mode, LocalDate target,
      ZoneId zone, boolean llmSummary) {
    String dateKey = target.toString();
    boolean preTrip = "pre-trip".equals(mode);
    TripDay day = preTrip ? firstDay(trip.getId()) : findDayByDate(trip.getId(), dateKey);
    String tripId = trip.getId();
    String tripStartKey = trip.getStartDate() == null ? null : Fmt.dateKey(trip.getStartDate());

    List<Map<String, Object>> schedule = List.of();
    Map<String, Object> weather = null;
    List<Map<String, Object>> bookings = List.of();
    Map<String, Object> budget = null;
    Map<String, Object> packing = null;
    Map<String, Object> documents = null;
    Map<String, Object> dataStatus = new LinkedHashMap<>();

    // 各模块独立聚合：一个失败只标记自己 unavailable，不拖垮简报
    try {
      schedule = buildSchedule(day);
      dataStatus.put("schedule", "ok");
    } catch (Exception ex) {
      log.warn("today-brief: 行程聚合失败 tripId={}", tripId, ex);
      dataStatus.put("schedule", "unavailable");
    }
    try {
      weather = buildWeather(userId, tripId, dateKey);
      dataStatus.put("weather", weather == null ? "unavailable" : "ok");
    } catch (Exception ex) {
      log.warn("today-brief: 天气聚合失败 tripId={}", tripId, ex);
      dataStatus.put("weather", "unavailable");
    }
    try {
      bookings = buildBookings(tripId, day, dateKey);
      dataStatus.put("bookings", "ok");
    } catch (Exception ex) {
      log.warn("today-brief: 预订聚合失败 tripId={}", tripId, ex);
      dataStatus.put("bookings", "unavailable");
    }
    try {
      budget = buildBudget(userId, tripId, dateKey, preTrip);
      dataStatus.put("expenses", "ok");
    } catch (Exception ex) {
      log.warn("today-brief: 预算聚合失败 tripId={}", tripId, ex);
      dataStatus.put("expenses", "unavailable");
    }
    try {
      packing = buildPacking(userId, tripId, weather);
      dataStatus.put("packing", "ok");
    } catch (Exception ex) {
      log.warn("today-brief: 打包聚合失败 tripId={}", tripId, ex);
      dataStatus.put("packing", "unavailable");
    }
    try {
      documents = buildDocuments(tripId);
      dataStatus.put("documents", "ok");
    } catch (Exception ex) {
      log.warn("today-brief: 证件聚合失败 tripId={}", tripId, ex);
      dataStatus.put("documents", "unavailable");
    }

    List<Alert> alerts = buildAlerts(tripId, tripStartKey, mode, dateKey, zone, day, schedule,
        weather, bookings, budget, packing, documents);

    SummaryResult summary = llmSummary
        ? summarizeViaLlm(trip, mode, day, schedule, weather, bookings, budget, packing, documents,
            alerts, zone)
        : new SummaryResult(fallbackSummary(mode, day, schedule, alerts.size(), packing), "fallback");

    Map<String, Object> tripMap = new LinkedHashMap<>();
    tripMap.put("id", tripId);
    tripMap.put("title", trip.getTitle());
    tripMap.put("destination", trip.getDestination());
    tripMap.put("mode", mode);
    tripMap.put("startDate", trip.getStartDate());
    tripMap.put("endDate", trip.getEndDate());

    Map<String, Object> dayMap = null;
    if (day != null) {
      dayMap = new LinkedHashMap<>();
      dayMap.put("id", day.getId());
      dayMap.put("date", day.getDate());
      dayMap.put("dayIndex", day.getDayIndex());
      dayMap.put("totalDays", countDays(tripId));
      dayMap.put("title", day.getTitle());
      dayMap.put("preview", preTrip);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trip", tripMap);
    body.put("mode", mode);
    body.put("day", dayMap);
    body.put("summary", summary.text());
    body.put("summarySource", summary.source());
    body.put("schedule", schedule);
    body.put("weather", weather);
    body.put("bookings", bookings);
    body.put("budget", budget);
    body.put("packing", packing);
    body.put("documents", documents);
    body.put("alerts", alerts.stream().map(Alert::body).toList());
    body.put("actions", buildActions(tripId, day, mode, bookings));
    body.put("dataStatus", dataStatus);
    body.put("generatedAt", new Date());
    return body;
  }

  // ---- 模块：行程 ----

  private List<Map<String, Object>> buildSchedule(TripDay day) {
    if (day == null) {
      return List.of();
    }
    List<TripPlace> places = tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripDayId, day.getId())
        .orderByAsc(TripPlace::getSortOrder));
    List<Map<String, Object>> timed = new ArrayList<>();
    List<Map<String, Object>> untimed = new ArrayList<>();
    for (TripPlace place : places) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", place.getId());
      row.put("name", place.getName());
      row.put("type", place.getType());
      row.put("address", place.getAddress());
      row.put("startTime", place.getStartTime());
      row.put("endTime", place.getEndTime());
      row.put("notes", place.getNotes());
      row.put("latitude", place.getLatitude() == null ? null : Fmt.coord(place.getLatitude()));
      row.put("longitude", place.getLongitude() == null ? null : Fmt.coord(place.getLongitude()));
      row.put("hasCoords", place.getLatitude() != null && place.getLongitude() != null);
      row.put("isCompleted", place.getIsCompleted());
      boolean isTimed = place.getStartTime() != null;
      row.put("timed", isTimed);
      if (isTimed) {
        timed.add(row);
      } else {
        untimed.add(row);
      }
    }
    timed.sort(Comparator.comparing(row -> (Date) row.get("startTime")));
    List<Map<String, Object>> merged = new ArrayList<>(timed);
    merged.addAll(untimed);
    return merged;
  }

  // ---- 模块：天气 ----

  @SuppressWarnings("unchecked")
  private Map<String, Object> buildWeather(String userId, String tripId, String dateKey) {
    Map<String, Object> res = weatherService.getTripWeather(tripId, userId);
    if (!(res.get("weather") instanceof List<?> rows)) {
      return null;
    }
    for (Object item : rows) {
      if (!(item instanceof Map<?, ?> row) || !(row.get("date") instanceof Date when)) {
        continue;
      }
      if (!Fmt.dateKey(when).equals(dateKey)) {
        continue;
      }
      Map<String, Object> weather = new LinkedHashMap<>();
      weather.put("date", when);
      weather.put("condition", row.get("condition"));
      weather.put("tempHigh", row.get("tempHigh"));
      weather.put("tempLow", row.get("tempLow"));
      weather.put("rainChance", row.get("rainChance"));
      return weather;
    }
    return null;
  }

  // ---- 模块：预订 ----

  /** 当日需要使用的预订：挂在当天、当天开始、或酒店入住区间覆盖当天。 */
  private List<Map<String, Object>> buildBookings(String tripId, TripDay day, String dateKey) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Booking booking : bookingService.findBookingsByTrip(tripId)) {
      boolean used = day != null && day.getId().equals(booking.getTripDayId());
      if (!used && booking.getStartTime() != null
          && Fmt.dateKey(booking.getStartTime()).equals(dateKey)) {
        used = true;
      }
      if (!used && "hotel".equals(booking.getType()) && booking.getStartTime() != null
          && booking.getEndTime() != null) {
        String from = Fmt.dateKey(booking.getStartTime());
        String to = Fmt.dateKey(booking.getEndTime());
        used = dateKey.compareTo(from) >= 0 && dateKey.compareTo(to) <= 0;
      }
      if (!used) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", booking.getId());
      row.put("type", booking.getType());
      row.put("typeLabel", BOOKING_TYPE_LABELS.getOrDefault(booking.getType(), "预订"));
      row.put("title", booking.getTitle());
      row.put("startTime", booking.getStartTime());
      row.put("endTime", booking.getEndTime());
      // 只回「是否有」订单号/确认码，绝不回值（首页与 LLM 都拿不到原文）
      row.put("hasOrderNo", booking.getOrderNo() != null && !booking.getOrderNo().isBlank());
      row.put("hasConfirmCode",
          booking.getConfirmCode() != null && !booking.getConfirmCode().isBlank());
      rows.add(row);
    }
    rows.sort(Comparator.comparing(row -> (Date) row.get("startTime"),
        Comparator.nullsLast(Comparator.naturalOrder())));
    return rows;
  }

  // ---- 模块：预算 ----

  @SuppressWarnings("unchecked")
  private Map<String, Object> buildBudget(String userId, String tripId, String dateKey,
      boolean preTrip) {
    Map<String, Object> budget = tripExpenseService.getBudget(tripId, userId);
    BigDecimal todaySpent = BigDecimal.ZERO;
    int todayCount = 0;
    String currency = "CNY";
    if (!preTrip) {
      for (TripExpense expense : tripExpenseService.findExpenses(tripId)) {
        if (expense.getSpentAt() == null || !Fmt.dateKey(expense.getSpentAt()).equals(dateKey)) {
          continue;
        }
        if (expense.getAmount() != null) {
          todaySpent = todaySpent.add(expense.getAmount());
          todayCount++;
        }
        if (expense.getCurrency() != null && !expense.getCurrency().isBlank()) {
          currency = expense.getCurrency();
        }
      }
    }

    Map<String, Object> tightest = null;
    BigDecimal tightestRemaining = null;
    if (budget.get("categories") instanceof List<?> categories) {
      for (Object item : categories) {
        if (!(item instanceof Map<?, ?> category)
            || !Boolean.TRUE.equals(category.get("hasBudget"))) {
          continue;
        }
        BigDecimal remaining = decimalOf(category.get("remaining"));
        if (tightestRemaining == null || remaining.compareTo(tightestRemaining) < 0) {
          tightestRemaining = remaining;
          Map<String, Object> pick = new LinkedHashMap<>();
          pick.put("category", category.get("category"));
          pick.put("label", category.get("label"));
          pick.put("budget", category.get("budget"));
          pick.put("spent", category.get("spent"));
          pick.put("remaining", category.get("remaining"));
          tightest = pick;
        }
      }
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tripBudget", budget.get("tripBudget"));
    body.put("totalSpent", budget.get("totalSpent"));
    body.put("totalEstimated", budget.get("totalEstimated"));
    body.put("totalRemaining", budget.get("totalRemaining"));
    body.put("totalWarning", Boolean.TRUE.equals(budget.get("totalWarning")));
    body.put("todaySpent", Fmt.money(todaySpent));
    body.put("todayCount", todayCount);
    body.put("currency", currency);
    body.put("tightestCategory", tightest);
    return body;
  }

  // ---- 模块：打包 ----

  @SuppressWarnings("unchecked")
  private Map<String, Object> buildPacking(String userId, String tripId,
      Map<String, Object> weather) {
    Map<String, Object> res = packingListService.findAll(tripId, userId);
    int total = 0;
    int packed = 0;
    List<String> unpackedNames = new ArrayList<>();
    if (res.get("packingLists") instanceof List<?> lists) {
      for (Object listObj : lists) {
        if (!(listObj instanceof Map<?, ?> list) || !(list.get("items") instanceof List<?> items)) {
          continue;
        }
        for (Object itemObj : items) {
          if (!(itemObj instanceof Map<?, ?> item)) {
            continue;
          }
          total++;
          if (Boolean.TRUE.equals(item.get("isPacked"))) {
            packed++;
          } else if (item.get("name") != null) {
            unpackedNames.add(item.get("name").toString());
          }
        }
      }
    }

    // 只在有天气依据时给出关联物品，最多 3 项；没有依据就空着，不猜测
    List<String> related = new ArrayList<>();
    if (weather != null) {
      List<String> keywords = weatherKeywords(weather);
      for (String name : unpackedNames) {
        for (String keyword : keywords) {
          if (name.contains(keyword)) {
            related.add(name);
            break;
          }
        }
        if (related.size() >= 3) {
          break;
        }
      }
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("totalItems", total);
    body.put("packedItems", packed);
    body.put("unpackedItems", total - packed);
    body.put("relatedItems", related);
    return body;
  }

  /** 天气 → 明显相关的打包关键词（雨/热/冷三类，其余不猜）。 */
  private List<String> weatherKeywords(Map<String, Object> weather) {
    List<String> keywords = new ArrayList<>();
    int rain = weather.get("rainChance") instanceof Number number ? number.intValue() : 0;
    String condition = weather.get("condition") == null ? "" : weather.get("condition").toString();
    if (rain >= RAIN_ALERT_THRESHOLD || condition.contains("雨") || condition.contains("雪")) {
      keywords.addAll(List.of("伞", "雨", "防水"));
    }
    if (weather.get("tempHigh") instanceof Number high && high.doubleValue() >= 32) {
      keywords.addAll(List.of("防晒", "墨镜", "帽"));
    }
    if (weather.get("tempLow") instanceof Number low && low.doubleValue() <= 5) {
      keywords.addAll(List.of("羽绒", "手套", "围巾", "暖"));
    }
    return keywords;
  }

  // ---- 模块：证件 ----

  private Map<String, Object> buildDocuments(String tripId) {
    Date now = new Date();
    long windowMs = TimeUnit.DAYS.toMillis(DOC_EXPIRY_WINDOW_DAYS);
    int expired = 0;
    int expiring = 0;
    for (TravelDocument document : travelDocumentService.findDocumentsByTrip(tripId)) {
      Date due = document.getExpiredAt();
      if (due == null) {
        continue;
      }
      if (due.before(now)) {
        expired++;
      } else if (due.getTime() - now.getTime() <= windowMs) {
        expiring++;
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("hasExpired", expired > 0);
    body.put("hasExpiring", expiring > 0);
    body.put("expiredCount", expired);
    body.put("expiringCount", expiring);
    return body;
  }

  // ==================== 提醒规则（确定性） ====================

  /** 单条提醒：body 为对外结构，severityRank/relevance 只用于排序（urgent &gt; warning &gt; info）。 */
  private record Alert(Map<String, Object> body, int severityRank, int relevance) {
  }

  private List<Alert> buildAlerts(String tripId, String tripStartKey, String mode, String dateKey,
      ZoneId zone, TripDay day, List<Map<String, Object>> schedule, Map<String, Object> weather,
      List<Map<String, Object>> bookings, Map<String, Object> budget, Map<String, Object> packing,
      Map<String, Object> documents) {
    List<Alert> alerts = new ArrayList<>();
    boolean preTrip = "pre-trip".equals(mode);

    // 1. 降雨/恶劣天气：当天有室外安排且降雨概率较高（只建议查看行程，不自动改期）
    if (weather != null && !schedule.isEmpty()) {
      int rain = weather.get("rainChance") instanceof Number number ? number.intValue() : 0;
      String condition = weather.get("condition") == null ? "" : weather.get("condition").toString();
      boolean rainy = rain >= RAIN_ALERT_THRESHOLD || condition.contains("雨")
          || condition.contains("雪") || condition.contains("雷");
      List<String> outdoor = new ArrayList<>();
      if (rainy) {
        for (Map<String, Object> place : schedule) {
          String type = String.valueOf(place.get("type"));
          if (List.of("attraction", "shopping", "custom").contains(type)) {
            outdoor.add(String.valueOf(place.get("name")));
          }
        }
      }
      if (!outdoor.isEmpty()) {
        alerts.add(alert("weather_rain", "warning", "天气可能影响室外安排",
            "预报" + (condition.isEmpty() ? "降雨概率 " + rain + "%" : condition + "，降雨概率 " + rain + "%")
                + "，当天有 " + outdoor.size() + " 项室外安排（如" + outdoor.get(0)
                + "），建议查看行程并考虑调整",
            "rainChance=" + rain + "，室外安排：" + String.join("、", outdoor),
            action("查看行程并考虑调整", itineraryUrl(tripId, day)), 3));
      }
    }

    // 2. 时间冲突：按开始时间排序后，上一项结束时间晚于下一项开始时间
    List<Map<String, Object>> timed = new ArrayList<>();
    for (Map<String, Object> place : schedule) {
      if (place.get("startTime") instanceof Date) {
        timed.add(place);
      }
    }
    for (int i = 0; i + 1 < timed.size(); i++) {
      Date end = (Date) timed.get(i).get("endTime");
      Date nextStart = (Date) timed.get(i + 1).get("startTime");
      if (end != null && nextStart != null && end.after(nextStart)) {
        alerts.add(alert("time_conflict", "warning", "今日时间安排有冲突",
            "「" + timed.get(i).get("name") + "」结束时间晚于「" + timed.get(i + 1).get("name")
                + "」的开始时间，建议查看行程并调整",
            timed.get(i).get("name") + " endTime(" + hmInZone(end, zone) + ") > "
                + timed.get(i + 1).get("name") + " startTime(" + hmInZone(nextStart, zone) + ")",
            action("查看行程", itineraryUrl(tripId, day)), 3));
        break;
      }
    }

    // 3. 时间信息不完整：当天有地点但全部没有时间
    if (!schedule.isEmpty()) {
      boolean anyTimed = schedule.stream()
          .anyMatch(place -> Boolean.TRUE.equals(place.get("timed")));
      if (!anyTimed) {
        alerts.add(alert("time_missing", "info", "今天的安排都还没有时间",
            "当天共 " + schedule.size() + " 项安排但全部缺少开始/结束时间，建议补充时间以便生成时间轴",
            "timedPlaces=0, totalPlaces=" + schedule.size(),
            action("去补充时间", itineraryUrl(tripId, day)), 3));
      }
    }

    // 4. 路线信息不完整：多个地点但部分缺坐标
    if (schedule.size() > 1) {
      long missing = schedule.stream()
          .filter(place -> !Boolean.TRUE.equals(place.get("hasCoords")))
          .count();
      if (missing > 0) {
        alerts.add(alert("route_incomplete", "info", "部分地点缺坐标",
            "当天有 " + schedule.size() + " 个地点，其中 " + missing + " 个缺少坐标，路线可能无法完整展示",
            "missingCoords=" + missing + "/" + schedule.size(),
            action("查看路线", mapUrl(tripId, day)), 3));
      }
    }

    // 5. 凭证缺失：有当日预订但缺确认信息（只提示确认，不断言未预订）
    long missingCodes = bookings.stream()
        .filter(booking -> !Boolean.TRUE.equals(booking.get("hasOrderNo"))
            && !Boolean.TRUE.equals(booking.get("hasConfirmCode")))
        .count();
    if (missingCodes > 0) {
      alerts.add(alert("voucher_missing", "info", "有预订缺确认信息",
          "当天有 " + missingCodes + " 条预订没有订单号与确认码，出发前记得确认预订信息",
          "bookingsWithoutCode=" + missingCodes,
          action("打开凭证", "/vouchers/?trip=" + tripId), 3));
    }

    // 6. 预算风险：超支 urgent；总预警 warning；剩余不足一成 warning
    if (budget != null) {
      BigDecimal spent = decimalOf(budget.get("totalSpent"));
      BigDecimal totalBudget = decimalOf(budget.get("tripBudget"));
      BigDecimal remaining = decimalOf(budget.get("totalRemaining"));
      String expenseUrl = "/expenses/?trip=" + tripId;
      if (totalBudget.signum() > 0 && spent.compareTo(totalBudget) > 0) {
        alerts.add(alert("budget_risk", "urgent", "花费已超过预算",
            "累计花费 " + budget.get("totalSpent") + " 已超过总预算 " + budget.get("tripBudget"),
            "totalSpent=" + budget.get("totalSpent") + ", tripBudget=" + budget.get("tripBudget"),
            action("查看账目", expenseUrl), 2));
      } else if (Boolean.TRUE.equals(budget.get("totalWarning"))) {
        alerts.add(alert("budget_risk", "warning", "预算可能超支",
            "按当前花费与预估占用，总预算可能不足，建议查看账目与预算",
            "totalSpent=" + budget.get("totalSpent") + ", totalEstimated="
                + budget.get("totalEstimated") + ", tripBudget=" + budget.get("tripBudget"),
            action("查看账目", expenseUrl), 2));
      } else if (totalBudget.signum() > 0 && remaining.signum() >= 0
          && remaining.compareTo(totalBudget.divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP)) <= 0) {
        alerts.add(alert("budget_risk", "warning", "剩余预算不足一成",
            "总预算 " + budget.get("tripBudget") + "，剩余 " + budget.get("totalRemaining"),
            "totalRemaining=" + budget.get("totalRemaining") + ", tripBudget="
                + budget.get("tripBudget"),
            action("查看账目", expenseUrl), 2));
      }
    }

    // 7. 打包未完成：仅出发当天或旅行进行中提示（行前未出发不提示，如实按规则）
    if (!preTrip && packing != null
        && packing.get("unpackedItems") instanceof Number unpacked && unpacked.intValue() > 0) {
      boolean departure = dateKey.equals(tripStartKey);
      alerts.add(alert("packing_incomplete", departure ? "warning" : "info",
          departure ? "出发当天还有物品没打包" : "还有物品没打包",
          "还有 " + unpacked.intValue() + " 件物品未打包，出发前记得清点",
          "unpackedItems=" + unpacked.intValue(),
          action("去打包清单", "/packing/?trip=" + tripId), 2));
    }

    // 8. 证件风险：已过期 urgent / 7 天内到期 warning（只报数量，不暴露证件内容）
    if (documents != null) {
      int expired = documents.get("expiredCount") instanceof Number number ? number.intValue() : 0;
      int expiring = documents.get("expiringCount") instanceof Number number ? number.intValue() : 0;
      if (expired > 0) {
        alerts.add(alert("document_risk", "urgent", "有证件已过期",
            "有 " + expired + " 份证件已过期，请尽快处理",
            "expiredCount=" + expired, action("查看证件", "/documents/?trip=" + tripId), 1));
      } else if (expiring > 0) {
        alerts.add(alert("document_risk", "warning", "有证件即将到期",
            "有 " + expiring + " 份证件将在 " + DOC_EXPIRY_WINDOW_DAYS + " 天内到期，注意及时更换",
            "expiringCount=" + expiring, action("查看证件", "/documents/?trip=" + tripId), 1));
      }
    }

    alerts.sort(Comparator.comparingInt(Alert::severityRank)
        .thenComparing(Alert::relevance, Comparator.reverseOrder()));
    return alerts;
  }

  // ==================== 摘要（LLM + 模板降级） ====================

  private record SummaryResult(String text, String source) {
  }

  private SummaryResult summarizeViaLlm(Trip trip, String mode, TripDay day,
      List<Map<String, Object>> schedule, Map<String, Object> weather,
      List<Map<String, Object>> bookings, Map<String, Object> budget, Map<String, Object> packing,
      Map<String, Object> documents, List<Alert> alerts, ZoneId zone) {
    String template = fallbackSummary(mode, day, schedule, alerts.size(), packing);
    if (!llm.isEnabled()) {
      return new SummaryResult(template, "fallback");
    }
    String facts = factText(trip, mode, day, schedule, weather, bookings, budget, packing,
        documents, alerts, zone);
    Future<String> future = llmExecutor.submit(() -> {
      Map<String, Object> message = llm.chat(List.of(
          Map.of("role", "system", "content", LLM_SYSTEM),
          Map.of("role", "user", "content", facts)), null);
      Object content = message.get("content");
      return content == null ? "" : content.toString().trim();
    });
    try {
      String text = future.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (text != null && !text.isBlank()) {
        return new SummaryResult(text.length() > 300 ? text.substring(0, 300) : text, "ai");
      }
    } catch (Exception ex) {
      future.cancel(true);
      log.warn("today-brief: LLM 摘要失败，降级模板摘要：{}", ex.toString());
    }
    return new SummaryResult(template, "fallback");
  }

  private static final String LLM_SYSTEM =
      "你是旅行简报的摘要写手。根据用户提供的结构化数据写一段 80~140 字的中文简报摘要，"
          + "要求：1) 只使用数据中出现的事实，绝不添加数据中不存在的地点、时间、天气、价格或预订；"
          + "2) 信息不足时直接说不足，不要用常识性事实冒充真实数据；"
          + "3) 结合已有提醒给出简短可执行的建议，但不要编造新的提醒；"
          + "4) 不暴露订单号、确认码、证件号或文件地址；5) 直接输出摘要正文，不要标题、列表或 Markdown。";

  /** 给 LLM 的最小字段事实文本：不含订单号/确认码/证件内容。 */
  private String factText(Trip trip, String mode, TripDay day, List<Map<String, Object>> schedule,
      Map<String, Object> weather, List<Map<String, Object>> bookings, Map<String, Object> budget,
      Map<String, Object> packing, Map<String, Object> documents, List<Alert> alerts, ZoneId zone) {
    StringBuilder text = new StringBuilder();
    text.append("行程：").append(trip.getTitle())
        .append("（").append(trip.getDestination() == null ? "未填目的地" : trip.getDestination())
        .append("）\n");
    text.append("模式：").append("pre-trip".equals(mode) ? "行前简报" : "今日简报").append("\n");
    if (day != null) {
      text.append("目标日：第 ").append(day.getDayIndex()).append(" 天（")
          .append(Fmt.dateKey(day.getDate())).append("）")
          .append(day.getTitle() == null ? "" : " " + day.getTitle()).append("\n");
    }
    text.append("安排（").append(schedule.size()).append(" 项）：\n");
    for (Map<String, Object> place : schedule) {
      text.append("- ");
      if (Boolean.TRUE.equals(place.get("timed"))) {
        text.append(hmInZone((Date) place.get("startTime"), zone));
      } else {
        text.append("时间待定");
      }
      text.append(" ").append(place.get("name")).append("（").append(place.get("type")).append("）\n");
    }
    if (weather != null) {
      text.append("天气：").append(weather.get("condition")).append("，")
          .append(weather.get("tempLow")).append("~").append(weather.get("tempHigh"))
          .append("℃，降雨概率 ").append(weather.get("rainChance")).append("%\n");
    } else {
      text.append("天气：暂不可用\n");
    }
    text.append("当日预订：").append(bookings.size()).append(" 条");
    for (Map<String, Object> booking : bookings) {
      text.append("；").append(booking.get("typeLabel")).append("·").append(booking.get("title"))
          .append(Boolean.TRUE.equals(booking.get("hasOrderNo"))
              || Boolean.TRUE.equals(booking.get("hasConfirmCode")) ? "（已有确认信息）" : "（缺确认信息）");
    }
    text.append("\n");
    if (budget != null) {
      text.append("预算：总 ").append(budget.get("tripBudget")).append("，已花 ")
          .append(budget.get("totalSpent")).append("，剩余 ").append(budget.get("totalRemaining"))
          .append("，今日 ").append(budget.get("todaySpent")).append("\n");
    }
    if (packing != null) {
      text.append("打包：共 ").append(packing.get("totalItems")).append(" 件，未打包 ")
          .append(packing.get("unpackedItems")).append(" 件\n");
    }
    if (documents != null) {
      text.append("证件：过期 ").append(documents.get("expiredCount")).append(" 份，")
          .append(DOC_EXPIRY_WINDOW_DAYS).append(" 天内到期 ")
          .append(documents.get("expiringCount")).append(" 份\n");
    }
    text.append("提醒：").append(alerts.isEmpty() ? "无" : "").append("\n");
    for (Alert alert : alerts) {
      text.append("- [").append(alert.body().get("title")).append("] ")
          .append(alert.body().get("detail")).append("\n");
    }
    return text.toString();
  }

  /** 模板降级摘要：LLM 未配置/失败时保证接口仍返回可读摘要（alertCount 为提醒条数）。 */
  static String fallbackSummary(String mode, TripDay day, List<Map<String, Object>> schedule,
      int alertCount, Map<String, Object> packing) {
    int total = schedule.size();
    int am = 0;
    int pm = 0;
    int tbd = 0;
    for (Map<String, Object> place : schedule) {
      if (!Boolean.TRUE.equals(place.get("timed")) || !(place.get("startTime") instanceof Date start)) {
        tbd++;
        continue;
      }
      if (LocalDateTime.ofInstant(start.toInstant(), DEFAULT_ZONE).getHour() < 12) {
        am++;
      } else {
        pm++;
      }
    }
    int unpacked = packing != null && packing.get("unpackedItems") instanceof Number number
        ? number.intValue() : 0;
    StringBuilder summary = new StringBuilder();
    if ("pre-trip".equals(mode)) {
      summary.append("行前简报：");
      summary.append(day == null ? "行程还没有安排"
          : "第 1 天共 " + total + " 项安排");
    } else {
      summary.append("今天共有 ").append(total).append(" 项安排，上午 ").append(am)
          .append(" 项、下午 ").append(pm).append(" 项");
      if (tbd > 0) {
        summary.append("，另有 ").append(tbd).append(" 项时间待定");
      }
      summary.append("。");
    }
    if (alertCount == 0) {
      summary.append("当前未发现需要特别处理的问题。");
    } else {
      summary.append("当前有 ").append(alertCount).append(" 条提醒");
      if (unpacked > 0) {
        summary.append("和 ").append(unpacked).append(" 件物品未打包");
      }
      summary.append("。请查看今日行程与凭证。");
    }
    return summary.toString();
  }

  // ==================== 入口动作 / 空态 ====================

  private List<Map<String, Object>> buildActions(String tripId, TripDay day, String mode,
      List<Map<String, Object>> bookings) {
    List<Map<String, Object>> actions = new ArrayList<>();
    actions.add(action("pre-trip".equals(mode) ? "查看第 1 天行程" : "查看今日行程",
        itineraryUrl(tripId, day)));
    actions.add(action("pre-trip".equals(mode) ? "查看路线" : "查看今日路线", mapUrl(tripId, day)));
    if (!bookings.isEmpty()) {
      actions.add(action("打开今日凭证", "/vouchers/?trip=" + tripId));
    } else {
      actions.add(action("快速记账", "/expenses/?trip=" + tripId));
    }
    return actions;
  }

  private Map<String, Object> emptyBrief() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trip", null);
    body.put("mode", "none");
    body.put("day", null);
    body.put("summary", "");
    body.put("summarySource", "fallback");
    body.put("schedule", List.of());
    body.put("weather", null);
    body.put("bookings", List.of());
    body.put("budget", null);
    body.put("packing", null);
    body.put("documents", null);
    body.put("alerts", List.of());
    body.put("actions", List.of(action("创建新旅行", "/trip-form/")));
    body.put("dataStatus", Map.of("weather", "unavailable", "expenses", "unavailable",
        "packing", "unavailable", "documents", "unavailable"));
    body.put("emptyState", Map.of("text", "还没有可以生成简报的旅行"));
    body.put("generatedAt", new Date());
    return body;
  }

  private Map<String, Object> chooseBrief(List<Trip> candidates) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Trip trip : candidates) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", trip.getId());
      row.put("title", trip.getTitle());
      row.put("startDate", trip.getStartDate());
      row.put("endDate", trip.getEndDate());
      rows.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trip", null);
    body.put("mode", "choose");
    body.put("day", null);
    body.put("summary", "有多个行程同时符合今天，请选择要生成简报的行程。");
    body.put("summarySource", "fallback");
    body.put("schedule", List.of());
    body.put("weather", null);
    body.put("bookings", List.of());
    body.put("budget", null);
    body.put("packing", null);
    body.put("documents", null);
    body.put("alerts", List.of());
    body.put("actions", List.of(action("创建新旅行", "/trip-form/")));
    body.put("dataStatus", Map.of("weather", "unavailable", "expenses", "unavailable",
        "packing", "unavailable", "documents", "unavailable"));
    body.put("candidates", rows);
    body.put("emptyState", Map.of("text", "请选择要生成简报的行程"));
    body.put("generatedAt", new Date());
    return body;
  }

  // ==================== 工具方法 ====================

  private TripDay findDayByDate(String tripId, String dateKey) {
    for (TripDay day : tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId))) {
      if (day.getDate() != null && Fmt.dateKey(day.getDate()).equals(dateKey)) {
        return day;
      }
    }
    return null;
  }

  private TripDay firstDay(String tripId) {
    List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .orderByAsc(TripDay::getDayIndex));
    return days.isEmpty() ? null : days.get(0);
  }

  private int countDays(String tripId) {
    Long count = tripDayMapper.selectCount(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId));
    return count == null ? 0 : count.intValue();
  }

  private int placeCount(String tripDayId) {
    Long count = tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripDayId, tripDayId));
    return count == null ? 0 : count.intValue();
  }

  private ZoneId userZone(String userId) {
    try {
      Map<String, Object> res = userPreferenceService.get(new UserContext(userId, null, null));
      if (res.get("preferences") instanceof Map<?, ?> preferences
          && preferences.get("timezone") instanceof String timezone && !timezone.isBlank()) {
        return ZoneId.of(timezone);
      }
    } catch (Exception ex) {
      log.warn("today-brief: 读取用户时区失败，按默认时区处理 userId={}", userId);
    }
    return DEFAULT_ZONE;
  }

  private LocalDate parseDateOrToday(String date, LocalDate today) {
    if (date == null || date.isBlank()) {
      return today;
    }
    try {
      return LocalDate.parse(date.trim());
    } catch (DateTimeParseException ex) {
      throw ApiException.badRequest("date 必须是 yyyy-MM-dd 格式");
    }
  }

  private Alert alert(String type, String severity, String title, String detail, String evidence,
      Map<String, Object> action, int relevance) {
    int rank = switch (severity) {
      case "urgent" -> 0;
      case "warning" -> 1;
      default -> 2;
    };
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", type);
    body.put("type", type);
    body.put("severity", severity);
    body.put("title", title);
    body.put("detail", detail);
    body.put("evidence", evidence);
    body.put("action", action);
    return new Alert(body, rank, relevance);
  }

  private Map<String, Object> action(String label, String url) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("label", label);
    map.put("url", url);
    return map;
  }

  private String itineraryUrl(String tripId, TripDay day) {
    return day == null ? "/itinerary/?trip=" + tripId
        : "/itinerary/?trip=" + tripId + "&day=" + day.getId();
  }

  private String mapUrl(String tripId, TripDay day) {
    return "/map/?trip=" + tripId + (day == null ? "" : "&day=" + day.getId());
  }

  private BigDecimal decimalOf(Object value) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException ex) {
      return BigDecimal.ZERO;
    }
  }

  /** HH:mm（用户时区），与前端时间轴展示口径一致。 */
  private String hmInZone(Date date, ZoneId zone) {
    if (date == null) {
      return "时间待定";
    }
    LocalDateTime time = LocalDateTime.ofInstant(date.toInstant(), zone);
    return String.format("%02d:%02d", time.getHour(), time.getMinute());
  }

  private record CacheEntry(Map<String, Object> brief, long cachedAt) {
  }
}
