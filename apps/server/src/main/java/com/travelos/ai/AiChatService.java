package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.AiConversationMapper;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.service.AuditService;
import com.travelos.service.BookingService;
import com.travelos.service.PlacesService;
import com.travelos.service.TripDayService;
import com.travelos.service.TripExpenseService;
import com.travelos.service.TripMemberService;
import com.travelos.service.TripPlaceService;
import com.travelos.service.TripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 旅行助手（第一期，设计文档 3.5）。
 * 所有工具以当前登录用户身份走现有 Service 层（鉴权/数据隔离/审计不绕过）；
 * 行程变更与记账先落「草稿」，用户点「应用」才真正写入（幂等）。
 */
@Service
public class AiChatService {

  private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

  /** 单次请求最多工具调用轮数，防止死循环。 */
  private static final int MAX_TOOL_ROUNDS = 6;

  /** 上下文携带的历史消息条数上限。 */
  private static final int HISTORY_LIMIT = 20;

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

  /** 每用户同时最多 1 个进行中的对话请求（并发限流）。 */
  private final Set<String> inFlightUsers = ConcurrentHashMap.newKeySet();

  private final AiConversationMapper conversationMapper;
  private final AiMessageMapper messageMapper;
  private final LlmGatewayService llm;
  private final TripService tripService;
  private final TripDayService tripDayService;
  private final TripPlaceService tripPlaceService;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripDayMapper tripDayMapper;
  private final PlacesService placesService;
  private final BookingService bookingService;
  private final TripExpenseService tripExpenseService;
  private final TripMemberService tripMemberService;
  private final AuditService auditService;

  public AiChatService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
      LlmGatewayService llm, TripService tripService, TripDayService tripDayService,
      TripPlaceService tripPlaceService, TripPlaceMapper tripPlaceMapper, TripDayMapper tripDayMapper,
      PlacesService placesService, BookingService bookingService,
      TripExpenseService tripExpenseService, TripMemberService tripMemberService,
      AuditService auditService) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.llm = llm;
    this.tripService = tripService;
    this.tripDayService = tripDayService;
    this.tripPlaceService = tripPlaceService;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripDayMapper = tripDayMapper;
    this.placesService = placesService;
    this.bookingService = bookingService;
    this.tripExpenseService = tripExpenseService;
    this.tripMemberService = tripMemberService;
    this.auditService = auditService;
  }

  // ==================== 会话管理 ====================

  public Map<String, Object> listConversations(String userId, String tripId) {
    LambdaQueryWrapper<AiConversation> query = new LambdaQueryWrapper<AiConversation>()
        .eq(AiConversation::getUserId, userId)
        .orderByDesc(AiConversation::getUpdatedAt);
    if (tripId != null && !tripId.isBlank()) {
      query.eq(AiConversation::getTripId, tripId);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (AiConversation conversation : conversationMapper.selectList(query)) {
      rows.add(toConversationResponse(conversation));
    }
    return Map.of("conversations", rows);
  }

  @Transactional
  public Map<String, Object> createConversation(String userId, Map<String, Object> dto) {
    AiConversation conversation = new AiConversation();
    conversation.setUserId(userId);
    String tripId = dto.get("tripId") == null ? null : dto.get("tripId").toString();
    if (tripId != null && !tripId.isBlank()) {
      // 确认当前用户是行程成员（数据隔离：非成员不能为该行程创建对话）
      tripMemberService.requireTripMember(tripId, userId);
      conversation.setTripId(tripId);
    }
    String title = dto.get("title") == null ? "" : dto.get("title").toString().trim();
    conversation.setTitle(title.isEmpty() ? "新的对话" : title.substring(0, Math.min(60, title.length())));
    conversation.setCreatedAt(new Date());
    conversation.setUpdatedAt(new Date());
    conversationMapper.insert(conversation);
    return Map.of("conversation", toConversationResponse(conversation));
  }

  public Map<String, Object> listMessages(String conversationId, String userId, String pageValue,
      String pageSizeValue, String latestValue) {
    AiConversation conversation = requireOwnedConversation(conversationId, userId);
    int page = parsePositive(pageValue, 1);
    int pageSize = Math.min(parsePositive(pageSizeValue, 50), 100);
    long total = messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversationId));
    // latest=true：取「最新 pageSize 条」并按时间升序返回（对话超长时仍能看到最新消息）。
    // 普通分页按页码取（升序），保持旧行为兼容。
    boolean latest = "true".equalsIgnoreCase(latestValue);
    int effectivePage = page;
    List<Map<String, Object>> rows = new ArrayList<>();
    if (latest) {
      long offset = Math.max(0, total - pageSize);
      for (AiMessage message : messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
          .eq(AiMessage::getConversationId, conversationId)
          .orderByAsc(AiMessage::getCreatedAt)
          .last("LIMIT " + pageSize + " OFFSET " + offset))) {
        rows.add(toMessageResponse(message));
      }
      effectivePage = (int) (offset / pageSize) + 1;
    } else {
      for (AiMessage message : messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
          .eq(AiMessage::getConversationId, conversationId)
          .orderByAsc(AiMessage::getCreatedAt)
          .last("LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize))) {
        rows.add(toMessageResponse(message));
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("conversation", toConversationResponse(conversation));
    body.put("messages", rows);
    body.put("total", total);
    body.put("page", effectivePage);
    body.put("pageSize", pageSize);
    return body;
  }

  /** 物理删除整个对话（含消息），落审计。 */
  @Transactional
  public Map<String, Object> deleteConversation(String conversationId, String userId) {
    requireOwnedConversation(conversationId, userId);
    messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversationId));
    conversationMapper.deleteById(conversationId);
    auditService.record(userId, null, "ai_conversation_deleted", "ai_conversation",
        conversationId, Map.of());
    return Map.of("success", true);
  }

  // ==================== 发送消息（工具循环） ====================

  public Map<String, Object> sendMessage(String conversationId, String userId,
      Map<String, Object> dto) {
    String content = dto.get("content") == null ? "" : dto.get("content").toString().trim();
    if (content.isEmpty()) {
      throw ApiException.badRequest("消息内容不能为空");
    }
    if (content.length() > 4000) {
      throw ApiException.badRequest("消息内容过长（最多 4000 字）");
    }
    if (!inFlightUsers.add(userId)) {
      throw ApiException.conflict("上一条回复还在生成中，请稍候");
    }
    try {
      return doSend(conversationId, userId, content);
    } finally {
      inFlightUsers.remove(userId);
    }
  }

  private Map<String, Object> doSend(String conversationId, String userId, String content) {
    AiConversation conversation = requireOwnedConversation(conversationId, userId);

    AiMessage userMessage = insertMessage(conversationId, userId, "user", content, null, null);

    // 未配置 key：友好降级为一条助手消息，不影响其他功能
    if (!llm.isEnabled()) {
      AiMessage reply = insertMessage(conversationId, userId, "assistant",
          llm.disabledMessage(), null, null);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(reply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    }

    try {
      List<Map<String, Object>> llmMessages = new ArrayList<>();
      llmMessages.add(Map.of("role", "system", "content", buildSystemPrompt(conversation)));
      appendHistory(llmMessages, conversationId);
      llmMessages.add(Map.of("role", "user", "content", content));

      AiMessage finalReply = null;
      String navigation = null;   /* 本轮 open_page 工具产生的页面跳转（随最终回复下发） */
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        Map<String, Object> message = llm.chat(llmMessages, toolDefinitions());
        List<Map<String, Object>> toolCalls = llm.extractToolCalls(message);
        String messageContent = message.get("content") == null ? ""
            : message.get("content").toString();

        if (toolCalls.isEmpty()) {
          finalReply = insertMessage(conversationId, userId, "assistant",
              messageContent.isBlank() ? "（AI 未返回内容，请重试）" : messageContent, navigation, null);
          break;
        }

        // 带工具调用的 assistant 消息加入上下文
        Map<String, Object> assistantTurn = new LinkedHashMap<>();
        assistantTurn.put("role", "assistant");
        assistantTurn.put("content", messageContent);
        assistantTurn.put("tool_calls", message.get("tool_calls"));
        llmMessages.add(assistantTurn);

        for (Map<String, Object> call : toolCalls) {
          String result = executeTool(call, conversation, userId);
          if ("open_page".equals(call.get("name"))) {
            navigation = extractNavigation(result);
          }
          Map<String, Object> toolMessage = new LinkedHashMap<>();
          toolMessage.put("role", "tool");
          toolMessage.put("tool_call_id", call.get("id"));
          toolMessage.put("content", result);
          llmMessages.add(toolMessage);
        }
      }
      if (finalReply == null) {
        finalReply = insertMessage(conversationId, userId, "assistant",
            "这个请求有点复杂，我先停在这里。你可以换个说法或拆成几步再试。", navigation, null);
      }
      conversation.setUpdatedAt(new Date());
      conversationMapper.updateById(conversation);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(finalReply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    } catch (ApiException ex) {
      // 模型超时/报错/未启用：友好提示落成一条助手消息，绝不向上抛
      log.warn("AI chat degraded: {}", ex.getMessage());
      AiMessage reply = insertMessage(conversationId, userId, "assistant",
          ex.getStatusCode() == 503
              ? "AI 服务暂时不可用：" + ex.getMessage()
              : "AI 服务暂时不可用：" + ex.getMessage(),
          null, null);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(reply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    } catch (Exception ex) {
      log.warn("AI chat failed", ex);
      AiMessage reply = insertMessage(conversationId, userId, "assistant",
          "AI 服务暂时不可用，请稍后再试。行程、预订、记账等核心功能不受影响。", null, null);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(reply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    }
  }

  /** 本次请求产生的全部新消息（含工具生成的草稿消息），按时间升序。 */
  private List<Map<String, Object>> newMessagesSince(String conversationId, Date since) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (AiMessage message : messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversationId)
        .ge(AiMessage::getCreatedAt, since)
        .orderByAsc(AiMessage::getCreatedAt))) {
      rows.add(toMessageResponse(message));
    }
    return rows;
  }

  // ==================== 草稿确认机制 ====================

  /** 应用草稿：行程变更走 TripPlaceService，记账走 TripExpenseService；幂等。 */
  @Transactional
  public Map<String, Object> applyDraft(String conversationId, String messageId, String userId) {
    AiMessage message = requireDraftMessage(conversationId, messageId, userId);
    if ("applied".equals(message.getDraftStatus())) {
      // 幂等：重复点击不重复写入
      return Map.of("success", true, "status", "applied", "message", "该草稿已应用过");
    }
    if ("discarded".equals(message.getDraftStatus())) {
      throw ApiException.badRequest("该草稿已放弃，无法应用");
    }
    Map<String, Object> draft = JsonUtil.readObject(message.getToolCalls() == null
        ? "{}" : message.getToolCalls());
    String type = String.valueOf(draft.get("type"));
    int applied = 0;
    if ("itinerary_change".equals(type)) {
      String tripId = String.valueOf(draft.get("tripId"));
      applied = applyItineraryChanges(tripId, draft, userId);
      auditService.record(userId, tripId, "ai_itinerary_applied", "ai_message", messageId,
          Map.of("changes", applied));
    } else if ("expense".equals(type)) {
      String tripId = String.valueOf(draft.get("tripId"));
      applyExpenseDraft(tripId, draft, userId);
      applied = 1;
      auditService.record(userId, tripId, "ai_expense_applied", "ai_message", messageId,
          Map.of("amount", String.valueOf(draft.getOrDefault("summary", ""))));
    } else {
      throw ApiException.badRequest("未知草稿类型");
    }
    message.setDraftStatus("applied");
    messageMapper.updateById(message);
    return Map.of("success", true, "status", "applied", "appliedCount", applied);
  }

  /** 放弃草稿：仅标记状态。 */
  @Transactional
  public Map<String, Object> discardDraft(String conversationId, String messageId, String userId) {
    AiMessage message = requireDraftMessage(conversationId, messageId, userId);
    if ("applied".equals(message.getDraftStatus())) {
      throw ApiException.badRequest("该草稿已应用，无法放弃");
    }
    message.setDraftStatus("discarded");
    messageMapper.updateById(message);
    return Map.of("success", true, "status", "discarded");
  }

  private int applyItineraryChanges(String tripId, Map<String, Object> draft, String userId) {
    if (!(draft.get("changes") instanceof List<?> changes) || changes.isEmpty()) {
      throw ApiException.badRequest("草稿中没有可应用的变更");
    }
    // 城市提示：取行程目的地，提升地理编码命中率（失败不阻断应用）
    String cityHint = "";
    try {
      Map<String, Object> tripRes = tripService.findOne(tripId, userId);
      @SuppressWarnings("unchecked")
      Map<String, Object> tripMap = (Map<String, Object>) tripRes.get("trip");
      if (tripMap != null && tripMap.get("destination") != null) {
        cityHint = tripMap.get("destination").toString();
      }
    } catch (Exception ignore) {
      // 读取行程失败仅影响地理编码质量
    }
    int count = 0;
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> raw)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> change = (Map<String, Object>) raw;
      String action = String.valueOf(change.get("action"));
      switch (action) {
        case "add" -> {
          TripDay day = findDayByIndex(tripId, toInt(change.get("dayIndex")));
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("tripDayId", day.getId());
          payload.put("name", change.get("name"));
          if (change.get("type") != null) {
            payload.put("type", change.get("type"));
          }
          if (change.get("notes") != null) {
            payload.put("notes", change.get("notes"));
          }
          if (change.get("address") != null) {
            payload.put("address", change.get("address"));
          }
          // 坐标：草稿携带则直接用，缺则按名称/地址地理编码补齐 —— 否则地图无法定位（历史 bug：AI 应用的地点全部无坐标）
          String[] coord = resolveCoordinates(change, cityHint);
          if (coord[0] != null) {
            payload.put("latitude", coord[0]);
            payload.put("longitude", coord[1]);
          }
          tripPlaceService().create(tripId, payload, userId);
          count++;
        }
        case "remove" -> {
          tripPlaceService().delete(tripId, String.valueOf(change.get("placeId")), userId);
          count++;
        }
        case "update" -> {
          Map<String, Object> payload = new LinkedHashMap<>();
          if (change.get("name") != null) {
            payload.put("name", change.get("name"));
          }
          if (change.get("type") != null) {
            payload.put("type", change.get("type"));
          }
          if (change.get("notes") != null) {
            payload.put("notes", change.get("notes"));
          }
          if (change.get("address") != null) {
            payload.put("address", change.get("address"));
          }
          // 更新也允许显式携带坐标（containsKey 语义，仅在草稿提供时写入）
          if (change.get("latitude") != null && change.get("longitude") != null) {
            payload.put("latitude", change.get("latitude"));
            payload.put("longitude", change.get("longitude"));
          }
          tripPlaceService().update(tripId, String.valueOf(change.get("placeId")), payload, userId);
          count++;
        }
        default -> {
          // 未知动作跳过
        }
      }
    }
    return count;
  }

  /**
   * 解析地点坐标：优先用草稿自带的 latitude/longitude（来自 search_places 结果）；
   * 缺失时按名称（其次地址）调高德地点联想做地理编码，取首个带坐标的建议。
   * 任何失败都返回 {null, null}，不阻断草稿应用。
   */
  private String[] resolveCoordinates(Map<String, Object> change, String cityHint) {
    Object lat = change.get("latitude");
    Object lng = change.get("longitude");
    if (lat != null && lng != null && !String.valueOf(lat).isBlank() && !String.valueOf(lng).isBlank()) {
      return new String[]{String.valueOf(lat), String.valueOf(lng)};
    }
    String name = change.get("name") == null ? "" : change.get("name").toString().trim();
    String address = change.get("address") == null ? "" : change.get("address").toString().trim();
    for (String keyword : new String[]{name, address}) {
      if (keyword.isBlank()) {
        continue;
      }
      try {
        Map<String, Object> res = placesService.getSuggestions(keyword,
            cityHint == null ? "" : cityHint, 3);
        if (res.get("suggestions") instanceof List<?> list) {
          for (Object o : list) {
            if (!(o instanceof Map<?, ?> rawSug)) {
              continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sug = (Map<String, Object>) rawSug;
            Object sugLat = sug.get("latitude");
            Object sugLng = sug.get("longitude");
            if (sugLat != null && sugLng != null
                && !String.valueOf(sugLat).isBlank() && !String.valueOf(sugLng).isBlank()) {
              return new String[]{String.valueOf(sugLat), String.valueOf(sugLng)};
            }
          }
        }
      } catch (Exception ignore) {
        // 地理编码失败（key 未配置/超时等）不阻断应用
      }
    }
    return new String[]{null, null};
  }

  private void applyExpenseDraft(String tripId, Map<String, Object> draft, String userId) {
    @SuppressWarnings("unchecked")
    Map<String, Object> expense = (Map<String, Object>) draft.get("expense");
    if (expense == null) {
      throw ApiException.badRequest("草稿中没有记账信息");
    }
    // 走 TripExpenseService.create 全量校验；付款人默认当前用户、缺省均分全部成员、时间默认当前
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("amount", expense.get("amount"));
    payload.put("category", expense.get("category"));
    if (expense.get("title") != null) {
      payload.put("title", expense.get("title"));
    }
    if (expense.get("notes") != null) {
      payload.put("notes", expense.get("notes"));
    }
    tripExpenseService.create(tripId, userId, payload);
  }

  // ==================== 工具执行（全部以当前用户身份） ====================

  private String executeTool(Map<String, Object> call, AiConversation conversation, String userId) {
    String name = String.valueOf(call.get("name"));
    Map<String, Object> args = LlmGatewayService.parseArguments(
        String.valueOf(call.getOrDefault("arguments", "{}")));
    try {
      return switch (name) {
        case "get_trip" -> JsonUtil.write(
            tripService.findOne(requireArg(args, "tripId"), userId));
        case "list_trip_days" -> JsonUtil.write(
            tripDayService.findAll(requireArg(args, "tripId"), userId));
        case "list_trip_places" -> JsonUtil.write(listPlacesOfDay(args, userId));
        case "search_places" -> JsonUtil.write(placesService.getSuggestions(
            requireArg(args, "keyword"),
            args.get("city") == null ? "" : args.get("city").toString(), 8));
        case "get_bookings" -> JsonUtil.write(
            bookingService.findAll(requireArg(args, "tripId"), userId));
        case "add_expense" -> createExpenseDraft(args, conversation, userId);
        case "propose_itinerary_change" -> createItineraryDraft(args, conversation, userId);
        case "open_page" -> openPage(args, conversation);
        default -> "{\"error\":\"未知工具 " + name + "\"}";
      };
    } catch (ApiException ex) {
      return "{\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}";
    }
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

  /** 记账意图 → 草稿（不直接写库，用户确认后才走 TripExpenseService.create）。 */
  private String createExpenseDraft(Map<String, Object> args, AiConversation conversation,
      String userId) {
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : (args.get("tripId") == null ? null : args.get("tripId").toString());
    if (tripId == null || tripId.isBlank()) {
      return "{\"error\":\"当前对话未关联行程，请先在行程中打开 AI 助手\"}";
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

  /** 行程变更 → 草稿（AI 不直接写行程，应用时才走 TripPlaceService）。 */
  private String createItineraryDraft(Map<String, Object> args, AiConversation conversation,
      String userId) {
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : (args.get("tripId") == null ? null : args.get("tripId").toString());
    if (tripId == null || tripId.isBlank()) {
      return "{\"error\":\"当前对话未关联行程，请先在行程中打开 AI 助手\"}";
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

  // ==================== 页面导航 ====================

  /**
   * open_page 工具：识别用户「打开/查看某页面」的意图，解析出前端路由。
   * 跳转动作本身由前端完成 —— 导航信息挂在最终助手消息的 navigation 字段上随响应下发。
   */
  private String openPage(Map<String, Object> args, AiConversation conversation) {
    String page = String.valueOf(args.get("page")).trim().toLowerCase();
    String[] meta = NAV_PAGES.get(page);
    if (meta == null) {
      return "{\"error\":\"未知页面，page 必须是以下之一：" + String.join(" / ", NAV_PAGES.keySet()) + "\"}";
    }
    String url = meta[0];
    if ("trip-detail".equals(page)) {
      String tripId = args.get("tripId") == null ? "" : args.get("tripId").toString().trim();
      if (tripId.isBlank() && conversation.getTripId() != null) {
        tripId = conversation.getTripId();
      }
      if (tripId.isBlank()) {
        return "{\"error\":\"打开行程详情需要行程 ID，请改用 trips（我的行程列表）页面\"}";
      }
      url = url + "?id=" + tripId;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("opened", true);
    result.put("page", page);
    result.put("label", meta[1]);
    result.put("url", url);
    result.put("hint", "页面跳转已随回复下发给前端，请在回复中用一句话自然告知用户即将打开的页面。");
    return JsonUtil.write(result);
  }

  /** 从 open_page 工具结果中提取导航信息（成功才返回 navigate JSON，否则 null）。 */
  private String extractNavigation(String toolResult) {
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

  // ==================== 系统提示词与工具定义 ====================

  private String buildSystemPrompt(AiConversation conversation) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("你是 Travel OS 的 AI 旅行助手，帮助用户规划行程、查询信息、记录开销。");
    prompt.append("规则：\n");
    prompt.append("1. 行程变更必须通过 propose_itinerary_change 工具生成草稿，绝不直接写行程；用户确认后才生效。")
        .append("新增或修改地点时，先用 search_places 查询该地点，把返回的 latitude/longitude 写入草稿的 change 项（名称也不要改动为其他城市同名地点），确保应用后地图能定位。\n");
    prompt.append("2. 记账必须通过 add_expense 工具生成草稿，并在回复中向用户复述关键信息（类别、金额、分摊方式），用户确认后才生效。\n");
    prompt.append("3. 回答使用简体中文，简洁友好；不确定时先用工具查询再回答。\n");
    prompt.append("4. 金额一律人民币，不做汇率折算。\n");
    prompt.append("5. 话题范围：你只提供旅游相关服务（行程规划、目的地建议、记账、打包、预订、游记等）。")
        .append("与旅游无关的问题（写代码、写作业、时事、闲聊、医疗/法律/投资建议等）一律不要回答，")
        .append("直接委婉引导回旅游话题，例如：「很抱歉，我专注于为您提供旅游服务，请问有什么旅游方面可以帮到您的吗？」")
        .append("可以顺势介绍你能帮忙的事，但语气保持自然友好、不生硬。\n");
    prompt.append("6. 页面导航：当用户明确想查看或打开系统中的某个页面（如「打开行程规划」「看看我的花费」「去记账」「帮我看下预订」），")
        .append("调用 open_page 工具选择最匹配的页面，并在回复中用一句话自然告知即将打开的页面（如「好的，这就带你去行程规划～」）。")
        .append("普通旅行咨询（攻略、建议、问答）不要调用 open_page，避免误跳转。\n");
    if (conversation.getTripId() != null) {
      prompt.append("\n当前关联行程上下文：\n");
      prompt.append("当前行程 ID：").append(conversation.getTripId())
          .append("（调用工具时直接使用此 ID，无需向用户询问）。当前用户是本行程成员，可直接操作。\n");
      try {
        Map<String, Object> trip = tripService.findOne(conversation.getTripId(),
            conversation.getUserId());
        @SuppressWarnings("unchecked")
        Map<String, Object> tripMap = (Map<String, Object>) trip.get("trip");
        if (tripMap != null) {
          prompt.append("行程：").append(tripMap.get("title"))
              .append("，目的地：").append(tripMap.get("destination"))
              .append("，日期：").append(tripMap.get("startDate")).append(" ~ ")
              .append(tripMap.get("endDate")).append("，总预算：")
              .append(tripMap.get("budget") == null ? "未设" : tripMap.get("budget")).append("。\n");
        }
        Map<String, Object> days = tripDayService.findAll(conversation.getTripId(),
            conversation.getUserId());
        if (days.get("days") instanceof List<?> dayList) {
          prompt.append("现有行程安排：\n");
          SimpleDateFormat format = new SimpleDateFormat("MM-dd");
          format.setTimeZone(TimeZone.getTimeZone("UTC"));
          for (Object item : dayList) {
            @SuppressWarnings("unchecked")
            Map<String, Object> day = (Map<String, Object>) item;
            prompt.append("第 ").append(day.get("dayIndex")).append(" 天（")
                .append(format.format(day.get("date"))).append("）：");
            if (day.get("places") instanceof List<?> places && !places.isEmpty()) {
              List<String> names = new ArrayList<>();
              for (Object placeItem : places) {
                @SuppressWarnings("unchecked")
                Map<String, Object> place = (Map<String, Object>) placeItem;
                names.add(String.valueOf(place.get("name")));
              }
              prompt.append(String.join("、", names));
            } else {
              prompt.append("（暂无行程点）");
            }
            prompt.append("\n");
          }
        }
      } catch (Exception ex) {
        prompt.append("（行程上下文读取失败，可请用户重新打开助手）\n");
      }
    } else {
      prompt.append("\n当前对话未关联行程，涉及行程操作时请引导用户在具体行程中打开 AI 助手。\n");
    }
    return prompt.toString();
  }

  private List<Map<String, Object>> toolDefinitions() {
    List<Map<String, Object>> tools = new ArrayList<>();
    tools.add(function("get_trip", "读取行程详情（名称、日期、预算、成员角色）",
        Map.of("tripId", stringProp("行程 ID"))));
    tools.add(function("list_trip_days", "读取行程的全部天数与每天的行程点",
        Map.of("tripId", stringProp("行程 ID"))));
    tools.add(function("list_trip_places", "读取某一天的全部行程点",
        Map.of("tripDayId", stringProp("行程天 ID"))));
    tools.add(function("search_places", "按关键词搜索地点（高德 POI），返回名称与地址建议",
        Map.of("keyword", stringProp("搜索关键词"), "city", stringProp("城市名，可空"))));
    tools.add(function("get_bookings", "读取行程的全部预订（机票/酒店/火车票/门票）",
        Map.of("tripId", stringProp("行程 ID"))));
    tools.add(function("add_expense",
        "记账草稿：识别到用户记账意图时调用（如「午饭两人136元」），生成待确认的记账草稿而非直接写入",
        Map.of("amount", stringProp("金额（元，数字字符串）"),
            "category", stringProp("类别：transport/hotel/food/ticket/shopping/other 之一"),
            "title", stringProp("标题，可空"), "notes", stringProp("备注，可空"))));
    tools.add(function("propose_itinerary_change",
        "行程变更草稿：新增/修改/删除行程点时调用，生成待确认的变更预览而非直接写入。add 需要 dayIndex(第几天,从1开始) 与 name；remove/update 需要 placeId（可先用 list_trip_days 查询）。若已通过 search_places 查到地点坐标，务必在 change 项中携带 latitude 与 longitude，可让地图直接定位",
        Map.of("summary", stringProp("变更摘要，如「为第二天新增 3 个景点」"),
            "changes", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "action", stringProp("add / remove / update"),
                        "dayIndex", stringProp("第几天（add 必填，从 1 开始）"),
                        "placeId", stringProp("行程点 ID（remove/update 必填）"),
                        "name", stringProp("地点名称"),
                        "type", stringProp("attraction/hotel/restaurant/transport/shopping/custom"),
                        "notes", stringProp("备注"),
                        "address", stringProp("地址"),
                        "latitude", stringProp("纬度（来自 search_places 结果，可空字符串）"),
                        "longitude", stringProp("经度（来自 search_places 结果，可空字符串）")))))));
    tools.add(function("open_page",
        "打开系统页面：用户想查看/前往某个页面时调用。page 可选值："
            + "home 首页总览、trips 我的行程、trip-form 新建行程、trip-detail 行程详情（需行程ID）、"
            + "itinerary 行程规划、map 行程地图、packing 打包清单、expenses 记账与花费、"
            + "bookings 预订管理、documents 旅行证件、vouchers 票券凭证、photos 旅行相册、"
            + "journals 旅行游记、stats 数据统计、notifications 消息通知、account 账户设置",
        Map.of("page", stringProp("页面标识（见描述中的可选值）"),
            "tripId", stringProp("行程 ID，仅 page=trip-detail 时需要"))));
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

  // ==================== 内部工具 ====================

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

  private void appendHistory(List<Map<String, Object>> llmMessages, String conversationId) {
    List<AiMessage> history = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversationId)
        .orderByDesc(AiMessage::getCreatedAt)
        .last("LIMIT " + HISTORY_LIMIT));
    for (int index = history.size() - 1; index >= 0; index--) {
      AiMessage message = history.get(index);
      if (message.getDraftStatus() != null) {
        // 草稿消息以简短摘要参与上下文
        llmMessages.add(Map.of("role", message.getRole(),
            "content", "[草稿·" + message.getDraftStatus() + "] " + message.getContent()));
      } else if (message.getContent() != null && !message.getContent().isBlank()) {
        llmMessages.add(Map.of("role", message.getRole(), "content", message.getContent()));
      }
    }
  }

  private AiConversation requireOwnedConversation(String conversationId, String userId) {
    AiConversation conversation = conversationMapper.selectById(conversationId);
    if (conversation == null || !conversation.getUserId().equals(userId)) {
      // 不向其他用户暴露对话存在性
      throw ApiException.notFound("对话不存在");
    }
    return conversation;
  }

  private AiMessage requireDraftMessage(String conversationId, String messageId, String userId) {
    requireOwnedConversation(conversationId, userId);
    AiMessage message = messageMapper.selectById(messageId);
    if (message == null || !message.getConversationId().equals(conversationId)
        || message.getDraftStatus() == null) {
      throw ApiException.notFound("草稿不存在");
    }
    return message;
  }

  private TripDay findDayByIndex(String tripId, int dayIndex) {
    TripDay day = tripDayMapper.selectOne(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .eq(TripDay::getDayIndex, dayIndex)
        .last("LIMIT 1"));
    if (day == null) {
      throw ApiException.badRequest("第 " + dayIndex + " 天不存在，无法应用变更");
    }
    return day;
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

  private String requireArg(Map<String, Object> args, String key) {
    Object value = args.get(key);
    if (value == null || value.toString().isBlank()) {
      throw ApiException.badRequest("缺少参数 " + key);
    }
    return value.toString();
  }

  private int toInt(Object value) {
    try {
      return Integer.parseInt(String.valueOf(value).split("\\.")[0]);
    } catch (Exception ex) {
      throw ApiException.badRequest("dayIndex 格式不正确");
    }
  }

  private int parsePositive(String value, int fallback) {
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(1, parsed);
    } catch (Exception ex) {
      return fallback;
    }
  }

  private Map<String, Object> toConversationResponse(AiConversation conversation) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", conversation.getId());
    map.put("tripId", conversation.getTripId());
    map.put("title", conversation.getTitle());
    map.put("createdAt", conversation.getCreatedAt());
    map.put("updatedAt", conversation.getUpdatedAt());
    return map;
  }

  private Map<String, Object> toMessageResponse(AiMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", message.getId());
    map.put("conversationId", message.getConversationId());
    map.put("role", message.getRole());
    map.put("content", message.getContent());
    map.put("draftStatus", message.getDraftStatus());
    if (message.getDraftStatus() != null && message.getToolCalls() != null) {
      map.put("draft", JsonUtil.readObject(message.getToolCalls()));
    } else if (message.getToolCalls() != null) {
      // 非草稿消息的 toolCalls 目前只承载页面导航（open_page），解析失败则不带该字段
      try {
        Map<String, Object> extra = JsonUtil.readObject(message.getToolCalls());
        if ("navigate".equals(extra.get("type"))) {
          map.put("navigation", extra);
        }
      } catch (Exception ignore) {
        // 历史数据或非法 JSON：忽略
      }
    }
    map.put("createdAt", message.getCreatedAt());
    return map;
  }

  /** 延迟注入避免循环依赖（TripPlaceService 依赖 TripDayService）。 */
  private TripPlaceService tripPlaceService() {
    return tripPlaceService;
  }
}
