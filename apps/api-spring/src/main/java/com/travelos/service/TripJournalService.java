package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.Photo;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripJournal;
import com.travelos.entity.TripJournalPhoto;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripJournalPhotoMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TripJournalService {

  private static final Set<String> MOODS = Set.of("happy", "excited", "tired", "sad", "relaxed");

  private final TripJournalMapper journalMapper;
  private final TripJournalPhotoMapper journalPhotoMapper;
  private final PhotoMapper photoMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;

  public TripJournalService(TripJournalMapper journalMapper,
      TripJournalPhotoMapper journalPhotoMapper, PhotoMapper photoMapper,
      TripDayMapper tripDayMapper, TripPlaceMapper tripPlaceMapper,
      TripMemberService tripMemberService) {
    this.journalMapper = journalMapper;
    this.journalPhotoMapper = journalPhotoMapper;
    this.photoMapper = photoMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    requireTripMember(tripId, userId);
    List<TripJournal> journals = journalMapper.selectList(new LambdaQueryWrapper<TripJournal>()
        .eq(TripJournal::getTripId, tripId)
        .orderByDesc(TripJournal::getCreatedAt));
    return Map.of("journals", journals.stream()
        .map(journal -> toJournalResponse(journal, true))
        .toList());
  }

  public Map<String, Object> findOne(String tripId, String journalId, String userId) {
    requireTripMember(tripId, userId);
    return Map.of("journal", toJournalResponse(requireJournal(tripId, journalId), true));
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    requireTripMember(tripId, userId);
    String tripDayId = optionalString(dto.get("tripDayId"));
    String tripPlaceId = optionalString(dto.get("tripPlaceId"));
    validateRelations(tripId, tripDayId, tripPlaceId);
    List<String> photoIds = validatePhotos(tripId, stringList(dto.get("photoIds")));

    TripJournal journal = new TripJournal();
    journal.setTripId(tripId);
    journal.setTripDayId(tripDayId);
    journal.setTripPlaceId(tripPlaceId);
    journal.setTitle(normalizeTitle(string(dto.get("title"))));
    journal.setContent(validateContent(string(dto.get("content"))));
    journal.setMood(validateMood(optionalString(dto.get("mood"))));
    journal.setIsDraft(dto.get("isDraft") == null || Boolean.parseBoolean(dto.get("isDraft").toString()));
    journal.setCreatedAt(new Date());
    journal.setUpdatedAt(new Date());
    journalMapper.insert(journal);
    saveJournalPhotos(journal.getId(), photoIds);
    return Map.of("journal", toJournalResponse(journal, true));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String journalId, Map<String, Object> dto,
      String userId) {
    requireTripMember(tripId, userId);
    TripJournal journal = requireJournal(tripId, journalId);
    if (dto.containsKey("tripDayId")) {
      String tripDayId = optionalString(dto.get("tripDayId"));
      if (tripDayId != null) {
        validateRelations(tripId, tripDayId, null);
      }
      journal.setTripDayId(tripDayId);
    }
    if (dto.containsKey("tripPlaceId")) {
      String tripPlaceId = optionalString(dto.get("tripPlaceId"));
      if (tripPlaceId != null) {
        validateRelations(tripId, null, tripPlaceId);
      }
      journal.setTripPlaceId(tripPlaceId);
    }
    if (dto.containsKey("title") && dto.get("title") != null) {
      journal.setTitle(normalizeTitle(string(dto.get("title"))));
    }
    if (dto.containsKey("content") && dto.get("content") != null) {
      journal.setContent(validateContent(string(dto.get("content"))));
    }
    if (dto.containsKey("mood")) {
      journal.setMood(validateMood(optionalString(dto.get("mood"))));
    }
    if (dto.containsKey("isDraft") && dto.get("isDraft") != null) {
      journal.setIsDraft(Boolean.parseBoolean(dto.get("isDraft").toString()));
    }
    journal.setUpdatedAt(new Date());
    journalMapper.updateById(journal);

    if (dto.containsKey("photoIds")) {
      List<String> photoIds = validatePhotos(tripId, stringList(dto.get("photoIds")));
      saveJournalPhotos(journalId, photoIds);
    }
    return Map.of("journal", toJournalResponse(journal, true));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String journalId, String userId) {
    requireTripMember(tripId, userId);
    requireJournal(tripId, journalId);
    journalPhotoMapper.delete(new LambdaQueryWrapper<TripJournalPhoto>()
        .eq(TripJournalPhoto::getJournalId, journalId));
    journalMapper.deleteById(journalId);
    return Map.of("success", true);
  }

  @Transactional
  public Map<String, Object> reorderPhotos(String tripId, String journalId, List<String> photoIds,
      String userId) {
    requireTripMember(tripId, userId);
    TripJournal journal = requireJournal(tripId, journalId);
    validatePhotos(tripId, photoIds);
    List<String> currentPhotoIds = journalPhotos(journalId).stream()
        .map(TripJournalPhoto::getPhotoId)
        .toList();
    Set<String> requested = new LinkedHashSet<>(photoIds);
    if (currentPhotoIds.size() != photoIds.size()
        || !requested.containsAll(currentPhotoIds)) {
      throw ApiException.badRequest("只能调整当前游记已关联照片的顺序");
    }
    for (int index = 0; index < photoIds.size(); index++) {
      TripJournalPhoto link = journalPhotoMapper.selectOne(new LambdaQueryWrapper<TripJournalPhoto>()
          .eq(TripJournalPhoto::getJournalId, journalId)
          .eq(TripJournalPhoto::getPhotoId, photoIds.get(index)));
      if (link != null && link.getOrderIndex() != index) {
        link.setOrderIndex(index);
        journalPhotoMapper.updateById(link);
      }
    }
    return Map.of("journal", toJournalResponse(journal, true));
  }

  private void saveJournalPhotos(String journalId, List<String> photoIds) {
    journalPhotoMapper.delete(new LambdaQueryWrapper<TripJournalPhoto>()
        .eq(TripJournalPhoto::getJournalId, journalId));
    for (int index = 0; index < photoIds.size(); index++) {
      TripJournalPhoto link = new TripJournalPhoto();
      link.setJournalId(journalId);
      link.setPhotoId(photoIds.get(index));
      link.setOrderIndex(index);
      journalPhotoMapper.insert(link);
    }
  }

  private List<TripJournalPhoto> journalPhotos(String journalId) {
    return journalPhotoMapper.selectList(new LambdaQueryWrapper<TripJournalPhoto>()
        .eq(TripJournalPhoto::getJournalId, journalId)
        .orderByAsc(TripJournalPhoto::getOrderIndex));
  }

  private void requireTripMember(String tripId, String userId) {
    try {
      tripMemberService.requireTripMember(tripId, userId);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.forbidden("没有权限访问该旅行的游记");
      }
      throw ex;
    }
  }

  private TripJournal requireJournal(String tripId, String journalId) {
    TripJournal journal = journalMapper.selectById(journalId);
    if (journal == null || !journal.getTripId().equals(tripId)) {
      throw ApiException.notFound("游记不存在");
    }
    return journal;
  }

  private void validateRelations(String tripId, String tripDayId, String tripPlaceId) {
    if (tripDayId != null) {
      TripDay day = tripDayMapper.selectById(tripDayId);
      if (day == null || !day.getTripId().equals(tripId)) {
        throw ApiException.badRequest("关联日期不属于当前旅行");
      }
    }
    if (tripPlaceId != null) {
      TripPlace place = tripPlaceMapper.selectById(tripPlaceId);
      if (place == null || !place.getTripId().equals(tripId)) {
        throw ApiException.badRequest("关联地点不属于当前旅行");
      }
    }
  }

  private List<String> validatePhotos(String tripId, List<String> photoIds) {
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(photoIds));
    if (unique.size() != photoIds.size()) {
      throw ApiException.badRequest("关联照片不能重复");
    }
    if (unique.isEmpty()) {
      return unique;
    }
    List<Photo> photos = photoMapper.selectList(new LambdaQueryWrapper<Photo>()
        .in(Photo::getId, unique));
    if (photos.size() != unique.size()
        || photos.stream().anyMatch(photo -> !photo.getTripId().equals(tripId))) {
      throw ApiException.badRequest("关联照片必须属于当前旅行");
    }
    return unique;
  }

  private String normalizeTitle(String value) {
    String title = value == null ? "" : value.trim();
    if (title.isEmpty() || title.length() > 200) {
      throw ApiException.badRequest("游记标题长度必须为 1-200 个字符");
    }
    return title;
  }

  private String validateContent(String value) {
    if (value != null && value.length() > 20000) {
      throw ApiException.badRequest("游记正文不能超过 20000 个字符");
    }
    return value;
  }

  private String validateMood(String value) {
    if (value != null && !MOODS.contains(value)) {
      throw ApiException.badRequest("不支持的心情标签");
    }
    return value;
  }

  public Map<String, Object> toJournalResponse(TripJournal journal, boolean includePhotos) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", journal.getId());
    map.put("tripId", journal.getTripId());
    map.put("tripDayId", journal.getTripDayId());
    map.put("tripPlaceId", journal.getTripPlaceId());
    map.put("title", journal.getTitle());
    map.put("content", journal.getContent());
    map.put("isDraft", journal.getIsDraft());
    map.put("mood", journal.getMood());
    map.put("createdAt", journal.getCreatedAt());
    map.put("updatedAt", journal.getUpdatedAt());
    if (journal.getTripDayId() != null) {
      TripDay day = tripDayMapper.selectById(journal.getTripDayId());
      if (day != null) {
        Map<String, Object> dayMap = new LinkedHashMap<>();
        dayMap.put("id", day.getId());
        dayMap.put("dayIndex", day.getDayIndex());
        dayMap.put("date", day.getDate());
        map.put("tripDay", dayMap);
      } else {
        map.put("tripDay", null);
      }
    } else {
      map.put("tripDay", null);
    }
    if (journal.getTripPlaceId() != null) {
      TripPlace place = tripPlaceMapper.selectById(journal.getTripPlaceId());
      if (place != null) {
        Map<String, Object> placeMap = new LinkedHashMap<>();
        placeMap.put("id", place.getId());
        placeMap.put("name", place.getName());
        map.put("tripPlace", placeMap);
      } else {
        map.put("tripPlace", null);
      }
    } else {
      map.put("tripPlace", null);
    }
    List<Map<String, Object>> photos = new ArrayList<>();
    if (includePhotos) {
      for (TripJournalPhoto link : journalPhotos(journal.getId())) {
        Photo photo = photoMapper.selectById(link.getPhotoId());
        if (photo == null) {
          continue;
        }
        Map<String, Object> photoMap = new LinkedHashMap<>();
        photoMap.put("id", photo.getId());
        photoMap.put("url", photo.getUrl());
        photoMap.put("caption", photo.getCaption());
        photoMap.put("orderIndex", link.getOrderIndex());
        photos.add(photoMap);
      }
    }
    map.put("photos", photos);
    return map;
  }

  private String string(Object value) {
    return value == null ? null : value.toString();
  }

  private String optionalString(Object value) {
    return value == null ? null : value.toString();
  }

  private List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(Object::toString).toList();
  }
}
