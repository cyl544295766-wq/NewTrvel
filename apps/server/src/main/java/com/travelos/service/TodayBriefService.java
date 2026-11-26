package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.ai.LlmGatewayService;
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
import com.travelos.trip.TripPermission;
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
import java.util.Set;
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
  public static final String WEATHER_RAIN = "weather_rain";
  public static final String TIME_CONFLICT = "time_conflict";
  private static final Set<String> POSSIBLY_OUTDOOR = Set.of("attraction", "shopping", "custom");
  private static final List<String> INDOOR_VENUES = List.of("博物馆", "美术馆", "展览馆");

  private static final Map<String, String> BOOKING_TYPE_LABELS = Map.of(
      "flight", "机票", "hotel", "酒店", "train", "火车票", "ticket", "门票", "other", "预订");

  private final TripService tripService;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;
  private final UserPreferenceService userPreferenceService;
  private final TripJourneyService journey;
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
      LlmGatewayService llm, TripJourneyService journey) {
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
    this.journey = journey;
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
    ZoneId zone = userPreferenceService.timeZone(userId);
    LocalDate today = LocalDate.now(zone);
    LocalDate target = parseDateOrToday(date, today);

    TripJourneyService.Selection selection = selectTrip(userId, tripId, target);
    if ((tripId == null || tripId.isBlank()) && List.of("draft", "past", "archived").contains(selection.phase())) {
      return emptyBrief();
    }
    if (!selection.candidates().isEmpty()) {
      return chooseBrief(selection.candidates());
    }
    if (selection.trip() == null) return emptyBrief();

    Trip trip = selection.trip();
    String mode = "draft".equals(selection.phase()) ? "pre-trip" : selection.phase();
    String dateKey = target.toString();
    boolean canAdjust = "today".equals(mode) && target.equals(today)
        && TripPermission.EDITABLE_ROLES.contains(tripMemberService.requireTripMember(trip.getId(), userId).getRole());
    String cacheKey = userId + "|" + trip.getId() + "|" + dateKey + "|" + mode + "|" + zone + "|" + llmSummary
        + "|" + canAdjust;
    if (!refresh) {
      CacheEntry cached = briefCache.get(cacheKey);
      if (cached != null && System.currentTimeMillis() - cached.cachedAt() < CACHE_TTL_MS) {
        return cached.brief();
      }
    }
    Map<String, Object> brief = buildBrief(userId, trip, mode, target, zone, llmSummary, canAdjust);
    brief.put("phase", selection.phase());
    brief.put("dateKey", dateKey);
    brief.put("timeZone", zone.getId());
    briefCache.put(cacheKey, new CacheEntry(brief, System.currentTimeMillis()));
    if (briefCache.size() > 200) {
      briefCache.clear();
    }
    return brief;
  }

  // ==================== 行程选择 ====================

  private TripJourneyService.Selection selectTrip(String userId, String explicitTripId, LocalDate target) {
    if (explicitTripId != null && !explicitTripId.isBlank()) {
      String id = explicitTripId.trim();
      tripMemberService.requireTripMember(id, userId);
      Trip trip = tripService.findLive(id);
      if (trip == null) throw ApiException.notFound("旅行不存在");
      return journey.choose(List.of(trip), id, target);
    }
    List<Trip> trips = new ArrayList<>();
    if (tripService.findAll(userId).get("trips") instanceof List<?> accessible) {
      for (Object item : accessible) {
        if (item instanceof Map<?, ?> row && row.get("id") != null) {
          Trip trip = tripService.findLive(row.get("id").toString());
          if (trip != null) trips.add(trip);
        }
      }
    }
    return journey.choose(trips, null, target);
  }


  // ==================== 简报构建 ====================

  private Map<String, Object> buildBrief(String userId, Trip trip, String mode, LocalDate target,
      ZoneId zone, boolean llmSummary, boolean canAdjust) {
    String dateKey = target.toString();
    boolean preTrip = "pre-trip".equals(mode);
    TripDay day = journey.dayFor(trip.getId(), mode, target);
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
      bookings = buildBookings(tripId, day, dateKey, zone);
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
    if (canAdjust) {
      for (Alert alert : alerts) {
        String type = (String) alert.body().get("type");
        var targets = switch (type) {
          case WEATHER_RAIN -> outdoorPlaces(schedule, weather);
          case TIME_CONFLICT -> firstOverlap(schedule);
          default -> List.<Map<String, Object>>of();
        };
        if (!actionPlaceIds(targets).isEmpty()) {
          alert.body().put("adjustment", Map.of("type", type, "date", dateKey,
              "label", WEATHER_RAIN.equals(type) ? "找室内替代" : "调整时间"));
        }
      }
    }

    SummaryResult summary = llmSummary
        ? summarizeViaLlm(trip, mode, day, schedule, weather, bookings, packing, documents,
            alerts, zone)
        : new SummaryResult(fallbackSummary(mode, day, schedule, alerts.size(), packing, zone), "fallback");

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
    body.put("summary", appendTodaySpending(summary.text(), budget));
    body.put("summarySource", summary.source());
    body.put("schedule", schedule);
    body.put("weather", weather);
    body.put("bookings", bookings);
    body.put("budget", budget);
    body.put("packing", packing);
    body.put("documents", documents);
    body.put("alerts", alerts.stream().map(Alert::body).toList());
    body.put("actions", buildActions(tripId, day, mode, bookings, dateKey));
    body.put("dataStatus", dataStatus);
    body.put("generatedAt", new Date());
    return body;
  }

  public record RouteAction(String type, String tripId, String date, String destination, List<String> placeIds) {}

  public RouteAction resolveRouteAction(String userId, String tripId, String type, String date) {
    if (tripId == null || tripId.isBlank()) {
      throw ApiException.badRequest("请从简报打开行程对应的助手");
    }
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    ZoneId zone = userPreferenceService.timeZone(userId);
    LocalDate today = LocalDate.now(zone);
    Trip trip = tripService.findLive(tripId);
    if (trip == null || !"today".equals(journey.phase(trip, today)) || !today.toString().equals(date)) {
      throw ApiException.conflict("提醒日期或行程状态已变更，请刷新今日简报");
    }
    TripDay day = journey.dayFor(tripId, "today", today);
    List<Map<String, Object>> schedule = buildSchedule(day);
    var candidates = switch (type) {
      case WEATHER_RAIN -> schedule.isEmpty() ? List.<Map<String, Object>>of()
          : outdoorPlaces(schedule, buildWeather(userId, tripId, date));
      case TIME_CONFLICT -> firstOverlap(schedule);
      default -> throw ApiException.badRequest("该提醒不支持一键调整");
    };
    var ids = actionPlaceIds(candidates);
    if (ids.isEmpty()) {
      throw ApiException.conflict("提醒已变化或相关安排不可编辑，请刷新简报或手动查看行程");
    }
    return new RouteAction(type, tripId, date, trip.getDestination(), ids);
  }

  private static List<String> actionPlaceIds(List<Map<String, Object>> places) {
    return places.stream().filter(place -> !Boolean.TRUE.equals(place.get("isCompleted")))
        .map(place -> (String) place.get("id")).toList();
  }

  private static List<Map<String, Object>> firstOverlap(List<Map<String, Object>> schedule) {
    Map<String, Object> latest = null;
    for (var place : schedule) {
      if (!(place.get("startTime") instanceof Date start)) break;
      Date latestEnd = latest == null ? null : (Date) latest.get("endTime");
      if (latestEnd != null && latestEnd.after(start)) return List.of(latest, place);
      if (place.get("endTime") instanceof Date end && (latestEnd == null || end.after(latestEnd))) latest = place;
    }
    return List.of();
  }

  private static List<Map<String, Object>> outdoorPlaces(List<Map<String, Object>> schedule, Map<String, Object> weather) {
    if (weather == null) return List.of();
    String condition = weatherCondition(weather);
    if (rainChance(weather) < RAIN_ALERT_THRESHOLD && !condition.contains("雨")
        && !condition.contains("雪") && !condition.contains("雷")) return List.of();
    return schedule.stream().filter(place -> POSSIBLY_OUTDOOR.contains(place.get("type")))
        .filter(place -> INDOOR_VENUES.stream().noneMatch(String.valueOf(place.get("name"))::contains))
        .toList();
  }

  private static String weatherCondition(Map<String, Object> weather) {
    return weather.get("condition") == null ? "" : weather.get("condition").toString();
  }

  private static int rainChance(Map<String, Object> weather) {
    return weather.get("rainChance") instanceof Number n ? n.intValue() : 0;
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
  private List<Map<String, Object>> buildBookings(String tripId, TripDay day, String dateKey, ZoneId zone) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Booking booking : bookingService.findBookingsByTrip(tripId)) {
      if (booking.getCancelledAt() != null) {
        continue;
      }
      boolean used = day != null && day.getId().equals(booking.getTripDayId());
      if (!used && booking.getStartTime() != null
          && localDateKey(booking.getStartTime(), zone).equals(dateKey)) {
        used = true;
      }
      if (!used && "hotel".equals(booking.getType()) && booking.getStartTime() != null
          && booking.getEndTime() != null) {
        String from = localDateKey(booking.getStartTime(), zone);
        String to = localDateKey(booking.getEndTime(), zone);
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
    // TR-02：按币种分组，不做隐式换算
    java.util.Map<String, BigDecimal> spentByCurrency = new java.util.LinkedHashMap<>();
    int todayCount = 0;
    if (!preTrip) {
      for (TripExpense expense : tripExpenseService.findExpenses(tripId)) {
        if (expense.getSpentAt() == null || !Fmt.dateKey(expense.getSpentAt()).equals(dateKey)) {
          continue;
        }
        if (expense.getAmount() != null) {
          String cur = TripExpenseService.normalizeCurrency(expense.getCurrency());
          spentByCurrency.merge(cur, expense.getAmount(), BigDecimal::add);
          todayCount++;
        }
      }
    }
    // 兼容单币种场景：todaySpent 取主币种，多币种时 todaySpent 为主币种金额、todaySpentByCurrency 给出全部
    String currency = spentByCurrency.isEmpty() ? "CNY" : spentByCurrency.keySet().iterator().next();
    BigDecimal todaySpent = spentByCurrency.getOrDefault(currency, BigDecimal.ZERO);

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
    body.put("budgetCurrency", budget.get("budgetCurrency"));
    body.put("foreignCurrencySpent", budget.get("foreignCurrencySpent"));
    body.put("foreignCurrencyEstimated", budget.get("foreignCurrencyEstimated"));
    body.put("totalSpent", budget.get("totalSpent"));
    body.put("totalEstimated", budget.get("totalEstimated"));
    body.put("totalRemaining", budget.get("totalRemaining"));
    body.put("totalWarning", Boolean.TRUE.equals(budget.get("totalWarning")));
    body.put("todaySpent", Fmt.money(todaySpent));
    body.put("todayCount", todayCount);
    body.put("currency", currency);
    // TR-02：分币种当日支出，避免混币虚假合计
    java.util.Map<String, String> spentByCurrencyFmt = new java.util.LinkedHashMap<>();
    spentByCurrency.forEach((cur, amt) -> spentByCurrencyFmt.put(cur, Fmt.money(amt)));
    body.put("todaySpentByCurrency", spentByCurrencyFmt);
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

    // 1. 类型只能提示可能受天气影响，地点是否为露天需由用户核对。
    List<String> outdoor = outdoorPlaces(schedule, weather).stream().map(place -> (String) place.get("name")).toList();
    if (!outdoor.isEmpty()) {
      int rain = rainChance(weather);
      String condition = weatherCondition(weather);
      alerts.add(alert(WEATHER_RAIN, "warning", "天气可能影响室外安排",
          "预报" + (condition.isEmpty() ? "降雨概率 " + rain + "%" : condition + "，降雨概率 " + rain + "%")
              + "，有 " + outdoor.size() + " 项安排可能受影响（如" + outdoor.get(0) + "）",
          "rainChance=" + rain + "，可能受天气影响的安排：" + String.join("、", outdoor),
          action("查看行程", itineraryUrl(tripId, day)), 3));
    }

    // 2. 时间冲突：按开始时间排序后，上一项结束时间晚于下一项开始时间
    var overlap = firstOverlap(schedule);
    if (!overlap.isEmpty()) {
      var first = overlap.get(0);
      var next = overlap.get(1);
      alerts.add(alert(TIME_CONFLICT, "warning", "今日时间安排有冲突",
          "「" + first.get("name") + "」结束时间晚于「" + next.get("name") + "」的开始时间",
          first.get("name") + " endTime(" + hmInZone((Date) first.get("endTime"), zone) + ") > "
              + next.get("name") + " startTime(" + hmInZone((Date) next.get("startTime"), zone) + ")",
          action("查看行程", itineraryUrl(tripId, day)), 3));
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
            "累计花费 " + budget.get("totalSpent") + " CNY 已超过总预算 "
                + budget.get("tripBudget") + " CNY",
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
            "总预算 " + budget.get("tripBudget") + " CNY，剩余 "
                + budget.get("totalRemaining") + " CNY",
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
      List<Map<String, Object>> bookings, Map<String, Object> packing,
      Map<String, Object> documents, List<Alert> alerts, ZoneId zone) {
    String template = fallbackSummary(mode, day, schedule, alerts.size(), packing, zone);
    if (!llm.isEnabled()) {
      return new SummaryResult(template, "fallback");
    }
    String facts = factText(trip, mode, day, schedule, weather, bookings, packing,
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
          + "4) 不暴露订单号、确认码、证件号或文件地址；"
          + "5) 不输出金额，系统会另行按币种展示；6) 直接输出摘要正文，不要标题、列表或 Markdown。";

  /** 给 LLM 的最小字段事实文本：不含订单号/确认码/证件内容。 */
  private String factText(Trip trip, String mode, TripDay day, List<Map<String, Object>> schedule,
      Map<String, Object> weather, List<Map<String, Object>> bookings,
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
      text.append("- [").append(alert.body().get("title")).append("]");
      if (!"budget_risk".equals(alert.body().get("type"))) {
        text.append(" ").append(alert.body().get("detail"));
      }
      text.append("\n");
    }
    return text.toString();
  }

  private String appendTodaySpending(String summary, Map<String, Object> budget) {
    if (budget == null || !(budget.get("todaySpentByCurrency") instanceof Map<?, ?> amounts)
        || amounts.isEmpty()) {
      return summary;
    }
    var spending = new java.util.StringJoiner("、", " 今日已记账：", "。");
    amounts.forEach((currency, amount) -> spending.add(amount + " " + currency));
    return summary + spending;
  }

  /** 模板降级摘要：LLM 未配置/失败时保证接口仍返回可读摘要（alertCount 为提醒条数）。 */
  static String fallbackSummary(String mode, TripDay day, List<Map<String, Object>> schedule,
      int alertCount, Map<String, Object> packing) {
    return fallbackSummary(mode, day, schedule, alertCount, packing, DEFAULT_ZONE);
  }

  static String fallbackSummary(String mode, TripDay day, List<Map<String, Object>> schedule,
      int alertCount, Map<String, Object> packing, ZoneId zone) {
    int total = schedule.size();
    int am = 0;
    int pm = 0;
    int tbd = 0;
    for (Map<String, Object> place : schedule) {
      if (!Boolean.TRUE.equals(place.get("timed")) || !(place.get("startTime") instanceof Date start)) {
        tbd++;
        continue;
      }
      if (LocalDateTime.ofInstant(start.toInstant(), zone).getHour() < 12) {
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
    } else if ("past".equals(mode) || "archived".equals(mode)) {
      summary.append("行程回顾：选定日期共有 ").append(total).append(" 项安排。");
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
      List<Map<String, Object>> bookings, String dateKey) {
    List<Map<String, Object>> actions = new ArrayList<>();
    if ("past".equals(mode) || "archived".equals(mode)) {
      return List.of(action("查看行程详情", "/trip-detail/?id=" + tripId),
          action("查看历史安排", itineraryUrl(tripId, day)));
    }
    actions.add(action("pre-trip".equals(mode) ? "查看第 1 天行程" : "查看今日行程",
        itineraryUrl(tripId, day) + ("today".equals(mode) ? "&date=" + dateKey : "")));
    actions.add(action("pre-trip".equals(mode) ? "查看路线" : "查看今日路线", mapUrl(tripId, day)));
    if (!bookings.isEmpty()) {
      actions.add(action("打开凭证", "/vouchers/?trip=" + tripId + ("today".equals(mode) ? "&date=" + dateKey : "")));
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

  private int countDays(String tripId) {
    Long count = tripDayMapper.selectCount(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId));
    return count == null ? 0 : count.intValue();
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

  private static String localDateKey(Date instant, ZoneId zone) {
    return instant.toInstant().atZone(zone).toLocalDate().toString();
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
