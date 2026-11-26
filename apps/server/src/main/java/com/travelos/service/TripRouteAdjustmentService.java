package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.Fmt;
import com.travelos.common.JsonUtil;
import com.travelos.common.Payloads;
import com.travelos.entity.Booking;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.travelos.service.TripRouteCheck.Coordinate;
import static com.travelos.service.TripRouteCheck.Stop;

@Service
public class TripRouteAdjustmentService {

  private static final int MAX_CHANGES = 8;
  private static final Set<String> PLACE_TYPES = Set.of("attraction", "hotel", "restaurant",
      "transport", "shopping", "custom");
  private final TripMapper trips;
  private final TripDayMapper days;
  private final TripPlaceMapper places;
  private final BookingMapper bookings;
  private final TripMemberService members;
  private final UserPreferenceService preferences;
  private final TripPlaceService tripPlaceService;
  private final JournalCollabService collab;
  private final WeatherService weather;
  private final TripRouteCheck routeCheck;

  public TripRouteAdjustmentService(TripMapper trips, TripDayMapper days, TripPlaceMapper places,
      BookingMapper bookings, TripMemberService members, UserPreferenceService preferences,
      TripPlaceService tripPlaceService, JournalCollabService collab, WeatherService weather, TripRouteCheck routeCheck) {
    this.trips = trips;
    this.days = days;
    this.places = places;
    this.bookings = bookings;
    this.members = members;
    this.preferences = preferences;
    this.tripPlaceService = tripPlaceService;
    this.collab = collab;
    this.weather = weather;
    this.routeCheck = routeCheck;
  }

  public Map<String, Object> check(String tripId, String userId, Map<String, Object> args) {
    members.requireTripMember(tripId, userId);
    Context context = context(tripId, userId, requireDate(args), false);
    Set<String> locked = protectedPlaces(context);
    var lookups = routeCheck.new Lookups();
    List<Stop> stops = stops(context, currentPlaces(context), lookups);
    return preview(context, context.places().stream().map(place -> placeView(place,
        context.zone(), locked.contains(place.getId()))).toList(),
        routeCheck.check(stops, stops, Set.of(), mode(args), context.zone(), context.date(),
            context.trip().getDestination(), lookups), userId);
  }

  /** 返回可保存的草稿和解释预览。已有冲突仍可查看，变更引入的已知冲突必须先处理。 */
  public Map<String, Object> propose(String tripId, String userId, Map<String, Object> args) {
    members.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Context context = context(tripId, userId, requireDate(args), false);
    String mode = mode(args);
    if (!(args.get("changes") instanceof List<?> changes) || changes.isEmpty() || changes.size() > MAX_CHANGES) {
      throw ApiException.badRequest("请为指定日期提供 1 至 " + MAX_CHANGES + " 个变更项目");
    }
    Set<String> locked = protectedPlaces(context);
    if (args.get("lockedPlaceIds") != null && !(args.get("lockedPlaceIds") instanceof List<?>)) {
      throw ApiException.badRequest("保留地点应使用地点 ID 列表");
    }
    if (args.get("lockedPlaceIds") instanceof List<?> explicitlyLocked) {
      for (Object id : explicitlyLocked) {
        String placeId = Payloads.trimmedOrNull(id);
        if (context.places().stream().noneMatch(place -> place.getId().equals(placeId))) {
          throw ApiException.badRequest("保留的地点必须属于所选日期");
        }
        locked.add(placeId);
      }
    }
    var lookups = routeCheck.new Lookups();
    Map<String, TripPlace> current = currentPlaces(context);
    List<Stop> baseline = stops(context, current, lookups);
    Set<String> changed = new HashSet<>();
    List<Map<String, Object>> patches = new ArrayList<>();
    List<Map<String, Object>> differences = new ArrayList<>();
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> change)) {
        throw ApiException.badRequest("变更项目格式无效");
      }
      String action = required(change, "action");
      String id = "add".equals(action) ? "new:" + changed.size() : required(change, "placeId");
      TripPlace before = current.get(id);
      if ((!Set.of("add", "update", "remove").contains(action))
          || (!"add".equals(action) && (before == null || locked.contains(id))) || !changed.add(id)) {
        throw ApiException.badRequest("每个地点只能调整一次，且必须属于当天并允许变更；保留、已完成和预订关联地点不可修改");
      }
      if (change.containsKey("tripDayId") || change.containsKey("dayIndex") || change.containsKey("date")) {
        throw ApiException.badRequest("临时调整仅限所选日期");
      }
      Map<String, Object> patch = new LinkedHashMap<>();
      patch.put("action", action);
      if (before != null) {
        patch.put("placeId", id);
      }
      TripPlace after = null;
      if ("remove".equals(action)) {
        current.remove(id);
      } else {
        after = updatedPlace(before, id, change, context, lookups, patch);
        current.put(id, after);
      }
      Map<String, Object> difference = new LinkedHashMap<>();
      difference.put("action", action);
      difference.put("before", before == null ? null : placeView(before, context.zone(), false));
      difference.put("after", after == null ? null : placeView(after, context.zone(), false));
      differences.add(difference);
      patches.add(patch);
    }
    List<Stop> proposed = stops(context, current, lookups);
    var report = routeCheck.check(baseline, proposed, changed, mode,
        context.zone(), context.date(), context.trip().getDestination(), lookups);
    Map<String, Object> preview = preview(context, current.values().stream()
        .map(place -> placeView(place, context.zone(), locked.contains(place.getId()))).toList(), report, userId);
    preview.put("differences", differences);
    preview.put("lockedPlaces", context.places().stream().filter(place -> locked.contains(place.getId()))
        .map(TripPlace::getName).toList());
    if (report.issues().stream().anyMatch(issue -> issue.affected() && issue.conflict())) {
      return Map.of("draftCreated", false, "preview", preview, "message",
          "拟议变更存在时间冲突，请调整或请用户选择其他安排");
    }
    String reason = Payloads.trimmedOrNull(args.get("reason"));
    preview.put("reason", reason == null ? "按要求调整所选日期的安排" : reason.substring(0, Math.min(250, reason.length())));
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("type", "itinerary_change");
    draft.put("tripId", tripId);
    draft.put("summary", Fmt.dateKey(context.day().getDate()) + " · 临时调整行程");
    draft.put("changes", patches);
    draft.put("routeAdjustment", Map.of("date", context.date().toString(), "snapshot", fingerprint(context),
        "preview", preview));
    return draft;
  }

  /** 在草稿应用事务内锁定快照依赖项，确认期间没有被手工编辑或撤销权限。 */
  @Transactional
  public int apply(String tripId, String userId, Map<String, Object> draft, Map<?, ?> adjustment) {
    members.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Context context = context(tripId, userId, requireDate(adjustment), true);
    if (draft.containsKey("briefAlert") && (!LocalDate.now(context.zone()).equals(context.date())
        || context.trip().getArchivedAt() != null || "archived".equals(context.trip().getStatus())
        || "completed".equals(context.trip().getStatus()))) {
      throw ApiException.conflict("今日简报草稿已过期，请刷新简报重新查看");
    }
    collab.requireDayAvailableForDraft(context.day().getId(), userId);
    if (!Objects.equals(fingerprint(context), adjustment.get("snapshot"))) {
      throw stale();
    }
    if (!(draft.get("changes") instanceof List<?> changes) || changes.isEmpty() || changes.size() > MAX_CHANGES) {
      throw ApiException.badRequest("临时调整草稿中的变更无效");
    }
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> change)) {
        throw ApiException.badRequest("临时调整草稿中的变更无效");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      for (String field : List.of("name", "type", "address", "latitude", "longitude", "startTime", "endTime", "notes")) {
        if (change.containsKey(field)) {
          payload.put(field, change.get(field));
        }
      }
      switch (required(change, "action")) {
        case "add" -> {
          payload.put("tripDayId", context.day().getId());
          tripPlaceService.create(tripId, payload, userId);
        }
        case "update" -> tripPlaceService.update(tripId, required(change, "placeId"), payload, userId);
        case "remove" -> tripPlaceService.delete(tripId, required(change, "placeId"), userId);
        default -> throw ApiException.badRequest("临时调整草稿中的操作无效");
      }
    }
    return changes.size();
  }

  private Context context(String tripId, String userId, LocalDate date, boolean locking) {
    LambdaQueryWrapper<Trip> tripQuery = new LambdaQueryWrapper<Trip>().eq(Trip::getId, tripId);
    if (locking) {
      tripQuery.last("FOR UPDATE");
    }
    Trip trip = trips.selectOne(tripQuery);
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    if (locking && members.lockTripMembers(tripId).stream().noneMatch(member ->
        userId.equals(member.getUserId()) && TripPermission.EDITABLE_ROLES.contains(member.getRole()))) {
      throw ApiException.forbidden("当前用户已无法编辑该行程");
    }
    LambdaQueryWrapper<TripDay> dayQuery = new LambdaQueryWrapper<TripDay>().eq(TripDay::getTripId, tripId)
        .eq(TripDay::getDate, Date.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()));
    TripDay day = days.selectOne(locking ? dayQuery.last("FOR UPDATE") : dayQuery);
    if (day == null) {
      if (locking) {
        throw stale();
      }
      throw ApiException.badRequest("该日期没有行程天，请确认日期或先生成行程天");
    }
    LambdaQueryWrapper<Booking> bookingQuery = new LambdaQueryWrapper<Booking>().eq(Booking::getTripId, tripId);
    List<Booking> tripBookings = bookings.selectList(locking ? bookingQuery.last("FOR SHARE") : bookingQuery);
    LambdaQueryWrapper<TripPlace> placeQuery = new LambdaQueryWrapper<TripPlace>().eq(TripPlace::getTripId, tripId);
    if (locking) {
      // 对同一事务中将作为路线锚点的地点加锁，包含其他天关联的预订地点。
      placeQuery.last("FOR UPDATE");
    }
    List<TripPlace> allPlaces = places.selectList(placeQuery);
    Map<String, TripPlace> byId = allPlaces.stream().collect(Collectors.toMap(TripPlace::getId, place -> place));
    List<TripPlace> dayPlaces = allPlaces.stream().filter(place -> day.getId().equals(place.getTripDayId()))
        .sorted(Comparator.comparing(TripPlace::getId)).toList();
    ZoneId zone = preferences.timeZone(userId);
    Set<String> dayPlaceIds = dayPlaces.stream().map(TripPlace::getId).collect(Collectors.toSet());
    List<String> linkedPlaces = tripBookings.stream().map(Booking::getTripPlaceId)
        .filter(dayPlaceIds::contains).distinct().sorted().toList();
    return new Context(trip, day, date, zone, dayPlaces, byId, linkedPlaces, tripBookings.stream()
        .filter(booking -> booking.getCancelledAt() == null)
        .filter(booking -> pertainsToDay(booking, day, date, zone)
            || dayPlaceIds.contains(booking.getTripPlaceId()))
        .sorted(Comparator.comparing(Booking::getId)).toList());
  }

  private boolean pertainsToDay(Booking booking, TripDay day, LocalDate date, ZoneId zone) {
    if (day.getId().equals(booking.getTripDayId())) {
      return true;
    }
    Instant start = instant(booking.getStartTime());
    Instant end = instant(booking.getEndTime());
    Instant dayStart = date.atStartOfDay(zone).toInstant();
    Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
    if ("hotel".equals(booking.getType()) && start != null) {
      end = start;
    }
    return start != null && start.minusSeconds(arrivalBuffer(booking) * 60L).isBefore(dayEnd)
        && !(end == null ? start : end).isBefore(dayStart);
  }

  private Set<String> protectedPlaces(Context context) {
    Set<String> locked = new HashSet<>(context.linkedPlaces());
    context.places().stream().filter(place -> Boolean.TRUE.equals(place.getIsCompleted())
        || "transport".equals(place.getType())).map(TripPlace::getId).forEach(locked::add);
    return locked;
  }

  private Map<String, TripPlace> currentPlaces(Context context) {
    Map<String, TripPlace> current = new LinkedHashMap<>();
    context.places().forEach(place -> current.put(place.getId(), place));
    return current;
  }

  private TripPlace updatedPlace(TripPlace before, String id, Map<?, ?> change, Context context,
      TripRouteCheck.Lookups lookups, Map<String, Object> patch) {
    TripPlace after = new TripPlace();
    after.setId(id);
    String name = change.containsKey("name") || before == null ? required(change, "name") : before.getName();
    String type = changedText(change, "type", before == null ? "custom" : before.getType());
    if (type == null || !PLACE_TYPES.contains(type)) {
      throw ApiException.badRequest("地点类型无效");
    }
    String address = changedText(change, "address", before == null ? null : before.getAddress());
    String notes = changedText(change, "notes", before == null ? null : before.getNotes());
    Date start = changedTime(change, "startTime", context, before == null ? null : before.getStartTime());
    Date end = changedTime(change, "endTime", context, before == null ? null : before.getEndTime());
    if (start == null || end == null || !start.before(end)
        || !localDate(start, context.zone()).equals(context.date()) || !localDate(end, context.zone()).equals(context.date())) {
      throw ApiException.badRequest("新增或调整的地点需要所选日期内有效的开始和结束时间");
    }
    Coordinate coordinate = coordinate(before);
    boolean newLocation = before == null || !Objects.equals(name, before.getName())
        || !Objects.equals(address, before.getAddress());
    if (change.containsKey("latitude") || change.containsKey("longitude")) {
      coordinate = Coordinate.of(change.get("latitude"), change.get("longitude"));
      if (coordinate == null) {
        throw ApiException.badRequest("地点经纬度无效");
      }
    } else if (newLocation) {
      var poi = lookups.poi(name, context.trip().getDestination(), null);
      coordinate = poi == null ? null : poi.coordinate();
      if (coordinate == null && coordinate(before) != null) {
        throw ApiException.badRequest("替换地点的坐标无法核实，请先选择地图搜索结果或明确移除后新增");
      }
    }
    after.setName(name);
    after.setType(type);
    after.setAddress(address);
    after.setNotes(notes);
    after.setStartTime(start);
    after.setEndTime(end);
    after.setIsCompleted(before == null ? false : before.getIsCompleted());
    if (coordinate != null) {
      after.setLatitude(coordinate.latitude());
      after.setLongitude(coordinate.longitude());
    }
    putChanged(patch, "name", name, before == null ? null : before.getName());
    putChanged(patch, "type", type, before == null ? null : before.getType());
    putChanged(patch, "address", address, before == null ? null : before.getAddress());
    putChanged(patch, "notes", notes, before == null ? null : before.getNotes());
    putChanged(patch, "startTime", iso(start), before == null ? null : iso(before.getStartTime()));
    putChanged(patch, "endTime", iso(end), before == null ? null : iso(before.getEndTime()));
    if (coordinate != null && !coordinate.equals(coordinate(before))) {
      patch.put("latitude", coordinate.latitude());
      patch.put("longitude", coordinate.longitude());
    }
    if (before != null && patch.size() == 2) {
      throw ApiException.badRequest("请提供实际需要修改的字段");
    }
    return after;
  }

  private List<Stop> stops(Context context, Map<String, TripPlace> current, TripRouteCheck.Lookups lookups) {
    List<Stop> stops = new ArrayList<>();
    Set<String> covered = new HashSet<>();
    for (Booking booking : context.bookings()) {
      if (!pertainsToDay(booking, context.day(), context.date(), context.zone())) {
        continue;
      }
      TripPlace linked = context.allPlaces().get(booking.getTripPlaceId());
      if (linked != null && booking.getStartTime() != null) {
        covered.add(linked.getId());
      }
      stops.add(bookingStop(booking, linked, context, lookups));
    }
    current.values().stream().filter(place -> !covered.contains(place.getId())).forEach(place ->
        stops.add(new Stop(place.getId(), place.getName(), instant(place.getStartTime()), instant(place.getEndTime()),
            coordinate(place), coordinate(place), false, 0)));
    return stops;
  }

  private Stop bookingStop(Booking booking, TripPlace linked, Context context, TripRouteCheck.Lookups lookups) {
    boolean train = "train".equals(booking.getType());
    boolean flight = "flight".equals(booking.getType());
    int buffer = arrivalBuffer(booking);
    Instant start = instant(booking.getStartTime());
    Instant end = "hotel".equals(booking.getType()) ? start : instant(booking.getEndTime());
    Coordinate from = coordinate(linked);
    Coordinate to = train || flight ? null : from;
    if (train || flight) {
      String[] ends = routeEnds(booking, train ? "stations" : "airports");
      if (ends.length == 2) {
        if (from == null) {
          from = station(ends[0], lookups);
        }
        to = station(ends[1], lookups);
      }
    }
    if (end == null && !train && !flight) {
      end = start;
    }
    return new Stop("booking:" + booking.getId(), booking.getTitle(),
        start == null ? null : start.minusSeconds(buffer * 60L), end, from, to, true, buffer);
  }

  private int arrivalBuffer(Booking booking) {
    return switch (booking.getType()) {
      case "train" -> 45;
      case "flight" -> 120;
      default -> 0;
    };
  }

  private Coordinate station(String name, TripRouteCheck.Lookups lookups) {
    var poi = lookups.poi(name.trim(), null, null);
    return poi == null ? null : poi.coordinate();
  }

  private String[] routeEnds(Booking booking, String field) {
    try {
      String route = Payloads.trimmedOrNull(JsonUtil.readObject(booking.getExtra()).get(field));
      return route == null ? new String[0] : route.split("\\s*(?:→|->)\\s*");
    } catch (RuntimeException ignored) {
      return new String[0];
    }
  }

  private Map<String, Object> preview(Context context, List<Map<String, Object>> places,
      TripRouteCheck.Report report, String userId) {
    Map<String, Object> preview = new LinkedHashMap<>();
    preview.put("tripTitle", context.trip().getTitle());
    preview.put("tripId", context.trip().getId());
    preview.put("date", context.date().toString());
    preview.put("dayIndex", context.day().getDayIndex());
    preview.put("timezone", context.zone().getId());
    preview.put("places", places);
    preview.put("bookings", context.bookings().stream().filter(booking ->
        pertainsToDay(booking, context.day(), context.date(), context.zone()))
        .map(booking -> bookingView(booking, context.zone())).toList());
    Map<String, Object> forecast = weather.getTripDayWeather(context.trip().getId(), context.day().getId(), userId);
    preview.put("weather", forecast.get("weather"));
    preview.put("weatherNote", forecast.get("weather") == null ? "该日天气不可用" : "每日预报，无法确认具体时段是否下雨");
    preview.put("report", report);
    return preview;
  }

  private Map<String, Object> placeView(TripPlace place, ZoneId zone, boolean locked) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", place.getId());
    view.put("name", place.getName());
    view.put("type", place.getType());
    view.put("address", place.getAddress());
    view.put("latitude", place.getLatitude());
    view.put("longitude", place.getLongitude());
    view.put("notes", place.getNotes());
    view.put("start", localTime(place.getStartTime(), zone));
    view.put("end", localTime(place.getEndTime(), zone));
    view.put("locked", locked);
    return view;
  }

  private Map<String, Object> bookingView(Booking booking, ZoneId zone) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", booking.getId());
    view.put("title", booking.getTitle());
    view.put("type", booking.getType());
    view.put("start", localTime(booking.getStartTime(), zone));
    view.put("end", localTime(booking.getEndTime(), zone));
    view.put("placeId", booking.getTripPlaceId());
    return view;
  }

  private String fingerprint(Context context) {
    List<Object> rows = new ArrayList<>();
    rows.add(List.of(context.trip().getId(), context.day().getId(), context.date().toString(), context.zone().getId()));
    rows.add(context.trip().getDestination());
    rows.add(context.linkedPlaces());
    context.places().forEach(place -> {
      rows.add(placeView(place, context.zone(), false));
      rows.add(place.getIsCompleted());
      rows.add(place.getSortOrder());
      rows.add(iso(place.getUpdatedAt()));
    });
    context.bookings().forEach(booking -> {
      rows.add(bookingView(booking, context.zone()));
      rows.add(iso(booking.getUpdatedAt()));
      rows.add(booking.getExtra());
      TripPlace linked = context.allPlaces().get(booking.getTripPlaceId());
      rows.add(linked == null ? null : placeView(linked, context.zone(), false));
    });
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(JsonUtil.write(rows).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void putChanged(Map<String, Object> patch, String field, Object value, Object before) {
    if (!Objects.equals(value, before)) {
      patch.put(field, value);
    }
  }

  private static String changedText(Map<?, ?> change, String field, String fallback) {
    return change.containsKey(field) ? required(change, field) : fallback;
  }

  private static Date changedTime(Map<?, ?> change, String field, Context context, Date fallback) {
    if (!change.containsKey(field)) {
      return fallback;
    }
    String time = required(change, field);
    try {
      if (!time.matches("\\d{2}:\\d{2}")) {
        throw new DateTimeException("expected HH:mm");
      }
      var local = context.date().atTime(LocalTime.parse(time));
      var offsets = context.zone().getRules().getValidOffsets(local);
      if (offsets.size() != 1) {
        throw new DateTimeException("ambiguous local time");
      }
      return Date.from(local.toInstant(offsets.get(0)));
    } catch (DateTimeException ex) {
      throw ApiException.badRequest("请提供所选时区内明确的 HH:mm 时间");
    }
  }

  private static String mode(Map<?, ?> args) {
    String mode = Payloads.trimmedOrNull(args.get("mode"));
    if (mode == null) {
      return "driving";
    }
    if (!Set.of("driving", "walking", "transit").contains(mode)) {
      throw ApiException.badRequest("交通方式应为驾车、步行或公共交通");
    }
    return mode;
  }

  private static LocalDate requireDate(Map<?, ?> args) {
    try {
      String date = Payloads.trimmedOrNull(args.get("date"));
      if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
        throw new DateTimeException("expected yyyy-MM-dd");
      }
      return LocalDate.parse(date);
    } catch (DateTimeException ex) {
      throw ApiException.badRequest("请先确认要核对的日期，格式为 yyyy-MM-dd");
    }
  }

  private static String required(Map<?, ?> args, String field) {
    String value = Payloads.trimmedOrNull(args.get(field));
    if (value == null) {
      throw ApiException.badRequest("请先确认必填字段 " + field);
    }
    return value;
  }

  private static Coordinate coordinate(TripPlace place) {
    return place == null ? null : Coordinate.of(place.getLatitude(), place.getLongitude());
  }

  private static Instant instant(Date date) {
    return date == null ? null : Instant.ofEpochMilli(date.getTime());
  }

  private static String iso(Date date) {
    return date == null ? null : instant(date).toString();
  }

  private static LocalDate localDate(Date date, ZoneId zone) {
    return instant(date).atZone(zone).toLocalDate();
  }

  private static String localTime(Date date, ZoneId zone) {
    return date == null ? null : instant(date).atZone(zone).toOffsetDateTime().toString();
  }

  private static ApiException stale() {
    return ApiException.conflict("行程日期、地点或预订已变化，请重新校验并生成草稿");
  }

  private record Context(Trip trip, TripDay day, LocalDate date, ZoneId zone, List<TripPlace> places,
                         Map<String, TripPlace> allPlaces, List<String> linkedPlaces, List<Booking> bookings) {}
}
