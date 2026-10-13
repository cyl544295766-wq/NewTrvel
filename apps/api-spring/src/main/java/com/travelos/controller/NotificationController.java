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

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
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
}
