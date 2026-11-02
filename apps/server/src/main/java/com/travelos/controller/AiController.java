package com.travelos.controller;

import com.travelos.ai.AiChatService;
import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** AI 旅行助手（第一期）：对话 + 草稿确认。所有数据按当前用户隔离。 */
@RestController
@RequestMapping("/api/ai/conversations")
public class AiController {

  private final AiChatService aiChatService;

  public AiController(AiChatService aiChatService) {
    this.aiChatService = aiChatService;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(value = "tripId", required = false) String tripId,
      @RequestParam(value = "scope", required = false) String scope,
      @CurrentUser UserContext user) {
    return aiChatService.listConversations(user.id(), tripId, scope);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> dto,
      @CurrentUser UserContext user) {
    return aiChatService.createConversation(user.id(), dto);
  }

  @GetMapping("/{conversationId}/messages")
  public Map<String, Object> messages(@PathVariable String conversationId,
      @RequestParam(value = "page", required = false) String page,
      @RequestParam(value = "pageSize", required = false) String pageSize,
      @RequestParam(value = "latest", required = false) String latest,
      @CurrentUser UserContext user) {
    return aiChatService.listMessages(conversationId, user.id(), page, pageSize, latest);
  }

  @PostMapping("/{conversationId}/messages")
  public Map<String, Object> send(@PathVariable String conversationId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return aiChatService.sendMessage(conversationId, user.id(), dto);
  }

  /**
   * 流式发送（P0-4）：SSE 推送 delta 增量，结束时以 done 事件下发
   * 完整装配结果（与非流式接口同形状），异常以 error 事件告知。
   */
  @PostMapping("/{conversationId}/messages/stream")
  public SseEmitter sendMessageStream(@PathVariable String conversationId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    SseEmitter emitter = new SseEmitter(180_000L);
    Thread worker = new Thread(() -> {
      try {
        Map<String, Object> result = aiChatService.sendMessageStream(
            conversationId, user.id(), dto,
            delta -> {
              try {
                emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
              } catch (Exception ignored) {
                /* 客户端断开：无需中断生成 */
              }
            });
        emitter.send(SseEmitter.event().name("done").data(result));
        emitter.complete();
      } catch (Exception ex) {
        String message = ex instanceof com.travelos.common.ApiException
            ? String.valueOf(ex.getMessage()) : "AI 服务异常，请稍后再试";
        try {
          emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
          emitter.complete();
        } catch (Exception ignored) {
          /* emitter 已关闭 */
        }
      }
    }, "ai-stream-" + conversationId);
    worker.setDaemon(true);
    worker.start();
    return emitter;
  }

  @DeleteMapping("/{conversationId}")
  public Map<String, Object> remove(@PathVariable String conversationId,
      @CurrentUser UserContext user) {
    return aiChatService.deleteConversation(conversationId, user.id());
  }

  /** 应用草稿（幂等）：行程变更写入 TripPlaceService，记账写入 TripExpenseService。 */
  @PostMapping("/{conversationId}/apply/{messageId}")
  public Map<String, Object> apply(@PathVariable String conversationId,
      @PathVariable String messageId, @CurrentUser UserContext user) {
    return aiChatService.applyDraft(conversationId, messageId, user.id());
  }

  /** 放弃草稿。 */
  @PostMapping("/{conversationId}/discard/{messageId}")
  public Map<String, Object> discard(@PathVariable String conversationId,
      @PathVariable String messageId, @CurrentUser UserContext user) {
    return aiChatService.discardDraft(conversationId, messageId, user.id());
  }
}
