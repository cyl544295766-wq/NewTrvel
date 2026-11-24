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

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class TripJourneyService {
  private final TripDayMapper days;
  private final TripPlaceMapper places;

  public TripJourneyService(TripDayMapper days, TripPlaceMapper places) {
    this.days = days;
    this.places = places;
  }

  public record Selection(Trip trip, String phase, List<Trip> candidates) {}

  public String phase(Trip trip, LocalDate today) {
    if (trip.getArchivedAt() != null || "archived".equals(trip.getStatus())) return "archived";
    if ("completed".equals(trip.getStatus())) return "past";
    if (trip.getStartDate() == null) return "draft";
    LocalDate start = LocalDate.parse(Fmt.dateKey(trip.getStartDate()));
    LocalDate end = trip.getEndDate() == null ? start : LocalDate.parse(Fmt.dateKey(trip.getEndDate()));
    if (today.isBefore(start)) return "pre-trip";
    return today.isAfter(end) ? "past" : "today";
  }

  public Selection choose(Collection<Trip> trips, String explicitId, LocalDate today) {
    List<Trip> live = trips.stream().filter(Objects::nonNull).filter(t -> t.getDeletedAt() == null).toList();
    if (explicitId != null && !explicitId.isBlank()) {
      Trip selected = live.stream().filter(t -> explicitId.trim().equals(t.getId())).findFirst()
          .orElseThrow(() -> ApiException.notFound("该行程已删除或你已无权访问"));
      return new Selection(selected, phase(selected, today), List.of());
    }
    for (String phase : List.of("today", "pre-trip", "draft", "past")) {
      List<Trip> candidates = live.stream().filter(t -> phase.equals(phase(t, today))).toList();
      if (candidates.isEmpty()) continue;
      if ("pre-trip".equals(phase)) {
        String nearest = candidates.stream().map(t -> Fmt.dateKey(t.getStartDate())).min(String::compareTo).orElseThrow();
        candidates = candidates.stream().filter(t -> nearest.equals(Fmt.dateKey(t.getStartDate()))).toList();
      }
      if (candidates.size() == 1) return new Selection(candidates.get(0), phase, List.of());
      if ("today".equals(phase) || "pre-trip".equals(phase)) {
        record Scored(Trip trip, long places, long updated) {}
        List<Scored> ranked = candidates.stream().map(trip -> {
          TripDay day = dayFor(trip.getId(), phase, today);
          long count = day == null ? 0 : places.selectCount(new LambdaQueryWrapper<TripPlace>()
              .eq(TripPlace::getTripDayId, day.getId()));
          return new Scored(trip, count, trip.getUpdatedAt() == null ? 0 : trip.getUpdatedAt().getTime());
        }).sorted(Comparator.comparingLong(Scored::places).thenComparingLong(Scored::updated).reversed()
            .thenComparing(s -> s.trip().getId())).toList();
        if (ranked.get(0).places() == ranked.get(1).places() && ranked.get(0).updated() == ranked.get(1).updated()) {
          return new Selection(null, "choose", ranked.stream().map(Scored::trip).toList());
        }
        return new Selection(ranked.get(0).trip(), phase, List.of());
      }
      return new Selection(candidates.stream().max(Comparator.comparingLong(
          (Trip t) -> t.getUpdatedAt() == null ? 0 : t.getUpdatedAt().getTime()).thenComparing(Trip::getId)).orElseThrow(),
          phase, List.of());
    }
    return new Selection(null, "none", List.of());
  }

  public TripDay dayFor(String tripId, String phase, LocalDate date) {
    var query = new LambdaQueryWrapper<TripDay>().eq(TripDay::getTripId, tripId);
    if ("pre-trip".equals(phase)) {
      query.orderByAsc(TripDay::getDayIndex);
    } else {
      query.ge(TripDay::getDate, DateUtil.parse(date.toString()))
          .lt(TripDay::getDate, DateUtil.parse(date.plusDays(1).toString()))
          .orderByAsc(TripDay::getDayIndex);
    }
    return days.selectList(query.last("LIMIT 1")).stream().findFirst().orElse(null);
  }
}
