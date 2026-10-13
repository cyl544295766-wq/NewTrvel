package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Photo;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
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
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TripService {

  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final TripMemberService tripMemberService;
  private final PhotoMapper photoMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;

  public TripService(TripMapper tripMapper, TripMemberMapper tripMemberMapper,
      TripMemberService tripMemberService, PhotoMapper photoMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper) {
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.tripMemberService = tripMemberService;
    this.photoMapper = photoMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
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
    tripMapper.insert(trip);
    tripMemberService.createOwner(trip.getId(), userId);

    return Map.of("trip", toTripResponse(trip, TripPermission.OWNER, null));
  }

  public Map<String, Object> findAll(String userId) {
    List<TripMember> memberships = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId)
        .inSql(TripMember::getTripId,
            "SELECT id FROM trips WHERE status <> 'archived' AND archived_at IS NULL AND deleted_at IS NULL"));

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
    TripMember membership = tripMemberService.requireTripRole(tripId, userId,
        TripPermission.EDITABLE_ROLES);
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
    return Map.of("trip", toTripResponse(trip, membership.getRole(), null));
  }

  @Transactional
  public Map<String, Object> archive(String tripId, String userId) {
    TripMember membership = tripMemberService.requireTripRole(tripId, userId,
        TripPermission.EDITABLE_ROLES);
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
    TripMember membership = tripMemberService.requireTripRole(tripId, userId,
        TripPermission.EDITABLE_ROLES);
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
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
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
