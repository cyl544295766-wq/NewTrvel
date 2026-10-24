package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.NotificationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

  private final NotificationService notificationService;
  private final com.travelos.service.NotificationStreamService streamService;

  public NotificationController(NotificationService notificationService,
      com.travelos.service.NotificationStreamService streamService) {
    this.notificationService = notificationService;
    this.streamService = streamService;
  }

  @GetMapping
  public Map<String, Object> findAll(
      @RequestParam(value = "page", required = false) String page,
      @RequestParam(value = "pageSize", required = false) String pageSize,
      @CurrentUser UserContext user) {
    return notificationService.findAll(user.id(), page, pageSize);
  }

  @GetMapping("/unread-count")
  public Map<String, Object> unreadCount(@CurrentUser UserContext user) {
    return notificationService.getUnreadCount(user.id());
  }

  /** P3-2 通知实时流（SSE）：连接即推一次当前未读数，后续未读变化时推 "unread" 事件。 */
  @org.springframework.web.bind.annotation.GetMapping("/stream")
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
      @CurrentUser UserContext user) {
    var emitter = streamService.subscribe(user.id());
    try {
      emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
          .name("unread")
          .data(notificationService.getUnreadCount(user.id())));
    } catch (Exception ignored) {
      // 首推失败无碍，客户端有轮询兜底
    }
    return emitter;
  }

  @PatchMapping("/{notificationId}/read")
  public Map<String, Object> markRead(@PathVariable String notificationId,
      @CurrentUser UserContext user) {
    return notificationService.markRead(notificationId, user.id());
  }

  @PostMapping("/read-all")
  public Map<String, Object> markAllRead(@CurrentUser UserContext user) {
    return notificationService.markAllRead(user.id());
  }

  @DeleteMapping("/clear-read")
  public Map<String, Object> clearRead(@CurrentUser UserContext user) {
    return notificationService.clearRead(user.id());
  }

  /** 删除单条通知（仅本人可删）。 */
  @DeleteMapping("/{notificationId}")
  public Map<String, Object> delete(@PathVariable String notificationId,
      @CurrentUser UserContext user) {
    return notificationService.delete(notificationId, user.id());
  }
}
