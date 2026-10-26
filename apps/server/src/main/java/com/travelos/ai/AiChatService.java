package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.mapper.AiConversationMapper;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.service.AuditService;
import com.travelos.service.TripDayService;
import com.travelos.service.TripMemberService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 旅行助手（第一期，设计文档 3.5）——会话管理与工具调用循环编排。
 * 职责拆分（2026-10 重构，行为不变）：
 * - {@link AiPromptBuilder}：系统提示词（规则/偏好/行程上下文/天气）构建、默认行程解析；
 * - {@link AiToolRegistry}：工具定义、分发与全部工具实现、回复元数据（导航/选项）解析；
 * - {@link ItineraryDraftEngine}：草稿应用（行程变更/记账/预订写入业务表）。
 * 本类只保留：会话 CRUD、发送消息的工具调用循环、草稿确认编排、回复兜底。
 */
@Service
public class AiChatService {

  private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

  /** 单次请求最多工具调用轮数，防止死循环（整趟多天规划需要逐天查询+生成草稿，8 轮不够用）。 */
  private static final int MAX_TOOL_ROUNDS = 12;

  /** 上下文携带的历史消息条数上限。 */
  private static final int HISTORY_LIMIT = 20;

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
  private final TripMemberService tripMemberService;
  private final AuditService auditService;
  private final AiPromptBuilder promptBuilder;
  private final AiToolRegistry toolRegistry;
  private final ItineraryDraftEngine draftEngine;

  public AiChatService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
      LlmGatewayService llm, TripService tripService, TripDayService tripDayService,
      TripMemberService tripMemberService, AuditService auditService,
      AiPromptBuilder promptBuilder, AiToolRegistry toolRegistry,
      ItineraryDraftEngine draftEngine) {
    this.conversationMapper = conversationMapper;
    this.messageMapper = messageMapper;
    this.llm = llm;
    this.tripService = tripService;
    this.tripDayService = tripDayService;
    this.tripMemberService = tripMemberService;
    this.auditService = auditService;
    this.promptBuilder = promptBuilder;
    this.toolRegistry = toolRegistry;
    this.draftEngine = draftEngine;
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
      llmMessages.add(Map.of("role", "system", "content",
          promptBuilder.buildPrompt(conversation)));
      appendHistory(llmMessages, conversationId);
      llmMessages.add(Map.of("role", "user", "content", content));

      AiMessage finalReply = null;
      String navigation = null;   /* 本轮 open_page 工具产生的页面跳转（随最终回复下发） */
      String options = null;      /* 本轮 ask_options 工具产生的选择按钮（随最终回复下发） */
      int draftClaimRetries = 0;  /* 「声称生成了草稿但实际没调用工具」的纠正次数 */
      Date turnStart = userMessage.getCreatedAt();
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        Map<String, Object> message = llm.chat(llmMessages, toolRegistry.toolDefinitions());
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
              toolRegistry.buildReplyMeta(navigation, replyOptions), null);
          break;
        }

        // 带工具调用的 assistant 消息加入上下文
        Map<String, Object> assistantTurn = new LinkedHashMap<>();
        assistantTurn.put("role", "assistant");
        assistantTurn.put("content", messageContent);
        assistantTurn.put("tool_calls", message.get("tool_calls"));
        llmMessages.add(assistantTurn);

        for (Map<String, Object> call : toolCalls) {
          String result = toolRegistry.executeTool(call, conversation, userId);
          if ("open_page".equals(call.get("name"))) {
            navigation = toolRegistry.extractNavigation(result);
          } else if ("ask_options".equals(call.get("name"))) {
            // 仅成功的解析结果才覆盖，避免后续失败调用把已捕获的按钮清空
            String parsedOptions = toolRegistry.extractOptions(result);
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
            toolRegistry.buildReplyMeta(navigation, replyOptions), null);
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

  /** 应用草稿：行程变更走 ItineraryDraftEngine（底层 TripPlaceService），记账/预订同理；幂等。 */
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
      applied = draftEngine.applyItineraryChanges(tripId, draft, userId);
      auditService.record(userId, tripId, "ai_itinerary_applied", "ai_message", messageId,
          Map.of("changes", applied));
    } else if ("expense".equals(type)) {
      String tripId = String.valueOf(draft.get("tripId"));
      draftEngine.applyExpenseDraft(tripId, draft, userId);
      applied = 1;
      auditService.record(userId, tripId, "ai_expense_applied", "ai_message", messageId,
          Map.of("amount", String.valueOf(draft.getOrDefault("summary", ""))));
    } else if ("booking".equals(type)) {
      String tripId = String.valueOf(draft.get("tripId"));
      draftEngine.applyBookingDraft(tripId, draft, userId);
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
      String hotelOptions = toolRegistry.hotelOptionsWithIntro(options, conversation, userId,
          turnStart);
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
        : promptBuilder.resolveDefaultTripId(userId);
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
      String defaultTripId = promptBuilder.resolveDefaultTripId(userId);
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
}
