package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.Photo;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.entity.User;
import com.travelos.entity.UserFriend;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.mapper.UserFriendMapper;
import com.travelos.mapper.UserMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 攻略广场：
 *   GET  /api/square                 公开攻略列表（keyword/destination/sort/page）
 *   GET  /api/square?scope=friends    好友可见攻略列表（互为好友）
 *   GET  /api/square/facets          目的地筛选项
 *   GET  /api/square/{tripId}        攻略详情（行程天 + 地点概览）
 *   POST /api/square/{tripId}/copy   一键复制为我的行程（副本默认私有、记录来源）
 *
 * 可见性规则与攻略（游记）一致：public 所有人可见，friends 好友可见，private 仅作者。
 * 浏览量只对非作者且不持有编辑权的查看 +1；复制成功后源行程 copy_count +1。
 */
@Service
public class TripSquareService {

  public static final Set<String> VISIBILITIES = Set.of("public", "friends", "private");

  private static final Set<String> SORTS = Set.of("latest", "hot", "copies");

  private final TripMapper tripMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberMapper tripMemberMapper;
  private final PhotoMapper photoMapper;
  private final UserMapper userMapper;
  private final UserFriendMapper userFriendMapper;
  private final FriendService friendService;
  private final TripService tripService;
  private final TripMemberService tripMemberService;

  public TripSquareService(TripMapper tripMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberMapper tripMemberMapper,
      PhotoMapper photoMapper, UserMapper userMapper, UserFriendMapper userFriendMapper,
      FriendService friendService, TripService tripService, TripMemberService tripMemberService) {
    this.tripMapper = tripMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.photoMapper = photoMapper;
    this.userMapper = userMapper;
    this.userFriendMapper = userFriendMapper;
    this.friendService = friendService;
    this.tripService = tripService;
    this.tripMemberService = tripMemberService;
  }

  /** 归一化可见性（供 TripService.create 复用）：非法或缺省一律 private */
  public static String normalizedVisibility(Object value) {
    String text = value == null ? "" : value.toString().trim();
    return VISIBILITIES.contains(text) ? text : "private";
  }

  // ==================== 广场列表 ====================

  @Transactional(readOnly = true)
  public Map<String, Object> square(String userId, Map<String, Object> query) {
    String scope = query.get("scope") == null ? "public" : query.get("scope").toString().trim();
    if (!Set.of("public", "friends").contains(scope)) {
      throw ApiException.badRequest("scope 只能是 public 或 friends");
    }
    int page = parsePositive(query.get("page"), 1);
    int pageSize = Math.min(parsePositive(query.get("pageSize"), 9), 24);
    String rawSort = string(query.get("sort"));
    String sort = rawSort != null && SORTS.contains(rawSort) ? rawSort : "latest";
    String keyword = string(query.get("keyword"));
    String destination = string(query.get("destination"));

    if ("friends".equals(scope)) {
      return friendsSquare(userId, page, pageSize, destination);
    }

    return bypassed(() -> {
      LambdaQueryWrapper<Trip> query2 = new LambdaQueryWrapper<Trip>()
          .eq(Trip::getVisibility, "public")
          .isNull(Trip::getDeletedAt)
          .ne(Trip::getStatus, "archived")
          .isNotNull(Trip::getPublishedAt);
      if (keyword != null && !keyword.isBlank()) {
        query2.and(w -> w.like(Trip::getTitle, keyword).or().like(Trip::getDescription, keyword)
            .or().like(Trip::getDestination, keyword));
      }
      if (destination != null && !destination.isBlank()) {
        query2.like(Trip::getDestination, destination);
      }

      Long total = tripMapper.selectCount(query2);
      long offset = (long) (page - 1) * pageSize;
      List<Trip> trips = new ArrayList<>();
      if (total == null || offset < total) {
        if ("hot".equals(sort)) {
          query2.last("ORDER BY view_count + copy_count * 5 DESC, updated_at DESC LIMIT " + pageSize + " OFFSET " + offset);
        } else if ("copies".equals(sort)) {
          query2.last("ORDER BY copy_count DESC, view_count DESC LIMIT " + pageSize + " OFFSET " + offset);
        } else {
          query2.last("ORDER BY published_at DESC, updated_at DESC LIMIT " + pageSize + " OFFSET " + offset);
        }
        trips = tripMapper.selectList(query2);
      }

      // 解析「我已复制过」的行程
      Set<String> copiedSourceIds = new HashSet<>();
      if (!trips.isEmpty()) {
        List<String> sourceIds = trips.stream().map(Trip::getId).toList();
        List<Trip> copies = tripMapper.selectList(new LambdaQueryWrapper<Trip>()
            .in(Trip::getSourceTripId, sourceIds)
            .eq(Trip::getOwnerId, userId));
        for (Trip copy : copies) {
          copiedSourceIds.add(copy.getSourceTripId());
        }
      }

      Map<String, Object> body = new LinkedHashMap<>();
      body.put("items", cardsOf(trips, userId, copiedSourceIds));
      body.put("page", page);
      body.put("pageSize", pageSize);
      body.put("total", total == null ? 0 : total);
      body.put("hasMore", offset + trips.size() < (total == null ? 0 : total));
      body.put("sort", sort);
      body.put("friendCount", countFriendVisibleTrips(userId));
      return body;
    });
  }

  /** 好友可见攻略：互为好友用户的 friends 行程 + 自己的公开/好友行程（便于作者管理） */
  private Map<String, Object> friendsSquare(String userId, int page, int pageSize, String destination) {
    return bypassed(() -> {
      List<String> friendIds = mutualFriendIds(userId);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("page", page);
      body.put("pageSize", pageSize);
      body.put("friendCount", friendIds.size());
      if (friendIds.isEmpty() && !hasOwnVisibleTrips(userId)) {
        body.put("items", new ArrayList<>());
        body.put("total", 0);
        body.put("hasMore", false);
        return body;
      }

      LambdaQueryWrapper<Trip> query = new LambdaQueryWrapper<Trip>()
          .isNull(Trip::getDeletedAt)
          .ne(Trip::getStatus, "archived")
          .and(w -> w
              .and(x -> x.in(Trip::getOwnerId, friendIds.isEmpty() ? List.of("") : friendIds)
                  .eq(Trip::getVisibility, "friends"))
              .or(x -> x.eq(Trip::getOwnerId, userId).ne(Trip::getVisibility, "private")));
      if (destination != null && !destination.isBlank()) {
        query.like(Trip::getDestination, destination);
      }
      Long total = tripMapper.selectCount(query);
      long offset = (long) (page - 1) * pageSize;
      List<Trip> trips = new ArrayList<>();
      if (total == null || offset < total) {
        query.last("ORDER BY updated_at DESC LIMIT " + pageSize + " OFFSET " + offset);
        trips = tripMapper.selectList(query);
      }
      body.put("items", cardsOf(trips, userId, Set.of()));
      body.put("total", total == null ? 0 : total);
      body.put("hasMore", offset + trips.size() < (total == null ? 0 : total));
      return body;
    });
  }

  /** 目的地筛选项：公开行程涉及到的目的地（按出现次数排序） */
  @Transactional(readOnly = true)
  public Map<String, Object> facets() {
    return bypassed(() -> {
      List<Trip> trips = tripMapper.selectList(new LambdaQueryWrapper<Trip>()
          .select(Trip::getDestination)
          .eq(Trip::getVisibility, "public")
          .isNull(Trip::getDeletedAt)
          .ne(Trip::getStatus, "archived"));
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Trip trip : trips) {
        String destination = trip.getDestination() == null ? null : trip.getDestination().trim();
        if (destination == null || destination.isEmpty()) {
          continue;
        }
        counts.merge(destination, 1, Integer::sum);
      }
      List<Map<String, Object>> list = new ArrayList<>();
      counts.entrySet().stream()
          .sorted((left, right) -> right.getValue() - left.getValue())
          .forEach(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("destination", entry.getKey());
            item.put("count", entry.getValue());
            list.add(item);
          });
      return Map.of("destinations", list);
    });
  }

  // ==================== 详情 ====================

  /** 单篇攻略详情：只要求「可见」，不要求是行程成员 */
  public Map<String, Object> detail(String tripId, String userId) {
    Trip trip = bypassed(() -> tripMapper.selectById(tripId));
    if (trip == null || trip.getDeletedAt() != null) {
      throw ApiException.notFound("行程不存在");
    }
    if (!canView(trip, userId)) {
      throw ApiException.forbidden("没有权限查看这篇攻略");
    }
    boolean isOwner = trip.getOwnerId() != null && trip.getOwnerId().equals(userId);
    if (!isOwner) {
      // 浏览量：非作者查看（公开或好友可见，无权者被拦截在外）时 +1。
      // 计数自增必须 bypass 行权限：非成员用户（如广场游客）按成员子查询会匹配 0 行而静默失效
      bypassed(() -> tripMapper.update(null, new LambdaUpdateWrapper<Trip>()
          .eq(Trip::getId, trip.getId())
          .setSql("view_count = view_count + 1")));
      trip.setViewCount(trip.getViewCount() == null ? 1 : trip.getViewCount() + 1);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    boolean copiedByMe = tripMapper.selectCount(new LambdaQueryWrapper<Trip>()
        .eq(Trip::getSourceTripId, trip.getId())
        .eq(Trip::getOwnerId, userId)) > 0;
    Map<String, Object> summary = summaryCard(trip, userId);
    summary.put("copiedByMe", copiedByMe);
    body.put("square", summary);
    body.put("canEdit", isOwner);
    body.put("canCopy", canCopy(trip, userId));

    List<TripDay> days = bypassed(() -> tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, trip.getId())
        .orderByAsc(TripDay::getDayIndex)));
    List<Map<String, Object>> dayList = new ArrayList<>();
    for (TripDay day : days) {
      Map<String, Object> dayMap = new LinkedHashMap<>();
      dayMap.put("dayIndex", day.getDayIndex());
      dayMap.put("date", day.getDate());
      dayMap.put("title", day.getTitle());
      List<TripPlace> places = bypassed(() -> tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
          .eq(TripPlace::getTripDayId, day.getId())
          .orderByAsc(TripPlace::getSortOrder)));
      List<Map<String, Object>> placeList = new ArrayList<>();
      for (TripPlace place : places) {
        Map<String, Object> placeMap = new LinkedHashMap<>();
        placeMap.put("id", place.getId());
        placeMap.put("name", place.getName());
        placeMap.put("type", place.getType());
        placeMap.put("address", place.getAddress());
        placeMap.put("sortOrder", place.getSortOrder());
        placeMap.put("latitude", place.getLatitude() == null ? "" : Fmt.coord(place.getLatitude()));
        placeMap.put("longitude", place.getLongitude() == null ? "" : Fmt.coord(place.getLongitude()));
        placeList.add(placeMap);
      }
      dayMap.put("places", placeList);
      dayList.add(dayMap);
    }
    Map<String, Object> overview = new LinkedHashMap<>();
    overview.put("dayCount", dayList.size());
    overview.put("days", dayList);
    body.put("overview", overview);
    return body;
  }

  /** 是否可见：作者、同行成员、公开、（好友可见且互为好友） */
  private boolean canView(Trip trip, String userId) {
    if (trip.getOwnerId() == null || userId == null) {
      return false;
    }
    if (trip.getOwnerId().equals(userId)) {
      return true;
    }
    if (tripMemberMapper.selectCount(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, trip.getId())
        .eq(TripMember::getUserId, userId)) > 0) {
      return true;
    }
    String visibility = effectiveVisibility(trip);
    if ("public".equals(visibility)) {
      return true;
    }
    return "friends".equals(visibility) && friendService.isMutualFriend(trip.getOwnerId(), userId);
  }

  private boolean canCopy(Trip trip, String userId) {
    if (userId == null || trip.getOwnerId() == null) {
      return false;
    }
    if (trip.getOwnerId().equals(userId)) {
      return false; // 作者自己复制自己请走行程页「副本」
    }
    String visibility = effectiveVisibility(trip);
    if ("public".equals(visibility)) {
      return true;
    }
    return "friends".equals(visibility) && friendService.isMutualFriend(trip.getOwnerId(), userId);
  }

  private String effectiveVisibility(Trip trip) {
    return trip.getVisibility() == null ? "private" : trip.getVisibility();
  }

  // ==================== 可见性 ====================

  /** 修改可见性：仅行程 owner。首次公开时记录 publishedAt。 */
  @Transactional
  public Map<String, Object> setVisibility(String tripId, String userId, String visibility) {
    String normalized = VISIBILITIES.contains(visibility == null ? "" : visibility.trim()) ? visibility.trim() : null;
    if (normalized == null) {
      throw ApiException.badRequest("visibility 只能是 public、friends 或 private");
    }
    // 行权限下非成员查不到该行程，先绕过校验存在性，再由 owner 检查给出 403
    Trip trip = bypassed(() -> tripService.findLive(tripId));
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    if (!trip.getOwnerId().equals(userId)) {
      throw ApiException.forbidden("只有行程创建者可以修改可见性");
    }
    trip.setVisibility(normalized);
    if ("public".equals(normalized) && trip.getPublishedAt() == null) {
      trip.setPublishedAt(new Date());
    }
    trip.setUpdatedAt(new Date());
    tripMapper.updateById(trip);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trip", tripService.toTripResponse(trip, TripPermission.OWNER, null));
    return body;
  }

  // ==================== 复制 ====================

  /** 复制攻略为我的行程：先校验可见性再做；副本默认私有、记录来源。 */
  @Transactional
  public Map<String, Object> copy(String sourceId, String userId) {
    Trip source = bypassed(() -> tripMapper.selectById(sourceId));
    if (source == null || source.getDeletedAt() != null) {
      throw ApiException.notFound("行程不存在");
    }
    if (!canCopy(source, userId)) {
      throw ApiException.forbidden("无权复制该攻略");
    }

    List<TripDay> sourceDays = bypassed(() -> tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, source.getId())
        .orderByAsc(TripDay::getDayIndex)));
    List<TripPlace> sourcePlaces = bypassed(() -> tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, source.getId())
        .orderByAsc(TripPlace::getSortOrder)));

    Trip trip = new Trip();
    trip.setTitle(source.getTitle() + " 副本");
    trip.setDescription(source.getDescription());
    trip.setDestination(source.getDestination());
    trip.setStartDate(source.getStartDate());
    trip.setEndDate(source.getEndDate());
    trip.setStatus("draft");
    trip.setCoverImageUrl(source.getCoverImageUrl());
    trip.setOwnerId(userId);
    trip.setIsFavorite(false);
    trip.setVisibility("private");
    trip.setViewCount(0);
    trip.setCopyCount(0);
    trip.setSourceTripId(source.getId());
    tripMapper.insert(trip);
    tripMemberService.createOwner(trip.getId(), userId);

    Map<String, String> dayIdMap = new HashMap<>();
    for (TripDay day : sourceDays) {
      TripDay newDay = new TripDay();
      newDay.setTripId(trip.getId());
      newDay.setDate(day.getDate());
      newDay.setDayIndex(day.getDayIndex());
      newDay.setTitle(day.getTitle());
      newDay.setSummary(day.getSummary());
      newDay.setCreatedAt(new Date());
      newDay.setUpdatedAt(new Date());
      tripDayMapper.insert(newDay);
      dayIdMap.put(day.getId(), newDay.getId());
    }
    for (TripPlace place : sourcePlaces) {
      TripPlace newPlace = new TripPlace();
      newPlace.setTripId(trip.getId());
      newPlace.setTripDayId(place.getTripDayId() == null ? null : dayIdMap.get(place.getTripDayId()));
      newPlace.setName(place.getName());
      newPlace.setType(place.getType());
      newPlace.setAddress(place.getAddress());
      newPlace.setLatitude(place.getLatitude());
      newPlace.setLongitude(place.getLongitude());
      newPlace.setNotes(place.getNotes());
      newPlace.setSortOrder(place.getSortOrder());
      newPlace.setIsCompleted(false);
      newPlace.setCreatedAt(new Date());
      newPlace.setUpdatedAt(new Date());
      tripPlaceMapper.insert(newPlace);
    }

    // 源行程复制数 +1（bypass 行权限：复制者不是源行程成员）
    bypassed(() -> tripMapper.update(null, new LambdaUpdateWrapper<Trip>()
        .eq(Trip::getId, source.getId())
        .setSql("copy_count = copy_count + 1")));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trip", tripService.toTripResponse(trip, TripPermission.OWNER, null));
    body.put("sourceTitle", source.getTitle());
    return body;
  }

  // ==================== 卡片组装 ====================

  private List<Map<String, Object>> cardsOf(List<Trip> trips, String viewerId, Set<String> copiedSourceIds) {
    List<Map<String, Object>> cards = new ArrayList<>();
    for (Trip trip : trips) {
      Map<String, Object> card = summaryCard(trip, viewerId);
      card.put("copiedByMe", copiedSourceIds.contains(trip.getId()));
      cards.add(card);
    }
    return cards;
  }

  private Map<String, Object> summaryCard(Trip trip, String viewerId) {
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("id", trip.getId());
    card.put("title", trip.getTitle());
    card.put("description", trip.getDescription());
    card.put("destination", trip.getDestination());
    card.put("startDate", trip.getStartDate());
    card.put("endDate", trip.getEndDate());
    card.put("status", trip.getStatus());
    card.put("budget", Fmt.money(trip.getBudget()));
    card.put("coverImageUrl", trip.getCoverImageUrl());
    card.put("visibility", effectiveVisibility(trip));
    card.put("viewCount", trip.getViewCount() == null ? 0 : trip.getViewCount());
    card.put("copyCount", trip.getCopyCount() == null ? 0 : trip.getCopyCount());
    card.put("heat", viewWeight(trip) + copyWeight(trip));
    card.put("publishedAt", trip.getPublishedAt());
    card.put("updatedAt", trip.getUpdatedAt());
    card.put("sourceTripId", trip.getSourceTripId());
    card.put("isOwner", viewerId != null && viewerId.equals(trip.getOwnerId()));
    card.put("canCopy", canCopy(trip, viewerId));

    long days = 0;
    if (trip.getStartDate() != null && trip.getEndDate() != null) {
      days = DateUtil.dayRange(trip.getStartDate(), trip.getEndDate()).size();
    }
    card.put("dayCount", days);

    // 封面：精选照片优先
    Photo cover = bypassed(() -> photoMapper.selectOne(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, trip.getId())
        .eq(Photo::getIsCover, true)
        .orderByDesc(Photo::getUpdatedAt)
        .last("LIMIT 1")));
    if (cover != null && cover.getUrl() != null && !cover.getUrl().isBlank()) {
      card.put("coverImageUrl", cover.getUrl());
    }

    // 作者
    User owner = bypassed(() -> userMapper.selectById(trip.getOwnerId()));
    Map<String, Object> author = new LinkedHashMap<>();
    if (owner != null) {
      author.put("id", owner.getId());
      author.put("username", owner.getUsername());
      author.put("displayName", owner.getDisplayName());
      author.put("avatarUrl", owner.getAvatarUrl());
      author.put("initial", (owner.getDisplayName() == null || owner.getDisplayName().isBlank()
          ? owner.getUsername() : owner.getDisplayName()).trim().substring(0, 1).toUpperCase());
    } else {
      author.put("displayName", "旅行者");
      author.put("initial", "旅");
    }
    card.put("author", author);
    return card;
  }

  private int viewWeight(Trip trip) {
    return trip.getViewCount() == null ? 0 : trip.getViewCount();
  }

  private int copyWeight(Trip trip) {
    return (trip.getCopyCount() == null ? 0 : trip.getCopyCount()) * 5;
  }

  /** 互为好友的用户 ID 列表（双向都有记录才算） */
  private List<String> mutualFriendIds(String userId) {
    List<UserFriend> outgoing = userFriendMapper.selectList(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getUserId, userId));
    List<UserFriend> incoming = userFriendMapper.selectList(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getFriendId, userId));
    Set<String> incomingIds = new HashSet<>();
    for (UserFriend row : incoming) {
      incomingIds.add(row.getUserId());
    }
    List<String> mutual = new ArrayList<>();
    for (UserFriend row : outgoing) {
      if (incomingIds.contains(row.getFriendId())) {
        mutual.add(row.getFriendId());
      }
    }
    return mutual;
  }

  /** 是否有"我的"非私有行程需要并入好友页签 */
  private boolean hasOwnVisibleTrips(String userId) {
    Long count = tripMapper.selectCount(new LambdaQueryWrapper<Trip>()
        .eq(Trip::getOwnerId, userId)
        .ne(Trip::getVisibility, "private")
        .isNull(Trip::getDeletedAt)
        .ne(Trip::getStatus, "archived"));
    return count != null && count > 0;
  }

  private long countFriendVisibleTrips(String userId) {
    List<String> friendIds = bypassed(() -> mutualFriendIds(userId));
    if (friendIds.isEmpty()) {
      return 0;
    }
    Long count = bypassed(() -> tripMapper.selectCount(new LambdaQueryWrapper<Trip>()
        .in(Trip::getOwnerId, friendIds)
        .eq(Trip::getVisibility, "friends")
        .isNull(Trip::getDeletedAt)
        .ne(Trip::getStatus, "archived")));
    return count == null ? 0 : count;
  }

  private <T> T bypassed(Supplier<T> action) {
    boolean prev = TripScopeContext.isBypassed();
    TripScopeContext.setBypassed(true);
    try {
      return action.get();
    } finally {
      TripScopeContext.setBypassed(prev);
    }
  }

  private int parsePositive(Object value, int fallback) {
    try {
      int parsed = Integer.parseInt(value == null ? "" : value.toString().trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private String string(Object value) {
    return value == null ? null : value.toString().trim();
  }
}
