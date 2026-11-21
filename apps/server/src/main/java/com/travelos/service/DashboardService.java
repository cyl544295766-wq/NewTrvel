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

  /** 人民币累计口径：与 TravelStatsService.PRIMARY_CURRENCY 保持一致，仅精确等于 "CNY"。 */
  private static final String PRIMARY_CURRENCY = "CNY";

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

    // 一次批量取回用户全部关联行程，后续排序 / 组装 / 统计全部走内存 Map，避免逐条 selectById
    Map<String, Trip> tripMap = loadTrips(memberships);
    Map<String, User> userMapByPayer = new LinkedHashMap<>();

    // 最近行程：在用户全部可访问（未删除）的行程中完成排序后再截取展示数量。
    // 原实现先 `LIMIT 8`（无排序）再内存排序截3条——用户行程超过8个时，真正应展示的
    // 行程可能不在候选集中；这里直接复用上方已批量载入的 memberships + tripMap，不新增查询。
    List<TripMember> ranked = new ArrayList<>(memberships);
    ranked.sort((left, right) -> {
      Trip leftTrip = tripMap.get(left.getTripId());
      Trip rightTrip = tripMap.get(right.getTripId());
      // 1) 收藏优先（保持原语义）
      boolean leftFavorite = leftTrip != null && Boolean.TRUE.equals(leftTrip.getIsFavorite());
      boolean rightFavorite = rightTrip != null && Boolean.TRUE.equals(rightTrip.getIsFavorite());
      int byFavorite = Boolean.compare(rightFavorite, leftFavorite);
      if (byFavorite != 0) {
        return byFavorite;
      }
      // 2) 更新时间降序；为空视为最早（保持原语义，并修复原实现在 updatedAt 为 null 时的 NPE）
      Date leftKey = leftTrip == null || leftTrip.getUpdatedAt() == null
          ? new Date(0) : leftTrip.getUpdatedAt();
      Date rightKey = rightTrip == null || rightTrip.getUpdatedAt() == null
          ? new Date(0) : rightTrip.getUpdatedAt();
      int byUpdated = rightKey.compareTo(leftKey);
      if (byUpdated != 0) {
        return byUpdated;
      }
      // 3) 稳定兜底：更新时间相同（或都为空）→ 行程 id 降序（雪花 id 时间有序 ≈ 更晚创建在前），
      //    保证刷新后顺序不随机变化
      String leftId = left.getTripId() == null ? "" : left.getTripId();
      String rightId = right.getTripId() == null ? "" : right.getTripId();
      return rightId.compareTo(leftId);
    });
    List<Map<String, Object>> recentTrips = ranked.stream()
        .map(member -> tripMap.get(member.getTripId()))
        .filter(trip -> trip != null) // 批量载入缺失（如查询间隙被删）的行程不进结果、不输出空对象
        .limit(3)
        .map(this::tripResponse)
        .toList();

    List<TripMember> upcoming = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId)
        .inSql(TripMember::getTripId,
            "SELECT id FROM trips WHERE deleted_at IS NULL AND status <> 'archived' AND archived_at IS NULL AND start_date >= '"
                + localDateKey(today) + "'"));
    List<Map<String, Object>> upcomingTrips = upcoming.stream()
        .map(member -> tripMap.get(member.getTripId()))
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
      List<PackingList> packingLists = packingListMapper.selectList(new LambdaQueryWrapper<PackingList>()
          .in(PackingList::getTripId, tripIds));
      if (!packingLists.isEmpty()) {
        pendingPackingItemCount = packingItemMapper.selectCount(new LambdaQueryWrapper<PackingItem>()
            .in(PackingItem::getPackingListId,
                packingLists.stream().map(PackingList::getId).toList())
            .eq(PackingItem::getIsPacked, false));
      }
      for (TripExpense expense : expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
          .in(TripExpense::getTripId, tripIds))) {
        if (!PRIMARY_CURRENCY.equals(expense.getCurrency())) {
          // 口径与 TravelStatsService 一致（PRIMARY_CURRENCY.equals）：首页「仅统计 CNY 花费」——
          // 只有精确等于 "CNY" 才计入人民币累计；NULL/空串/外币不折算、不混入
          // （最近费用明细仍按原币透出真实金额与币种）。
          continue;
        }
        totalExpenseCents += toCents(expense.getAmount());
      }
      List<TripExpense> recentExpenseRows = expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
          .in(TripExpense::getTripId, tripIds)
          .orderByDesc(TripExpense::getSpentAt)
          .last("LIMIT 5"));
      List<String> payerIds = recentExpenseRows.stream()
          .map(TripExpense::getPayerUserId)
          .filter(java.util.Objects::nonNull)
          .distinct()
          .toList();
      if (!payerIds.isEmpty()) {
        for (User payer : userMapper.selectBatchIds(payerIds)) {
          userMapByPayer.put(payer.getId(), payer);
        }
      }
      recentExpenses = recentExpenseRows.stream()
          .map(expense -> expenseResponse(expense, tripMap, userMapByPayer))
          .toList();
      List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
          .in(Photo::getTripId, tripIds)
          .orderByDesc(Photo::getShotAt, Photo::getCreatedAt)
          .last("LIMIT 6"));
      for (Photo photo : photos) {
        Trip trip = tripMap.get(photo.getTripId());
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
            Trip trip = tripMap.get(document.getTripId());
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
            Trip trip = tripMap.get(journal.getTripId());
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
    // 分 → 展示金额：BigDecimal.valueOf(cents, 2) 精确构造，Fmt.money 统一 HALF_UP 两位小数
    //（与费用明细同一金额约定；不再经过 double 除法）
    stats.put("totalExpenseAmount",
        Fmt.money(java.math.BigDecimal.valueOf(totalExpenseCents, 2)));
    stats.put("totalDays", sumTripDays(tripMap));
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

  private Map<String, Object> expenseResponse(TripExpense expense, Map<String, Trip> tripMap,
      Map<String, User> payerMap) {
    Trip trip = tripMap.get(expense.getTripId());
    User payer = payerMap.get(expense.getPayerUserId());
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

  /**
   * BigDecimal → 分（long）：先按项目金额约定 HALF_UP 保留 2 位（同 {@link Fmt#money}），
   * 再取 unscaledValue 得到精确的最小货币单位——不使用 {@code doubleValue() * 100}，
   * 避免二进制浮点误差（0.1+0.2 类金额在分账累加中必须精确）。
   */
  private static long toCents(java.math.BigDecimal amount) {
    if (amount == null) {
      return 0;
    }
    return amount.setScale(2, java.math.RoundingMode.HALF_UP).unscaledValue().longValue();
  }

  private long sumTripDays(Map<String, Trip> tripMap) {
    long total = 0;
    for (Trip trip : tripMap.values()) {
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

  private Map<String, Trip> loadTrips(List<TripMember> memberships) {
    List<String> ids = memberships.stream().map(TripMember::getTripId).distinct().toList();
    if (ids.isEmpty()) {
      return new LinkedHashMap<>();
    }
    Map<String, Trip> map = new LinkedHashMap<>();
    for (Trip trip : tripMapper.selectBatchIds(ids)) {
      map.put(trip.getId(), trip);
    }
    return map;
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
