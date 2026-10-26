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
import com.travelos.service.AiPreferenceService;
import com.travelos.service.BookingService;
import com.travelos.service.PlacesService;
import com.travelos.service.TripDayService;
import com.travelos.service.TripExpenseService;
import com.travelos.service.TripMemberService;
import com.travelos.service.TripPlaceService;
import com.travelos.service.TripService;
import com.travelos.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 旅行助手（第一期，设计文档 3.5）。
 * 所有工具以当前登录用户身份走现有 Service 层（鉴权/数据隔离/审计不绕过）；
 * 行程变更与记账先落「草稿」，用户点「应用」才真正写入（幂等）。
 */
@Service
public class AiChatService {

  private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

  /** 单次请求最多工具调用轮数，防止死循环（整趟多天规划需要逐天查询+生成草稿，8 轮不够用）。 */
  private static final int MAX_TOOL_ROUNDS = 12;

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

  /** 「预订某天行程」类请求的确定性兜底识别。 */
  private static final Pattern BOOKING_INTENT_PATTERN = Pattern.compile("预定|预订|预约|订");
  private static final Pattern DAY_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十两]+)\\s*天");
  private static final Pattern VAGUE_DAY_PATTERN = Pattern.compile("某天|哪天");
  /** 酒店/住宿意图（用于给酒店候选按钮确定性补上评分/价格/地址/电话介绍）。 */
  private static final Pattern HOTEL_INTENT_PATTERN =
      Pattern.compile("酒店|住宿|民宿|客栈|宾馆|住哪|住在哪|住哪家");
  private static final Map<Character, Integer> CN_NUMERALS = Map.ofEntries(
      Map.entry('一', 1), Map.entry('二', 2), Map.entry('两', 2), Map.entry('三', 3),
      Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
      Map.entry('八', 8), Map.entry('九', 9), Map.entry('十', 10));

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
  private final WeatherService weatherService;
  private final AiPreferenceService preferenceService;

  public AiChatService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
      LlmGatewayService llm, TripService tripService, TripDayService tripDayService,
      TripPlaceService tripPlaceService, TripPlaceMapper tripPlaceMapper, TripDayMapper tripDayMapper,
      PlacesService placesService, BookingService bookingService,
      TripExpenseService tripExpenseService, TripMemberService tripMemberService,
      AuditService auditService, WeatherService weatherService,
      AiPreferenceService preferenceService) {
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
    this.weatherService = weatherService;
    this.preferenceService = preferenceService;
  }

  // ==================== 会话管理 ====================

  public Map<String, Object> listConversations(String userId, String tripId, String scope) {
    LambdaQueryWrapper<AiConversation> query = new LambdaQueryWrapper<AiConversation>()
        .eq(AiConversation::getUserId, userId)
        .orderByDesc(AiConversation::getUpdatedAt);
    if (tripId != null && !tripId.isBlank()) {
      /* 指定行程：仅该行程的会话 */
      query.eq(AiConversation::getTripId, tripId);
    } else if ("global".equalsIgnoreCase(scope)) {
      /* 全局范围：仅不含行程的会话（全站助手无行程上下文时使用） */
      query.and(w -> w.isNull(AiConversation::getTripId).or().eq(AiConversation::getTripId, ""));
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
      String options = null;      /* 本轮 ask_options 工具产生的选择按钮（随最终回复下发） */
      int draftClaimRetries = 0;  /* 「声称生成了草稿但实际没调用工具」的纠正次数 */
      Date turnStart = userMessage.getCreatedAt();
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        Map<String, Object> message = llm.chat(llmMessages, toolDefinitions());
        List<Map<String, Object>> toolCalls = llm.extractToolCalls(message);
        String messageContent = message.get("content") == null ? ""
            : message.get("content").toString();

        if (toolCalls.isEmpty()) {
          // 模型可能只写「草稿已生成/点卡片应用」却在轮内没真正调用工具 → 用户看不到卡片，纠正后重试
          if (draftClaimRetries < 2 && claimsMissingDraft(messageContent, conversationId, turnStart, content)) {
            draftClaimRetries++;
            Map<String, Object> retryTurn = new LinkedHashMap<>();
            retryTurn.put("role", "assistant");
            retryTurn.put("content", messageContent);
            llmMessages.add(retryTurn);
            llmMessages.add(Map.of("role", "user", "content",
                "（系统提示：你刚才的回复说草稿已生成、让用户点卡片上的「应用」，但本轮并没有调用 propose_itinerary_change，"
                    + "用户看不到任何卡片。请立刻调用 propose_itinerary_change 生成草稿，不要在正文里重复描述方案；"
                    + "若确实缺少必要信息（哪一天、哪个行程），就直说需要用户补充什么。）"));
            continue;
          }
          String finalContent = messageContent.isBlank() ? "（AI 未返回内容，请重试）" : messageContent;
          if (claimsMissingDraft(messageContent, conversationId, turnStart, content)) {
            // 重试仍未生成草稿：明确告知，避免用户以为有卡片
            finalContent = "（提示：本轮没有生成可应用的卡片，以下内容仅供参考；回复「生成草稿」我再试一次）\n\n" + finalContent;
          }
          finalContent = appendDefaultTripNote(finalContent, conversation, userId, turnStart);
          String replyOptions = resolveReplyOptions(options, content, conversation, userId, turnStart);
          finalReply = insertMessage(conversationId, userId, "assistant", finalContent,
              buildReplyMeta(navigation, replyOptions), null);
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
          } else if ("ask_options".equals(call.get("name"))) {
            // 仅成功的解析结果才覆盖，避免后续失败调用把已捕获的按钮清空
            String parsedOptions = extractOptions(result);
            if (parsedOptions != null) {
              options = parsedOptions;
            }
          }
          Map<String, Object> toolMessage = new LinkedHashMap<>();
          toolMessage.put("role", "tool");
          toolMessage.put("tool_call_id", call.get("id"));
          toolMessage.put("content", result);
          llmMessages.add(toolMessage);
        }
      }
      if (finalReply == null) {
        String replyOptions = resolveReplyOptions(options, content, conversation, userId, turnStart);
        String fallback;
        if (replyOptions != null) {
          fallback = "想预订的话，直接点选下面的一项就可以～";
        } else if (hasPendingDraft(conversationId, turnStart)) {
          // 本轮的草稿卡片已经生成，不要再说「请求复杂」，否则用户会以为没出应用按钮
          fallback = "草稿已经生成，点卡片上的「应用」即可写入行程；想调整的话，直接告诉我要改哪一天或哪个地点。";
        } else {
          fallback = "这个请求有点复杂，我先停在这里。你可以换个说法或拆成几步再试。";
        }
        finalReply = insertMessage(conversationId, userId, "assistant", fallback,
            buildReplyMeta(navigation, replyOptions), null);
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

  /** 本轮是否已经生成了待确认草稿（用于工具轮数耗尽时的兜底文案）。 */
  private boolean hasPendingDraft(String conversationId, Date turnStart) {
    return messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversationId)
        .ge(AiMessage::getCreatedAt, turnStart)
        .eq(AiMessage::getDraftStatus, "pending")) > 0;
  }

  /**
   * 本轮回复「声称已生成草稿、让用户点应用」但实际没有任何待确认草稿 —— 用户会看不到卡片。
   * 仅当用户这轮确实在要求改行程/记账/预订时才判定，避免误伤普通问答。
   */
  private boolean claimsMissingDraft(String reply, String conversationId, Date turnStart,
      String userContent) {
    if (reply == null || reply.isBlank() || userContent == null) {
      return false;
    }
    boolean claims = reply.contains("草稿已生成") || reply.contains("草稿已经生成")
        || reply.contains("已生成草稿") || reply.contains("已为你生成")
        || reply.contains("已重新生成") || reply.contains("生成草稿卡片")
        || reply.contains("点卡片") || reply.contains("卡片上的「应用」")
        || reply.contains("点「应用」") || reply.contains("点“应用”")
        || (reply.contains("卡片") && reply.contains("应用"));
    if (!claims) {
      return false;
    }
    boolean changeRequest = userContent.contains("规划") || userContent.contains("安排")
        || userContent.contains("行程") || userContent.contains("改")
        || userContent.contains("预订") || userContent.contains("订") || userContent.contains("记账")
        || userContent.contains("删除") || userContent.contains("新增") || userContent.contains("加一个");
    if (!changeRequest) {
      return false;
    }
    return !hasPendingDraft(conversationId, turnStart);
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
    } else if ("booking".equals(type)) {
      String tripId = String.valueOf(draft.get("tripId"));
      applyBookingDraft(tripId, draft, userId);
      applied = 1;
      auditService.record(userId, tripId, "ai_booking_applied", "ai_message", messageId,
          Map.of("summary", String.valueOf(draft.getOrDefault("summary", ""))));
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
    // 目标天 → 当天 add 变更数：用于识别「重新规划整天」（多个点 → 覆盖当天，而非叠加）
    Map<Integer, Integer> addCountByDay = new LinkedHashMap<>();
    for (Object item : changes) {
      if (item instanceof Map<?, ?> raw && raw.get("dayIndex") != null
          && "add".equals(String.valueOf(raw.get("action")))) {
        try {
          addCountByDay.merge(toInt(raw.get("dayIndex")), 1, Integer::sum);
        } catch (Exception ignore) {
          // dayIndex 非法时跳过统计，后续按普通追加处理
        }
      }
    }
    Object replaceFlag = draft.get("replaceDay");
    Set<String> replacedDayIds = new HashSet<>();
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
          // 覆盖语义：重新规划当天时清空原有安排，避免多次应用后「一天出现多段行程」
          if (shouldReplaceDay(replaceFlag, addCountByDay, change.get("dayIndex"))
              && replacedDayIds.add(day.getId())) {
            clearDayPlaces(tripId, day.getId(), null);
          }
          String placeType = change.get("type") == null ? null : change.get("type").toString();
          // 住宿是「一家」：同一天重新选酒店时替换旧酒店，而不是并列两家
          if (placeType != null && "hotel".equalsIgnoreCase(placeType)) {
            clearDayPlaces(tripId, day.getId(), "hotel");
          }
          String placeName = change.get("name") == null ? null
              : change.get("name").toString().trim();
          boolean duplicate = placeName != null && !placeName.isEmpty()
              && dayHasPlaceNamed(tripId, day.getId(), placeName);
          if (duplicate) {
            // 同名点已存在：视为同一安排，跳过重复插入
            count++;
          } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripDayId", day.getId());
            payload.put("name", change.get("name"));
            if (change.get("type") != null) {
              payload.put("type", change.get("type"));
            }
            if (change.get("address") != null) {
              payload.put("address", change.get("address"));
            }
            // 时间规划：把草稿的 HH:mm 落到该天的具体日期（startTime/endTime）
            putTime(payload, "startTime", day.getDate(), change.get("startTime"));
            putTime(payload, "endTime", day.getDate(), change.get("endTime"));
            // 票价：写入预估费用（estimatedCost）
            putCost(payload, change.get("estimatedCost"));
            // 营业时间并入备注（notes）
            putNotes(payload, change.get("notes"), stringValue(change.get("openTime")));
            // 坐标：草稿携带则直接用，缺则按名称/地址地理编码补齐 —— 否则地图无法定位（历史 bug：AI 应用的地点全部无坐标）
            String[] coord = resolveCoordinates(change, cityHint);
            if (coord[0] != null) {
              payload.put("latitude", coord[0]);
              payload.put("longitude", coord[1]);
            }
            tripPlaceService().create(tripId, payload, userId);
            count++;
          }
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
          if (change.get("address") != null) {
            payload.put("address", change.get("address"));
          }
          // 时间规划：确定目标日期后落 startTime/endTime
          Date dayDate = resolveDayDate(change);
          putTime(payload, "startTime", dayDate, change.get("startTime"));
          putTime(payload, "endTime", dayDate, change.get("endTime"));
          putCost(payload, change.get("estimatedCost"));
          putNotes(payload, change.get("notes"), stringValue(change.get("openTime")));
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
   * 是否用本次变更覆盖当天原有安排：
   * 草稿显式 replaceDay=true → 覆盖；显式 false → 追加；
   * 未提供时按启发式判断——同一草稿给某天 add 了 2 个及以上地点，视为「重新规划当天」→ 覆盖。
   * 历史 bug：多次应用同一天的规划时地点不断累加，导致一天出现多段行程。
   */
  private boolean shouldReplaceDay(Object replaceFlag, Map<Integer, Integer> addCountByDay,
      Object dayIndexValue) {
    if (Boolean.TRUE.equals(replaceFlag)) {
      return true;
    }
    if (Boolean.FALSE.equals(replaceFlag)) {
      return false;
    }
    if (dayIndexValue == null) {
      return false;
    }
    try {
      return addCountByDay.getOrDefault(toInt(dayIndexValue), 0) >= 2;
    } catch (Exception ignore) {
      return false;
    }
  }

  /** 清空某天原有地点（type 为 null 时清全部；指定 type 时只清该类型，如换酒店）。 */
  private void clearDayPlaces(String tripId, String dayId, String type) {
    LambdaQueryWrapper<TripPlace> wrapper = new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId)
        .eq(TripPlace::getTripDayId, dayId);
    if (type != null) {
      wrapper.eq(TripPlace::getType, type);
    }
    tripPlaceMapper.delete(wrapper);
  }

  /** 当天是否已存在同名地点（用于避免重复叠加同一安排）。 */
  private boolean dayHasPlaceNamed(String tripId, String dayId, String name) {
    Long existing = tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId)
        .eq(TripPlace::getTripDayId, dayId)
        .eq(TripPlace::getName, name));
    return existing != null && existing > 0;
  }

  /**
   * 变更目标日期：update 只信该地点当前所属行程天。
   * dayIndex 仅对 add 有意义——update 不携带 tripDayId，无法挪天，
   * 若这里改用 dayIndex 的日期拼 startTime，会出现「地点在第 N 天却带第 M 天时间戳」的错乱。
   */
  private Date resolveDayDate(Map<String, Object> change) {
    try {
      Object placeId = change.get("placeId");
      if (placeId != null) {
        TripPlace existing = tripPlaceMapper.selectById(String.valueOf(placeId));
        if (existing != null && existing.getTripDayId() != null) {
          TripDay day = tripDayMapper.selectById(existing.getTripDayId());
          if (day != null) {
            return day.getDate();
          }
        }
      }
    } catch (Exception ignore) {
      // 无法确定日期时，时间字段将被忽略
    }
    return null;
  }

  /** 把 HH:mm 形式的时刻落到指定日期，写入 payload（日期为空或格式非法则忽略）。 */
  private void putTime(Map<String, Object> payload, String key, Date dayDate, Object timeValue) {
    String time = stringValue(timeValue);
    if (dayDate == null || time == null || time.isBlank()) {
      return;
    }
    String hm = time.trim();
    if (hm.length() > 5) {
      hm = hm.substring(0, 5);
    }
    if (!hm.matches("\\d{2}:\\d{2}")) {
      return;
    }
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    payload.put(key, format.format(dayDate) + " " + hm + ":00");
  }

  /**
   * 票价写入 estimatedCost。LLM 常输出「免费 / 约100元 / ￥120.5」等非纯数字文本
   * （提示词要求纯数字但模型不总守约）：这里清洗出金额，「免费/免票」记 0，
   * 解析不出金额则丢弃该字段——而不是把脏字符串直传给下游抛错，避免整批变更应用失败。
   */
  private void putCost(Map<String, Object> payload, Object cost) {
    String text = stringValue(cost);
    if (text == null || text.isBlank()) {
      return;
    }
    text = text.trim();
    if (text.contains("免费") || text.contains("免票")) {
      payload.put("estimatedCost", "0");
      return;
    }
    String normalized = text.replaceAll("(?<=\\d)[,，](?=\\d)", "");
    Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(normalized);
    if (matcher.find()) {
      payload.put("estimatedCost", matcher.group());
    }
  }

  /** 营业时间并入备注；没有营业时间时保留原备注。 */
  private void putNotes(Map<String, Object> payload, Object notesObj, String openTime) {
    String notes = stringValue(notesObj);
    String notesText = notes == null ? "" : notes.trim();
    if (openTime != null && !openTime.isBlank()) {
      String merged = "营业时间：" + openTime.trim()
          + (notesText.isEmpty() ? "" : "；" + notesText);
      payload.put("notes", merged);
    } else if (!notesText.isEmpty()) {
      payload.put("notes", notesText);
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : value.toString();
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
    /* 去括注名（如「日喀则（返程过夜点）」→「日喀则」）：高德联想对带括注的全名常搜不到，
     * 而括注多为「过夜点/途经落脚」等人工标注，去掉后才能命中真实地点 */
    String stripped = name.replaceAll("（[^）]*）", "").replaceAll("\\([^)]*\\)", "").trim();
    java.util.LinkedHashSet<String> keywords = new java.util.LinkedHashSet<>();
    if (!name.isBlank()) keywords.add(name);
    if (!stripped.isBlank() && !stripped.equals(name)) keywords.add(stripped);
    if (!address.isBlank()) keywords.add(address);
    for (String keyword : keywords) {
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

  private void applyBookingDraft(String tripId, Map<String, Object> draft, String userId) {
    @SuppressWarnings("unchecked")
    Map<String, Object> booking = (Map<String, Object>) draft.get("booking");
    if (booking == null) {
      throw ApiException.badRequest("草稿中没有预订信息");
    }
    // 走 BookingService.create 全量校验（类型、标题、归属行程等），写入预定管理
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", booking.get("type"));
    payload.put("title", booking.get("title"));
    if (booking.get("startTime") != null) {
      payload.put("startTime", normalizeBookingTime(booking.get("startTime")));
    }
    if (booking.get("endTime") != null) {
      payload.put("endTime", normalizeBookingTime(booking.get("endTime")));
    }
    if (booking.get("amount") != null) {
      payload.put("amount", booking.get("amount"));
    }
    if (booking.get("orderNo") != null) {
      payload.put("orderNo", booking.get("orderNo"));
    }
    if (booking.get("confirmCode") != null) {
      payload.put("confirmCode", booking.get("confirmCode"));
    }
    if (booking.get("dayIndex") != null) {
      try {
        TripDay day = findDayByIndex(tripId, toInt(booking.get("dayIndex")));
        payload.put("tripDayId", day.getId());
      } catch (Exception ignore) {
        // 天数不存在时忽略关联，不阻断预订写入
      }
    }
    if (booking.get("extra") instanceof Map<?, ?> extra && !extra.isEmpty()) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : extra.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object value = entry.getValue();
        if (value == null || String.valueOf(value).isBlank()) {
          continue;
        }
        normalized.put(key,
            "freeCancelDeadline".equals(key) ? normalizeBookingTime(value) : value);
      }
      if (!normalized.isEmpty()) {
        payload.put("extra", normalized);
      }
    }
    bookingService.create(tripId, userId, payload);
  }

  /** 预订时间归一化：与前端 datetime-local 约定一致（"YYYY-MM-DD HH:mm"，空格分隔；纯日期按当天零点）。 */
  private String normalizeBookingTime(Object value) {
    String text = String.valueOf(value).trim().replace('T', ' ');
    if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
      text += " 00:00:00";
    } else if (text.length() == 16) {
      text += ":00";
    }
    return text;
  }

  // ==================== 工具执行（全部以当前用户身份） ====================

  private String executeTool(Map<String, Object> call, AiConversation conversation, String userId) {
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

  /**
   * 酒店检索前置校验：行程的每一天都必须已有景点/行程安排，才允许检索推荐酒店。
   * 未满足时返回 blocked 指引（不下发任何酒店数据），由模型转述为「先确定每天行程」的引导话术。
   */
  private String searchHotels(Map<String, Object> args, AiConversation conversation, String userId) {
    String tripId = conversation.getTripId();
    if (tripId == null) {
      tripId = resolveDefaultTripId(userId);
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
          : (resolveDefaultTripId(userId) == null ? "" : resolveDefaultTripId(userId));
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

  /** 记账意图 → 草稿（不直接写库，用户确认后才走 TripExpenseService.create）。 */
  private String createExpenseDraft(Map<String, Object> args, AiConversation conversation,
      String userId) {
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : (args.get("tripId") == null ? null : args.get("tripId").toString());
    if (tripId == null || tripId.isBlank()) {
      // 全局对话：默认落到最近行程（与系统提示词中「最近行程」上下文一致）
      tripId = resolveDefaultTripId(userId);
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
      tripId = resolveDefaultTripId(userId);
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
      tripId = resolveDefaultTripId(userId);
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

  // ==================== 页面导航 ====================

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
      tripId = resolveDefaultTripId(userId);
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

  /** 从 ask_options 工具结果中提取选择按钮（成功才返回 options JSON，否则 null）。 */
  private String extractOptions(String toolResult) {
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
  private String buildReplyMeta(String navigation, String options) {
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

  /**
   * 最终回复的选项按钮：模型给出（ask_options）优先；否则对「预订某天行程」类请求做确定性兜底——
   * 从该天真实行程点生成可点选按钮。模型偶发漏调/调错工具时，保证用户始终看到选项（历史 bug）。
   */
  private String resolveReplyOptions(String options, String userContent, AiConversation conversation,
      String userId, Date turnStart) {
    if (userContent == null || userContent.isBlank()
        || !BOOKING_INTENT_PATTERN.matcher(userContent).find()) {
      return options;
    }
    // 酒店/住宿候选：保证按钮携带评分/参考价/地址/电话介绍（模型漏给时用检索结果补全）
    if (HOTEL_INTENT_PATTERN.matcher(userContent).find()) {
      String hotelOptions = hotelOptionsWithIntro(options, conversation, userId, turnStart);
      if (hotelOptions != null) {
        return hotelOptions;
      }
    }
    if (options != null) {
      return options;
    }
    Integer dayIndex = extractDayIndex(userContent);
    if (dayIndex == null && !VAGUE_DAY_PATTERN.matcher(userContent).find()) {
      return null;
    }
    // 本轮已生成待确认草稿（含预订草稿）时不补按钮，避免与卡片重复
    Long pending = messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getConversationId, conversation.getId())
        .eq(AiMessage::getDraftStatus, "pending")
        .ge(AiMessage::getCreatedAt, turnStart));
    if (pending != null && pending > 0) {
      return null;
    }
    String tripId = conversation.getTripId() != null ? conversation.getTripId()
        : resolveDefaultTripId(userId);
    if (tripId == null) {
      return null;
    }
    try {
      Map<String, Object> daysRes = tripDayService.findAll(tripId, userId);
      if (!(daysRes.get("days") instanceof List<?> days) || days.isEmpty()) {
        return null;
      }
      List<String> optionTexts = new ArrayList<>();
      String question;
      if (dayIndex != null) {
        Map<String, Object> target = null;
        for (Object item : days) {
          if (item instanceof Map<?, ?> rawDay) {
            @SuppressWarnings("unchecked")
            Map<String, Object> day = (Map<String, Object>) rawDay;
            if (day.get("dayIndex") instanceof Number num
                && num.intValue() == dayIndex) {
              target = day;
              break;
            }
          }
        }
        if (target == null || !(target.get("places") instanceof List<?> places)
            || places.isEmpty()) {
          return null;
        }
        for (Object placeItem : places) {
          if (placeItem instanceof Map<?, ?> rawPlace) {
            @SuppressWarnings("unchecked")
            Map<String, Object> place = (Map<String, Object>) rawPlace;
            String name = String.valueOf(place.get("name"));
            if (name.isBlank() || "null".equals(name)) {
              continue;
            }
            if (name.length() > 22) {
              name = name.substring(0, 22);
            }
            optionTexts.add(name + " 门票");
            if (optionTexts.size() >= 5) {
              break;
            }
          }
        }
        if (optionTexts.isEmpty()) {
          return null;
        }
        optionTexts.add("当天住宿");
        question = "想订第 " + dayIndex + " 天的哪一项？";
      } else {
        SimpleDateFormat format = new SimpleDateFormat("MM-dd");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (Object item : days) {
          if (item instanceof Map<?, ?> rawDay && optionTexts.size() < 6) {
            @SuppressWarnings("unchecked")
            Map<String, Object> day = (Map<String, Object>) rawDay;
            Object dayNumber = day.get("dayIndex");
            if (dayNumber instanceof Number num && day.get("date") instanceof Date date) {
              optionTexts.add("第 " + num.intValue() + " 天（" + format.format(date) + "）");
            }
          }
        }
        if (optionTexts.size() < 2) {
          return null;
        }
        question = "想订哪一天的行程？";
      }
      Map<String, Object> opt = new LinkedHashMap<>();
      opt.put("type", "options");
      opt.put("question", question);
      opt.put("options", optionTexts);
      return JsonUtil.write(opt);
    } catch (Exception ex) {
      log.warn("booking day options fallback failed", ex);
      return null;
    }
  }

  /**
   * 酒店候选按钮补全：把 search_hotels 的评分/参考价/地址/电话写成按钮介绍。
   * 模型已给按钮时按名称匹配补介绍（匹配不上则保持原样、返回 null）；模型漏给按钮时
   * 直接用检索结果生成候选。检索不可用或与行程城市无关时返回 null，走通用兜底。
   */
  private String hotelOptionsWithIntro(String options, AiConversation conversation, String userId,
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

  /** 从用户话术里解析「第 N 天」的天数（支持阿拉伯数字与常见的汉字数字）。 */
  private Integer extractDayIndex(String content) {
    Matcher matcher = DAY_PATTERN.matcher(content);
    if (!matcher.find()) {
      return null;
    }
    String raw = matcher.group(1).trim();
    if (raw.matches("\\d+")) {
      try {
        return Integer.parseInt(raw);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return CN_NUMERALS.get(raw.charAt(0));
  }

  // ==================== 系统提示词与工具定义 ====================

  /**
   * 全局对话中草稿默认落到「最近行程」时，回复必须以一句话明示默认假设。
   * 模型偶有遗漏，这里兜底：仅在模型未自行提及行程名时加前缀（避免重复）。
   */
  private String appendDefaultTripNote(String content, AiConversation conversation,
      String userId, Date since) {
    if (conversation.getTripId() != null) {
      return content;
    }
    try {
      String defaultTripId = resolveDefaultTripId(userId);
      if (defaultTripId == null) {
        return content;
      }
      boolean usedDefault = false;
      List<AiMessage> drafts = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
          .eq(AiMessage::getConversationId, conversation.getId())
          .eq(AiMessage::getDraftStatus, "pending")
          .ge(AiMessage::getCreatedAt, since));
      for (AiMessage draft : drafts) {
        if (draft.getToolCalls() == null) {
          continue;
        }
        Map<String, Object> data = JsonUtil.readObject(draft.getToolCalls());
        if (data.get("tripId") != null && defaultTripId.equals(data.get("tripId").toString())) {
          usedDefault = true;
          break;
        }
      }
      if (!usedDefault) {
        return content;
      }
      Map<String, Object> tripRes = tripService.findOne(defaultTripId, userId);
      @SuppressWarnings("unchecked")
      Map<String, Object> tripMap = (Map<String, Object>) tripRes.get("trip");
      if (tripMap == null || tripMap.get("title") == null) {
        return content;
      }
      String title = tripMap.get("title").toString();
      if (content.contains(title)) {
        return content;
      }
      StringBuilder note = new StringBuilder("默认按最近行程《").append(title).append("》");
      Object start = tripMap.get("startDate");
      Object end = tripMap.get("endDate");
      if (start instanceof Date startDate && end instanceof Date endDate) {
        SimpleDateFormat format = new SimpleDateFormat("MM-dd");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        note.append("（").append(format.format(startDate)).append("~")
            .append(format.format(endDate)).append("）");
      }
      note.append("处理，如需更换行程或天数请直接告诉我。");
      return note.append("\n\n").append(content).toString();
    } catch (Exception ignore) {
      return content;
    }
  }

  private String buildSystemPrompt(AiConversation conversation) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("你是 Travel OS 的 AI 旅行助手，帮助用户规划行程、查询信息、记录开销。");
    prompt.append("规则：\n");
    prompt.append("1. 行程变更必须通过 propose_itinerary_change 工具生成草稿，绝不直接写行程；用户确认后才生效。")
        .append("新增或修改地点时，先用 search_places 查询该地点，把返回的 latitude/longitude 写入草稿的 change 项（名称也不要改动为其他城市同名地点），确保应用后地图能定位。")
        .append("重新规划某一整天的行程时，工具参数 replaceDay 传 true：应用时会先清空当天原有安排再写入，")
        .append("避免同一天多次应用后新行程与旧行程叠加（历史 bug：一天出现多段行程）；只是往某天追加单个地点时传 false。\n");
    prompt.append("2. 记账必须通过 add_expense 工具生成草稿，并在回复中向用户复述关键信息（类别、金额、分摊方式），用户确认后才生效。\n");
    prompt.append("3. 回答使用简体中文，简洁友好；不确定时先用工具查询再回答。\n");
    prompt.append("4. 金额一律人民币，不做汇率折算。\n");
    prompt.append("5. 话题范围：你只提供旅游相关服务（行程规划、目的地建议、记账、打包、预订、游记等）。")
        .append("与旅游无关的问题（写代码、写作业、时事、闲聊、医疗/法律/投资建议等）一律不要回答，")
        .append("直接委婉引导回旅游话题，例如：「很抱歉，我专注于为您提供旅游服务，请问有什么旅游方面可以帮到您的吗？」")
        .append("可以顺势介绍你能帮忙的事，但语气保持自然友好、不生硬。\n");
    prompt.append("6. 页面导航：当用户明确想查看或打开系统中的某个页面（如「打开行程规划」「打开预定管理」「看看我的花费」「去记账」），")
        .append("调用 open_page 工具选择最匹配的页面（预定/预订相关选 bookings，不要选 trips），并在回复中用一句话自然告知即将打开的页面（如「好的，这就带你去行程规划～」）。")
        .append("普通旅行咨询（攻略、建议、问答）不要调用 open_page，避免误跳转。\n");
    prompt.append("7. 餐厅推荐：用户想找吃的/餐厅时，调用 search_restaurants，结合行程地点、口味与预算推荐 2-4 家，"
        + "并给出菜系、评分、人均消费、营业时间与地址；营业时间以工具返回为准。\n");
    prompt.append("8. 景点信息：查询景点营业时间/评分/地址时调用 search_attractions。"
        + "营业时间必须采用工具返回值；仅当工具未返回时才可凭常识给出，并注明「以景区公告为准」。\n");
    prompt.append("9. 酒店推荐（前置条件与流程，重要）：用户想找住的地方/酒店/民宿时，必须先确认行程的每一天都已经有景点/行程安排，才可调用 search_hotels 推荐。"
        + "工具返回 blocked 即表示条件未满足：此时绝对不要推荐或罗列任何酒店（包括凭常识推荐），"
        + "应简洁引导用户先确定缺失天的景点/行程（指出是第几天），并可主动提议「要我先帮你规划第 X 天吗？」；确认后再推荐。"
        + "满足条件后，流程与行程规划保持一致、一次到位：结合当天行程（尤其当天最后一个景点位置与次日出发路线）、评分与参考价格，"
        + "由你直接选定最合适的一家，立即调用 propose_itinerary_change 生成 type=hotel 的写入草稿"
        + "（change 项填全 name/address/latitude/longitude/startTime/endTime/notes，replaceDay 传 false——住宿是往当天追加，"
        + "同一天重复选酒店会自动替换旧酒店，不会并列两家），"
        + "不要先让用户点选、再等下一轮才生成草稿。"
        + "回复正文必须包含：① 选定酒店的完整介绍——评分、参考价格（工具未返回价格时按常识估计并注明「仅供参考」）、地址、电话（工具返回时给出）；"
        + "② 一句话选择理由（如离当晚景点/次日出发更近）；③ 其他候选酒店各一行（名称·评分·参考价·地址），"
        + "并说明可点下方「换住」按钮或直接说换哪家；"
        + "④ 用户选定某家并要求订下/登记时，调用 add_booking（type=hotel）生成登记草稿。不要使用 Markdown 表格，介绍用简短行文或「·」列表。\n");
    prompt.append("10. 时间规划（重要）：用户要求规划某一天的行程时，用 propose_itinerary_change 生成草稿（不要直接写行程）。要求："
        + "(a) 为每个景点给出合理停留时长——小型景点 1~1.5 小时、中型 2~3 小时、大型景区/博物馆 3~4 小时，"
        + "并用 24 小时制 startTime/endTime 标出；"
        + "(b) 相邻地点之间预留 20~40 分钟交通时间，并按地理远近合理安排顺序；"
        + "(c) 用餐时段（午 11:30–13:00、晚 17:30–19:00）安排餐厅，避免与景点冲突；"
        + "(d) 严格遵守景点营业时间，避开展馆闭馆日/闭馆时段（如博物馆多周一闭馆）；"
        + "(e) 每个景点在 change 项给出 estimatedCost（门票，人民币数字，免费填 0），并将营业时间放入 openTime；"
        + "(f) 一天不宜排得过满，一般不超过 6 个点；回复中给出该天的整体时间轴概览；"
        + "(g) 该整天规划草稿必须传 replaceDay=true，应用时覆盖当天原有安排；若只是往某天补充单个地点，则传 replaceDay=false；"
        + "(h) 用户一次要求规划多天（2 天及以上，如「规划前两天」「第 1、2 天一起安排」）时，必须把全部天放进同一次 propose_itinerary_change 调用"
        + "（changes 里用不同 dayIndex 区分，replaceDay=true），不要按天拆成多次调用，也不要只规划其中一天。\n");
    prompt.append("11. 票价：购物车/高德不返回门票价格，estimatedCost 由你按常识给出参考值，"
        + "并在回复中说明「票价与营业时间仅供参考，以景区/餐厅公示为准」。金额一律人民币。\n");
    prompt.append("12. 相对天数与默认行程（重要）：用户说「第二天」「第 N 天」时，一律指行程内的第 N 天（dayIndex=N，从 1 开始），"
        + "结合行程日期与现有安排换算成真实日期后据此规划，绝不理解为自然日历上的「明天」；"
        + "用户未指明行程时：对话已关联行程则用当前行程，全局对话则用下方「最近行程」上下文，"
        + "并在回复开头用一句话明示默认假设（须包含「默认」或「假设」字样，例如「默认按最近行程《赤峰之旅》（07-10~07-12），第二天即 07-11 为你规划」），"
        + "同时提示用户如需调整（换行程或换天数）可以随时说；"
        + "若该行程不存在第 N 天、或用户所指的行程不明确，先与用户确认，不要臆造日期。"
        + "全局对话中的记账等写入操作同样默认作用于最近行程，并在回复中说明这一点。\n");
    prompt.append("13. 预订登记（重要）：用户要求预订/登记/记录一条预订（如「订了机票 CA1234」「把这家酒店记下来」「门票已订好」），或选定酒店后要求订下/登记时，"
        + "必须调用 add_booking 生成待确认草稿，绝不直接写入；生成后向用户复述类型、标题、时间与金额，并说明在卡片点「应用」后即写入预定管理。"
        + "title 必填；时间不确定时可用行程内合理时间并注明假设；不确定是否已登记过时可先 get_bookings 查询避免重复。"
        + "用户只说「预订/订一下/预定某天的行程」而没指明具体对象（哪一天、哪一项、哪家酒店）时，先按规则 14 用 ask_options 让用户点选，再生成草稿，不要让用户手动输入。\n");
    prompt.append("14. 选择按钮（重要）：当需要用户从有限候选中做选择时（预订哪一天的哪一项、选择类型/日期、是否确认），"
        + "必须调用 ask_options 生成可点击按钮，不要让用户手动打字：options 必须基于工具返回的真实数据"
        + "（如 list_trip_days 的每天行程点或 search_hotels 的酒店），每条简短具体（≤16 字），2-6 条；"
        + "酒店/住宿候选的每条选项必须写成对象 {\"label\":\"订这家：××酒店\",\"description\":\"4.6 分 · ¥320 · 地址 ×× · 电话 ××\"}，"
        + "description 取自 search_hotels 的 rating/avgCost/address/tel（缺项省略），让用户能比较后再点选；其他场景选项用字符串即可。"
        + "调用后最终回复只写一句自然提问，不要重复罗列选项文本"
        + "（酒店推荐场景除外：按规则 9 正文必须给出选定酒店的完整介绍与其他候选清单）。"
        + "注意：本轮已经生成草稿卡片时，选择已由卡片承载，不要再调用 ask_options；"
        + "酒店推荐生成草稿后，可额外附一组「换住：××」按钮（同样带 description 介绍）方便用户一键换酒店。\n");
    prompt.append("15. 草稿必须真调用工具（重要）：历史消息里形如「【系统记录·历史草稿卡片（…），不是你的回复】」的内容是系统记录，"
        + "不是你说过的话，也绝不代表本轮已有草稿。用户每次要求规划/调整行程、记账、登记预订时，"
        + "你都必须在本轮实际调用对应工具（propose_itinerary_change / add_expense / add_booking）生成新草稿；"
        + "回复正文里出现「草稿已生成」「点卡片上的应用」之前，必须已经成功调用了工具。"
        + "禁止只用文字描述变更、或在正文里复述一句草稿摘要就声称卡片已生成——用户看不到任何卡片。\n");
    prompt.append("16. 整趟规划（重要）：用户要求一次性规划整趟行程/全程/所有天（如「帮我规划整个行程」「把这几天都安排好」「生成一份 N 日游攻略」）时，"
        + "不要一天一天反复对话，在同一轮内完成：\n"
        + "(a) 逐天规划、并行查询：一次消息里同时调用多个 search_places/search_attractions（每天一组），减少工具轮次；\n"
        + "(b) 查完后只调用一次 propose_itinerary_change：changes 数组包含全部各天的变更项，每项必须带 dayIndex、name、"
        + "latitude/longitude（来自搜索结果）、startTime/endTime、estimatedCost、openTime；"
        + "整趟重新规划时 replaceDay=true（会覆盖各天原有安排，须在回复中明确提醒用户）；"
        + "若行程已有部分安排、用户只想补全空白天，则只对空白天给出变更且 replaceDay=false；\n"
        + "(c) 每天安排 2-5 个景点/活动 + 对应用餐，遵守规则 10 的时长/交通/用餐/营业时间规范，按地理路线就近排序、避免来回折返；"
        + "相邻天之间顺路衔接（前一晚住在哪，次日上午就从附近开始）；\n"
        + "(d) 严格基于行程已有的目的地与天数规划，不要虚构行程没有的天数；行程还没有任何天时，先引导用户创建行程再规划；\n"
        + "(e) 用户给了偏好（亲子/穷游/自驾/摄影/美食等）时按偏好调整：自驾控制单日车程与路线顺路性，亲子减少每日点数并优先亲子友好场所，"
        + "穷游优先免费/低价景点，并在回复开头用一句话点明采纳的偏好；\n"
        + "(f) 行程超过 7 天时建议分两段（先前半段，确认后再规划后半段），避免单次内容过长；\n"
        + "(g) 最终回复给出逐天概览（每天一行：「第 N 天（日期）：A → B → C」），说明票价与营业时间仅供参考，"
        + "并告知点草稿卡片「应用」后会一次性写入全部天。\n");
    prompt.append("17. 天气参考（重要）：上下文与 get_weather 工具提供目的地每日预报（现象/最高最低温/降雨概率，"
        + "Open-Meteo 数据，仅覆盖未来日期，存在 6 小时缓存）。规划行程、推荐景点/餐厅/酒店时必须结合天气：\n"
        + "(a) 雨/雪/雷暴天优先安排博物馆、展馆、室内乐园等项目，或建议用户把户外景点与晴好天互换"
        + "（如「10-23 有雨，建议把户外景点换到 10-22，10-23 改逛博物馆」）；\n"
        + "(b) 温差大（≥12℃）或气温极端（≥35℃/≤-5℃）时提醒携带衣物/防晒/保暖；\n"
        + "(c) 规划草稿的 notes 里可注明当天天气与提醒；\n"
        + "(d) 预报未覆盖的日期（过去、或超出预报范围）无法预知天气，直接说明即可，绝不编造天气。\n");
    prompt.append("18. 偏好记忆（重要）：系统上下文中的「用户旅行偏好」是用户让 AI 长期记住的持久偏好，"
        + "所有推荐（餐厅/景点/酒店）、规划（单天/整趟）与回答都必须遵守——例如用户不吃辣就绝不推荐辣味餐厅，"
        + "并可在合适时自然提及「按您的偏好」让用户感到被记住。规则：\n"
        + "(a) 用户表达持久偏好（忌口、玩法喜好、预算档、住宿档次、节奏、同行人群等）时，"
        + "调用 remember_preference 保存，并用一句话简短确认（如「好的，已记住您不吃辣，之后推荐都会避开辣味」）；"
        + "内容已保存过时工具会返回 alreadyKnown=true，此时直接说「这条我已经记着啦」；\n"
        + "(b) 单次性/临时性要求（如「这次想去看海」）不要保存，本次遵守即可；\n"
        + "(c) 用户要求忘记/删除某条偏好时调用 forget_preference；用户问「我的偏好」时调用 list_preferences；\n"
        + "(d) 偏好与实际情况冲突时（如用户不吃辣但行程目的地以辣为主），如实提醒用户并提供替代方案。\n");
    try {
      List<String> preferenceLines = preferenceService.promptLines(conversation.getUserId());
      if (!preferenceLines.isEmpty()) {
        prompt.append("\n用户旅行偏好（持久记忆，推荐与规划必须遵守）：\n");
        for (int i = 0; i < preferenceLines.size(); i++) {
          prompt.append(i + 1).append(") ").append(preferenceLines.get(i)).append("\n");
        }
      }
    } catch (Exception ignore) {
      // 偏好读取失败不影响对话
    }
    if (conversation.getTripId() != null) {
      prompt.append("\n当前关联行程上下文：\n");
      prompt.append("当前行程 ID：").append(conversation.getTripId())
          .append("（调用工具时直接使用此 ID，无需向用户询问）。当前用户是本行程成员，可直接操作。\n");
      appendTripContext(prompt, conversation.getTripId(), conversation.getUserId());
    } else {
      prompt.append("\n当前对话未关联具体行程（在全局页面打开）。\n");
      String defaultTripId = resolveDefaultTripId(conversation.getUserId());
      if (defaultTripId != null) {
        prompt.append("最近行程 ID：").append(defaultTripId)
            .append("（默认引用对象；用户未指明行程时，行程变更/记账等操作一律以该行程为准，无需向用户询问 ID）。\n");
        appendTripContext(prompt, defaultTripId, conversation.getUserId());
        prompt.append("用户说「第二天」等相对天数时，指本行程的第 N 天（按上方各天日期换算），不是自然日历上的明天。\n");
        prompt.append("（全局对话规则）用户未指明行程时，回复开头第一句必须写明默认假设（包含「默认」或「假设」字样），")
            .append("格式如：「默认按最近行程《行程名》（开始~结束），第二天即 M月D日 为你规划，如需更换行程或天数可以告诉我」，再给出规划内容。\n");
      } else {
        prompt.append("该用户还没有行程，涉及行程操作时先引导其创建行程（可用 open_page 打开 trips 或 trip-form 页面）。\n");
      }
    }
    return prompt.toString();
  }

  /**
   * 行程目的地逐日预报，按「MM-dd」（UTC，与行程天日期同口径）建索引，供 appendTripContext 内联注入。
   * WeatherService 自带 6 小时缓存与兜底（无数据返回空列表），任何异常都不影响提示词生成。
   */
  private Map<String, Map<String, Object>> loadWeatherByDate(String tripId, String userId) {
    Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
    try {
      SimpleDateFormat keyFormat = new SimpleDateFormat("MM-dd");
      keyFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      Map<String, Object> res = weatherService.getTripWeather(tripId, userId);
      if (res.get("weather") instanceof List<?> rows) {
        for (Object row : rows) {
          if (row instanceof Map<?, ?> map && map.get("date") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> weather = (Map<String, Object>) map;
            byDate.putIfAbsent(keyFormat.format((Date) map.get("date")), weather);
          }
        }
      }
    } catch (Exception ignore) {
      // 天气不可用时不注入，不影响规划主流程
    }
    return byDate;
  }

  /** 行程基础信息 + 逐天现有安排，供系统提示词复用。 */
  private void appendTripContext(StringBuilder prompt, String tripId, String userId) {
    try {
      Map<String, Object> trip = tripService.findOne(tripId, userId);
      @SuppressWarnings("unchecked")
      Map<String, Object> tripMap = (Map<String, Object>) trip.get("trip");
      if (tripMap != null) {
        prompt.append("行程：").append(tripMap.get("title"))
            .append("，目的地：").append(tripMap.get("destination"))
            .append("，日期：").append(tripMap.get("startDate")).append(" ~ ")
            .append(tripMap.get("endDate")).append("，总预算：")
            .append(tripMap.get("budget") == null ? "未设" : tripMap.get("budget")).append("。\n");
      }
      Map<String, Object> days = tripDayService.findAll(tripId, userId);
      if (days.get("days") instanceof List<?> dayList) {
        prompt.append("现有行程安排：\n");
        SimpleDateFormat format = new SimpleDateFormat("MM-dd");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        Map<String, Map<String, Object>> weatherByDate = loadWeatherByDate(tripId, userId);
        List<Integer> emptyDays = new ArrayList<>();
        for (Object item : dayList) {
          @SuppressWarnings("unchecked")
          Map<String, Object> day = (Map<String, Object>) item;
          prompt.append("第 ").append(day.get("dayIndex")).append(" 天（")
              .append(format.format(day.get("date"))).append("）：");
          Object dayDate = day.get("date");
          Map<String, Object> weather = dayDate == null ? null
              : weatherByDate.get(format.format(dayDate));
          if (weather != null) {
            prompt.append("【天气：").append(weather.get("condition"));
            if (weather.get("tempLow") != null && weather.get("tempHigh") != null) {
              prompt.append(" ").append(weather.get("tempLow")).append("~")
                  .append(weather.get("tempHigh")).append("℃");
            }
            if (weather.get("rainChance") != null) {
              prompt.append("，降雨概率 ").append(weather.get("rainChance")).append("%");
            }
            prompt.append("】：");
          }
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
            emptyDays.add(day.get("dayIndex") instanceof Number number ? number.intValue() : 0);
          }
          prompt.append("\n");
        }
        List<String> emptyLabels = new ArrayList<>();
        for (int dayNumber : emptyDays) {
          if (dayNumber > 0) {
            emptyLabels.add(String.valueOf(dayNumber));
          }
        }
        if (!emptyLabels.isEmpty()) {
          prompt.append("注意：第 ").append(String.join("、", emptyLabels))
              .append(" 天还没有任何行程点；在行程每天都有景点/安排之前，不要推荐酒店，先引导用户补齐这些天的行程。\n");
        }
      }
    } catch (Exception ex) {
      prompt.append("（行程上下文读取失败，可请用户重新打开助手）\n");
    }
  }

  /**
   * 全局对话的默认行程：按「我的行程」排序（updatedAt 倒序）取最近一次**已规划**
   * （某天已有行程点）的行程；其次是最近有天的行程；再次则回退为最新行程。
   */
  private String resolveDefaultTripId(String userId) {
    try {
      Map<String, Object> res = tripService.findAll(userId);
      if (!(res.get("trips") instanceof List<?> trips) || trips.isEmpty()) {
        return null;
      }
      String firstId = null;
      String firstWithDays = null;
      int examined = 0;
      for (Object item : trips) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tripMap = (Map<String, Object>) item;
        Object idObj = tripMap.get("id");
        if (idObj == null) {
          continue;
        }
        String id = idObj.toString();
        if (firstId == null) {
          firstId = id;
        }
        if (++examined > 10) {
          break;
        }
        try {
          Map<String, Object> daysRes = tripDayService.findAll(id, userId);
          if (!(daysRes.get("days") instanceof List<?> days) || days.isEmpty()) {
            continue;
          }
          if (firstWithDays == null) {
            firstWithDays = id;
          }
          for (Object dayObj : days) {
            if (dayObj instanceof Map<?, ?> day
                && day.get("places") instanceof List<?> places && !places.isEmpty()) {
              return id;
            }
          }
        } catch (Exception ignore) {
          // 单条读取失败则继续尝试下一条
        }
      }
      return firstWithDays != null ? firstWithDays : firstId;
    } catch (Exception ignore) {
      // 读取失败视为没有默认行程
    }
    return null;
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
        // 草稿消息是系统生成的卡片记录，必须与「你的回复」明确区分，
        // 否则模型会模仿这个格式、只在正文里写一句草稿摘要就谎称已生成卡片（历史 bug）
        llmMessages.add(Map.of("role", message.getRole(),
            "content", "【系统记录·历史草稿卡片（" + message.getDraftStatus() + "），不是你的回复】"
                + message.getContent()));
      } else if (message.getContent() != null && !message.getContent().isBlank()) {
        // 历史里遗留的旧格式标记（模型曾模仿它在正文里谎称生成了草稿）在送上下文前剥掉
        String text = message.getContent().replaceFirst("^\\s*\\[草稿·[^\\]]*\\]\\s*", "");
        if (!text.isBlank()) {
          llmMessages.add(Map.of("role", message.getRole(), "content", text));
        }
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
      // 非草稿消息的 toolCalls 承载页面导航（open_page）与选择按钮（ask_options），解析失败则不带
      try {
        Map<String, Object> extra = JsonUtil.readObject(message.getToolCalls());
        if ("navigate".equals(extra.get("type"))) {
          map.put("navigation", extra);
        } else if ("options".equals(extra.get("type"))) {
          map.put("options", extra);
        } else if ("meta".equals(extra.get("type"))) {
          if (extra.get("navigation") instanceof Map<?, ?>) {
            map.put("navigation", extra.get("navigation"));
          }
          if (extra.get("options") instanceof Map<?, ?>) {
            map.put("options", extra.get("options"));
          }
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
