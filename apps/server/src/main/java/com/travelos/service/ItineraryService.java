package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripLeg;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripLegMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App「我的行程」与服务端行程模型的整份同步。
 *
 * <p>设计取舍：App 端把行程当作一份快照（若干天 + 每天的地点/路线/花销），
 * 粒度是「整份覆盖」而不是逐条 diff——两端并发编辑同一条行程属于少数场景，
 * 用请求级事务覆盖比维护一套增量协议可靠得多。网页端仍走原有 /api/trips 系列接口，
 * 因此网页改完、App 下次拉取即可看到。</p>
 */
@Service
public class ItineraryService {

  private static final int MAX_DAYS = 60;
  private static final int MAX_PLACES_PER_DAY = 50;
  private static final int MAX_LEGS_PER_DAY = 50;
  private static final int MAX_EXPENSES_PER_DAY = 100;
  private static final Set<String> EXPENSE_CATEGORIES =
      Set.of("transport", "hotel", "food", "ticket", "shopping", "activity", "other");
  private static final String DEFAULT_TITLE = "我的行程";

  private final TripMapper tripMapper;
  private final TripDayMapper dayMapper;
  private final TripPlaceMapper placeMapper;
  private final TripLegMapper legMapper;
  private final TripExpenseMapper expenseMapper;
  private final TripMemberMapper memberMapper;

  public ItineraryService(TripMapper tripMapper, TripDayMapper dayMapper,
      TripPlaceMapper placeMapper, TripLegMapper legMapper, TripExpenseMapper expenseMapper,
      TripMemberMapper memberMapper) {
    this.tripMapper = tripMapper;
    this.dayMapper = dayMapper;
    this.placeMapper = placeMapper;
    this.legMapper = legMapper;
    this.expenseMapper = expenseMapper;
    this.memberMapper = memberMapper;
  }

  /** 读取 App 端那一份行程；没有时返回空快照而不是 404，客户端无需区分首次同步 */
  @Transactional(readOnly = true)
  public Map<String, Object> snapshot(String userId) {
    Trip trip = findDefaultTrip(userId);
    if (trip == null) {
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("tripId", null);
      empty.put("title", DEFAULT_TITLE);
      empty.put("updatedAt", null);
      empty.put("days", List.of());
      return empty;
    }
    return buildSnapshot(trip);
  }

  /** 整份覆盖：天、地点、路线、花销全部按请求重建 */
  @Transactional
  public Map<String, Object> replace(String userId, ItineraryPayload payload) {
    List<DayPayload> days = payload.days() == null ? List.of() : payload.days();
    if (days.size() > MAX_DAYS) {
      throw ApiException.badRequest("行程天数不能超过 " + MAX_DAYS + " 天");
    }
    validateDays(days);

    Trip trip = findDefaultTrip(userId);
    LocalDate startDate = resolveStartDate(days, payload.startDate());
    if (trip == null) {
      trip = new Trip();
      trip.setTitle(titleOf(payload));
      trip.setOwnerId(userId);
      trip.setStatus("planning");
      trip.setAppDefault(true);
      trip.setStartDate(toDate(startDate));
      trip.setEndDate(toDate(startDate.plusDays(Math.max(0, days.size() - 1))));
      tripMapper.insert(trip);
      // 数据权限按 trip_members 过滤，建行程必须同时写 owner 成员关系，
      // 否则刚建好的行程在后续查询里会被拦截器过滤掉（表现为「写入成功但读不到」）。
      TripMember owner = new TripMember();
      owner.setTripId(trip.getId());
      owner.setUserId(userId);
      owner.setRole("owner");
      memberMapper.insert(owner);
    } else {
      trip.setTitle(titleOf(payload));
      trip.setStartDate(toDate(startDate));
      trip.setEndDate(toDate(startDate.plusDays(Math.max(0, days.size() - 1))));
      tripMapper.updateById(trip);
      ensureMembership(trip.getId(), userId);
    }

    clearChildren(trip.getId());

    int dayCount = 0;
    for (DayPayload day : days) {
      int dayIndex = day.dayIndex() == null ? dayCount + 1 : day.dayIndex();
      LocalDate date = day.date() == null || day.date().isBlank()
          ? startDate.plusDays(Math.max(0, dayIndex - 1))
          : parseDate(day.date(), startDate);
      TripDay row = new TripDay();
      row.setTripId(trip.getId());
      row.setDayIndex(dayIndex);
      row.setDate(toDate(date));
      row.setSummary(day.note());
      dayMapper.insert(row);
      dayCount++;

      insertPlaces(trip.getId(), row.getId(), day.places());
      insertLegs(trip.getId(), row.getId(), day.legs());
      insertExpenses(trip.getId(), row.getId(), userId, date, day.expenses());
    }

    Trip reloaded = tripMapper.selectById(trip.getId());
    return buildSnapshot(reloaded == null ? trip : reloaded);
  }

  private void validateDays(List<DayPayload> days) {
    for (DayPayload day : days) {
      if (day.places() != null && day.places().size() > MAX_PLACES_PER_DAY) {
        throw ApiException.badRequest("单天地点数不能超过 " + MAX_PLACES_PER_DAY + " 个");
      }
      if (day.legs() != null && day.legs().size() > MAX_LEGS_PER_DAY) {
        throw ApiException.badRequest("单天路线段不能超过 " + MAX_LEGS_PER_DAY + " 段");
      }
      if (day.expenses() != null && day.expenses().size() > MAX_EXPENSES_PER_DAY) {
        throw ApiException.badRequest("单天花销条数不能超过 " + MAX_EXPENSES_PER_DAY + " 条");
      }
    }
  }

  private void insertPlaces(String tripId, String dayId, List<PlacePayload> places) {
    if (places == null) {
      return;
    }
    int order = 0;
    for (PlacePayload place : places) {
      if (place == null || place.name() == null || place.name().isBlank()) {
        continue;
      }
      TripPlace row = new TripPlace();
      row.setTripId(tripId);
      row.setTripDayId(dayId);
      row.setName(place.name().trim());
      row.setType("custom");
      row.setAddress(place.address());
      row.setLatitude(decimal(place.latitude()));
      row.setLongitude(decimal(place.longitude()));
      row.setSortOrder(order++);
      placeMapper.insert(row);
    }
  }

  private void insertLegs(String tripId, String dayId, List<LegPayload> legs) {
    if (legs == null) {
      return;
    }
    int index = 0;
    for (LegPayload leg : legs) {
      if (leg == null) {
        continue;
      }
      TripLeg row = new TripLeg();
      row.setTripId(tripId);
      row.setTripDayId(dayId);
      row.setSegmentIndex(leg.segmentIndex() == null ? index : leg.segmentIndex());
      row.setDistanceMeters(leg.distanceMeters());
      row.setDurationSeconds(leg.durationSeconds());
      if (leg.points() != null && !leg.points().isEmpty()) {
        row.setPointsJson(JsonUtil.write(leg.points()));
      }
      legMapper.insert(row);
      index++;
    }
  }

  private void insertExpenses(String tripId, String dayId, String userId, LocalDate date,
      List<ExpensePayload> expenses) {
    if (expenses == null) {
      return;
    }
    for (ExpensePayload expense : expenses) {
      if (expense == null || expense.title() == null || expense.title().isBlank()) {
        continue;
      }
      long amountFen = expense.amountFen() == null ? 0L : Math.max(0L, expense.amountFen());
      String category = expense.category() == null ? "other" : expense.category();
      TripExpense row = new TripExpense();
      row.setTripId(tripId);
      row.setTripDayId(dayId);
      row.setPayerUserId(userId);
      row.setTitle(expense.title().trim());
      row.setAmount(BigDecimal.valueOf(amountFen).divide(BigDecimal.valueOf(100), 2,
          RoundingMode.HALF_UP));
      row.setCurrency("CNY");
      row.setCategory(EXPENSE_CATEGORIES.contains(category) ? category : "other");
      row.setSpentAt(toDate(date));
      expenseMapper.insert(row);
    }
  }

  /** 历史数据或缺成员记录的行程：补一条 owner 关系，保证数据权限能查到它 */
  private void ensureMembership(String tripId, String userId) {
    Long exists = memberMapper.selectCount(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId)
        .eq(TripMember::getUserId, userId));
    if (exists != null && exists > 0) {
      return;
    }
    TripMember member = new TripMember();
    member.setTripId(tripId);
    member.setUserId(userId);
    member.setRole("owner");
    memberMapper.insert(member);
  }

  private void clearChildren(String tripId) {
    legMapper.delete(new LambdaQueryWrapper<TripLeg>().eq(TripLeg::getTripId, tripId));
    expenseMapper.delete(new LambdaQueryWrapper<TripExpense>().eq(TripExpense::getTripId, tripId));
    placeMapper.delete(new LambdaQueryWrapper<TripPlace>().eq(TripPlace::getTripId, tripId));
    dayMapper.delete(new LambdaQueryWrapper<TripDay>().eq(TripDay::getTripId, tripId));
  }

  private Trip findDefaultTrip(String userId) {
    return tripMapper.selectOne(new LambdaQueryWrapper<Trip>()
        .eq(Trip::getOwnerId, userId)
        .eq(Trip::getAppDefault, true)
        .isNull(Trip::getDeletedAt)
        .last("LIMIT 1"));
  }

  private Map<String, Object> buildSnapshot(Trip trip) {
    List<TripDay> dayRows = dayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, trip.getId())
        .orderByAsc(TripDay::getDayIndex));
    List<Map<String, Object>> days = new ArrayList<>(dayRows.size());
    for (TripDay dayRow : dayRows) {
      Map<String, Object> day = new LinkedHashMap<>();
      day.put("dayIndex", dayRow.getDayIndex());
      day.put("date", formatDate(dayRow.getDate()));
      day.put("note", dayRow.getSummary() == null ? "" : dayRow.getSummary());
      day.put("places", placesOf(trip.getId(), dayRow.getId()));
      day.put("legs", legsOf(trip.getId(), dayRow.getId()));
      day.put("expenses", expensesOf(dayRow.getId()));
      days.add(day);
    }
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("tripId", trip.getId());
    snapshot.put("title", trip.getTitle());
    snapshot.put("updatedAt", trip.getUpdatedAt() == null ? null : trip.getUpdatedAt().getTime());
    snapshot.put("days", days);
    return snapshot;
  }

  private List<Map<String, Object>> placesOf(String tripId, String dayId) {
    List<TripPlace> rows = placeMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId)
        .eq(TripPlace::getTripDayId, dayId)
        .orderByAsc(TripPlace::getSortOrder));
    List<Map<String, Object>> places = new ArrayList<>(rows.size());
    for (TripPlace row : rows) {
      Map<String, Object> place = new LinkedHashMap<>();
      place.put("name", row.getName());
      place.put("address", row.getAddress() == null ? "" : row.getAddress());
      place.put("latitude", row.getLatitude());
      place.put("longitude", row.getLongitude());
      places.add(place);
    }
    return places;
  }

  private List<Map<String, Object>> legsOf(String tripId, String dayId) {
    List<TripLeg> rows = legMapper.selectList(new LambdaQueryWrapper<TripLeg>()
        .eq(TripLeg::getTripId, tripId)
        .eq(TripLeg::getTripDayId, dayId)
        .orderByAsc(TripLeg::getSegmentIndex));
    List<Map<String, Object>> legs = new ArrayList<>(rows.size());
    for (TripLeg row : rows) {
      Map<String, Object> leg = new LinkedHashMap<>();
      leg.put("segmentIndex", row.getSegmentIndex());
      leg.put("distanceMeters", row.getDistanceMeters());
      leg.put("durationSeconds", row.getDurationSeconds());
      leg.put("points", readPoints(row.getPointsJson()));
      legs.add(leg);
    }
    return legs;
  }

  private List<Map<String, Object>> expensesOf(String dayId) {
    List<TripExpense> rows = expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
        .eq(TripExpense::getTripDayId, dayId));
    List<Map<String, Object>> expenses = new ArrayList<>(rows.size());
    for (TripExpense row : rows) {
      Map<String, Object> expense = new LinkedHashMap<>();
      expense.put("title", row.getTitle());
      expense.put("category", row.getCategory());
      expense.put("amountFen", row.getAmount() == null
          ? 0L : row.getAmount().multiply(BigDecimal.valueOf(100)).longValue());
      expenses.add(expense);
    }
    return expenses;
  }

  private static Object readPoints(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return JsonUtil.mapper().readValue(json, List.class);
    } catch (Exception ex) {
      return List.of();
    }
  }

  private static String titleOf(ItineraryPayload payload) {
    String title = payload.title();
    return title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();
  }

  private static LocalDate resolveStartDate(List<DayPayload> days, String startDate) {
    if (startDate != null && !startDate.isBlank()) {
      try {
        return LocalDate.parse(startDate);
      } catch (DateTimeParseException ignored) {
        // 非法日期退回首天日期/今天
      }
    }
    for (DayPayload day : days) {
      if (day.date() != null && !day.date().isBlank()) {
        try {
          return LocalDate.parse(day.date());
        } catch (DateTimeParseException ignored) {
          break;
        }
      }
    }
    return LocalDate.now();
  }

  private static LocalDate parseDate(String value, LocalDate fallback) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException ex) {
      return fallback;
    }
  }

  private static Date toDate(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  private static String formatDate(Date date) {
    if (date == null) {
      return "";
    }
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
  }

  private static BigDecimal decimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  /** 请求体：整份行程快照 */
  public record ItineraryPayload(String title, String startDate, List<DayPayload> days) {
  }

  public record DayPayload(Integer dayIndex, String date, String note, List<PlacePayload> places,
      List<LegPayload> legs, List<ExpensePayload> expenses) {
  }

  public record PlacePayload(String name, String address, Double latitude, Double longitude) {
  }

  public record LegPayload(Integer segmentIndex, Double distanceMeters, Double durationSeconds,
      List<PointPayload> points) {
  }

  public record PointPayload(Double latitude, Double longitude) {
  }

  public record ExpensePayload(String title, String category, Long amountFen) {
  }
}
