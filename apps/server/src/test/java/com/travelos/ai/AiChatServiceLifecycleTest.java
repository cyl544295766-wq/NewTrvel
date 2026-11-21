package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.mapper.AiConversationMapper;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.service.AuditService;
import com.travelos.service.TripDayService;
import com.travelos.service.TripMemberService;
import com.travelos.service.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务上下文在工具循环中的检查点：取消后不再进入下一轮模型/工具、不再写降级回复；
 * 超时后恰好写一条超时提示；模型异常与未启用路径各只产生一条回复（既有降级语义回归）。
 *
 * <p>使用 mock 模型与工具，不调用任何真实付费模型。</p>
 */
class AiChatServiceLifecycleTest {

  private static final String USER = "u1";
  private static final String CONV = "c1";

  private AiConversationMapper conversationMapper;
  private AiMessageMapper messageMapper;
  private LlmGatewayService llm;
  private AiToolRegistry toolRegistry;
  private AiPromptBuilder promptBuilder;
  private List<AiMessage> messages;
  private AtomicInteger toolRuns;
  private AiChatService service;

  @BeforeEach
  void setUp() {
    messages = new ArrayList<>();
    toolRuns = new AtomicInteger();

    conversationMapper = mock(AiConversationMapper.class);
    AiConversation conversation = new AiConversation();
    conversation.setId(CONV);
    conversation.setUserId(USER);
    conversation.setTripId("t1"); // 绑定行程：跳过默认行程兜底分支
    conversation.setTitle("测试会话");
    when(conversationMapper.selectById(CONV)).thenReturn(conversation);
    when(conversationMapper.updateById(any(AiConversation.class))).thenReturn(1);

    messageMapper = mock(AiMessageMapper.class);
    AtomicInteger seq = new AtomicInteger();
    when(messageMapper.insert(any(AiMessage.class))).thenAnswer(inv -> {
      AiMessage m = inv.getArgument(0);
      m.setId("m" + seq.incrementAndGet());
      messages.add(m);
      return 1;
    });
    when(messageMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
      List<AiMessage> copy = new ArrayList<>(messages);
      // appendHistory 用 createdAt 倒序取历史：测试里直接倒置即可
      java.util.Collections.reverse(copy);
      return copy;
    });
    when(messageMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    when(messageMapper.updateById(any(AiMessage.class))).thenReturn(1);

    llm = mock(LlmGatewayService.class);
    when(llm.isEnabled()).thenReturn(true);
    when(llm.extractToolCalls(any())).thenAnswer(inv -> {
      Object raw = inv.getArgument(0);
      if (raw instanceof Map<?, ?> map && map.get("tool_calls") instanceof List<?> list) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) list;
        return new ArrayList<>(calls);
      }
      return List.of();
    });

    toolRegistry = mock(AiToolRegistry.class);
    when(toolRegistry.toolDefinitions()).thenReturn(List.of());
    when(toolRegistry.executeTool(any(), any(), anyString())).thenAnswer(inv -> {
      toolRuns.incrementAndGet();
      return "{\"ok\":true}";
    });

    promptBuilder = mock(AiPromptBuilder.class);
    when(promptBuilder.buildPrompt(any())).thenReturn("PROMPT");

    service = new AiChatService(conversationMapper, messageMapper, llm,
        mock(TripService.class), mock(TripDayService.class), mock(TripMemberService.class),
        mock(AuditService.class), promptBuilder, toolRegistry,
        mock(ItineraryDraftEngine.class),
        new AiTaskManager(1, 2, 2, 5, 60, 10, 100, 1000, 60_000),
        new AiIdempotencyService(mock(com.travelos.mapper.AiRequestKeyMapper.class), 7, 300_000),
        null, // 草稿应用事务管理器（本测试不走 applyDraft/discardDraft 路径）
        60_000L);
  }

  private AiTaskContext ctx(long timeoutMs) {
    return new AiTaskContext("t-test", USER, CONV, null, timeoutMs);
  }

  private static long assistantCount(List<AiMessage> store) {
    return store.stream().filter(m -> "assistant".equals(m.getRole())).count();
  }

  private static long userCount(List<AiMessage> store) {
    return store.stream().filter(m -> "user".equals(m.getRole())).count();
  }

  private static Map<String, Object> toolCallMessage() {
    return Map.of("role", "assistant", "content", "",
        "tool_calls", List.of(Map.of("id", "call_1", "type", "function",
            "function", Map.of("name", "ask_options", "arguments", "{}"))));
  }

  // ==================== 取消传播 ====================

  @Test
  void cancelDuringModelCallStopsBeforeToolsAndNextRound() {
    AiTaskContext context = ctx(60_000);
    when(llm.chat(anyList(), anyList())).thenAnswer(inv -> {
      context.cancel("client-disconnected"); // 模拟：模型返回同时客户端已断开
      return toolCallMessage();
    });

    assertThrows(AiCancelledException.class,
        () -> service.executeTurn(context, CONV, USER, "hello there", null));

    verify(llm, times(1)).chat(anyList(), anyList()); // 不再进入下一轮模型调用
    assertEquals(0, toolRuns.get(), "取消后不得执行任何工具");
    assertEquals(1, userCount(messages), "用户消息已写入");
    assertEquals(0, assistantCount(messages), "取消不写降级回复");
  }

  @Test
  void cancelBetweenRoundsStopsRemainingTools() {
    AiTaskContext context = ctx(60_000);
    AtomicInteger calls = new AtomicInteger();
    when(llm.chat(anyList(), anyList())).thenAnswer(inv -> {
      if (calls.incrementAndGet() == 1) {
        return toolCallMessage(); // 第 1 轮：正常工具调用
      }
      context.cancel("client-disconnected"); // 第 2 轮模型返回前已断开
      return toolCallMessage();
    });

    assertThrows(AiCancelledException.class,
        () -> service.executeTurn(context, CONV, USER, "hello there", null));

    assertEquals(2, calls.get());
    assertEquals(1, toolRuns.get(), "第 1 轮工具执行；取消后不再执行新工具");
    assertEquals(0, assistantCount(messages), "取消不写降级回复");
  }

  // ==================== 总超时 ====================

  @Test
  void timeoutWritesExactlyOneTimeoutReply() throws Exception {
    AiTaskContext context = ctx(300); // 总时限 300ms
    when(llm.chat(anyList(), anyList())).thenAnswer(inv -> {
      Thread.sleep(500); // 单轮模型耗时超过总时限
      return Map.of("role", "assistant", "content", "迟到的回复");
    });

    Map<String, Object> result = service.executeTurn(context, CONV, USER, "hello there", null);

    assertNotNull(result.get("assistantMessage"));
    verify(llm, times(1)).chat(anyList(), anyList()); // 超时后不再进入下一轮
    assertEquals(1, assistantCount(messages), "超时必须恰好一条提示消息（不重复降级）");
    assertTrue(String.valueOf(((Map<?, ?>) result.get("assistantMessage")).get("content"))
        .contains("超时"), "超时提示文案");
    assertEquals(1, userCount(messages));
  }

  // ==================== 降级与未启用回归 ====================

  @Test
  void modelApiExceptionDegradesToSingleAssistantReply() {
    AiTaskContext context = ctx(60_000);
    when(llm.chat(anyList(), anyList()))
        .thenThrow(ApiException.serviceUnavailable("AI 服务暂时不可用（超时或网络异常），请稍后再试"));

    Map<String, Object> result = service.executeTurn(context, CONV, USER, "hello there", null);

    assertNotNull(result);
    verify(llm, times(1)).chat(anyList(), anyList());
    assertEquals(1, assistantCount(messages), "模型异常只落一条友好降级");
    assertTrue(String.valueOf(((Map<?, ?>) result.get("assistantMessage")).get("content"))
        .contains("AI 服务暂时不可用"));
  }

  @Test
  void genericModelFailureDegradesToSingleAssistantReply() {
    AiTaskContext context = ctx(60_000);
    when(llm.chat(anyList(), anyList())).thenThrow(new IllegalStateException("boom"));

    Map<String, Object> result = service.executeTurn(context, CONV, USER, "hello there", null);

    assertNotNull(result);
    assertEquals(1, assistantCount(messages), "异常只落一条降级回复");
  }

  @Test
  void disabledModelWritesSingleDisabledReplyWithoutModelCall() {
    AiTaskContext context = ctx(60_000);
    when(llm.isEnabled()).thenReturn(false);
    when(llm.disabledMessage()).thenReturn(
        "AI 助手功能未启用：请在服务端配置 llm.base-url / llm.api-key / llm.model 后重启。");

    Map<String, Object> result = service.executeTurn(context, CONV, USER, "hello there", null);

    assertNotNull(result);
    verify(llm, never()).chat(anyList(), anyList());
    assertEquals(1, assistantCount(messages), "未配置模型：一条未启用提示");
    assertTrue(String.valueOf(((Map<?, ?>) result.get("assistantMessage")).get("content"))
        .contains("未启用"));
  }

  @Test
  void entryTimeoutBeforeUserMessageFailsWithoutPartialWrites() throws Exception {
    AiTaskContext context = ctx(1); // 已过期的总时限（排队过久）
    Thread.sleep(5);
    assertThrows(AiTimeoutException.class,
        () -> service.executeTurn(context, CONV, USER, "hello there", null));
    assertEquals(0, messages.size(), "尚未开始的超时不写任何消息");
    verify(llm, never()).chat(anyList(), anyList());
  }
}
