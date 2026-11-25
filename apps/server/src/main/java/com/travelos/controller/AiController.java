package com.travelos.controller;

import com.travelos.ai.AiChatService;
import com.travelos.ai.AiReceiptService;
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
  private final AiReceiptService receipts;

  public AiController(AiChatService aiChatService, AiReceiptService receipts) {
    this.aiChatService = aiChatService;
    this.receipts = receipts;
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
   * 流式发送（SSE）：delta 增量、结束时 done 事件（与非流式同形状）、异常 error 事件。
   *
   * <p>限流/幂等准入在<b>返回响应之前</b>由 {@link AiChatService#startStream} 同步完成：
   * 被拒绝时抛 429（统一 JSON + Retry-After），不会先 200 再用 error 事件表达。
   * 执行交给有界线程池（AiTaskManager），不再创建裸线程；客户端断开经 SSE 回调传播取消。</p>
   */
  @PostMapping("/{conversationId}/messages/stream")
  public SseEmitter sendMessageStream(@PathVariable String conversationId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return aiChatService.startStream(conversationId, user.id(), dto);
  }

  @DeleteMapping("/{conversationId}")
  public Map<String, Object> remove(@PathVariable String conversationId,
      @CurrentUser UserContext user) {
    return aiChatService.deleteConversation(conversationId, user.id());
  }

  @PostMapping("/{conversationId}/receipts")
  public Map<String, Object> recognizeReceipt(@PathVariable String conversationId,
      @RequestBody Map<String, Object> image, @CurrentUser UserContext user) {
    return receipts.recognize(conversationId, user, image);
  }

  @PostMapping("/{conversationId}/receipts/{messageId}/confirm")
  public Map<String, Object> confirmReceipt(@PathVariable String conversationId, @PathVariable String messageId,
      @RequestBody Map<String, Object> fields, @CurrentUser UserContext user) {
    return receipts.confirm(conversationId, messageId, user.id(), fields);
  }

  /** 应用草稿（幂等）。打包草稿另带用户选择的清单、物品和数量。 */
  @PostMapping("/{conversationId}/apply/{messageId}")
  public Map<String, Object> apply(@PathVariable String conversationId,
      @PathVariable String messageId, @RequestBody(required = false) Map<String, Object> choices,
      @CurrentUser UserContext user) {
    return aiChatService.applyDraft(conversationId, messageId, user.id(), choices == null ? Map.of() : choices);
  }

  /** 放弃草稿。 */
  @PostMapping("/{conversationId}/discard/{messageId}")
  public Map<String, Object> discard(@PathVariable String conversationId,
      @PathVariable String messageId, @CurrentUser UserContext user) {
    return aiChatService.discardDraft(conversationId, messageId, user.id());
  }
}
