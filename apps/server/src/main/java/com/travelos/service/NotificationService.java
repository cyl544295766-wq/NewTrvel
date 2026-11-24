package com.travelos.service;

import com.travelos.entity.Notification;
import com.travelos.mapper.NotificationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.Pagination;
import com.travelos.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NotificationService {

  private final NotificationMapper notificationMapper;
  private final NotificationStreamService streamService;
  private final JdbcTemplate jdbc;

  public NotificationService(NotificationMapper notificationMapper,
      NotificationStreamService streamService, JdbcTemplate jdbc) {
    this.notificationMapper = notificationMapper;
    this.streamService = streamService;
    this.jdbc = jdbc;
  }

  private enum Preference {
    TRIP("notify_trip"), DOCUMENT("notify_document"), MEMBER("notify_member");
    private final String column;
    Preference(String column) { this.column = column; }
  }

  public Map<String, Object> findAll(String userId, String pageValue, String pageSizeValue) {
    int page = Pagination.positive(pageValue, 1);
    int pageSize = Math.min(Pagination.positive(pageSizeValue, 20), 100);
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
    // P2-4 口径补充：total 仅统计未过期通知（会随过期变少），totalUnfiltered 为该用户全部通知数，
    // 供需要稳定总数的调用方使用
    Long totalUnfiltered = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId));
    body.put("totalUnfiltered", totalUnfiltered);
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
    streamService.notifyUnreadChanged(userId, "read"); // P3-2
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
    streamService.notifyUnreadChanged(userId, "read_all"); // P3-2
    return Map.of("success", true, "count", unread.size());
  }

  public Map<String, Object> clearRead(String userId) {
    int count = notificationMapper.delete(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getIsRead, true));
    return Map.of("success", true, "count", count);
  }

  /** 删除单条通知（仅本人可删）。 */
  public Map<String, Object> delete(String notificationId, String userId) {
    Notification notification = notificationMapper.selectById(notificationId);
    if (notification == null || !notification.getUserId().equals(userId)) {
      throw ApiException.notFound("通知不存在");
    }
    notificationMapper.deleteById(notificationId);
    streamService.notifyUnreadChanged(userId, "deleted"); // P3-2
    return Map.of("success", true);
  }

  /** 好友请求：A 添加 B（尚未互为好友）时通知 B。 */
  public void createFriendRequest(String toUserId, String fromUserId, String fromDisplayName) {
    create(toUserId, "friend", "收到好友请求",
        fromDisplayName + " 想添加你为好友，回加后即可互为好友、一起记账。",
        "/friends/", Map.of("kind", "friend_request", "fromUserId", fromUserId), "normal", null);
  }

  /** 好友达成：一侧回加后通知另一侧。 */
  public void createFriendAccepted(String toUserId, String otherUserId, String otherDisplayName) {
    create(toUserId, "friend", "好友添加成功",
        "你与 " + otherDisplayName + " 已互为好友，现在可以互发私信、互相邀请进账本了。",
        "/friends/", Map.of("kind", "friend_accepted", "fromUserId", otherUserId), "normal", null);
  }

  @Transactional
  public void createMemberInvited(String userId, String tripId, String tripTitle, String role, String memberId) {
    createReminder(Preference.MEMBER, userId, "member:" + memberId, "member_invited", "你已加入新的旅行",
        "你已被加入旅行「" + tripTitle + "」，角色为" + roleLabel(role) + "。",
        "/trip-detail/?id=" + tripId, Map.of("tripId", tripId, "role", role), "normal");
  }

  public void createRoleChanged(String userId, String tripId, String tripTitle, String role) {
    create(userId, "role_changed", "旅行角色已变更",
        "你在旅行「" + tripTitle + "」中的角色已变更为" + roleLabel(role) + "。",
        "/trip-detail/?id=" + tripId, Map.of("tripId", tripId, "role", role), "normal", null);
  }

  public void createOwnerTransferred(String userId, String tripId, String tripTitle) {
    create(userId, "role_changed", "你已成为旅行所有者",
        "旅行「" + tripTitle + "」的所有权已转移给你。",
        "/trip-detail/?id=" + tripId, Map.of("tripId", tripId, "role", "owner"), "normal", null);
  }

  /** 预订提醒（设计文档 3.3.3），发給行程成员。 */
  @Transactional
  public void createBookingReminder(String userId, String tripId, String tripTitle,
      String bookingId, long startsAt, String content) {
    createReminder(Preference.TRIP, userId, "booking:" + bookingId + ":" + startsAt,
        "system", "预订提醒 · " + tripTitle, content,
        "/bookings/?trip=" + tripId + "&booking=" + bookingId,
        Map.of("tripId", tripId, "bookingId", bookingId, "kind", "booking_reminder"), "high");
  }

  @Transactional
  public void createDocumentExpiry(String userId, String tripId, String documentId, String title, String expiry) {
    createReminder(Preference.DOCUMENT, userId, "document:" + documentId + ":" + expiry,
        "system", "证件即将到期", "「" + title + "」有效期至 " + expiry + "，请核对出行所需证件。",
        "/documents/?trip=" + tripId + "&document=" + documentId,
        Map.of("tripId", tripId, "documentId", documentId, "kind", "document_expiry"), "high");
  }

  /** 行前检查清单汇总通知（设计文档 3.3.3），precheckKey 用于同一天幂等去重。 */
  @Transactional
  public void createTripPrecheck(String userId, String tripId, String precheckKey,
      String content) {
    createReminder(Preference.TRIP, userId, "precheck:" + precheckKey, "system", "行前检查清单", content,
        "/trip-detail/?id=" + tripId + "#journey-preparation",
        Map.of("tripId", tripId, "precheckKey", precheckKey, "kind", "trip_precheck"), "high");
  }

  /** 行前检查清单幂等判断：同 key 的通知是否已存在。 */
  public boolean precheckAlreadySent(String userId, String precheckKey) {
    Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getType, "system")
        .apply("metadata->>'precheckKey' = {0}", precheckKey));
    return count != null && count > 0;
  }

  /** 结算确认通知（设计文档 3.4.3），发给全部行程成员。 */
  public void createSettlementConfirmed(String userId, String tripId, String tripTitle,
      String content) {
    create(userId, "system", "账单已确认 · " + tripTitle, content,
        "/expenses/?trip=" + tripId + "&tab=settlement", Map.of("tripId", tripId, "kind", "settlement_confirmed"),
        "normal", null);
  }

  /** 预算提醒去重：同一行程同一档位（warn ≥80% / over ≥100%）只发一次。 */
  public boolean budgetWarningAlreadySent(String userId, String tripId, String level) {
    Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .apply("metadata->>'kind' = 'budget_warning'")
        .apply("metadata->>'tripId' = {0}", tripId)
        .apply("metadata->>'level' = {0}", level));
    return count != null && count > 0;
  }

  /** 天气提醒（调度器主动）：出发倒计时/明日遇雨等，weatherKey 用于幂等去重。 */
  @Transactional
  public void createWeatherAlert(String userId, String tripId, String tripTitle,
      String content, String weatherKey) {
    createReminder(Preference.TRIP, userId, "weather:" + weatherKey, "system", "天气提醒 · " + tripTitle, content,
        "/itinerary/?trip=" + tripId,
        Map.of("tripId", tripId, "kind", "weather_alert", "weatherKey", weatherKey),
        "high");
  }

  /** 天气提醒幂等判断：同一 user + weatherKey 只发一次。 */
  public boolean weatherAlertAlreadySent(String userId, String weatherKey) {
    Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .apply("metadata->>'kind' = 'weather_alert'")
        .apply("metadata->>'weatherKey' = {0}", weatherKey));
    return count != null && count > 0;
  }

  /** 预算提醒（AI 主动）：总预算使用率到 80% 预警、到 100% 超支。 */
  public void createBudgetWarning(String userId, String tripId, String tripTitle,
      String content, String level) {
    create(userId, "system", "🤖 预算提醒 · " + tripTitle, content,
        "/trips/" + tripId + "/expenses",
        Map.of("tripId", tripId, "kind", "budget_warning", "level", level),
        "high", null);
  }

  private void createReminder(Preference preference, String userId, String eventKey, String type,
      String title, String content, String link, Map<String, Object> metadata, String priority) {
    int claimed = jdbc.update("INSERT INTO notification_event_receipts (user_id, event_key) VALUES (?, ?) "
        + "ON CONFLICT (user_id, event_key) DO NOTHING", userId, eventKey);
    if (claimed == 0) return;
    Boolean enabled = jdbc.queryForObject("SELECT COALESCE((SELECT " + preference.column
        + " FROM user_preferences WHERE user_id = ?), TRUE)", Boolean.class, userId);
    if (Boolean.TRUE.equals(enabled)) create(userId, type, title, content, link, metadata, priority, null);
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
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          streamService.notifyUnreadChanged(userId, type);
        }
      });
    } else {
      streamService.notifyUnreadChanged(userId, type);
    }
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

}
