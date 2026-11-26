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
import com.travelos.service.TodayBriefService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  private final AiTaskManager taskManager;
  private final AiIdempotencyService idempotency;
  /** 草稿应用业务事务（业务+审计+最终落账原子；与独立的抢占/释放自动提交分开）。 */
  private final TransactionTemplate businessTx;
  /** applying 租约过期阈值（毫秒）：超过才允许条件接管（ai.draft.apply-stale-ms）。 */
  private final long draftApplyStaleMs;

  public AiChatService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
      LlmGatewayService llm, TripService tripService, TripDayService tripDayService,
      TripMemberService tripMemberService, AuditService auditService,
      AiPromptBuilder promptBuilder, AiToolRegistry toolRegistry,
      ItineraryDraftEngine draftEngine, AiTaskManager taskManager,
      AiIdempotencyService idempotency, PlatformTransactionManager transactionManager,
      @Value("${ai.draft.apply-stale-ms:60000}") long draftApplyStaleMs) {
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
    this.taskManager = taskManager;
    this.idempotency = idempotency;
    this.businessTx = transactionManager == null ? null
        : new TransactionTemplate(transactionManager);
    this.draftApplyStaleMs = Math.max(100, draftApplyStaleMs);
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

  // ==================== 发送消息（幂等 + 组合准入 + 有界执行） ====================

  /**
   * 普通发送（同步等待结果）：校验 → 幂等回放/占位 → 组合准入（429 在此同步返回，
   * 不写任何消息）→ 有界线程池执行 → 等待结果。幂等状态由执行体统一收尾。
   */
  public Map<String, Object> sendMessage(String conversationId, String userId,
      Map<String, Object> dto) {
    String content = validateContent(dto);
    String clientRequestId = extractClientRequestId(dto);
    BriefRequest brief = briefRequest(dto);
    requireOwnedConversation(conversationId, userId); // 先做归属校验，避免无效键残留

    AiIdempotencyService.Begin begin =
        idempotency.begin(userId, conversationId, clientRequestId);
    Map<String, Object> replay = replayOrConflict(begin);
    if (replay != null) {
      return replay;
    }

    AiTaskManager.TaskHandle handle;
    try {
      handle = submitTurn(conversationId, userId, content, brief, begin, null, null, null);
    } catch (ApiException ex) {
      idempotency.abandon(userId, conversationId, begin); // 准入被拒：只清本请求自己的占位
      throw ex;
    }
    long waitMs = Math.max(5_000L,
        handle.context().deadlineMillis() - System.currentTimeMillis()
            + llm.readTimeoutMs() + 10_000L);
    try {
      Map<String, Object> result = handle.await(waitMs);
      if (result == null) {
        // 执行体被取消（服务关闭/中断）：结果不可用，保持 cancelled 语义
        idempotency.markCancelled(userId, conversationId, begin);
        throw ApiException.serviceUnavailable("请求已取消，请稍后重试");
      }
      return result;
    } catch (CancellationException ex) {
      idempotency.markCancelled(userId, conversationId, begin);
      throw ApiException.serviceUnavailable("请求已取消，请稍后重试");
    } catch (TimeoutException ex) {
      handle.cancel("await-timeout");
      idempotency.markFailed(userId, conversationId, begin);
      throw ApiException.serviceUnavailable("AI 生成超时，请稍后重试");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      handle.cancel("interrupted");
      idempotency.markFailed(userId, conversationId, begin);
      throw ApiException.serviceUnavailable("请求已中断，请稍后重试");
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause() == null ? ex : ex.getCause();
      if (cause instanceof AiCancelledException) {
        throw ApiException.serviceUnavailable("请求已取消，请稍后重试");
      }
      if (cause instanceof AiTimeoutException) {
        // 入口/排队即超时（尚未写入任何消息）：明确 503，不留半截数据
        throw ApiException.serviceUnavailable("AI 生成排队超时，请稍后再试");
      }
      if (cause instanceof ApiException api) {
        throw api; // 幂等状态已由执行体收尾（failed/…）
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(cause);
    }
  }

  /**
   * 流式发送：<b>准入与幂等前置在返回响应之前完成</b>——被限流时抛 429（统一 JSON），
   * 而不是先 200 再用 error 事件表达。已进入流式后的运行时异常走统一 error 事件。
   * 客户端断开（SSE 完成/超时/错误回调、delta 写失败）→ 取消任务并传播到模型与工具循环。
   */
  public SseEmitter startStream(String conversationId, String userId,
      Map<String, Object> dto) {
    String content = validateContent(dto);
    String clientRequestId = extractClientRequestId(dto);
    BriefRequest brief = briefRequest(dto);
    requireOwnedConversation(conversationId, userId);

    SseEmitter emitter = new SseEmitter(taskManager.taskTimeoutMs() + 15_000L);
    java.util.concurrent.atomic.AtomicBoolean ownComplete =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    AiIdempotencyService.Begin begin =
        idempotency.begin(userId, conversationId, clientRequestId);
    switch (begin.state()) {
      case RUNNING:
        emitAndComplete(emitter, "inprogress",
            Map.of("clientRequestId", String.valueOf(begin.key())));
        return emitter;
      case SUCCEEDED:
        emitAndComplete(emitter, "done", begin.result() == null ? Map.of() : begin.result());
        return emitter;
      case CANCELLED:
        emitAndComplete(emitter, "cancelled",
            Map.of("clientRequestId", String.valueOf(begin.key()),
                "message", "上一次请求已停止，请以新的请求重新发送"));
        return emitter;
      case NEW:
      default:
        break;
    }

    AiTaskManager.TaskHandle handle;
    try {
      handle = submitTurn(conversationId, userId, content, brief, begin,
          (ctx, delta) -> {
            try {
              emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
            } catch (Exception ex) {
              // 写失败 = 客户端断开：标记取消，下个检查点立即停止模型/工具/写库
              ctx.cancel("client-disconnected");
            }
          },
          (ctx, result) -> {
            if (ctx.isCancelled()) {
              return; // 已断开：不再发 done（数据已落库，幂等记录为 succeeded）
            }
            try {
              emitter.send(SseEmitter.event().name("done").data(result));
              ownComplete.set(true); // 先标记“我方正常完成”，避免 onCompletion 回调误判为断开
              emitter.complete();
            } catch (Exception ex) {
              // done 事件写失败：连接已断；结果有效，不回滚
            }
          },
          (ctx, error) -> {
            // 已进入流式后的运行时异常：统一 error 事件（取消/已完成则不再发）
            if (ctx.isCancelled() || ownComplete.get()) {
              return;
            }
            String message = error instanceof com.travelos.common.ApiException api
                ? api.getMessage()
                : error instanceof AiTimeoutException
                    ? "生成超时（排队等待过久），请稍后再试" : "AI 服务异常，请稍后再试";
            try {
              emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
              ownComplete.set(true);
              emitter.complete();
            } catch (Exception ignored) {
              // 连接已断
            }
          });
    } catch (ApiException ex) {
      idempotency.abandon(userId, conversationId, begin);
      throw ex; // 429/400 → 控制器直接以 JSON 返回（SSE 尚未提交）
    }
    final AiTaskManager.TaskHandle started = handle;
    emitter.onCompletion(() -> {
      if (!ownComplete.get() && !started.context().isFinished()) {
        started.cancel("client-completed");
      }
    });
    emitter.onTimeout(() -> started.cancel("client-timeout"));
    emitter.onError(e -> started.cancel("client-error"));
    return emitter;
  }

  /**
   * 提交一次生成：执行体 = 工具循环 + 幂等收尾（succeeded / cancelled / failed）。
   * deltaSink 为空即非流式；afterResult 在结果落库后、幂等成功标记前执行（流式发 done 用）；
   * onError 在执行体以运行时异常逃逸时回调（流式发 error 事件）。
   */
  private AiTaskManager.TaskHandle submitTurn(String conversationId, String userId, String content,
      BriefRequest brief, AiIdempotencyService.Begin begin, java.util.function.BiConsumer<AiTaskContext, String> deltaSink,
      java.util.function.BiConsumer<AiTaskContext, Map<String, Object>> afterResult,
      java.util.function.BiConsumer<AiTaskContext, Throwable> onError) {
    return taskManager.submit(userId, conversationId, begin.key(), ctx -> {
      try {
        Map<String, Object> result = executeTurn(ctx, conversationId, userId, content, brief,
            deltaSink == null ? null : delta -> deltaSink.accept(ctx, delta));
        if (afterResult != null) {
          afterResult.accept(ctx, result);
        }
        idempotency.markSucceeded(userId, conversationId, begin, result);
        return result;
      } catch (AiCancelledException ex) {
        idempotency.markCancelled(userId, conversationId, begin);
        throw ex;
      } catch (RuntimeException ex) {
        // 取消与失败分别落不同状态：取消不阻断同键语义（前端换新 ID），失败允许同键重试
        if (ctx.isCancelled()) {
          idempotency.markCancelled(userId, conversationId, begin);
        } else {
          idempotency.markFailed(userId, conversationId, begin);
        }
        if (onError != null) {
          try {
            onError.accept(ctx, ex);
          } catch (Exception ignored) {
            // 事件发送失败不改变任务状态
          }
        }
        throw ex;
      }
    });
  }

  /** 幂等回放：非 NEW 状态给出统一结果（null = 继续执行）。 */
  private Map<String, Object> replayOrConflict(AiIdempotencyService.Begin begin) {
    switch (begin.state()) {
      case RUNNING:
        return Map.of("inProgress", true, "clientRequestId", String.valueOf(begin.key()));
      case SUCCEEDED:
        return begin.result() == null ? Map.of() : begin.result();
      case CANCELLED:
        return Map.of("cancelled", true, "clientRequestId", String.valueOf(begin.key()),
            "message", "上一次请求已停止，请以新的请求重新发送");
      case NEW:
      default:
        return null;
    }
  }

  private void emitAndComplete(SseEmitter emitter, String name, Map<String, Object> payload) {
    try {
      emitter.send(SseEmitter.event().name(name).data(payload));
      emitter.complete();
    } catch (Exception ex) {
      // 连接已断：幂等状态已经是权威结果，无需重试写入
    }
  }

  private String validateContent(Map<String, Object> dto) {
    String content = dto.get("content") == null ? "" : dto.get("content").toString().trim();
    if (content.isEmpty()) {
      throw ApiException.badRequest("消息内容不能为空");
    }
    if (content.length() > 4000) {
      throw ApiException.badRequest("消息内容过长（最多 4000 字）");
    }
    return content;
  }

  private String extractClientRequestId(Map<String, Object> dto) {
    Object raw = dto.get("clientRequestId");
    if (raw == null) {
      return null;
    }
    String value = raw.toString().trim();
    return value.isEmpty() ? null : value;
  }

  private record BriefRequest(String type, String date) {}

  private BriefRequest briefRequest(Map<String, Object> dto) {
    Object value = dto.get("briefAction");
    if (value == null) return null;
    if (!(value instanceof Map<?, ?> action) || !(action.get("type") instanceof String type)
        || !Set.of(TodayBriefService.WEATHER_RAIN, TodayBriefService.TIME_CONFLICT).contains(type)
        || !(action.get("date") instanceof String date) || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
      throw ApiException.badRequest("简报操作无效，请从今日简报重试");
    }
    return new BriefRequest(type, date);
  }

  /**
   * 工具调用主循环（执行体）。进度检查点：每个模型调用前后、每个工具调用前后、
   * 每次写库之前都会 {@link AiTaskContext#checkActive()}——取消后不再开始下一轮模型与工具，
   * 超时后立即收口（恰好一条超时提示消息，不重复降级）。
   */
  Map<String, Object> executeTurn(AiTaskContext ctx, String conversationId, String userId,
      String content, java.util.function.Consumer<String> onDelta) {
    return executeTurn(ctx, conversationId, userId, content, null, onDelta);
  }

  private Map<String, Object> executeTurn(AiTaskContext ctx, String conversationId, String userId,
      String content, BriefRequest brief, java.util.function.Consumer<String> onDelta) {
    AiConversation conversation = requireOwnedConversation(conversationId, userId);
    ctx.checkActive();
    var briefTools = brief == null ? null : toolRegistry.briefTools(conversation, userId, brief.type(), brief.date());

    // 用户消息：取消发生在写入前则直接终止（策略：未写入的不再写）
    ctx.checkActive();
    AiMessage userMessage = insertMessage(conversationId, userId, "user", content, null, null);
    ctx.markUserMessageWritten();

    // 未配置 key：友好降级为一条助手消息，不影响其他功能
    if (!llm.isEnabled()) {
      ctx.checkActive();
      AiMessage reply = insertMessage(conversationId, userId, "assistant",
          llm.disabledMessage(), null, null);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(reply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    }

    try {
      List<Map<String, Object>> llmMessages = new ArrayList<>();
      llmMessages.add(Map.of("role", "system", "content",
          briefTools == null ? promptBuilder.buildPrompt(conversation) : briefTools.instructions()));
      if (briefTools == null) appendHistory(llmMessages, conversationId);
      llmMessages.add(Map.of("role", "user", "content", content));
      var tools = briefTools == null ? toolRegistry.toolDefinitions() : briefTools.definitions();

      AiMessage finalReply = null;
      String navigation = null;   /* 本轮 open_page 工具产生的页面跳转（随最终回复下发） */
      String options = null;      /* 本轮 ask_options 工具产生的选择按钮（随最终回复下发） */
      int draftClaimRetries = 0;  /* 「声称生成了草稿但实际没调用工具」的纠正次数 */
      boolean routeWorkflow = false;
      Date turnStart = userMessage.getCreatedAt();
      for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        ctx.checkActive(); // 每轮模型调用前：取消/超时立即收口
        Map<String, Object> message = onDelta == null
            ? llm.chat(llmMessages, tools)
            : llm.chatStream(llmMessages, tools, onDelta,
                ctx::isCancelled); // 流式读取中逐行检查取消
        ctx.checkActive(); // 模型返回后、处理前
        List<Map<String, Object>> toolCalls = llm.extractToolCalls(message);
        String messageContent = message.get("content") == null ? ""
            : message.get("content").toString();

        if (toolCalls.isEmpty()) {
          // 模型可能只写「草稿已生成/点卡片应用」却在轮内没真正调用工具 → 用户看不到卡片，纠正后重试
          if (briefTools == null && draftClaimRetries < 2 && claimsMissingDraft(messageContent, conversationId, turnStart, content)) {
            draftClaimRetries++;
            Map<String, Object> retryTurn = new LinkedHashMap<>();
            retryTurn.put("role", "assistant");
            retryTurn.put("content", messageContent);
            llmMessages.add(retryTurn);
            llmMessages.add(Map.of("role", "user", "content",
                "（系统提示：你刚才的回复说草稿已生成、让用户点卡片上的「应用」，但本轮没有成功生成草稿，"
                    + "用户看不到卡片。普通规划用 propose_itinerary_change；临时调整请按路线核对流程使用 propose_route_adjustment，"
                    + "打包清单用 propose_packing_list；"
                    + "如工具提示冲突请先解决或向用户说明；"
                    + "若确实缺少必要信息（哪一天、哪个行程），就直说需要用户补充什么。）"));
            continue;
          }
          String finalContent = messageContent.isBlank() ? "（AI 未返回内容，请重试）" : messageContent;
          if (briefTools != null && !briefTools.created()) {
            finalContent = "本次尚未生成可应用的调整。可回到简报查看原行程或重试。\n\n" + finalContent;
          } else if (briefTools == null && claimsMissingDraft(messageContent, conversationId, turnStart, content)) {
            // 重试仍未生成草稿：明确告知，避免用户以为有卡片
            finalContent = "（提示：本轮没有生成可应用的卡片，以下内容仅供参考；回复「生成草稿」我再试一次）\n\n" + finalContent;
          }
          if (briefTools == null) finalContent = appendDefaultTripNote(finalContent, conversation, userId, turnStart);
          String replyOptions = briefTools == null ? resolveReplyOptions(options, content, conversation, userId, turnStart) : null;
          ctx.checkActive(); // 写最终回复前最后检查（取消则不写）
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

        routeWorkflow |= toolCalls.stream().anyMatch(call -> "check_day_route".equals(call.get("name"))
            || "propose_route_adjustment".equals(call.get("name")));
        for (Map<String, Object> call : toolCalls) {
          ctx.checkActive(); // 每个工具调用前：断开/超时后不再执行新工具
          String result;
          if (briefTools != null) {
            result = briefTools.execute(call, ctx);
          } else if (routeWorkflow && "propose_itinerary_change".equals(call.get("name"))) {
            result = JsonUtil.write(Map.of("error", "路线校验后的行程调整请使用 propose_route_adjustment"));
          } else {
            result = toolRegistry.executeTool(call, conversation, userId);
          }
          if (briefTools == null && "open_page".equals(call.get("name"))) {
            navigation = toolRegistry.extractNavigation(result);
          } else if (briefTools == null && "ask_options".equals(call.get("name"))) {
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
        String replyOptions = briefTools == null ? resolveReplyOptions(options, content, conversation, userId, turnStart) : null;
        String fallback;
        if (replyOptions != null) {
          fallback = "想预订的话，直接点选下面的一项就可以～";
        } else if (briefTools == null ? hasPendingDraft(conversationId, turnStart) : briefTools.created()) {
          // 本轮的草稿卡片已经生成，不要再说「请求复杂」，否则用户会以为没出应用按钮
          fallback = "草稿已经生成，点卡片上的「应用」即可写入行程；想调整的话，直接告诉我要改哪一天或哪个地点。";
        } else {
          fallback = "这个请求有点复杂，我先停在这里。你可以换个说法或拆成几步再试。";
        }
        ctx.checkActive();
        finalReply = insertMessage(conversationId, userId, "assistant", fallback,
            toolRegistry.buildReplyMeta(navigation, replyOptions), null);
      }
      conversation.setUpdatedAt(new Date());
      conversationMapper.updateById(conversation);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(finalReply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    } catch (AiCancelledException ex) {
      throw ex; // 取消：不写降级回复（已写入的用户消息/草稿保留）
    } catch (AiTimeoutException ex) {
      // 总超时：恰好一条超时提示（不与其他降级重复），随后正常收尾
      log.warn("AI task timeout after {}ms (task {})", ex.getTimeoutMs(), ctx.taskId());
      AiMessage reply = insertMessage(conversationId, userId, "assistant",
          "这次回复超时了（超过 " + Math.max(1, ex.getTimeoutMs() / 1000)
              + " 秒），我先停下来。可以再发一次或把请求拆小一点。",
          null, null);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(reply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    } catch (ApiException ex) {
      if (ctx.isCancelled()) {
        throw new AiCancelledException(ctx.cancelReason());
      }
      // 模型超时/报错/未启用：友好提示落成一条助手消息，绝不向上抛
      log.warn("AI chat degraded: {}", ex.getMessage());
      ctx.checkActive();
      AiMessage reply = insertMessage(conversationId, userId, "assistant",
          ex.getStatusCode() == 503
              ? "AI 服务暂时不可用：" + ex.getMessage()
              : "AI 服务暂时不可用：" + ex.getMessage(),
          null, null);
      return Map.of("userMessage", toMessageResponse(userMessage),
          "assistantMessage", toMessageResponse(reply),
          "newMessages", newMessagesSince(conversationId, userMessage.getCreatedAt()));
    } catch (Exception ex) {
      if (ctx.isCancelled()) {
        throw new AiCancelledException(ctx.cancelReason());
      }
      if (ex instanceof AiTimeoutException timeout) {
        throw timeout;
      }
      log.warn("AI chat failed", ex.getClass().getSimpleName());
      ctx.checkActive();
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
        || userContent.contains("删除") || userContent.contains("新增") || userContent.contains("加一个")
        || userContent.contains("打包清单");
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

  /**
   * 应用草稿。状态机：<b>pending → applying → applied</b>（另有 pending → discarded，互斥）。
   *
   * <p><b>原子抢占</b>：调用任何行程/费用/预订业务 Service 之前，先以数据库条件更新
   * （pending → applying + 执行令牌 + 租约起点，影响行数 = 1）抢占；并发请求只有一个赢家，
   * 其余按最新状态返回：applied → 回放既有成功结果（不重新执行业务）、applying（未过期）→
   * 409 进行中、discarded → 400。多实例下同一套数据库条件更新保持互斥。</p>
   *
   * <p><b>事务边界</b>：业务写入 + 审计 + 最终落账（applying + 本令牌 → applied + 结果 JSON）
   * 在同一事务中——业务/落账失败整体回滚，绝不留下错误的 applied 或半应用行程；
   * 抢占与释放为独立自动提交（失败释放后立即回到 pending 可重试）。</p>
   *
   * <p><b>fencing</b>：最终落账必须同时匹配 applying 与本执行令牌；被接管后的旧执行者
   * 影响行数 = 0 → 其业务事务回滚（不产生业务副作用）、不能覆盖新执行者状态。
   * <b>过期接管</b>：applying 超过 ai.draft.apply-stale-ms 才允许条件接管
   * （状态 + 旧令牌 + 旧租约起点三条件，恢复者只有一个赢家）；崩溃遗留由租约恢复，
   * 不会永久卡死，也绝不“超时改回 pending 让新旧执行者同时写业务”。</p>
   *
   * <p><b>结果回放</b>：成功结果持久化在 draft_apply_result，同稿重试返回与首次完全一致的
   * 结果（历史无结果的 applied 行回退通用成功响应）。</p>
   */
  public Map<String, Object> applyDraft(String conversationId, String messageId, String userId,
      Map<String, Object> choices) {
    AiMessage message = requireDraftMessage(conversationId, messageId, userId); // 授权/归属校验（不变）
    boolean terminal = "applied".equals(message.getDraftStatus()) || "discarded".equals(message.getDraftStatus());
    Map<String, Object> draft = terminal || message.getToolCalls() == null
        ? Map.of() : JsonUtil.readObject(message.getToolCalls());
    String type = String.valueOf(draft.get("type"));
    if (AiReceiptService.REVIEW.equals(type)) {
      throw ApiException.badRequest("请先核对票据内容并生成预订或记账草稿");
    }
    String myToken = UUID.randomUUID().toString();

    // ---- 1) 原子抢占（条件更新；失败则按最新状态决策，最多重读 6 次收敛） ----
    boolean claimed = false;
    AiMessage row = message; // 首轮直接用授权读到的状态（与 discard 路径对称，无额外预读）
    for (int attempt = 0; attempt < 6 && !claimed; attempt++) {
      if (row == null || row.getDraftStatus() == null) {
        throw ApiException.notFound("草稿不存在");
      }
      switch (row.getDraftStatus()) {
        case "applied":
          return appliedReplay(row); // 已应用：回放既有结果，不再调用业务 Service
        case "discarded":
          throw ApiException.badRequest("该草稿已放弃，无法应用");
        case "applying": {
          if (!draftLeaseExpired(row)) {
            throw ApiException.conflict("该草稿正在应用中，请稍后再试");
          }
          Date leaseAt = row.getDraftApplyStartedAt() != null
              ? row.getDraftApplyStartedAt() : row.getCreatedAt();
          int took = messageMapper.takeoverDraftApply(messageId, row.getDraftApplyToken(),
              leaseAt, myToken, new Date());
          if (took == 1) {
            claimed = true; // 过期接管成功：本请求成为唯一执行者，旧令牌立即失效
          } else {
            row = messageMapper.selectById(messageId); // 接管竞争失败 → 重读最新状态
          }
          break;
        }
        case "pending": {
          int took = messageMapper.claimDraftApply(messageId, myToken, new Date());
          if (took == 1) {
            claimed = true;
          } else {
            row = messageMapper.selectById(messageId); // 已被他人抢走 → 重读决策
          }
          break;
        }
        default:
          throw ApiException.conflict("草稿状态异常，请刷新后重试");
      }
    }
    if (!claimed) {
      throw ApiException.conflict("请求正在处理中，请稍后重试");
    }

    // ---- 2) 业务应用 + 审计 + 最终落账（同一事务；落账 fencing 失败 → 整体回滚） ----
    try {
      return businessTx.execute(status -> {
        int applied;
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("itinerary_change".equals(type)) {
          String tripId = String.valueOf(draft.get("tripId"));
          applied = draftEngine.applyItineraryChanges(tripId, draft, userId);
          if (draft.get("briefAlert") != null) {
            payload.put("briefAlert", draft.get("briefAlert"));
            payload.put("tripId", tripId);
          }
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
        } else if ("packing".equals(type)) {
          String tripId = String.valueOf(draft.get("tripId"));
          Map<String, Object> result = draftEngine.applyPackingDraft(tripId, draft, userId, choices);
          applied = ((Number) result.get("appliedCount")).intValue();
          payload.put("packing", result);
          auditService.record(userId, tripId, "ai_packing_applied", "ai_message", messageId,
              Map.of("listId", result.get("listId"), "itemsAdded", applied));
        } else {
          throw ApiException.badRequest("未知草稿类型");
        }
        payload.put("success", true);
        payload.put("status", "applied");
        payload.put("appliedCount", applied);
        int fin = messageMapper.finishDraftApply(messageId, myToken,
            JsonUtil.write(payload), new Date());
        if (fin != 1) {
          // 已被接管（fencing）：抛出以回滚本事务的全部业务写入，旧执行者不留副作用
          throw ApiException.conflict("草稿应用状态已变化，请刷新后重试");
        }
        return payload;
      });
    } catch (RuntimeException ex) {
      // ---- 3) 失败恢复：区分“提交实际成功”与“需释放重试” ----
      AiMessage after = messageMapper.selectById(messageId);
      if (after != null && "applied".equals(after.getDraftStatus())) {
        // 结果未知的失败但事务已提交（或他方已完成）：回放既有结果，绝不重复执行业务
        return appliedReplay(after);
      }
      try {
        // 释放自己的占位（仅当仍持有）→ 回到 pending 立即可重试；
        // 若已被接管/状态已变则影响行数 = 0，交给租约机制处理
        messageMapper.releaseDraftApply(messageId, myToken);
      } catch (RuntimeException releaseEx) {
        log.warn("ai draft release failed: {}", releaseEx.getClass().getSimpleName());
      }
      throw ex;
    }
  }

  /**
   * 放弃草稿：与 apply 参与同一套互斥——仅 <b>pending → discarded</b> 条件更新。
   * apply 抢先（applying/applied）时 discard 得到 409/400，绝不覆盖；
   * discard 先成功时 apply 抢占失败 → 400 且不产生任何业务写入。
   * 已 discarded 重复放弃幂等返回成功。
   */
  public Map<String, Object> discardDraft(String conversationId, String messageId, String userId) {
    requireDraftMessage(conversationId, messageId, userId); // 授权/归属校验（不变）
    AiMessage row = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      int rows = messageMapper.discardDraftIfPending(messageId);
      if (rows == 1) {
        return Map.of("success", true, "status", "discarded");
      }
      row = messageMapper.selectById(messageId);
      if (row == null || row.getDraftStatus() == null) {
        throw ApiException.notFound("草稿不存在");
      }
      if (!"pending".equals(row.getDraftStatus())) {
        break; // 已被 apply 抢走或已是终态 → 按状态返回
      }
      // 仍为 pending：上一持有者刚释放（极小概率窗口）→ 重试抢占
    }
    String status = row == null ? null : row.getDraftStatus();
    if ("discarded".equals(status)) {
      return Map.of("success", true, "status", "discarded"); // 幂等
    }
    if ("applied".equals(status)) {
      throw ApiException.badRequest("该草稿已应用，无法放弃");
    }
    if ("applying".equals(status)) {
      throw ApiException.conflict("该草稿正在应用中，无法放弃");
    }
    throw ApiException.conflict("请求正在处理中，请稍后重试");
  }

  /** 已应用草稿的重试回放：返回与首次成功完全一致的持久化结果（历史行回退通用响应）。 */
  private Map<String, Object> appliedReplay(AiMessage row) {
    String saved = row.getDraftApplyResult();
    if (saved != null && !saved.isBlank()) {
      try {
        Map<String, Object> result = JsonUtil.readObject(saved);
        if (result.get("success") != null) {
          return result;
        }
      } catch (Exception ex) {
        log.warn("ai draft result unreadable, use generic replay: {}", row.getId());
      }
    }
    return Map.of("success", true, "status", "applied", "message", "该草稿已应用过");
  }

  /** 租约是否过期：超过 ai.draft.apply-stale-ms 才允许接管；无租约信息（防御）视为可接管。 */
  private boolean draftLeaseExpired(AiMessage row) {
    Date startedAt = row.getDraftApplyStartedAt() != null
        ? row.getDraftApplyStartedAt() : row.getCreatedAt();
    if (startedAt == null) {
      return true;
    }
    return System.currentTimeMillis() - startedAt.getTime() > draftApplyStaleMs;
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
        if (!data.containsKey("routeAdjustment") && !"packing".equals(data.get("type"))
            && defaultTripId.equals(data.get("tripId"))) {
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
      Map<String, Object> draft = JsonUtil.readObject(message.getToolCalls());
      draft.remove(AiReceiptService.FINGERPRINT);
      map.put("draft", draft);
      if ("packing".equals(draft.get("type")) && "applied".equals(message.getDraftStatus())) {
        map.put("packingResult", appliedReplay(message).get("packing"));
      }
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
