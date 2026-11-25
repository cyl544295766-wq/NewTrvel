package com.travelos.service;

import com.travelos.common.Fmt;
import com.travelos.common.Payloads;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class TripRouteCheck {

  private static final Pattern HOURS = Pattern.compile(
      "(?:每日|每天)?\\s*([0-2]\\d:[0-5]\\d)\\s*[-–—至~～]\\s*([0-2]\\d:[0-5]\\d)");
  private static final String WEEKDAYS = "一二三四五六日";
  private static final Pattern WEEKLY_CLOSURE = Pattern.compile("周([一二三四五六日天])闭馆[，,；;]\\s*(?:其余时间)?\\s*(.+)");
  private static final Comparator<Stop> CHRONOLOGICAL = Comparator.comparing(Stop::start).thenComparing(Stop::id);
  private final PlacesService places;

  TripRouteCheck(PlacesService places) {
    this.places = places;
  }

  Report check(List<Stop> baseline, List<Stop> proposed, Set<String> changed, String mode,
      ZoneId zone, LocalDate date, String city, Lookups lookups) {
    List<Stop> timed = timed(proposed);
    Set<LegKey> previousLegs = pairs(timed(baseline));
    List<Issue> issues = new ArrayList<>();
    List<Leg> legs = new ArrayList<>();
    Stop latestEnd = null;
    for (int index = 0; index < timed.size(); index++) {
      Stop stop = timed.get(index);
      if (latestEnd != null && latestEnd.end().isAfter(stop.start())) {
        boolean affected = changed.contains(latestEnd.id()) || changed.contains(stop.id());
        issues.add(new Issue("overlap", true, affected, latestEnd.name() + " 与 " + stop.name() + " 的时间重叠"));
      }
      if (index > 0) {
        Stop from = timed.get(index - 1);
        boolean affected = affected(from, stop, changed, previousLegs);
        addLeg(legs, issues, from, stop, mode, affected, lookups);
      }
      if (stop.end() != null && (latestEnd == null || stop.end().isAfter(latestEnd.end()))) {
        latestEnd = stop;
      }
    }
    for (Stop stop : proposed) {
      if (stop.start() == null || stop.end() == null) {
        issues.add(new Issue("missing_time", false, changed.contains(stop.id()), stop.name() + " 的时间不完整"));
      }
      if (!stop.booking() && stop.start() != null) {
        checkHours(stop, zone, date, city, changed.contains(stop.id()), lookups, issues);
      }
    }
    String status = issues.stream().anyMatch(Issue::conflict) ? "conflict"
        : issues.isEmpty() ? "checked" : "partial";
    return new Report(status, mode, legs, issues);
  }

  private void addLeg(List<Leg> legs, List<Issue> issues, Stop from, Stop to, String mode,
      boolean affected, Lookups lookups) {
    Long available = from.end() == null ? null : Duration.between(from.end(), to.start()).getSeconds();
    OptionalLong route = available == null || available < 0 ? OptionalLong.empty()
        : lookups.travel(from.to(), to.from(), mode);
    Long duration = route.isPresent() ? route.getAsLong() : null;
    legs.add(new Leg(from.name(), to.name(), duration, available, to.bufferMinutes()));
    if (duration != null && duration > available) {
      issues.add(new Issue("travel_time", true, affected,
          from.name() + " → " + to.name() + " 的交通耗时超过可用空档"));
    } else if (duration == null && (available == null || available >= 0)) {
      issues.add(new Issue("missing_route", false, affected,
          from.name() + " → " + to.name() + " 暂无可用交通耗时"));
    }
  }

  private void checkHours(Stop stop, ZoneId zone, LocalDate date, String city, boolean affected,
      Lookups lookups, List<Issue> issues) {
    Poi poi = stop.from() == null ? null : lookups.poi(stop.name(), city, stop.from());
    Boolean open = poi == null ? null : withinHours(poi.hours(), date,
        stop.start().atZone(zone).toLocalTime(), stop.end() == null ? null : stop.end().atZone(zone).toLocalTime());
    if (!Boolean.TRUE.equals(open)) {
      issues.add(new Issue("opening_hours", Boolean.FALSE.equals(open), affected,
          stop.name() + (open == null ? " 的营业时间未能核实" : " 的安排超出已查到的营业时间")));
    }
  }

  static Boolean withinHours(String text, LocalDate date, LocalTime start, LocalTime end) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String hours = text.trim();
    Matcher weekly = WEEKLY_CLOSURE.matcher(hours);
    if (weekly.matches()) {
      char closed = weekly.group(1).replace('天', '日').charAt(0);
      if (WEEKDAYS.indexOf(closed) + 1 == date.getDayOfWeek().getValue()) {
        return false;
      }
      hours = weekly.group(2);
    }
    if (Set.of("全天开放", "24小时营业", "24小时开放").contains(hours)) {
      return true;
    }
    Matcher range = HOURS.matcher(hours);
    if (!range.matches()) {
      return null;
    }
    try {
      LocalTime opens = LocalTime.parse(range.group(1));
      LocalTime closes = LocalTime.parse(range.group(2));
      if (!opens.isBefore(closes)) {
        return null;
      }
      return !start.isBefore(opens) && start.isBefore(closes) && (end == null || !end.isAfter(closes));
    } catch (java.time.DateTimeException ex) {
      return null;
    }
  }

  private List<Stop> timed(List<Stop> stops) {
    return stops.stream().filter(stop -> stop.start() != null).sorted(CHRONOLOGICAL).toList();
  }

  private Set<LegKey> pairs(List<Stop> stops) {
    Set<LegKey> pairs = new HashSet<>();
    for (int i = 1; i < stops.size(); i++) {
      pairs.add(new LegKey(stops.get(i - 1).id(), stops.get(i).id()));
    }
    return pairs;
  }

  private boolean affected(Stop from, Stop to, Set<String> changed, Set<LegKey> previousLegs) {
    return !changed.isEmpty() && (changed.contains(from.id()) || changed.contains(to.id())
        || !previousLegs.contains(new LegKey(from.id(), to.id())));
  }

  /** 只接受唯一同名结果；已有坐标时还要求 POI 与该坐标接近。 */
  final class Lookups {
    private static final int MAX_CALLS = 16;
    private final long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    private final Map<PoiKey, Poi> pois = new HashMap<>();
    private final Map<RouteKey, OptionalLong> routes = new HashMap<>();
    private int calls;

    Poi poi(String name, String city, Coordinate nearby) {
      PoiKey key = new PoiKey(name, city, nearby);
      if (pois.containsKey(key)) {
        return pois.get(key);
      }
      Poi poi = null;
      Duration timeout = nextTimeout();
      if (timeout != null) {
        try {
          Map<String, Object> found = places.searchPois(name, city, null, 8, timeout);
          if (found.get("places") instanceof List<?> result) {
            List<Poi> matching = new ArrayList<>();
            for (Object item : result) {
              if (item instanceof Map<?, ?> hit && name.equals(Payloads.trimmedOrNull(hit.get("name")))) {
                Coordinate coords = Coordinate.of(hit.get("latitude"), hit.get("longitude"));
                if (coords != null && (nearby == null || nearby.near(coords))) {
                  matching.add(new Poi(coords, Payloads.trimmedOrNull(hit.get("openTime"))));
                }
              }
            }
            List<Poi> distinct = matching.stream().distinct().toList();
            if (distinct.size() == 1) {
              poi = distinct.get(0);
            }
          }
        } catch (com.travelos.common.ApiException ignored) {
          // 外部数据缺失时继续检查其他项目。
        }
      }
      pois.put(key, poi);
      return poi;
    }

    OptionalLong travel(Coordinate from, Coordinate to, String mode) {
      if (from == null || to == null) {
        return OptionalLong.empty();
      }
      if (from.equals(to)) {
        return OptionalLong.of(0);
      }
      return routes.computeIfAbsent(new RouteKey(from, to, mode), key -> {
        Duration timeout = nextTimeout();
        return timeout == null ? OptionalLong.empty()
            : places.routeSeconds(from.parameter(), to.parameter(), mode, timeout);
      });
    }

    private Duration nextTimeout() {
      long remaining = deadline - System.nanoTime();
      if (calls >= MAX_CALLS || remaining <= 0 || Thread.currentThread().isInterrupted()) {
        return null;
      }
      calls++;
      return Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(3).toNanos()));
    }
  }

  record Coordinate(BigDecimal latitude, BigDecimal longitude) {
    static Coordinate of(Object latitude, Object longitude) {
      try {
        BigDecimal lat = new BigDecimal(String.valueOf(latitude)).stripTrailingZeros();
        BigDecimal lon = new BigDecimal(String.valueOf(longitude)).stripTrailingZeros();
        return lat.abs().doubleValue() <= 90 && lon.abs().doubleValue() <= 180
            && lat.scale() <= 8 && lon.scale() <= 8 ? new Coordinate(lat, lon) : null;
      } catch (NumberFormatException ex) {
        return null;
      }
    }

    boolean near(Coordinate other) {
      return Math.abs(latitude.doubleValue() - other.latitude.doubleValue()) < 0.002
          && Math.abs(longitude.doubleValue() - other.longitude.doubleValue()) < 0.002;
    }

    String parameter() {
      return Fmt.coord(longitude.setScale(6, RoundingMode.HALF_UP)) + ","
          + Fmt.coord(latitude.setScale(6, RoundingMode.HALF_UP));
    }
  }

  record Stop(String id, String name, Instant start, Instant end, Coordinate from, Coordinate to,
              boolean booking, int bufferMinutes) {}
  record Poi(Coordinate coordinate, String hours) {}
  record Issue(String kind, boolean conflict, boolean affected, String message) {}
  record Leg(String from, String to, Long durationSeconds, Long availableSeconds, int arrivalBufferMinutes) {}
  record Report(String status, String mode, List<Leg> legs, List<Issue> issues) {}
  private record LegKey(String from, String to) {}
  private record PoiKey(String name, String city, Coordinate nearby) {}
  private record RouteKey(Coordinate from, Coordinate to, String mode) {}
}
