package com.travelos.service;

import com.travelos.entity.Notification;
import com.travelos.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NotificationService {

  private final NotificationMapper notificationMapper;

  public NotificationService(NotificationMapper notificationMapper) {
    this.notificationMapper = notificationMapper;
  }

  public Map<String, Object> findAll(String userId, String pageValue, String pageSizeValue) {
    int page = toPositiveInteger(pageValue, 1);
    int pageSize = Math.min(toPositiveInteger(pageSizeValue, 20), 100);
    Date now = new Date();

    LambdaQueryWrapper<Notification> visible = new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .and(wrapper -> wrapper.isNull(Notification::getExpiresAt)
            .or()
            .ge(Notification::getExpiresAt, now));
    Long total = notificationMapper.selectCount(visible);

    List<Notification> notifications = notificationMapper.selectList(visible
        .orderByAsc(Notification::getIsRead)
        .orderByDesc(Notification::getCreatedAt)
        .last("LIMIT " + pageSize + " OFFSET " + (long) (page - 1) * pageSize));

    List<Map<String, Object>> items = notifications.stream().map(this::toResponse).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("notifications", items);
    body.put("total", total);
    body.put("page", page);
    body.put("pageSize", pageSize);
    body.put("totalPages", (int) Math.ceil((double) total / pageSize));
    return body;
  }

  public Map<String, Object> getUnreadCount(String userId) {
    Date now = new Date();
    Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getIsRead, false)
        .and(wrapper -> wrapper.isNull(Notification::getExpiresAt)
            .or()
            .ge(Notification::getExpiresAt, now)));
    return Map.of("count", count);
  }

  public Map<String, Object> markRead(String notificationId, String userId) {
    Notification notification = notificationMapper.selectById(notificationId);
    if (notification == null || !notification.getUserId().equals(userId)) {
      throw ApiException.notFound("通知不存在");
    }
    notification.setIsRead(true);
    notification.setReadAt(new Date());
    notificationMapper.updateById(notification);
    return Map.of("success", true);
  }

  public Map<String, Object> markAllRead(String userId) {
    List<Notification> unread = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getIsRead, false));
    Date now = new Date();
    for (Notification notification : unread) {
      notification.setIsRead(true);
      notification.setReadAt(now);
      notificationMapper.updateById(notification);
    }
    return Map.of("success", true, "count", unread.size());
  }

  public Map<String, Object> clearRead(String userId) {
    int count = notificationMapper.delete(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getIsRead, true));
    return Map.of("success", true, "count", count);
  }

  public void createMemberInvited(String userId, String tripId, String tripTitle, String role) {
    create(userId, "member_invited", "你已加入新的旅行",
        "你已被加入旅行「" + tripTitle + "」，角色为" + roleLabel(role) + "。",
        "/trips/" + tripId, Map.of("tripId", tripId, "role", role), "normal", null);
  }

  public void createRoleChanged(String userId, String tripId, String tripTitle, String role) {
    create(userId, "role_changed", "旅行角色已变更",
        "你在旅行「" + tripTitle + "」中的角色已变更为" + roleLabel(role) + "。",
        "/trips/" + tripId, Map.of("tripId", tripId, "role", role), "normal", null);
  }

  public void createOwnerTransferred(String userId, String tripId, String tripTitle) {
    create(userId, "role_changed", "你已成为旅行所有者",
        "旅行「" + tripTitle + "」的所有权已转移给你。",
        "/trips/" + tripId, Map.of("tripId", tripId, "role", "owner"), "normal", null);
  }

  /** 预订提醒（设计文档 3.3.3），发給行程成员。 */
  public void createBookingReminder(String userId, String tripId, String tripTitle,
      String content) {
    create(userId, "system", "预订提醒 · " + tripTitle, content,
        "/trips/" + tripId + "/vouchers", Map.of("tripId", tripId), "high", null);
  }

  /** 行前检查清单汇总通知（设计文档 3.3.3），precheckKey 用于同一天幂等去重。 */
  public void createTripPrecheck(String userId, String tripId, String precheckKey,
      String content) {
    create(userId, "system", "行前检查清单", content,
        "/trips/" + tripId + "/bookings", Map.of("tripId", tripId, "precheckKey", precheckKey),
        "high", null);
  }

  /** 行前检查清单幂等判断：同 key 的通知是否已存在。 */
  public boolean precheckAlreadySent(String userId, String precheckKey) {
    Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getType, "system")
        .apply("metadata->>'precheckKey' = {0}", precheckKey));
    return count != null && count > 0;
  }

  private void create(String userId, String type, String title, String content, String link,
      Map<String, Object> metadata, String priority, Date expiresAt) {
    Notification notification = new Notification();
    notification.setUserId(userId);
    notification.setType(type);
    notification.setTitle(title);
    notification.setContent(content);
    notification.setLink(link);
    notification.setMetadata(com.travelos.common.JsonUtil.write(metadata));
    notification.setIsRead(false);
    notification.setPriority(priority);
    notification.setExpiresAt(expiresAt);
    notificationMapper.insert(notification);
  }

  private Map<String, Object> toResponse(Notification notification) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", notification.getId());
    map.put("userId", notification.getUserId());
    map.put("type", notification.getType());
    map.put("title", notification.getTitle());
    map.put("content", notification.getContent());
    map.put("link", notification.getLink());
    map.put("metadata", notification.getMetadata() == null ? null
        : com.travelos.common.JsonUtil.readObject(notification.getMetadata()));
    map.put("isRead", notification.getIsRead());
    map.put("priority", notification.getPriority());
    map.put("readAt", notification.getReadAt());
    map.put("createdAt", notification.getCreatedAt());
    map.put("expiresAt", notification.getExpiresAt());
    return map;
  }

  private String roleLabel(String role) {
    return switch (role) {
      case "owner" -> "所有者";
      case "admin" -> "管理员";
      case "member" -> "成员";
      case "viewer" -> "观察者";
      default -> role;
    };
  }

  private int toPositiveInteger(String value, int fallback) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
