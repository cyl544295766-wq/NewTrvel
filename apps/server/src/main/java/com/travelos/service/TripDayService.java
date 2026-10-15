package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@Service
public class TripDayService {

  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;
  private final TripService tripService;
  private final BookingService bookingService;

  public TripDayService(TripDayMapper tripDayMapper, TripPlaceMapper tripPlaceMapper,
      TripMemberService tripMemberService, TripService tripService,
      BookingService bookingService) {
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
    this.tripService = tripService;
    this.bookingService = bookingService;
  }

  @Transactional
  public Map<String, Object> generate(String tripId, String userId) {
    Trip trip = tripService.findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    if (trip.getStartDate() == null || trip.getEndDate() == null) {
      throw ApiException.badRequest("旅行缺少开始日期或结束日期");
    }

    List<Date> dates = buildDateRange(trip.getStartDate(), trip.getEndDate());
    if (dates.size() > 60) {
      throw ApiException.badRequest("行程天数不能超过 60 天");
    }

    for (int index = 0; index < dates.size(); index++) {
      // skip days that already exist (skipDuplicates behaviour)
      Long existing = tripDayMapper.selectCount(new LambdaQueryWrapper<TripDay>()
          .eq(TripDay::getTripId, tripId)
          .eq(TripDay::getDate, dates.get(index)));
      if (existing > 0) {
        continue;
      }
      TripDay day = new TripDay();
      day.setTripId(tripId);
      day.setDate(dates.get(index));
      day.setDayIndex(index + 1);
      day.setCreatedAt(new Date());
      day.setUpdatedAt(new Date());
      tripDayMapper.insert(day);
    }
    return findAll(tripId, userId);
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> days = tripDayMapper
        .selectList(new LambdaQueryWrapper<TripDay>()
            .eq(TripDay::getTripId, tripId)
            .orderByAsc(TripDay::getDayIndex))
        .stream()
        .map(this::toDayResponse)
        .toList();
    return Map.of("days", days);
  }

  @Transactional
  public Map<String, Object> update(String tripId, String dayId, Map<String, Object> dto,
      String userId) {
    TripDay day = tripDayMapper.selectById(dayId);
    if (day == null || !day.getTripId().equals(tripId)) {
      throw ApiException.notFound("行程天数不存在");
    }
    if (dto.containsKey("title")) {
      day.setTitle(toNullable(dto.get("title")));
    }
    if (dto.containsKey("summary")) {
      day.setSummary(toNullable(dto.get("summary")));
    }
    day.setUpdatedAt(new Date());
    tripDayMapper.updateById(day);
    return Map.of("day", toDayResponse(day));
  }

  private Map<String, Object> toDayResponse(TripDay day) {
    List<TripPlace> places = tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripDayId, day.getId())
        .orderByAsc(TripPlace::getSortOrder));
    List<Map<String, Object>> placeMaps = places.stream().map(this::toPlaceResponse).toList();
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", day.getId());
    map.put("tripId", day.getTripId());
    map.put("date", day.getDate());
    map.put("dayIndex", day.getDayIndex());
    map.put("title", day.getTitle());
    map.put("summary", day.getSummary());
    map.put("createdAt", day.getCreatedAt());
    map.put("updatedAt", day.getUpdatedAt());
    map.put("places", placeMaps);
    // 预订角标：关联到当天的预订摘要（时间轴显示订单角标）
    map.put("bookings", bookingService.daySummaries(day.getTripId(), day.getId()));
    return map;
  }

  public Map<String, Object> toPlaceResponse(TripPlace place) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", place.getId());
    map.put("tripId", place.getTripId());
    map.put("tripDayId", place.getTripDayId());
    map.put("name", place.getName());
    map.put("type", place.getType());
    map.put("address", place.getAddress());
    map.put("latitude", place.getLatitude() == null ? null : Fmt.coord(place.getLatitude()));
    map.put("longitude", place.getLongitude() == null ? null : Fmt.coord(place.getLongitude()));
    map.put("startTime", place.getStartTime());
    map.put("endTime", place.getEndTime());
    map.put("notes", place.getNotes());
    map.put("sortOrder", place.getSortOrder());
    map.put("isCompleted", place.getIsCompleted());
    map.put("estimatedCost", place.getEstimatedCost() == null ? null
        : Fmt.money(place.getEstimatedCost()));
    map.put("createdAt", place.getCreatedAt());
    map.put("updatedAt", place.getUpdatedAt());
    return map;
  }

  private List<Date> buildDateRange(Date startDate, Date endDate) {
    Date start = DateUtil.utcDay(startDate);
    Date end = DateUtil.utcDay(endDate);
    if (end.before(start)) {
      throw ApiException.badRequest("结束日期不能早于开始日期");
    }
    List<Date> dates = new ArrayList<>();
    Calendar cursor = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cursor.setTime(start);
    Date cursorEnd = end;
    while (!cursor.getTime().after(cursorEnd)) {
      dates.add(cursor.getTime());
      cursor.add(Calendar.DATE, 1);
    }
    return dates;
  }

  private String toNullable(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
