package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.Fmt;
import com.travelos.common.ManagedFileNames;
import com.travelos.common.Payloads;
import com.travelos.entity.Photo;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class TripRecapMaterials {
  static final int MAX_PHOTOS = 12;
  static final int MAX_NOTES = 16;
  private final TripDayService tripDays;
  private final PhotoMapper photoMapper;
  private final TripPlaceMapper placeMapper;

  TripRecapMaterials(TripDayService tripDays, PhotoMapper photoMapper, TripPlaceMapper placeMapper) {
    this.tripDays = tripDays;
    this.photoMapper = photoMapper;
    this.placeMapper = placeMapper;
  }

  Map<String, Object> list(String tripId, String userId) {
    Schedule schedule = schedule(tripId, userId);
    List<PhotoOption> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId).orderByAsc(Photo::getShotAt, Photo::getCreatedAt, Photo::getId)).stream()
        .map(photo -> new PhotoOption(photo.getId(), schedule.dateOf(photo), schedule.placeOf(photo),
            bounded(photo.getCaption(), 300), ManagedFileNames.isManagedUrl(photo.getUrl()) ? photo.getUrl() : null))
        .toList();
    return Map.of("photos", photos, "notes", new ArrayList<>(schedule.notes().values()),
        "maxPhotos", MAX_PHOTOS, "maxNotes", MAX_NOTES);
  }

  Schedule schedule(String tripId, String userId) {
    Map<String, Note> notes = new LinkedHashMap<>();
    Map<String, String> dayDates = new LinkedHashMap<>();
    Map<String, String> placeNames = new LinkedHashMap<>();
    List<Day> daily = new ArrayList<>();
    if (tripDays.findAll(tripId, userId).get("days") instanceof List<?> rows) {
      for (Object row : rows) {
        Map<?, ?> day = (Map<?, ?>) row;
        String date = day.get("date") instanceof Date value ? Fmt.dateKey(value) : String.valueOf(day.get("date"));
        String dayId = String.valueOf(day.get("id"));
        dayDates.put(dayId, date);
        String label = "第 " + day.get("dayIndex") + " 天";
        addNote(notes, "day:" + dayId, date, label, day.get("summary"));
        List<String> completed = new ArrayList<>();
        List<String> planned = new ArrayList<>();
        if (day.get("places") instanceof List<?> places) {
          for (Object value : places) {
            Map<?, ?> place = (Map<?, ?>) value;
            String name = bounded(place.get("name"), 100);
            String placeId = String.valueOf(place.get("id"));
            if (!name.isEmpty()) {
              placeNames.put(placeId, name);
              (Boolean.TRUE.equals(place.get("isCompleted")) ? completed : planned).add(name);
            }
            addNote(notes, "place:" + placeId, date, name.isEmpty() ? label : name, place.get("notes"));
          }
        }
        daily.add(new Day(date, completed, planned));
      }
    }
    List<String> undatedCompleted = new ArrayList<>();
    List<String> undatedPlanned = new ArrayList<>();
    for (TripPlace place : placeMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId).isNull(TripPlace::getTripDayId)
        .orderByAsc(TripPlace::getCreatedAt, TripPlace::getId))) {
      String name = bounded(place.getName(), 100);
      if (!name.isEmpty()) {
        placeNames.put(place.getId(), name);
        (Boolean.TRUE.equals(place.getIsCompleted()) ? undatedCompleted : undatedPlanned).add(name);
      }
      addNote(notes, "place:" + place.getId(), "", name.isEmpty() ? "未安排日期的地点" : name, place.getNotes());
    }
    if (!undatedCompleted.isEmpty() || !undatedPlanned.isEmpty()) {
      daily.add(new Day("", undatedCompleted, undatedPlanned));
    }
    return new Schedule(daily, notes, dayDates, placeNames);
  }

  List<Note> selectedNotes(Schedule schedule, List<String> ids) {
    List<Note> selected = new ArrayList<>(ids.size());
    for (String id : ids) {
      Note note = schedule.notes().get(id);
      if (note == null) {
        throw ApiException.badRequest("所选笔记已变更或不属于当前行程，请重新选择");
      }
      selected.add(note);
    }
    return selected;
  }

  List<PhotoFact> selectedPhotos(String tripId, Schedule schedule, List<String> ids, boolean lock) {
    if (ids.isEmpty()) {
      return List.of();
    }
    var query = new LambdaQueryWrapper<Photo>().eq(Photo::getTripId, tripId).in(Photo::getId, ids);
    Map<String, Photo> photos = photoMapper.selectList(lock ? query.last("FOR SHARE") : query).stream()
        .collect(Collectors.toMap(Photo::getId, Function.identity()));
    if (photos.size() != ids.size()) {
      throw ApiException.badRequest("所选照片已删除或不属于当前行程，请重新选择");
    }
    return ids.stream().map(photos::get).map(photo -> new PhotoFact(
        schedule.dateOf(photo), schedule.placeOf(photo), bounded(photo.getCaption(), 300))).toList();
  }

  private static void addNote(Map<String, Note> notes, String id, String date, String label, Object value) {
    String text = bounded(value, 450);
    if (!text.isEmpty()) {
      notes.put(id, new Note(id, date, label, text));
    }
  }

  private static String bounded(Object value, int max) {
    String text = Payloads.trimmedOrNull(value);
    return text == null ? "" : text.substring(0, Math.min(max, text.length()));
  }

  record Schedule(List<Day> days, Map<String, Note> notes, Map<String, String> dayDates,
                  Map<String, String> placeNames) {
    String dateOf(Photo photo) {
      return photo.getShotAt() != null ? Fmt.dateKey(photo.getShotAt())
          : dayDates.getOrDefault(photo.getTripDayId(), "");
    }

    String placeOf(Photo photo) {
      return placeNames.getOrDefault(photo.getTripPlaceId(), "");
    }
  }

  record Day(String date, List<String> completed, List<String> planned) {}
  record Note(String id, String date, String label, String text) {}
  record PhotoFact(String date, String place, String caption) {}
  private record PhotoOption(String id, String date, String place, String caption, String url) {}
}
