package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TripPlaceService {

  private final TripPlaceMapper tripPlaceMapper;
  private final TripDayMapper tripDayMapper;
  private final TripMemberService tripMemberService;
  private final TripDayService tripDayService;

  public TripPlaceService(TripPlaceMapper tripPlaceMapper, TripDayMapper tripDayMapper,
      TripMemberService tripMemberService, TripDayService tripDayService) {
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripMemberService = tripMemberService;
    this.tripDayService = tripDayService;
  }

  @Transactional
  public Map<String, Object> create(String tripId, Map<String, Object> dto, String userId) {
    String tripDayId = optionalString(dto.get("tripDayId"));
    if (tripDayId != null) {
      ensureDayBelongsToTrip(tripDayId, tripId);
    }
    String name = optionalString(dto.get("name"));
    if (name == null || name.isBlank()) {
      throw ApiException.badRequest("name should not be empty");
    }
    int sortOrder = tripDayId == null ? 0
        : tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
            .eq(TripPlace::getTripDayId, tripDayId)).intValue();

    TripPlace place = new TripPlace();
    place.setTripId(tripId);
    place.setTripDayId(tripDayId);
    place.setName(name.trim());
    place.setType(optionalString(dto.get("type")) == null ? "custom" : dto.get("type").toString());
    place.setAddress(toNullable(dto.get("address")));
    place.setLatitude(toDecimal(dto.get("latitude")));
    place.setLongitude(toDecimal(dto.get("longitude")));
    place.setStartTime(toDateOrNull(dto.get("startTime")));
    place.setEndTime(toDateOrNull(dto.get("endTime")));
    place.setNotes(toNullable(dto.get("notes")));
    place.setSortOrder(sortOrder);
    place.setIsCompleted(dto.get("isCompleted") != null && Boolean.parseBoolean(dto.get("isCompleted").toString()));
    place.setEstimatedCost(toDecimal(dto.get("estimatedCost")));
    place.setCreatedAt(new Date());
    place.setUpdatedAt(new Date());
    tripPlaceMapper.insert(place);
    return Map.of("place", tripDayService.toPlaceResponse(place));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String placeId, Map<String, Object> dto,
      String userId) {
    TripPlace place = findPlace(placeId);
    if (place == null || !place.getTripId().equals(tripId)) {
      throw ApiException.notFound("地点不存在");
    }
    if (dto.containsKey("tripDayId")) {
      String tripDayId = optionalString(dto.get("tripDayId"));
      if (tripDayId != null) {
        ensureDayBelongsToTrip(tripDayId, tripId);
      }
      place.setTripDayId(tripDayId);
    }
    if (dto.containsKey("name")) {
      String name = optionalString(dto.get("name"));
      place.setName(name == null ? null : name.trim());
    }
    if (dto.containsKey("type") && dto.get("type") != null) {
      place.setType(dto.get("type").toString());
    }
    if (dto.containsKey("address")) {
      place.setAddress(toNullable(dto.get("address")));
    }
    if (dto.containsKey("latitude")) {
      place.setLatitude(toDecimal(dto.get("latitude")));
    }
    if (dto.containsKey("longitude")) {
      place.setLongitude(toDecimal(dto.get("longitude")));
    }
    if (dto.containsKey("startTime")) {
      place.setStartTime(toDateOrNull(dto.get("startTime")));
    }
    if (dto.containsKey("estimatedCost")) {
      place.setEstimatedCost(toDecimal(dto.get("estimatedCost")));
    }
    if (dto.containsKey("endTime")) {
      place.setEndTime(toDateOrNull(dto.get("endTime")));
    }
    if (dto.containsKey("notes")) {
      place.setNotes(toNullable(dto.get("notes")));
    }
    if (dto.containsKey("sortOrder") && dto.get("sortOrder") != null) {
      place.setSortOrder(Integer.parseInt(dto.get("sortOrder").toString()));
    }
    if (dto.containsKey("isCompleted") && dto.get("isCompleted") != null) {
      place.setIsCompleted(Boolean.parseBoolean(dto.get("isCompleted").toString()));
    }
    place.setUpdatedAt(new Date());
    tripPlaceMapper.updateById(place);
    return Map.of("place", tripDayService.toPlaceResponse(place));
  }

  @Transactional
  public Map<String, Object> move(String tripId, String placeId, String tripDayId,
      String userId) {
    TripPlace place = findPlace(placeId);
    if (place == null || !place.getTripId().equals(tripId)) {
      throw ApiException.notFound("地点不存在");
    }
    ensureDayBelongsToTrip(tripDayId, tripId);
    int sortOrder = tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripDayId, tripDayId)).intValue();
    place.setTripDayId(tripDayId);
    place.setSortOrder(sortOrder);
    place.setUpdatedAt(new Date());
    tripPlaceMapper.updateById(place);
    return Map.of("place", tripDayService.toPlaceResponse(place));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String placeId, String userId) {
    TripPlace place = findPlace(placeId);
    if (place == null || !place.getTripId().equals(tripId)) {
      throw ApiException.notFound("地点不存在");
    }
    tripPlaceMapper.deleteById(placeId);
    return Map.of("success", true);
  }

  @Transactional
  public Map<String, Object> reorder(String tripId, String dayId, List<String> placeIds,
      String userId) {
    ensureDayBelongsToTrip(dayId, tripId);
    List<TripPlace> places = tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .in(TripPlace::getId, placeIds));
    if (places.size() != placeIds.size()) {
      throw ApiException.badRequest("地点列表不完整");
    }
    for (TripPlace place : places) {
      if (!place.getTripId().equals(tripId) || !dayId.equals(place.getTripDayId())) {
        throw ApiException.badRequest("地点必须属于当前旅行和当前日期");
      }
    }
    List<Map<String, Object>> reordered = new java.util.ArrayList<>();
    for (int index = 0; index < placeIds.size(); index++) {
      final String targetId = placeIds.get(index);
      TripPlace place = places.stream()
          .filter(item -> item.getId().equals(targetId))
          .findFirst()
          .orElseThrow();
      place.setSortOrder(index);
      place.setUpdatedAt(new Date());
      tripPlaceMapper.updateById(place);
      reordered.add(tripDayService.toPlaceResponse(place));
    }
    return Map.of("places", reordered);
  }

  public void ensureDayBelongsToTrip(String dayId, String tripId) {
    TripDay day = tripDayMapper.selectById(dayId);
    if (day == null || !day.getTripId().equals(tripId)) {
      throw ApiException.badRequest("行程日期不属于当前旅行");
    }
  }

  public TripPlace findPlace(String placeId) {
    return tripPlaceMapper.selectById(placeId);
  }

  private String optionalString(Object value) {
    return value == null ? null : value.toString();
  }

  private String toNullable(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private java.math.BigDecimal toDecimal(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return new java.math.BigDecimal(text);
    } catch (NumberFormatException ex) {
      throw ApiException.badRequest("坐标格式不正确");
    }
  }

  private Date toDateOrNull(Object value) {
    return value == null ? null : DateUtil.parse(value.toString());
  }
}
