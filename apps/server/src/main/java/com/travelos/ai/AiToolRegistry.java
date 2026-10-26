package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.AiConversationMapper;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.service.AiPreferenceService;
import com.travelos.service.BookingService;
import com.travelos.service.PlacesService;
import com.travelos.service.TripDayService;
import com.travelos.service.TripMemberService;
import com.travelos.service.TripService;
import com.travelos.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具注册表：工具定义（function calling schema）、分发与全部工具实现。
 * 从 AiChatService 拆出，职责单一——「模型能调用的每一个工具」的定义与行为都在这里。
 * 所有工具以当前登录用户身份走现有 Service 层（鉴权/数据隔离/审计不绕过）；
 * 写入类工具（add_expense/add_booking/propose_itinerary_change）只落草稿消息，
 * 用户点「应用」后才由 ItineraryDraftEngine 写入业务表。
 */
@Component
public class AiToolRegistry {

  private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

  /** AI 可直接打开的系统页面目录（page key → [前端路由, 页面名称]），与 apps/web 目录一一对应。 */
  private static final Map<String, String[]> NAV_PAGES = Map.ofEntries(
      Map.entry("home", new String[]{"/home/", "首页总览"}),
      Map.entry("trips", new String[]{"/trips/", "我的行程"}),
      Map.entry("trip-form", new String[]{"/trip-form/", "新建行程"}),
      Map.entry("trip-detail", new String[]{"/trip-detail/", "行程详情"}),
      Map.entry("itinerary", new String[]{"/itinerary/", "行程规划"}),
      Map.entry("map", new String[]{"/map/", "行程地图"}),
      Map.entry("packing", new String[]{"/packing/", "打包清单"}),
      Map.entry("expenses", new String[]{"/expenses/", "记账与花费"}),
      Map.entry("bookings", new String[]{"/bookings/", "预订管理"}),
      Map.entry("documents", new String[]{"/documents/", "旅行证件"}),
      Map.entry("vouchers", new String[]{"/vouchers/", "票券凭证"}),
      Map.entry("photos", new String[]{"/photos/", "旅行相册"}),
      Map.entry("journals", new String[]{"/journals/", "旅行游记"}),
      Map.entry("stats", new String[]{"/stats/", "数据统计"}),
      Map.entry("notifications", new String[]{"/notifications/", "消息通知"}),
      Map.entry("account", new String[]{"/account/", "账户设置"}));

  /** 需要行程参数（?trip=）才能直达的行程子页；缺参会被前端跳回「我的行程」列表。 */
  private static final Set<String> TRIP_SCOPED_PAGES = Set.of(
      "itinerary", "expenses", "packing", "documents", "photos", "journals", "bookings", "vouchers");

  /** 预订类型与中文名（与 BookingService 的校验清单保持一致）。 */
  private static final Map<String, String> BOOKING_TYPE_LABELS = Map.of(
      "flight", "机票", "hotel", "酒店", "train", "火车票", "ticket", "门票", "other", "预订");

  /** 各预订类型的扩展字段（与预定管理页表单一致，应用时写入 extra）。 */
  private static final Map<String, List<String>> BOOKING_EXTRA_KEYS = Map.of(
      "flight", List.of("flightNo", "airports", "cabin"),
      "hotel", List.of("hotelName", "roomType", "cancelPolicy", "freeCancelDeadline"),
      "train", List.of("trainNo", "stations", "seat"),
      "ticket", List.of("itemName", "session"),
      "other", List.of("pickup"));

  private final AiConversationMapper conversationMapper;
  private final AiMessageMapper messageMapper;
  private final TripService tripService;
  private final TripDayService tripDayService;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;
  private final PlacesService placesService;
  private final BookingService bookingService;
  private final WeatherService weatherService;
  private final AiPreferenceService preferenceService;
  private final AiPromptBuilder promptBuilder;

  public AiToolRegistry(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
      TripService tripService, TripDayService tripDayService, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberService tripMemberService,
      PlacesService placesService, BookingService bookingService,
      WeatherService weatherService, AiPreferenceService preferenceService,
      AiPromptBuilder promptBuilder) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.tripService = tripService;
    this.tripDayService = tripDayService;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
    this.placesService = placesService;
    this.bookingService = bookingService;
    this.weatherService = weatherService;
    this.preferenceService = preferenceService;
    this.promptBuilder = promptBuilder;
  }

  // ==================== 工具定义 ====================

  public List<Map<String, Object>> toolDefinitions() {
    List<Map<String, Object>> tools = new ArrayList<>();
    tools.add(function("get_trip", "读取行程详情（名称、日期、预算、成员角色）",
        Map.of("tripId", stringProp("行程 ID"))));
    tools.add(function("list_trip_days", "读取行程的全部天数与每天的行程点",
        Map.of("tripId", stringProp("行程 ID"))));
    tools.add(function("list_trip_places", "读取某一天的全部行程点",
        Map.of("tripDayId", stringProp("行程天 ID"))));
    tools.add(function("search_places", "按关键词搜索地点（高德 POI），返回名称与地址建议",
        Map.of("keyword", stringProp("搜索关键词"), "city", stringProp("城市名，可空"))));
    tools.add(function("search_restaurants",
        "搜索餐厅/美食（高德 POI）。用于「饭店推荐」：返回菜系(type)、评分(rating)、人均消费(avgCost)、"
            + "营业时间(openTime)、地址、电话、坐标。结合行程地点、口味与预算给出 2-4 家建议",
        Map.of("keyword", stringProp("关键词，如「烤鸭」「火锅」「本地菜」"),
            "city", stringProp("城市名，可空（默认用行程目的地）"))));
    tools.add(function("search_attractions",
        "搜索景点（高德 POI）。用于查询「景点营业时间」与做时间规划：返回营业时间(openTime)、评分、"
            + "地址、坐标。营业时间必须采用返回结果",
        Map.of("keyword", stringProp("关键词，如「故宫」「博物馆」"),
            "city", stringProp("城市名，可空（默认用行程目的地）"))));
    tools.add(function("search_hotels",
        "搜索酒店/住宿（高德 POI）。前置条件（重要）：行程的每一天都必须已有景点/行程安排，才能推荐酒店；"
            + "若返回 blocked（行程有空白天或还没有行程），不要推荐任何酒店，按 hint 引导用户先确定每天的行程景点。"
            + "可用时返回评分(rating)、参考价格(avgCost)、地址、电话、坐标。"
            + "拿到结果后按系统提示词规则 9：结合当天行程直接选定最合适的一家并立即调用 propose_itinerary_change（type=hotel）生成写入草稿，"
            + "回复正文给出选定酒店的评分/参考价/地址/电话介绍、选择理由与其他候选清单，可附「换住：××」选项按钮",
        Map.of("keyword", stringProp("关键词，如「酒店」「民宿」「青旅」「近地铁」"),
            "city", stringProp("城市名，可空（默认用行程目的地）"))));
    tools.add(function("get_weather",
        "查询行程目的地的每日天气预报（Open-Meteo，最多未来 15 天）：每天返回天气现象(condition)、"
            + "最高/最低温度(tempHigh/tempLow ℃)、降雨概率(rainChance %)。"
            + "规划行程、推荐景点/餐厅/酒店前先调用，把户外项目排在晴好天、雨雪天安排室内并提醒用户；"
            + "仅覆盖未来日期，过去的日期与超出范围的日期无数据。对话未关联行程时默认查最近行程",
        Map.of("tripId", stringProp("行程 ID，可空（默认当前对话关联行程或最近行程）"))));
    tools.add(function("remember_preference",
        "记住用户的一条持久旅行偏好（口味忌口/玩法喜好/预算档/住宿档次/节奏快慢/同行人群等），"
            + "保存后所有推荐与规划都会自动遵守。仅当用户表达的是持久偏好时调用（如「我不吃辣」「带孩子出行」「住宿控制在300/晚」）；"
            + "单次性、临时性的要求（如「这次想去海边」）不要保存，直接在本次规划遵守即可。content 用一句话短句",
        Map.of("content", stringProp("偏好内容，一句话短句，如「不吃辣」「喜欢博物馆」「住宿 300 元/晚以内」"),
            "category", stringProp("分类：food/activity/budget/accommodation/pace/companion/general，可空"))));
    tools.add(function("forget_preference",
        "忘记用户的一条旅行偏好：用户要求删除/忘记某条偏好时调用，target 传偏好内容关键词或其 ID",
        Map.of("target", stringProp("要忘记的偏好内容关键词或 ID"))));
    tools.add(function("list_preferences",
        "读取当前用户已保存的全部旅行偏好（用户问「我的偏好/你记住了什么」时调用）",
        Map.of()));
    tools.add(function("get_bookings", "读取行程的全部预订（机票/酒店/火车票/门票）",
        Map.of("tripId", stringProp("行程 ID"))));
    tools.add(function("add_booking",
        "预订登记草稿：用户要预订/登记/记录一条预订（机票、酒店、火车票、门票等），或酒店推荐后用户选定某家要求订下/记下时调用，"
            + "生成待确认草稿而非直接写入；点「应用」后写入预定管理。必填 type 与 title；"
            + "时间用「YYYY-MM-DD HH:mm」或「YYYY-MM-DD」；手边没有的信息可以留空。"
            + "各类型扩展字段：机票 flightNo/airports/cabin，酒店 hotelName/roomType/cancelPolicy，"
            + "火车 trainNo/stations/seat，门票 itemName/session，其他 pickup。"
            + "对话未关联行程时（全局对话），默认登记到最近行程，并在回复中说明",
        Map.ofEntries(
            Map.entry("type", stringProp("预订类型：flight/hotel/train/ticket/other")),
            Map.entry("title", stringProp("标题，如酒店名、航班号、车次")),
            Map.entry("startTime", stringProp("开始时间 YYYY-MM-DD HH:mm（可空）")),
            Map.entry("endTime", stringProp("结束时间 YYYY-MM-DD HH:mm（可空，如酒店离店）")),
            Map.entry("amount", stringProp("金额（人民币数字字符串，可空）")),
            Map.entry("orderNo", stringProp("订单号（可空）")),
            Map.entry("confirmCode", stringProp("确认码（可空）")),
            Map.entry("dayIndex", stringProp("关联行程第几天（数字，可空）")),
            Map.entry("flightNo", stringProp("航班号（机票，可空）")),
            Map.entry("airports", stringProp("起降机场（机票，如 大兴T2 → 虹桥T2，可空）")),
            Map.entry("cabin", stringProp("舱位（机票，可空）")),
            Map.entry("hotelName", stringProp("酒店名称（酒店，可空）")),
            Map.entry("roomType", stringProp("房型（酒店，可空）")),
            Map.entry("cancelPolicy", stringProp("取消政策（酒店，可空）")),
            Map.entry("trainNo", stringProp("车次（火车，可空）")),
            Map.entry("stations", stringProp("发到站（火车，如 北京南 → 上海虹桥，可空）")),
            Map.entry("seat", stringProp("座位（火车，可空）")),
            Map.entry("itemName", stringProp("项目名称（门票，可空）")),
            Map.entry("session", stringProp("场次（门票，可空）")),
            Map.entry("pickup", stringProp("取还/详情（其他，可空）")))));
    tools.add(function("ask_options",
        "选择按钮：需要用户从有限候选中做选择时调用（如订哪一天的哪一项、选哪家酒店、选类型、要不要登记），"
            + "界面会把 options 渲染成可点击按钮，用户点一下即作为消息发回给你。"
            + "选项必须基于工具返回的真实数据、简短具体（如「第 2 天 · 乌兰布统影视基地」「订这家：××酒店」「暂不登记」），2-6 条；"
            + "酒店/住宿候选必须用对象 {\"label\":\"订这家：××酒店\",\"description\":\"评分 · 参考价 · 地址 · 电话\"} 形式携带介绍"
            + "（介绍取自 search_hotels 的 rating/avgCost/address/tel 字段，缺项省略），方便用户比较后点选；其他场景直接传字符串；"
            + "调用后最终回复用一句话提问即可，不要重复罗列选项文字",
        Map.ofEntries(
            Map.entry("question", stringProp("引导提问，如「想订哪一天的哪一项？」")),
            Map.entry("options", Map.of(
                "type", "array",
                "description", "可点击选项（2-6 条）：字符串，或酒店候选的对象 "
                    + "{\"label\":\"订这家：××酒店\",\"description\":\"4.6 分 · ¥320 · 地址 ×× · 电话 ××\"}",
                "items", Map.of("type", "string"))))));
    tools.add(function("add_expense",
        "记账草稿：识别到用户记账意图时调用（如「午饭两人136元」），生成待确认的记账草稿而非直接写入。"
            + "对话未关联行程时（全局对话），默认记入最近行程，并在回复中说明",
        Map.of("amount", stringProp("金额（元，数字字符串）"),
            "category", stringProp("类别：transport/hotel/food/ticket/shopping/other 之一"),
            "title", stringProp("标题，可空"), "notes", stringProp("备注，可空"))));
    tools.add(function("propose_itinerary_change",
        "行程变更草稿：新增/修改/删除行程点时调用，生成待确认的变更预览而非直接写入。add 需要 dayIndex(第几天,从1开始) 与 name；remove/update 需要 placeId（可先用 list_trip_days 查询）。"
            + "changes 数组可一次包含多天的变更项（整趟规划时一次调用覆盖全部天，每项各带 dayIndex）；"
            + "「第二天」「第 N 天」一律按行程的第 N 天理解（结合上下文中的各天日期），不要按日历的明天理解；"
            + "对话未关联行程时（全局对话），草稿默认作用于最近行程（见系统提示词中的「最近行程」）。"
            + "规划某天行程时，请为每个点给出合理的 startTime/endTime（含游玩时长与交通衔接）、estimatedCost（票价）与 openTime（营业时间，来自 search_attractions），并携带 latitude/longitude（来自搜索结果）以便地图定位。"
            + "replaceDay：重新规划某一天（整天的行程安排）时传 true，应用时会先清空当天原有安排再写入（避免同一天多次应用后旧行程与新行程叠加）；"
            + "只是往某天追加单个地点时传 false",
        Map.of("summary", stringProp("变更摘要，如「第二天：故宫→午餐→天安门」"),
            "replaceDay", Map.of(
                "type", "boolean",
                "description", "是否覆盖当天原有安排：整天重新规划传 true；仅追加单个地点传 false。同一天给出 2 个及以上 add 时默认按 true 处理"),
            "changes", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.ofEntries(
                        Map.entry("action", stringProp("add / remove / update")),
                        Map.entry("dayIndex", stringProp("第几天（add 必填，从 1 开始）")),
                        Map.entry("placeId", stringProp("行程点 ID（remove/update 必填）")),
                        Map.entry("name", stringProp("地点名称")),
                        Map.entry("type", stringProp("attraction/hotel/restaurant/transport/shopping/custom")),
                        Map.entry("address", stringProp("地址")),
                        Map.entry("latitude", stringProp("纬度（来自搜索工具结果，可空）")),
                        Map.entry("longitude", stringProp("经度（来自搜索工具结果，可空）")),
                        Map.entry("startTime", stringProp("开始时刻 HH:mm（24 小时制，时间规划用）")),
                        Map.entry("endTime", stringProp("结束时刻 HH:mm（24 小时制，时间规划用）")),
                        Map.entry("estimatedCost", stringProp("票价/费用（人民币数字字符串，免费填 0，可空）")),
                        Map.entry("openTime", stringProp("营业时间文本（来自 search_attractions/search_restaurants，将写入备注）")),
                        Map.entry("notes", stringProp("备注，可空"))))))));
    tools.add(function("open_page",
        "打开系统页面：用户想查看/前往某个页面时调用（如「打开预定管理」→ bookings、「打开行程规划」→ itinerary、「去记账」→ expenses）。page 可选值："
            + "home 首页总览、trips 我的行程、trip-form 新建行程、trip-detail 行程详情、"
            + "itinerary 行程规划、map 行程地图、packing 打包清单、expenses 记账与花费、"
            + "bookings 预订管理（预定/预订机票、酒店、火车、门票）、documents 旅行证件、vouchers 票券凭证、photos 旅行相册、"
            + "journals 旅行游记、stats 数据统计、notifications 消息通知、account 账户设置。"
            + "行程相关页面会自动关联当前/最近行程，无需传 tripId",
        Map.of("page", stringProp("页面标识（见描述中的可选值）"),
            "tripId", stringProp("行程 ID（一般不用传）：仅当用户明确指定了另一个行程 ID 时才传"))));
    return tools;
  }

  private Map<String, Object> function(String name, String description,
      Map<String, Object> parameters) {
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", name);
    function.put("description", description);
    function.put("parameters", Map.of(
        "type", "object",
        "properties", parameters));
    return Map.of("type", "function", "function", function);
  }

  private Map<String, Object> stringProp(String description) {
    return Map.of("type", "string", "description", description);
  }

  // ==================== 工具执行 ====================

  /** 执行单个工具调用（全部以当前用户身份），返回 JSON 文本结果。 */
  public String executeTool(Map<String, Object> call, AiConversation conversation, String userId) {
    String name = String.valueOf(call.get("name"));
    Map<String, Object> args = LlmGatewayService.parseArguments(
        String.valueOf(call.getOrDefault("arguments", "{}")));
    try {
      String result = switch (name) {
        case "get_trip" -> JsonUtil.write(
            tripService.findOne(requireArg(args, "tripId"), userId));
        case "list_trip_days" -> JsonUtil.write(
            tripDayService.findAll(requireArg(args, "tripId"), userId));
        case "list_trip_places" -> JsonUtil.write(listPlacesOfDay(args, userId));
        case "search_places" -> JsonUtil.write(placesService.getSuggestions(
            requireArg(args, "keyword"),
            args.get("city") == null ? "" : args.get("city").toString(), 8));
        case "search_restaurants" -> JsonUtil.write(placesService.searchPois(
            requireArg(args, "keyword"), resolveCity(args, conversation), "restaurant", 6));
        case "search_attractions" -> JsonUtil.write(placesService.searchPois(
            requireArg(args, "keyword"), resolveCity(args, conversation), "attraction", 6));
        case "search_hotels" -> searchHotels(args, conversation, userId);
        case "get_weather" -> getWeather(args, conversation, userId);
        case "remember_preference" -> rememberPreference(args, userId);
        case "forget_preference" -> forgetPreference(args, userId);
        case "list_preferences" -> listPreferences(userId);
        case "get_bookings" -> JsonUtil.write(
            bookingService.findAll(requireArg(args, "tripId"), userId));
        case "add_expense" -> createExpenseDraft(args, conversation, userId);
        case "add_booking" -> createBookingDraft(args, conversation, userId);
        case "ask_options" -> askOptions(args);
        case "propose_itinerary_change" -> createItineraryDraft(args, conversation, userId);
        case "open_page" -> openPage(args, conversation, userId);
        default -> "{\"error\":\"未知工具 " + name + "\"}";
      };
      if (result != null && result.startsWith("{\"error\":")) {
        // 工具参数错误会让模型「嘴上说草稿已生成、实际没有卡片」，这里留痕便于排查
        log.warn("AI tool {} failed: {}", name,
            result.length() > 300 ? result.substring(0, 300) : result);
      }
      return result;
    } catch (ApiException ex) {
      log.warn("AI tool {} rejected: {}", name, ex.getMessage());
      return "{\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
    }
  }

  // ==================== 检索类工具 ====================

  /**
   * 酒店检索前置校验：行程的每一天都必须已有景点/行程安排，才允许检索推荐酒店。
   * 未满足时返回 blocked 指引（不下发任何酒店数据），由模型转述为「先确定每天行程」的引导话术。
   */
  private String searchHotels(Map<String, Object> args, AiConversation conversation, String userId) {
    String tripId = conversation.getTripId();
    if (tripId == null) {
      tripId = promptBuilder.resolveDefaultTripId(userId);
    }
    if (tripId == null) {
      return blockedHotelResult("no_trip", List.of(),
          "用户还没有行程，不能推荐酒店。请先引导用户创建行程并确定每天要去的景点/行程，再推荐酒店；现在不要罗列或推荐任何酒店。");
    }
    Map<String, Object> daysRes = tripDayService.findAll(tripId, userId);
    List<?> dayList = daysRes.get("days") instanceof List<?> list ? list : List.of();
    if (dayList.isEmpty()) {
      return blockedHotelResult("no_days", List.of(),
          "该行程还没有任何天安排，不能推荐酒店。请先引导用户确定每天的景点/行程，再推荐酒店；现在不要罗列或推荐任何酒店。");
    }
    List<Integer> emptyDays = new ArrayList<>();
    for (Object item : dayList) {
      @SuppressWarnings("unchecked")
      Map<String, Object> day = (Map<String, Object>) item;
      if (!(day.get("places") instanceof List<?> places) || places.isEmpty()) {
        emptyDays.add(day.get("dayIndex") instanceof Number number ? number.intValue() : 0);
      }
    }
    if (!emptyDays.isEmpty()) {
      List<String> labels = new ArrayList<>();
      for (int dayNumber : emptyDays) {
        if (dayNumber > 0) {
          labels.add(String.valueOf(dayNumber));
        }
      }
      return blockedHotelResult("empty_days", emptyDays,
          "行程第 " + String.join("、", labels.isEmpty() ? List.of("几天") : labels)
              + " 天还没有任何景点/行程安排。酒店需要结合每天的行程选位置，请先引导用户确定这些天的景点/行程（可主动提议帮忙规划），确认后再推荐酒店；现在不要罗列或推荐任何酒店。");
    }
    return JsonUtil.write(placesService.searchPois(
        requireArg(args, "keyword"), resolveCity(args, conversation), "hotel", 6));
  }

  private String blockedHotelResult(String reason, List<Integer> emptyDays, String hint) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("blocked", true);
    result.put("reason", reason);
    if (!emptyDays.isEmpty()) {
      result.put("emptyDays", emptyDays);
    }
    result.put("hint", hint);
    return JsonUtil.write(result);
  }

  /**
   * 天气查询（get_weather 工具）：返回行程目的地的每日预报。
   * 行程缺省回落：显式参数 > 对话关联行程 > 最近行程；失败时返回 error 文本，不阻断对话。
   */
  private String getWeather(Map<String, Object> args, AiConversation conversation, String userId) {
    String tripId = args.get("tripId") == null ? "" : args.get("tripId").toString().trim();
    if (tripId.isEmpty()) {
      tripId = conversation.getTripId() != null ? conversation.getTripId()
          : (promptBuilder.resolveDefaultTripId(userId) == null ? ""
              : promptBuilder.resolveDefaultTripId(userId));
    }
    if (tripId.isEmpty()) {
      return "{\"error\":\"当前没有可用行程，无法查询天气。请先创建行程并在行程中设置目的地，再查询天气预报。\"}";
    }
    try {
      return JsonUtil.write(weatherService.getTripWeather(tripId, userId));
    } catch (Exception ex) {
      log.warn("get_weather 查询失败 tripId={}", tripId, ex);
      return "{\"error\":\"天气查询暂时不可用，请稍后再试；规划时先不要引用天气信息\"}";
    }
  }

  // ==================== 偏好工具 ====================

  /** 记住偏好（remember_preference 工具）：保存一条持久旅行偏好。 */
  private String rememberPreference(Map<String, Object> args, String userId) {
    Object content = args.get("content");
    if (content == null || content.toString().isBlank()) {
      return "{\"error\":\"content 不能为空，请传用户偏好的自然语言短句（如「不吃辣」）\"}";
    }
    try {
      return JsonUtil.write(preferenceService.remember(userId,
          content.toString(), stringValue(args.get("category"))));
    } catch (ApiException ex) {
      return "{\"error\":\"" + ex.getMessage() + "\"}";
    } catch (Exception ex) {
      log.warn("remember_preference 失败 userId={}", userId, ex);
      return "{\"error\":\"偏好保存失败，请稍后再试\"}";
    }
  }

  /** 忘记偏好（forget_preference 工具）：按内容关键词或 ID 移除。 */
  private String forgetPreference(Map<String, Object> args, String userId) {
    Object target = args.get("target");
    if (target == null || target.toString().isBlank()) {
      return "{\"error\":\"target 不能为空，传要忘记的偏好内容关键词或 ID\"}";
    }
    try {
      return JsonUtil.write(preferenceService.forget(userId, target.toString()));
    } catch (ApiException ex) {
      return "{\"error\":\"" + ex.getMessage() + "\"}";
    } catch (Exception ex) {
      log.warn("forget_preference 失败 userId={}", userId, ex);
      return "{\"error\":\"偏好移除失败，请稍后再试\"}";
    }
  }

  /** 偏好列表（list_preferences 工具）：用户询问「我的偏好」时返回。 */
  private String listPreferences(String userId) {
    try {
      return JsonUtil.write(Map.of("preferences", preferenceService.list(userId)));
    } catch (Exception ex) {
      log.warn("list_preferences 失败 userId={}", userId, ex);
      return "{\"error\":\"偏好读取失败，请稍后再试\"}";
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : value.toString();
  }

  /** 城市提示：优先用工具参数 city，缺省回落到当前行程的目的地。 */
  @SuppressWarnings("unchecked")
  private String resolveCity(Map<String, Object> args, AiConversation conversation) {
    Object city = args.get("city");
    if (city != null && !city.toString().isBlank()) {
      return city.toString().trim();
    }
    if (conversation.getTripId() != null) {
      try {
        Map<String, Object> res = tripService.findOne(conversation.getTripId(),
            conversation.getUserId());
        Map<String, Object> trip = (Map<String, Object>) res.get("trip");
        if (trip != null && trip.get("destination") != null) {
          return trip.get("destination").toString();
        }
      } catch (Exception ignore) {
        // 读行程失败仅影响城市过滤，不影响搜索
      }
    }
    return "";
  }

  private String listPlacesOfDay(Map<String, Object> args, String userId) {
    String tripDayId = requireArg(args, "tripDayId");
    TripDay day = tripDayMapper.selectById(tripDayId);
    if (day == null) {
      throw ApiException.notFound("行程天不存在");
    }
    tripMemberService.requireTripMember(day.getTripId(), userId);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (TripPlace place : tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripDayId, tripDayId)
        .orderByAsc(TripPlace::getSortOrder))) {
      rows.add(tripDayService.toPlaceResponse(place));
    }
    return JsonUtil.write(Map.of("places", rows));
  }

  // ==================== 草稿类工具 ====================

  /** 记账意图 → 草稿（不直接写库，用户确认后才走 TripExpenseService.create）。 */
  private String createExpenseDraft(Map<String, Object> args, AiConversation conversation,
      String userId) {
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : (args.get("tripId") == null ? null : args.get("tripId").toString());
    if (tripId == null || tripId.isBlank()) {
      // 全局对话：默认落到最近行程（与系统提示词中「最近行程」上下文一致）
      tripId = promptBuilder.resolveDefaultTripId(userId);
    }
    if (tripId == null || tripId.isBlank()) {
      return "{\"error\":\"当前对话未关联行程，也没有可用的最近行程，请先创建行程或在具体行程中打开 AI 助手\"}";
    }
    tripMemberService.requireTripMember(tripId, userId);
    String amount = String.valueOf(args.get("amount"));
    String category = normalizeCategory(args.get("category"));
    String title = args.get("title") == null ? "" : args.get("title").toString();
    Map<String, Object> expense = new LinkedHashMap<>();
    expense.put("amount", amount);
    expense.put("category", category);
    if (!title.isBlank()) {
      expense.put("title", title);
    }
    if (args.get("notes") != null) {
      expense.put("notes", args.get("notes").toString());
    }
    String summary = "将为当前行程记录：" + categoryLabel(category) + " " + amount + " 元，"
        + "付款人 " + "当前用户，均分给全部行程成员（应用时生效）。";

    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "expense");
    draft.put("tripId", tripId);
    draft.put("summary", summary);
    draft.put("expense", expense);

    AiMessage draftMessage = insertMessage(conversation.getId(), userId, "assistant",
        summary, JsonUtil.write(draft), "pending");
    conversation.setUpdatedAt(new Date());
    conversationMapper.updateById(conversation);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("draftCreated", true);
    result.put("messageId", draftMessage.getId());
    result.put("summary", summary);
    result.put("hint", "草稿已创建，请告知用户在「变更预览卡片」上点击「应用」完成记账。");
    return JsonUtil.write(result);
  }

  /** 预订登记 → 草稿（AI 不直接写预订，应用时才走 BookingService 写入预定管理）。 */
  private String createBookingDraft(Map<String, Object> args, AiConversation conversation,
      String userId) {
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : (args.get("tripId") == null ? null : args.get("tripId").toString());
    if (tripId == null || tripId.isBlank()) {
      // 全局对话：默认落到最近行程（与系统提示词中「最近行程」上下文一致）
      tripId = promptBuilder.resolveDefaultTripId(userId);
    }
    if (tripId == null || tripId.isBlank()) {
      return "{\"error\":\"当前对话未关联行程，也没有可用的最近行程，请先创建行程或在具体行程中打开 AI 助手\"}";
    }
    tripMemberService.requireTripMember(tripId, userId);
    String type = args.get("type") == null ? "" : args.get("type").toString().trim();
    if (!BOOKING_TYPE_LABELS.containsKey(type)) {
      return "{\"error\":\"type 必须是 flight/hotel/train/ticket/other 之一\"}";
    }
    String title = args.get("title") == null ? "" : args.get("title").toString().trim();
    if (title.isBlank()) {
      return "{\"error\":\"title（预订标题，如酒店名/航班号/车次）不能为空\"}";
    }
    Map<String, Object> booking = new LinkedHashMap<>();
    booking.put("type", type);
    booking.put("title", title);
    for (String key : List.of("startTime", "endTime", "amount", "orderNo", "confirmCode",
        "dayIndex")) {
      if (args.get(key) != null && !String.valueOf(args.get(key)).isBlank()) {
        booking.put(key, args.get(key));
      }
    }
    Map<String, Object> extra = new LinkedHashMap<>();
    for (String key : BOOKING_EXTRA_KEYS.getOrDefault(type, List.of())) {
      if (args.get(key) != null && !String.valueOf(args.get(key)).isBlank()) {
        extra.put(key, args.get(key).toString());
      }
    }
    if (!extra.isEmpty()) {
      booking.put("extra", extra);
    }

    String summary = bookingSummary(booking);
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "booking");
    draft.put("tripId", tripId);
    draft.put("summary", summary);
    draft.put("booking", booking);

    AiMessage draftMessage = insertMessage(conversation.getId(), userId, "assistant",
        summary, JsonUtil.write(draft), "pending");
    conversation.setUpdatedAt(new Date());
    conversationMapper.updateById(conversation);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("draftCreated", true);
    result.put("messageId", draftMessage.getId());
    result.put("summary", summary);
    result.put("hint", "预订草稿已创建，请告知用户在「变更预览卡片」上点击「应用」写入预定管理。");
    return JsonUtil.write(result);
  }

  private String bookingSummary(Map<String, Object> booking) {
    String label = BOOKING_TYPE_LABELS.getOrDefault(String.valueOf(booking.get("type")), "预订");
    StringBuilder summary = new StringBuilder("将为当前行程登记").append(label).append("「")
        .append(booking.get("title")).append("」");
    if (booking.get("startTime") != null) {
      summary.append("，").append(booking.get("startTime"));
      if (booking.get("endTime") != null) {
        summary.append(" ~ ").append(booking.get("endTime"));
      }
    }
    if (booking.get("amount") != null) {
      summary.append("，金额 ").append(booking.get("amount")).append(" 元");
    }
    if (booking.get("dayIndex") != null) {
      summary.append("，关联第 ").append(booking.get("dayIndex")).append(" 天");
    }
    return summary.append("（应用后写入预定管理）。").toString();
  }

  /** 行程变更 → 草稿（AI 不直接写行程，应用时才走 TripPlaceService）。 */
  private String createItineraryDraft(Map<String, Object> args, AiConversation conversation,
      String userId) {
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : (args.get("tripId") == null ? null : args.get("tripId").toString());
    if (tripId == null || tripId.isBlank()) {
      // 全局对话：默认落到最近行程（与系统提示词中「最近行程」上下文一致）
      tripId = promptBuilder.resolveDefaultTripId(userId);
    }
    if (tripId == null || tripId.isBlank()) {
      return "{\"error\":\"当前对话未关联行程，也没有可用的最近行程，请先创建行程或在具体行程中打开 AI 助手\"}";
    }
    tripMemberService.requireTripMember(tripId, userId);
    if (!(args.get("changes") instanceof List<?> changes) || changes.isEmpty()) {
      return "{\"error\":\"changes 不能为空，请按 [{action:'add',dayIndex:2,name:'...',type:'attraction'}] 提供\"}";
    }
    String summary = args.get("summary") == null ? "行程变更草稿" : args.get("summary").toString();

    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "itinerary_change");
    draft.put("tripId", tripId);
    draft.put("summary", summary);
    draft.put("changes", changes);
    if (args.get("replaceDay") != null) {
      draft.put("replaceDay", Boolean.parseBoolean(args.get("replaceDay").toString()));
    }

    AiMessage draftMessage = insertMessage(conversation.getId(), userId, "assistant",
        summary, JsonUtil.write(draft), "pending");
    conversation.setUpdatedAt(new Date());
    conversationMapper.updateById(conversation);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("draftCreated", true);
    result.put("messageId", draftMessage.getId());
    result.put("summary", summary);
    result.put("hint", "草稿已创建，请告知用户预览变更并点击「应用」或「放弃」。");
    return JsonUtil.write(result);
  }

  private String normalizeCategory(Object raw) {
    String value = raw == null ? "" : raw.toString().toLowerCase();
    // lodging 是设计文档口径，映射到现有 hotel 类别
    if ("lodging".equals(value)) {
      return "hotel";
    }
    return List.of("transport", "hotel", "food", "ticket", "shopping", "other").contains(value)
        ? value : "other";
  }

  private String categoryLabel(String category) {
    return switch (category) {
      case "transport" -> "交通";
      case "hotel" -> "住宿";
      case "food" -> "餐饮";
      case "ticket" -> "门票";
      case "shopping" -> "购物";
      default -> "其他";
    };
  }

  // ==================== 页面导航 / 选项按钮 ====================

  /**
   * open_page 工具：识别用户「打开/查看某页面」的意图，解析出前端路由。
   * 跳转动作本身由前端完成 —— 导航信息挂在最终助手消息的 navigation 字段上随响应下发。
   * 行程子页（预定管理/行程规划/记账等）必须携带 ?trip=，否则页面缺参会被跳回「我的行程」列表
   * （历史问题：用户说「打开预定管理」最终只落到我的旅行列表）。行程参数解析顺序：
   * 工具显式传参 > 会话绑定行程 > 全局对话的最近已规划行程。
   */
  private String openPage(Map<String, Object> args, AiConversation conversation, String userId) {
    String page = String.valueOf(args.get("page")).trim().toLowerCase();
    String[] meta = NAV_PAGES.get(page);
    if (meta == null) {
      return "{\"error\":\"未知页面，page 必须是以下之一：" + String.join(" / ", NAV_PAGES.keySet()) + "\"}";
    }
    String pageKey = page;
    String label = meta[1];
    String url = meta[0];
    String hint = "页面跳转已随回复下发给前端，请在回复中用一句话自然告知用户即将打开的页面。";

    String tripId = args.get("tripId") == null ? "" : args.get("tripId").toString().trim();
    if (tripId.isBlank() && conversation.getTripId() != null) {
      tripId = conversation.getTripId();
    }
    if (tripId.isBlank()) {
      tripId = promptBuilder.resolveDefaultTripId(userId);
    }
    if (tripId == null) {
      tripId = "";
    }

    if ("trip-detail".equals(page)) {
      if (tripId.isBlank()) {
        return "{\"error\":\"打开行程详情需要行程 ID，请改用 trips（我的行程列表）页面\"}";
      }
      url = url + "?id=" + tripId;
    } else if (TRIP_SCOPED_PAGES.contains(page)) {
      if (tripId.isBlank()) {
        // 用户还没有任何行程：退化为行程列表页，避免目标页缺参被跳回列表、看起来「没反应」
        pageKey = "trips";
        label = NAV_PAGES.get("trips")[1];
        url = NAV_PAGES.get("trips")[0];
        hint = "用户还没有行程，已改为打开行程列表；请提示用户先创建或进入一个行程后再查看该页面。";
      } else {
        url = url + "?trip=" + tripId;
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("opened", true);
    result.put("page", pageKey);
    result.put("label", label);
    result.put("url", url);
    result.put("hint", hint);
    return JsonUtil.write(result);
  }

  /** 从 open_page 工具结果中提取导航信息（成功才返回 navigate JSON，否则 null）。 */
  public String extractNavigation(String toolResult) {
    try {
      Map<String, Object> map = JsonUtil.readObject(toolResult);
      if (Boolean.TRUE.equals(map.get("opened")) && map.get("url") != null) {
        Map<String, Object> nav = new LinkedHashMap<>();
        nav.put("type", "navigate");
        nav.put("page", map.get("page"));
        nav.put("label", map.get("label"));
        nav.put("url", map.get("url"));
        return JsonUtil.write(nav);
      }
    } catch (Exception ignore) {
      // 工具结果异常时忽略导航，不影响回复本身
    }
    return null;
  }

  /** 从 ask_options 工具结果中提取选择按钮（成功才返回 options JSON，否则 null）。 */
  public String extractOptions(String toolResult) {
    try {
      Map<String, Object> map = JsonUtil.readObject(toolResult);
      if (Boolean.TRUE.equals(map.get("shown")) && map.get("options") instanceof List<?> list
          && !list.isEmpty()) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "options");
        if (map.get("question") != null) {
          options.put("question", map.get("question"));
        }
        options.put("options", list);
        return JsonUtil.write(options);
      }
    } catch (Exception ignore) {
      // 工具结果异常时忽略选项，不影响回复本身
    }
    return null;
  }

  /** 合并导航与选项按钮为随最终回复下发的元数据（都为 null 时不落库）。 */
  public String buildReplyMeta(String navigation, String options) {
    if (navigation == null && options == null) {
      return null;
    }
    if (navigation == null) {
      return options;
    }
    if (options == null) {
      return navigation;
    }
    try {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("type", "meta");
      meta.put("navigation", JsonUtil.readObject(navigation));
      meta.put("options", JsonUtil.readObject(options));
      return JsonUtil.write(meta);
    } catch (Exception ignore) {
      return navigation;
    }
  }

  /** ask_options 工具：校验选项并回传（随最终回复下发给前端渲染可点击按钮）。 */
  private String askOptions(Map<String, Object> args) {
    List<Object> options = new ArrayList<>();
    Object raw = args.get("options");
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        addOption(options, item);
      }
    } else if (raw instanceof String text) {
      // 模型偶尔把数组塞成字符串：按常见分隔符拆分
      for (String part : text.split("[、，,|;；\\n]")) {
        addOption(options, part);
      }
    }
    if (options.isEmpty()) {
      log.warn("ask_options 参数不可用: {}", args);
      return "{\"error\":\"options 不能为空，请把选项作为 JSON 字符串数组传入（如 [\\\"选项A\\\",\\\"选项B\\\"]）\"}";
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("shown", true);
    if (args.get("question") != null && !args.get("question").toString().isBlank()) {
      result.put("question", args.get("question").toString());
    }
    result.put("options", options);
    result.put("hint", "选项按钮已随回复下发给用户，点击即等价于发送该选项文本；不要再重复调用本工具，"
        + "直接给出一句最终提问即可，也不要再罗列选项文字。");
    return JsonUtil.write(result);
  }

  /**
   * 单个选项归一化：兼容字符串与 {label/text/value/name} 对象，对象可带 description 介绍。
   * 去重复（按展示文字）、限长、限量；有介绍时才输出 {label, description} 对象。
   */
  private void addOption(List<Object> options, Object item) {
    if (item == null || options.size() >= 6) {
      return;
    }
    String label;
    String description = null;
    if (item instanceof Map<?, ?> map) {
      Object value = firstNonNull(map.get("label"), map.get("text"), map.get("value"),
          map.get("name"));
      label = value == null ? "" : value.toString();
      Object detail = firstNonNull(map.get("description"), map.get("detail"), map.get("desc"),
          map.get("intro"));
      if (detail != null) {
        description = detail.toString().trim();
        if (description.length() > 80) {
          description = description.substring(0, 80);
        }
        if (description.isBlank()) {
          description = null;
        }
      }
    } else {
      label = item.toString();
    }
    label = label.trim();
    if (label.length() > 30) {
      label = label.substring(0, 30);
    }
    if (label.isBlank()) {
      return;
    }
    for (Object existing : options) {
      String existingLabel = existing instanceof Map<?, ?> map
          ? String.valueOf(map.get("label")) : String.valueOf(existing);
      if (label.equals(existingLabel)) {
        return;
      }
    }
    if (description == null) {
      options.add(label);
    } else {
      Map<String, Object> option = new LinkedHashMap<>();
      option.put("label", label);
      option.put("description", description);
      options.add(option);
    }
  }

  private Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null && !value.toString().isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String requireArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null || value.toString().isBlank()) {
      throw ApiException.badRequest("缺少参数 " + key);
    }
    return value.toString();
  }

  // ==================== 酒店候选按钮补全（供回复兜底调用） ====================

  /**
   * 酒店候选按钮补全：把 search_hotels 的评分/参考价/地址/电话写成按钮介绍。
   * 模型已给按钮时按名称匹配补介绍（匹配不上则保持原样、返回 null）；模型漏给按钮时
   * 直接用检索结果生成候选。检索不可用或与行程城市无关时返回 null，走通用兜底。
   */
  public String hotelOptionsWithIntro(String options, AiConversation conversation, String userId,
      Date turnStart) {
    List<Map<String, Object>> hotels = searchHotelRows(conversation);
    if (hotels.isEmpty()) {
      return null;
    }
    if (options != null) {
      try {
        Map<String, Object> payload = JsonUtil.readObject(options);
        if (!(payload.get("options") instanceof List<?> rawItems) || rawItems.isEmpty()) {
          return null;
        }
        List<Object> items = new ArrayList<>();
        for (Object item : rawItems) {
          addOption(items, item);
        }
        Map<String, String> intros = new LinkedHashMap<>();
        for (Map<String, Object> hotel : hotels) {
          String name = hotel.get("name") == null ? "" : hotel.get("name").toString().trim();
          String intro = hotelIntro(hotel);
          if (!name.isBlank() && intro != null) {
            intros.put(name, intro);
          }
        }
        List<Object> merged = new ArrayList<>();
        boolean enriched = false;
        for (Object item : items) {
          if (item instanceof Map<?, ?> map && map.get("description") != null) {
            merged.add(item);
            enriched = true;
            continue;
          }
          String label = item instanceof Map<?, ?> map ? String.valueOf(map.get("label"))
              : String.valueOf(item);
          String intro = matchHotelIntro(label, intros);
          if (intro == null) {
            merged.add(label);
          } else {
            Map<String, Object> withIntro = new LinkedHashMap<>();
            withIntro.put("label", label);
            withIntro.put("description", intro);
            merged.add(withIntro);
            enriched = true;
          }
        }
        if (!enriched) {
          return null;
        }
        payload.put("options", merged);
        if (payload.get("type") == null) {
          payload.put("type", "options");
        }
        return JsonUtil.write(payload);
      } catch (Exception ex) {
        log.warn("hotel options intro merge failed", ex);
        return null;
      }
    }
    // 模型漏给按钮：直接生成带介绍的酒店候选（本轮已有待确认草稿时不补，避免与卡片重复）
    Long pending = messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversation.getId())
        .eq(AiMessage::getDraftStatus, "pending")
        .ge(AiMessage::getCreatedAt, turnStart));
    if (pending != null && pending > 0) {
      return null;
    }
    List<Object> generated = new ArrayList<>();
    for (Map<String, Object> hotel : hotels) {
      String name = hotel.get("name") == null ? "" : hotel.get("name").toString().trim();
      if (name.isBlank()) {
        continue;
      }
      if (name.length() > 22) {
        name = name.substring(0, 22);
      }
      Map<String, Object> option = new LinkedHashMap<>();
      option.put("label", "订这家：" + name);
      String intro = hotelIntro(hotel);
      if (intro != null) {
        option.put("description", intro);
      }
      generated.add(option);
      if (generated.size() >= 5) {
        break;
      }
    }
    if (generated.isEmpty()) {
      return null;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "options");
    result.put("question", "想订哪家酒店？");
    result.put("options", generated);
    return JsonUtil.write(result);
  }

  /** 选项文字与酒店名的匹配（如「订这家：鑫农门客栈」命中「鑫农门客栈」）。 */
  private String matchHotelIntro(String label, Map<String, String> intros) {
    if (label == null || label.isBlank()) {
      return null;
    }
    for (Map.Entry<String, String> entry : intros.entrySet()) {
      if (label.contains(entry.getKey()) || entry.getKey().contains(label)) {
        return entry.getValue();
      }
    }
    return null;
  }

  /** 酒店介绍：评分 · 参考价 · 地址 · 电话（缺项自动省略，最多 80 字）。 */
  private String hotelIntro(Map<String, Object> hotel) {
    List<String> parts = new ArrayList<>();
    Object rating = hotel.get("rating");
    if (rating != null && !rating.toString().isBlank()) {
      parts.add(rating + " 分");
    }
    Object avgCost = hotel.get("avgCost");
    if (avgCost != null && !avgCost.toString().isBlank()) {
      parts.add("¥" + avgCost);
    }
    Object address = hotel.get("address");
    Object district = hotel.get("district");
    String location = address != null && !address.toString().isBlank() ? address.toString()
        : (district == null ? null : district.toString());
    if (location != null && !location.isBlank()) {
      parts.add("地址 " + location);
    }
    Object tel = hotel.get("tel");
    if (tel != null && !tel.toString().isBlank()) {
      parts.add("电话 " + tel);
    }
    if (parts.isEmpty()) {
      return null;
    }
    String intro = String.join(" · ", parts);
    return intro.length() > 80 ? intro.substring(0, 80) : intro;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> searchHotelRows(AiConversation conversation) {
    try {
      Map<String, Object> result = placesService.searchPois("酒店",
          resolveCity(Map.of(), conversation), "hotel", 6);
      if (!(result.get("places") instanceof List<?> list) || list.isEmpty()) {
        return List.of();
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          rows.add((Map<String, Object>) map);
        }
      }
      return rows;
    } catch (Exception ex) {
      log.warn("hotel intro search failed", ex);
      return List.of();
    }
  }

  private AiMessage insertMessage(String conversationId, String userId, String role,
      String content, String toolCalls, String draftStatus) {
    AiMessage message = new AiMessage();
    message.setConversationId(conversationId);
    message.setUserId(userId);
    message.setRole(role);
    message.setContent(content);
    message.setToolCalls(toolCalls);
    message.setDraftStatus(draftStatus);
    message.setCreatedAt(new Date());
    messageMapper.insert(message);
    return message;
  }
}
