package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Booking;
import com.travelos.entity.Photo;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@Service
public class TripService {

  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final TripMemberService tripMemberService;
  private final PhotoMapper photoMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final BookingMapper bookingMapper;

  public TripService(TripMapper tripMapper, TripMemberMapper tripMemberMapper,
      TripMemberService tripMemberService, PhotoMapper photoMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, BookingMapper bookingMapper) {
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.tripMemberService = tripMemberService;
    this.photoMapper = photoMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.bookingMapper = bookingMapper;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> dto, String userId) {
    String title = string(dto.get("title"));
    if (title == null || title.isBlank()) {
      throw ApiException.badRequest("title should not be empty");
    }
    validateDateRange(dto.get("startDate"), dto.get("endDate"));

    Trip trip = new Trip();
    trip.setTitle(title.trim());
    trip.setDescription(toNullable(dto.get("description")));
    trip.setDestination(toNullable(dto.get("destination")));
    trip.setStartDate(toDateOrNull(dto.get("startDate")));
    trip.setEndDate(toDateOrNull(dto.get("endDate")));
    trip.setStatus("draft");
    trip.setCoverImageUrl(toNullable(dto.get("coverImageUrl")));
    trip.setOwnerId(userId);
    trip.setIsFavorite(false);
    String visibility = TripSquareService.normalizedVisibility(dto.get("visibility"));
    trip.setVisibility(visibility);
    if ("public".equals(visibility)) {
      trip.setPublishedAt(new Date());
    }
    trip.setViewCount(0);
    trip.setCopyCount(0);
    tripMapper.insert(trip);
    tripMemberService.createOwner(trip.getId(), userId);

    return Map.of("trip", toTripResponse(trip, TripPermission.OWNER, null));
  }

  public Map<String, Object> findAll(String userId) {
    List<TripMember> memberships = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId)
        .inSql(TripMember::getTripId,
            "SELECT id FROM trips WHERE deleted_at IS NULL"));

    List<String> tripIds = memberships.stream().map(TripMember::getTripId).toList();
    Map<String, String> coverByTripId = new HashMap<>();
    if (!tripIds.isEmpty()) {
      List<Photo> covers = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
          .in(Photo::getTripId, tripIds)
          .eq(Photo::getIsCover, true)
          .orderByDesc(Photo::getUpdatedAt));
      for (Photo photo : covers) {
        coverByTripId.putIfAbsent(photo.getTripId(), photo.getUrl());
      }
    }

    List<Map<String, Object>> trips = new ArrayList<>();
    for (TripMember membership : memberships) {
      Trip trip = tripMapper.selectById(membership.getTripId());
      if (trip == null) {
        continue;
      }
      trips.add(toTripResponse(trip, membership.getRole(), coverByTripId.get(trip.getId())));
    }
    trips.sort((left, right) -> ((Date) right.get("updatedAt")).compareTo((Date) left.get("updatedAt")));
    return Map.of("trips", trips);
  }

  public Map<String, Object> findOne(String tripId, String userId) {
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    Photo cover = photoMapper.selectOne(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId)
        .eq(Photo::getIsCover, true)
        .orderByDesc(Photo::getUpdatedAt)
        .last("LIMIT 1"));
    return Map.of("trip", toTripResponse(trip, membership.getRole(),
        cover == null ? null : cover.getUrl()));
  }

  public Map<String, Object> getRoute(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .orderByAsc(TripDay::getDayIndex));

    List<Map<String, Object>> route = new ArrayList<>();
    for (TripDay day : days) {
      List<TripPlace> places = tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
          .eq(TripPlace::getTripDayId, day.getId())
          .isNotNull(TripPlace::getLatitude)
          .isNotNull(TripPlace::getLongitude)
          .orderByAsc(TripPlace::getSortOrder));
      List<Map<String, Object>> placeMaps = places.stream()
          .map(place -> {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", place.getId());
            map.put("name", place.getName());
            map.put("type", place.getType());
            map.put("latitude", place.getLatitude() == null ? "" : Fmt.coord(place.getLatitude()));
            map.put("longitude", place.getLongitude() == null ? "" : Fmt.coord(place.getLongitude()));
            map.put("sortOrder", place.getSortOrder());
            return map;
          })
          .toList();
      Map<String, Object> dayMap = new LinkedHashMap<>();
      dayMap.put("dayId", day.getId());
      dayMap.put("dayIndex", day.getDayIndex());
      dayMap.put("date", day.getDate());
      dayMap.put("places", placeMaps);
      route.add(dayMap);
    }
    return Map.of("route", route);
  }

  @Transactional
  public Map<String, Object> update(String tripId, Map<String, Object> dto, String userId) {
    validateDateRange(dto.get("startDate"), dto.get("endDate"));
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }

    if (dto.containsKey("title")) {
      String title = string(dto.get("title"));
      trip.setTitle(title == null ? null : title.trim());
    }
    if (dto.containsKey("description")) {
      trip.setDescription(toNullable(dto.get("description")));
    }
    if (dto.containsKey("destination")) {
      trip.setDestination(toNullable(dto.get("destination")));
    }
    if (dto.containsKey("startDate")) {
      trip.setStartDate(toDateOrNull(dto.get("startDate")));
    }
    if (dto.containsKey("endDate")) {
      trip.setEndDate(toDateOrNull(dto.get("endDate")));
    }
    if (dto.containsKey("status") && dto.get("status") != null) {
      trip.setStatus(dto.get("status").toString());
    }
    if (dto.containsKey("coverImageUrl")) {
      trip.setCoverImageUrl(toNullable(dto.get("coverImageUrl")));
    }
    if (dto.containsKey("budget")) {
      trip.setBudget(toBudgetOrNull(dto.get("budget")));
    }
    trip.setUpdatedAt(new Date());
    tripMapper.updateById(trip);
    syncDaysWithRange(tripId);
    return Map.of("trip", toTripResponse(trip, membership.getRole(), null));
  }

  /**
   * 把行程天与旅行的开始/结束日期对齐：补齐缺失的天、按 dayIndex 重排已有天的日期。
   * 日期整体前移/后移时会先写临时日期再落最终日期，避开 (trip_id, date) 唯一约束。
   * 新范围之外的天：已排过内容（景点/预订）的保留，空天删除，避免误删用户已排好的计划。
   */
  @Transactional
  public Map<String, Object> syncDaysWithRange(String tripId) {
    Trip trip = findLive(tripId);
    if (trip == null || trip.getStartDate() == null || trip.getEndDate() == null) {
      return Map.of("created", 0, "updated", 0, "removed", 0);
    }
    List<Date> dates = DateUtil.dayRange(trip.getStartDate(), trip.getEndDate());
    if (dates.size() > 60) {
      throw ApiException.badRequest("行程天数不能超过 60 天");
    }
    List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .orderByAsc(TripDay::getDayIndex));

    Map<Integer, TripDay> byIndex = new LinkedHashMap<>();
    for (TripDay day : days) {
      if (day.getDayIndex() != null) {
        byIndex.putIfAbsent(day.getDayIndex(), day);
      }
    }

    // 第一步：需要改日期的天先挪到临时日期，避免与其它天撞 (trip_id, date) 唯一键
    List<TripDay> pending = new ArrayList<>();
    for (int index = 0; index < dates.size(); index++) {
      TripDay day = byIndex.get(index + 1);
      if (day == null || sameDay(day.getDate(), dates.get(index))) {
        continue;
      }
      day.setDate(tempDate(pending.size()));
      day.setUpdatedAt(new Date());
      tripDayMapper.updateById(day);
      pending.add(day);
    }

    // 第二步：补齐缺失的天
    int created = 0;
    for (int index = 0; index < dates.size(); index++) {
      if (byIndex.get(index + 1) != null) {
        continue;
      }
      TripDay fresh = new TripDay();
      fresh.setTripId(tripId);
      fresh.setDate(dates.get(index));
      fresh.setDayIndex(index + 1);
      fresh.setCreatedAt(new Date());
      fresh.setUpdatedAt(new Date());
      tripDayMapper.insert(fresh);
      created++;
    }

    // 第三步：临时日期落回最终日期（要补的天已经建好，此时不会撞唯一键）
    int updated = 0;
    for (TripDay day : pending) {
      Integer dayIndex = day.getDayIndex();
      if (dayIndex == null || dayIndex < 1 || dayIndex > dates.size()) {
        continue;
      }
      day.setDate(dates.get(dayIndex - 1));
      day.setUpdatedAt(new Date());
      tripDayMapper.updateById(day);
      updated++;
    }

    // 第四步：新范围之外的空天清理掉，有内容的天保留
    int removed = 0;
    for (TripDay day : days) {
      if (day.getDayIndex() == null || day.getDayIndex() <= dates.size()) {
        continue;
      }
      if (dayHasContent(day.getId())) {
        continue;
      }
      tripDayMapper.deleteById(day.getId());
      removed++;
    }
    return Map.of("created", created, "updated", updated, "removed", removed);
  }

  private boolean dayHasContent(String dayId) {
    Long places = tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripDayId, dayId));
    if (places != null && places > 0) {
      return true;
    }
    Long bookings = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getTripDayId, dayId));
    return bookings != null && bookings > 0;
  }

  private boolean sameDay(Date left, Date right) {
    return left != null && DateUtil.utcDay(left).equals(DateUtil.utcDay(right));
  }

  /** 临时日期：远离真实行程日期且彼此不重复，仅供同步过程中的过渡使用。 */
  private Date tempDate(int offset) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.clear();
    calendar.set(1900, Calendar.JANUARY, 1);
    calendar.add(Calendar.DATE, offset);
    return calendar.getTime();
  }

  @Transactional
  public Map<String, Object> archive(String tripId, String userId) {
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    trip.setStatus("archived");
    trip.setArchivedAt(new Date());
    trip.setUpdatedAt(new Date());
    tripMapper.updateById(trip);
    return Map.of("trip", toTripResponse(trip, membership.getRole(), null));
  }

  @Transactional
  public Map<String, Object> remove(String tripId, String userId) {
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    trip.setDeletedAt(new Date());
    trip.setUpdatedAt(new Date());
    tripMapper.updateById(trip);
    return Map.of("trip", toTripResponse(trip, membership.getRole(), null));
  }

  @Transactional
  public Map<String, Object> duplicate(String tripId, String userId) {
    Trip source = findLive(tripId);
    if (source == null) {
      throw ApiException.notFound("Trip not found");
    }
    List<TripDay> sourceDays = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId).orderByAsc(TripDay::getDayIndex));
    List<TripPlace> sourcePlaces = tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId).orderByAsc(TripPlace::getSortOrder));

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
    return Map.of("trip", toTripResponse(trip, TripPermission.OWNER, null));
  }

  @Transactional
  public Map<String, Object> favorite(String tripId, String userId) {
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    trip.setIsFavorite(!Boolean.TRUE.equals(trip.getIsFavorite()));
    trip.setUpdatedAt(new Date());
    tripMapper.updateById(trip);
    return Map.of("trip", toTripResponse(trip, membership.getRole(), null));
  }

  public Trip findLive(String tripId) {
    Trip trip = tripMapper.selectById(tripId);
    return trip == null || trip.getDeletedAt() != null ? null : trip;
  }

  private void validateDateRange(Object startDate, Object endDate) {
    if (startDate == null || endDate == null) {
      return;
    }
    Date start = DateUtil.parse(startDate.toString());
    Date end = DateUtil.parse(endDate.toString());
    if (end.before(start)) {
      throw ApiException.badRequest("endDate must be greater than or equal to startDate");
    }
  }

  public Map<String, Object> toTripResponse(Trip trip, String currentUserRole, String coverPhotoUrl) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", trip.getId());
    map.put("title", trip.getTitle());
    map.put("description", trip.getDescription());
    map.put("destination", trip.getDestination());
    map.put("startDate", trip.getStartDate());
    map.put("endDate", trip.getEndDate());
    map.put("status", trip.getStatus());
    map.put("coverImageUrl", coverPhotoUrl != null ? coverPhotoUrl : trip.getCoverImageUrl());
    map.put("budget", Fmt.money(trip.getBudget()));
    map.put("ownerId", trip.getOwnerId());
    map.put("visibility", trip.getVisibility() == null ? "private" : trip.getVisibility());
    map.put("publishedAt", trip.getPublishedAt());
    map.put("viewCount", trip.getViewCount() == null ? 0 : trip.getViewCount());
    map.put("copyCount", trip.getCopyCount() == null ? 0 : trip.getCopyCount());
    map.put("sourceTripId", trip.getSourceTripId());
    map.put("currentUserRole", currentUserRole);
    map.put("isFavorite", trip.getIsFavorite());
    map.put("archivedAt", trip.getArchivedAt());
    map.put("deletedAt", trip.getDeletedAt());
    map.put("createdAt", trip.getCreatedAt());
    map.put("updatedAt", trip.getUpdatedAt());
    return map;
  }

  private String string(Object value) {
    return value == null ? null : value.toString();
  }

  private String toNullable(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private Date toDateOrNull(Object value) {
    return value == null ? null : DateUtil.parse(value.toString());
  }

  private BigDecimal toBudgetOrNull(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      BigDecimal amount = new BigDecimal(text);
      if (amount.signum() < 0) {
        throw ApiException.badRequest("预算金额不能小于 0");
      }
      return amount;
    } catch (NumberFormatException ex) {
      throw ApiException.badRequest("预算金额格式不正确");
    }
  }
}
