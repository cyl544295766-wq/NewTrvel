package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.common.Payloads;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TripService {

  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final TripMemberService tripMemberService;
  private final PhotoMapper photoMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final JdbcTemplate jdbcTemplate;

  public TripService(TripMapper tripMapper, TripMemberMapper tripMemberMapper,
      TripMemberService tripMemberService, PhotoMapper photoMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, JdbcTemplate jdbcTemplate) {
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.tripMemberService = tripMemberService;
    this.photoMapper = photoMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> dto, String userId) {
    String title = Payloads.text(dto.get("title"));
    if (title == null || title.isBlank()) {
      throw ApiException.badRequest("title should not be empty");
    }
    validateDateRange(dto.get("startDate"), dto.get("endDate"));

    Trip trip = new Trip();
    trip.setTitle(title.trim());
    trip.setDescription(Payloads.trimmedOrNull(dto.get("description")));
    trip.setDestination(Payloads.trimmedOrNull(dto.get("destination")));
    trip.setStartDate(toDateOrNull(dto.get("startDate")));
    trip.setEndDate(toDateOrNull(dto.get("endDate")));
    trip.setStatus("draft");
    trip.setCoverImageUrl(validateCoverImageUrl(dto.get("coverImageUrl"), null, null));
    if (dto.containsKey("budget")) {
      trip.setBudget(toBudgetOrNull(dto.get("budget")));
    }
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
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLiveForUpdate(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    Date start = effectiveDate(dto, "startDate", trip.getStartDate());
    Date end = effectiveDate(dto, "endDate", trip.getEndDate());
    boolean datesChanged = !Objects.equals(start, trip.getStartDate())
        || !Objects.equals(end, trip.getEndDate());
    DayPlan dayPlan = null;
    if (datesChanged || dto.containsKey("previewToken")) {
      String expectedToken = dto.containsKey("previewToken")
          ? Objects.toString(dto.get("previewToken"), "") : null;
      dayPlan = planDays(trip, start, end, true, expectedToken);
    }

    var changes = new LambdaUpdateWrapper<Trip>().eq(Trip::getId, tripId);
    String title = Payloads.trimmed(dto.get("title"));
    if (title != null) {
      trip.setTitle(title);
      changes.set(Trip::getTitle, trip.getTitle());
    }
    if (dto.containsKey("description")) {
      trip.setDescription(Payloads.trimmedOrNull(dto.get("description")));
      changes.set(Trip::getDescription, trip.getDescription());
    }
    if (dto.containsKey("destination")) {
      trip.setDestination(Payloads.trimmedOrNull(dto.get("destination")));
      changes.set(Trip::getDestination, trip.getDestination());
    }
    if (dto.containsKey("startDate")) {
      trip.setStartDate(start);
      changes.set(Trip::getStartDate, start);
    }
    if (dto.containsKey("endDate")) {
      trip.setEndDate(end);
      changes.set(Trip::getEndDate, end);
    }
    if (dto.containsKey("status") && dto.get("status") != null) {
      trip.setStatus(dto.get("status").toString());
      changes.set(Trip::getStatus, trip.getStatus());
    }
    if (dto.containsKey("coverImageUrl")) {
      trip.setCoverImageUrl(
          validateCoverImageUrl(dto.get("coverImageUrl"), tripId, trip.getCoverImageUrl()));
      changes.set(Trip::getCoverImageUrl, trip.getCoverImageUrl());
    }
    if (dto.containsKey("budget")) {
      trip.setBudget(toBudgetOrNull(dto.get("budget")));
      changes.set(Trip::getBudget, trip.getBudget());
    }
    trip.setUpdatedAt(new Date());
    tripMapper.update(null, changes.set(Trip::getUpdatedAt, trip.getUpdatedAt()));
    Map<String, Object> syncResult = dayPlan == null
        ? Map.of("created", 0, "updated", 0, "removed", 0)
        : applyDayPlan(tripId, dayPlan);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("trip", toTripResponse(trip, membership.getRole(), null));
    result.put("daySync", syncResult);
    return result;
  }

  private static final List<String> DAY_REFERENCE_TABLES = List.of(
      "trip_places", "bookings", "trip_expenses", "photos", "trip_journals",
      "travel_documents", "trip_weather", "trip_legs");

  private record DayTarget(int dayIndex, Date date, TripDay existing) {
  }

  private record DayPlan(List<TripDay> days, List<DayTarget> moves, List<DayTarget> additions,
      List<TripDay> preserved, List<TripDay> removed, String blockedReason, String token) {
    Map<String, Object> impact() {
      return Map.of(
          "moves", moves.stream().map(move -> Map.of(
              "dayId", move.existing().getId(), "dayIndex", move.dayIndex(),
              "from", Fmt.dateKey(move.existing().getDate()), "to", Fmt.dateKey(move.date()))).toList(),
          "additions", additions.stream().map(day -> Map.of(
              "dayIndex", day.dayIndex(), "date", Fmt.dateKey(day.date()))).toList(),
          "preserved", preserved.stream().map(DayPlan::dayEntry).toList(),
          "removed", removed.stream().map(DayPlan::dayEntry).toList(),
          "createCount", additions.size(),
          "hasRisk", !preserved.isEmpty() || !removed.isEmpty() || blockedReason != null);
    }

    private static Map<String, Object> dayEntry(TripDay day) {
      return Map.of("dayId", day.getId(), "dayIndex", day.getDayIndex(),
          "date", Fmt.dateKey(day.getDate()));
    }
  }

  @Transactional
  public Map<String, Object> syncDaysWithRange(String tripId) {
    Trip trip = findLiveForUpdate(tripId);
    if (trip == null || trip.getStartDate() == null || trip.getEndDate() == null) {
      return Map.of("created", 0, "updated", 0, "removed", 0);
    }
    return applyDayPlan(tripId, planDays(trip, trip.getStartDate(), trip.getEndDate(), true, null));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> dateChangePreview(String tripId, Map<String, String> dates, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLive(tripId);
    if (trip == null) {
      throw ApiException.notFound("Trip not found");
    }
    Date start = effectiveDate(dates, "startDate", trip.getStartDate());
    Date end = effectiveDate(dates, "endDate", trip.getEndDate());
    DayPlan plan = planDays(trip, start, end, false, null);
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("startDate", start == null ? null : Fmt.dateKey(start));
    range.put("endDate", end == null ? null : Fmt.dateKey(end));
    Map<String, Object> result = new LinkedHashMap<>(plan.impact());
    result.put("newRange", range);
    result.put("previewToken", plan.token());
    result.put("canSave", plan.blockedReason() == null);
    if (plan.blockedReason() != null) {
      result.put("blockedReason", plan.blockedReason());
    } else if (start == null || end == null) {
      result.put("message", "日期不完整，已有行程天会原样保留。补齐日期时会同步行程天。");
    }
    return result;
  }

  private Date effectiveDate(Map<String, ?> changes, String key, Date current) {
    if (!changes.containsKey(key)) {
      return current;
    }
    String value = Payloads.trimmedOrNull(changes.get(key));
    return value == null ? null : DateUtil.parse(value);
  }

  private DayPlan planDays(Trip trip, Date start, Date end, boolean lock, String expectedToken) {
    var query = new LambdaQueryWrapper<TripDay>().eq(TripDay::getTripId, trip.getId());
    if (lock) {
      query.last("FOR UPDATE");
    }
    // 行级权限拦截器会把 FOR UPDATE 重排到 ORDER BY 前，因此在持锁后排序。
    List<TripDay> days = tripDayMapper.selectList(query).stream()
        .sorted(Comparator.comparing(TripDay::getDayIndex)).toList();
    var references = dayReferences(trip.getId());
    String token = dayPlanToken(trip, start, end, days, references);
    if (expectedToken != null && !expectedToken.equals(token)) {
      throw ApiException.conflict("改期预览已失效，请重新查看影响并确认。");
    }
    if (start != null && end != null) {
      if (end.before(start)) {
        throw ApiException.badRequest("结束日期不能早于开始日期");
      }
      long daySpan = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(
          DateUtil.utcDay(end).getTime() - DateUtil.utcDay(start).getTime());
      if (daySpan >= 60) {
        throw ApiException.badRequest("行程天数不能超过 60 天");
      }
    }
    List<DayTarget> moves = new ArrayList<>();
    List<DayTarget> additions = new ArrayList<>();
    List<TripDay> preserved = new ArrayList<>();
    List<TripDay> removed = new ArrayList<>();
    String blockedReason = null;

    if (start == null || end == null) {
      preserved.addAll(days);
    } else {
      List<Date> dates = DateUtil.dayRange(start, end);
      Map<Integer, TripDay> byIndex = new HashMap<>();
      for (TripDay day : days) {
        byIndex.put(day.getDayIndex(), day);
      }
      for (int i = 0; i < dates.size(); i++) {
        int dayIndex = i + 1;
        Date date = dates.get(i);
        TripDay day = byIndex.get(dayIndex);
        if (day == null) {
          additions.add(new DayTarget(dayIndex, date, null));
        } else if (!DateUtil.utcDay(day.getDate()).equals(date)) {
          moves.add(new DayTarget(dayIndex, date, day));
        }
      }
      Set<Date> targetDates = new HashSet<>(dates);
      for (TripDay day : days) {
        if (day.getDayIndex() >= 1 && day.getDayIndex() <= dates.size()) {
          continue;
        }
        if (references.containsKey(day.getId()) || Payloads.trimmedOrNull(day.getTitle()) != null
            || Payloads.trimmedOrNull(day.getSummary()) != null) {
          preserved.add(day);
          if (targetDates.contains(DateUtil.utcDay(day.getDate()))) {
            blockedReason = "新日期范围与已有内容的第 " + day.getDayIndex()
                + " 天（" + Fmt.dateKey(day.getDate()) + "）重叠，请调整日期后重试。";
          }
        } else {
          removed.add(day);
        }
      }
    }
    return new DayPlan(days, moves, additions, preserved, removed, blockedReason, token);
  }

  private Map<String, List<String>> dayReferences(String tripId) {
    Map<String, List<String>> references = new HashMap<>();
    // 使用来自固定表名列表的 SQL，并通过已授权行程的 day ID 限定范围。
    for (String table : DAY_REFERENCE_TABLES) {
      jdbcTemplate.query("SELECT child.trip_day_id, child.id FROM " + table + " child"
              + " JOIN trip_days day ON day.id = child.trip_day_id"
              + " WHERE day.trip_id = ? ORDER BY child.id",
          rs -> {
            references.computeIfAbsent(rs.getString(1), key -> new ArrayList<>())
                .add(table + ":" + rs.getString(2));
          }, tripId);
    }
    return references;
  }

  private String dayPlanToken(Trip trip, Date start, Date end, List<TripDay> days,
      Map<String, List<String>> references) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      addTokenValue(digest, trip.getId());
      addTokenValue(digest, trip.getStartDate());
      addTokenValue(digest, trip.getEndDate());
      addTokenValue(digest, start);
      addTokenValue(digest, end);
      for (TripDay day : days) {
        addTokenValue(digest, day.getId());
        addTokenValue(digest, day.getDayIndex());
        addTokenValue(digest, day.getDate());
        addTokenValue(digest, day.getUpdatedAt());
        addTokenValue(digest, day.getTitle());
        addTokenValue(digest, day.getSummary());
        var ids = references.getOrDefault(day.getId(), List.of());
        addTokenValue(digest, ids.size());
        ids.forEach(ref -> addTokenValue(digest, ref));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void addTokenValue(MessageDigest digest, Object value) {
    if (value == null) {
      digest.update((byte) 0);
      return;
    }
    digest.update((byte) 1);
    byte[] bytes = (value instanceof Date date ? Long.toString(date.getTime()) : value.toString())
        .getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private Map<String, Object> applyDayPlan(String tripId, DayPlan plan) {
    if (plan.blockedReason() != null) {
      throw ApiException.conflict(plan.blockedReason());
    }
    Map<String, Object> result = new LinkedHashMap<>(plan.impact());
    result.put("created", plan.additions().size());
    result.put("updated", plan.moves().size());
    result.put("removedDays", result.get("removed"));
    result.put("removed", plan.removed().size());
    result.put("preservedDayIds", plan.preserved().stream().map(TripDay::getId).toList());
    result.put("preservedCount", plan.preserved().size());

    for (TripDay day : plan.removed()) {
      tripDayMapper.deleteById(day.getId());
    }
    // 先临时移走待重排的天，规避 (trip_id, date) 唯一约束，同时保留原 ID。
    Set<Date> occupied = new HashSet<>();
    plan.days().forEach(day -> occupied.add(DateUtil.utcDay(day.getDate())));
    plan.moves().forEach(move -> occupied.add(move.date()));
    plan.additions().forEach(day -> occupied.add(day.date()));
    LocalDate temporary = LocalDate.of(1900, 1, 1);
    for (DayTarget move : plan.moves()) {
      Date tempDate = Date.from(temporary.atStartOfDay(ZoneOffset.UTC).toInstant());
      while (!occupied.add(tempDate)) {
        temporary = temporary.plusDays(1);
        tempDate = Date.from(temporary.atStartOfDay(ZoneOffset.UTC).toInstant());
      }
      setDayDate(move.existing().getId(), tempDate);
    }
    for (DayTarget addition : plan.additions()) {
      TripDay fresh = new TripDay();
      fresh.setTripId(tripId);
      fresh.setDayIndex(addition.dayIndex());
      fresh.setDate(addition.date());
      tripDayMapper.insert(fresh);
    }
    for (DayTarget move : plan.moves()) {
      setDayDate(move.existing().getId(), move.date());
    }
    return result;
  }

  private void setDayDate(String dayId, Date date) {
    tripDayMapper.update(null, new LambdaUpdateWrapper<TripDay>().eq(TripDay::getId, dayId)
        .set(TripDay::getDate, date).set(TripDay::getUpdatedAt, new Date()));
  }

  Trip findLiveForUpdate(String tripId) {
    return tripMapper.selectOne(new LambdaQueryWrapper<Trip>()
        .eq(Trip::getId, tripId).isNull(Trip::getDeletedAt).last("FOR UPDATE"));
  }


  @Transactional
  public Map<String, Object> archive(String tripId, String userId) {
    TripMember membership = tripMemberService.requireTripMember(tripId, userId);
    Trip trip = findLiveForUpdate(tripId);
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
    Trip trip = findLiveForUpdate(tripId);
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
    trip.setCoverImageUrl(duplicatedCover(source));
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
    Trip trip = findLiveForUpdate(tripId);
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

  /**
   * 行程封面写路径加固（对象级授权的前置防线）：
   * <ul>
   *   <li>外部 http(s) 地址：放行；</li>
   *   <li>受管 /files/** 地址：仅限「当前封面原值」或「本行程已有的照片」——
   *       禁止把他人行程/他人的受管文件设为封面（否则可借公开行程泄露私有文件）；</li>
   *   <li>其余（data URL、javascript:、协议相对地址等）：400。</li>
   * </ul>
   */
  private String validateCoverImageUrl(Object raw, String tripId, String current) {
    String value = Payloads.trimmedOrNull(raw);
    if (value == null) {
      return null;
    }
    if (com.travelos.common.ManagedFileNames.isManagedUrl(value)) {
      if (value.equals(current)) {
        return value; // 编辑时保留原值
      }
      if (tripId != null && photoMapper.selectCount(new LambdaQueryWrapper<Photo>()
          .eq(Photo::getTripId, tripId)
          .eq(Photo::getUrl, value)) > 0) {
        return value;
      }
      throw ApiException.badRequest("封面仅支持外部图片地址或本行程照片");
    }
    if (value.startsWith("http://") || value.startsWith("https://")) {
      return value;
    }
    throw ApiException.badRequest("封面地址格式不正确");
  }

  /**
   * 复制行程时的封面传播规则：外部地址照搬；受管文件仅在源行程 public 时传播——
   * 私有/好友可见行程的照片不允许借「复制」带到新行程，避免绕过原行程成员边界。
   */
  private String duplicatedCover(Trip source) {
    String cover = source.getCoverImageUrl();
    if (cover == null || !com.travelos.common.ManagedFileNames.isManagedUrl(cover)) {
      return cover;
    }
    return "public".equals(source.getVisibility()) ? cover : null;
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
