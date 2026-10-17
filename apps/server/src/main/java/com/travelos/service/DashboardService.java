package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.common.PhotoUtil;
import com.travelos.entity.Notification;
import com.travelos.entity.Photo;
import com.travelos.entity.PackingItem;
import com.travelos.entity.PackingList;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripJournal;
import com.travelos.entity.TripMember;
import com.travelos.entity.User;
import com.travelos.mapper.NotificationMapper;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@Service
public class DashboardService {

  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final UserMapper userMapper;
  private final TripExpenseMapper expenseMapper;
  private final PhotoMapper photoMapper;
  private final TravelDocumentMapper documentMapper;
  private final TripJournalMapper journalMapper;
  private final PackingListMapper packingListMapper;
  private final PackingItemMapper packingItemMapper;
  private final NotificationMapper notificationMapper;

  public DashboardService(TripMapper tripMapper, TripMemberMapper tripMemberMapper,
      UserMapper userMapper, TripExpenseMapper expenseMapper, PhotoMapper photoMapper,
      TravelDocumentMapper documentMapper, TripJournalMapper journalMapper,
      PackingListMapper packingListMapper, PackingItemMapper packingItemMapper,
      NotificationMapper notificationMapper) {
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.userMapper = userMapper;
    this.expenseMapper = expenseMapper;
    this.photoMapper = photoMapper;
    this.documentMapper = documentMapper;
    this.journalMapper = journalMapper;
    this.packingListMapper = packingListMapper;
    this.packingItemMapper = packingItemMapper;
    this.notificationMapper = notificationMapper;
  }

  public Map<String, Object> findOne(String userId) {
    Date today = startOfToday();
    Date reminderWindowEnd = endOfReminderWindow(today);

    List<TripMember> memberships = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId)
        .inSql(TripMember::getTripId, "SELECT id FROM trips WHERE deleted_at IS NULL"));

    List<TripMember> recent = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId)
        .inSql(TripMember::getTripId, "SELECT id FROM trips WHERE deleted_at IS NULL")
        .last("LIMIT 8"));
    recent.sort((left, right) -> {
      Trip leftTrip = tripMapper.selectById(left.getTripId());
      Trip rightTrip = tripMapper.selectById(right.getTripId());
      Date leftKey = leftTrip == null ? new Date(0) : leftTrip.getUpdatedAt();
      Date rightKey = rightTrip == null ? new Date(0) : rightTrip.getUpdatedAt();
      int byFavorite = Boolean.compare(
          rightTrip != null && Boolean.TRUE.equals(rightTrip.getIsFavorite()),
          leftTrip != null && Boolean.TRUE.equals(leftTrip.getIsFavorite()));
      return byFavorite != 0 ? byFavorite : rightKey.compareTo(leftKey);
    });
    List<Map<String, Object>> recentTrips = recent.stream().limit(3)
        .map(member -> tripResponse(tripMapper.selectById(member.getTripId())))
        .toList();

    List<TripMember> upcoming = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId)
        .inSql(TripMember::getTripId,
            "SELECT id FROM trips WHERE deleted_at IS NULL AND status <> 'archived' AND archived_at IS NULL AND start_date >= '"
                + localDateKey(today) + "'"));
    List<Map<String, Object>> upcomingTrips = upcoming.stream()
        .map(member -> tripMapper.selectById(member.getTripId()))
        .filter(trip -> trip != null && trip.getStartDate() != null)
        .sorted((left, right) -> left.getStartDate().compareTo(right.getStartDate()))
        .limit(3)
        .map(this::tripResponse)
        .toList();

    Long unreadCount = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, userId)
        .eq(Notification::getIsRead, false)
        .and(wrapper -> wrapper.isNull(Notification::getExpiresAt)
            .or()
            .ge(Notification::getExpiresAt, new Date())));

    List<String> tripIds = memberships.stream().map(TripMember::getTripId).toList();
    long pendingPackingItemCount = 0;
    long totalExpenseCents = 0;
    List<Map<String, Object>> recentExpenses = new ArrayList<>();
    List<Map<String, Object>> recentPhotos = new ArrayList<>();
    List<Map<String, Object>> upcomingDocuments = new ArrayList<>();
    List<Map<String, Object>> recentJournals = new ArrayList<>();

    if (!tripIds.isEmpty()) {
      for (PackingList list : packingListMapper.selectList(new LambdaQueryWrapper<PackingList>()
          .in(PackingList::getTripId, tripIds))) {
        pendingPackingItemCount += packingItemMapper.selectCount(new LambdaQueryWrapper<PackingItem>()
            .eq(PackingItem::getPackingListId, list.getId())
            .eq(PackingItem::getIsPacked, false));
      }
      for (TripExpense expense : expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
          .in(TripExpense::getTripId, tripIds))) {
        totalExpenseCents += Math.round(expense.getAmount().doubleValue() * 100);
      }
      recentExpenses = expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
          .in(TripExpense::getTripId, tripIds)
          .orderByDesc(TripExpense::getSpentAt)
          .last("LIMIT 5"))
          .stream()
          .map(this::expenseResponse)
          .toList();
      List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
          .in(Photo::getTripId, tripIds)
          .orderByDesc(Photo::getShotAt, Photo::getCreatedAt)
          .last("LIMIT 6"));
      for (Photo photo : photos) {
        Trip trip = tripMapper.selectById(photo.getTripId());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", photo.getId());
        map.put("tripId", photo.getTripId());
        map.put("tripTitle", trip == null ? null : trip.getTitle());
        try {
          map.put("thumbnailUrl", PhotoUtil.createThumbnail(photo.getUrl(),
              PhotoUtil.DEFAULT_THUMBNAIL_WIDTH));
        } catch (Exception ex) {
          map.put("thumbnailUrl", photo.getUrl());
        }
        map.put("alt", photo.getCaption() == null
            ? (trip == null ? "" : trip.getTitle())
            : photo.getCaption());
        recentPhotos.add(map);
      }
      upcomingDocuments = documentMapper.selectList(new LambdaQueryWrapper<TravelDocument>()
          .in(TravelDocument::getTripId, tripIds)
          .eq(TravelDocument::getIsReminder, true)
          .ge(TravelDocument::getExpiredAt, today)
          .le(TravelDocument::getExpiredAt, reminderWindowEnd)
          .orderByAsc(TravelDocument::getExpiredAt))
          .stream()
          .map(document -> {
            Trip trip = tripMapper.selectById(document.getTripId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", document.getId());
            map.put("tripId", document.getTripId());
            map.put("tripTitle", trip == null ? null : trip.getTitle());
            map.put("type", document.getType());
            map.put("title", document.getTitle());
            map.put("expiredAt", document.getExpiredAt());
            return map;
          })
          .toList();
      recentJournals = journalMapper.selectList(new LambdaQueryWrapper<TripJournal>()
          .in(TripJournal::getTripId, tripIds)
          .eq(TripJournal::getIsDraft, false)
          .orderByDesc(TripJournal::getCreatedAt)
          .last("LIMIT 3"))
          .stream()
          .map(journal -> {
            Trip trip = tripMapper.selectById(journal.getTripId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", journal.getId());
            map.put("tripId", journal.getTripId());
            map.put("tripTitle", trip == null ? null : trip.getTitle());
            map.put("title", journal.getTitle());
            map.put("summary", journal.getContent() == null ? ""
                : journal.getContent().substring(0, Math.min(100, journal.getContent().length())));
            map.put("createdAt", journal.getCreatedAt());
            return map;
          })
          .toList();
    }

    User user = userMapper.selectById(userId);
    Map<String, Object> userMap = new LinkedHashMap<>();
    userMap.put("id", userId);
    userMap.put("username", user == null ? null : user.getUsername());

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("tripCount", memberships.size());
    stats.put("totalExpenseAmount", String.format(java.util.Locale.ROOT, "%.2f",
        totalExpenseCents / 100.0));
    stats.put("totalDays", sumTripDays(tripIds));
    stats.put("pendingPackingItemCount", pendingPackingItemCount);
    stats.put("unreadNotificationCount", unreadCount);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", userMap);
    body.put("stats", stats);
    body.put("recentTrips", recentTrips);
    body.put("upcomingTrips", upcomingTrips);
    body.put("recentExpenses", recentExpenses);
    body.put("recentPhotos", recentPhotos);
    body.put("upcomingDocuments", upcomingDocuments);
    body.put("recentJournals", recentJournals);
    return body;
  }

  private Map<String, Object> tripResponse(Trip trip) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (trip == null) {
      return map;
    }
    map.put("id", trip.getId());
    map.put("title", trip.getTitle());
    map.put("destination", trip.getDestination());
    map.put("startDate", trip.getStartDate());
    map.put("endDate", trip.getEndDate());
    map.put("status", trip.getStatus());
    map.put("isFavorite", trip.getIsFavorite());
    map.put("updatedAt", trip.getUpdatedAt());
    return map;
  }

  private Map<String, Object> expenseResponse(TripExpense expense) {
    Trip trip = tripMapper.selectById(expense.getTripId());
    User payer = userMapper.selectById(expense.getPayerUserId());
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", expense.getId());
    map.put("tripId", expense.getTripId());
    map.put("tripTitle", trip == null ? null : trip.getTitle());
    map.put("title", expense.getTitle());
    map.put("amount", Fmt.money(expense.getAmount()));
    map.put("currency", expense.getCurrency());
    map.put("category", expense.getCategory());
    map.put("spentAt", expense.getSpentAt());
    map.put("createdAt", expense.getCreatedAt());
    map.put("payerDisplayName", payer == null ? null : payer.getDisplayName());
    return map;
  }

  private long sumTripDays(List<String> tripIds) {
    long total = 0;
    for (String tripId : tripIds) {
      Trip trip = tripMapper.selectById(tripId);
      if (trip == null || trip.getStartDate() == null || trip.getEndDate() == null) {
        continue;
      }
      Date start = DateUtil.utcDay(trip.getStartDate());
      Date end = DateUtil.utcDay(trip.getEndDate());
      long diff = Math.floorDiv(end.getTime() - start.getTime(), 86_400_000L);
      total += Math.max(diff, 0);
    }
    return total;
  }

  private Date startOfToday() {
    Calendar calendar = Calendar.getInstance();
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    return calendar.getTime();
  }

  /**
   * 本地时区下的 yyyy-MM-dd。行程 start_date 按 UTC 零点存储（DateUtil.parse 的约定），
   * 而“今天”的边界必须取本地日历日期，否则 UTC+8 用户在凌晨会得到前一天，导致“下一段旅程”多算一天。
   */
  private static String localDateKey(Date date) {
    return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
  }

  private Date endOfReminderWindow(Date today) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(today);
    calendar.add(Calendar.DATE, 30);
    calendar.set(Calendar.HOUR_OF_DAY, 23);
    calendar.set(Calendar.MINUTE, 59);
    calendar.set(Calendar.SECOND, 59);
    calendar.set(Calendar.MILLISECOND, 999);
    return calendar.getTime();
  }
}
