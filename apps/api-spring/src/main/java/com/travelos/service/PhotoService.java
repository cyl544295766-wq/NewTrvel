package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.PhotoUtil;
import com.travelos.entity.Photo;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PhotoService {

  private static final int MAX_PHOTOS_PER_TRIP = 500;

  private final PhotoMapper photoMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;

  public PhotoService(PhotoMapper photoMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberService tripMemberService) {
    this.photoMapper = photoMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    requireTripMember(tripId, userId);
    return Map.of("photos", photoMapper.selectList(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId)
        .orderByDesc(Photo::getUpdatedAt))
        .stream()
        .map(photo -> toPhotoResponse(photo, false))
        .toList());
  }

  public Map<String, Object> findOne(String tripId, String photoId, String userId) {
    requireTripMember(tripId, userId);
    return Map.of("photo", toPhotoResponse(requirePhoto(tripId, photoId), true));
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    requireTripMember(tripId, userId);
    String url = string(dto.get("url"));
    validatePhotoUrl(url);
    long count = photoMapper.selectCount(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId));
    if (count >= MAX_PHOTOS_PER_TRIP) {
      throw ApiException.badRequest("每个旅行最多上传 500 张照片");
    }
    String tripDayId = optionalString(dto.get("tripDayId"));
    String tripPlaceId = optionalString(dto.get("tripPlaceId"));
    validateRelations(tripId, tripDayId, tripPlaceId);
    boolean isCover = dto.get("isCover") != null && Boolean.parseBoolean(dto.get("isCover").toString());

    Photo photo = new Photo();
    photo.setTripId(tripId);
    photo.setTripDayId(tripDayId);
    photo.setTripPlaceId(tripPlaceId);
    photo.setUrl(url);
    photo.setCaption(toNullable(dto.get("caption")));
    photo.setShotAt(dto.get("shotAt") == null ? null : DateUtil.parse(dto.get("shotAt").toString()));
    photo.setIsCover(isCover);
    photo.setCreatedAt(new Date());
    photo.setUpdatedAt(new Date());
    photoMapper.insert(photo);
    if (isCover) {
      clearOtherCovers(tripId, photo.getId());
    }
    return Map.of("photo", toPhotoResponse(photo, true));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String photoId, Map<String, Object> dto,
      String userId) {
    requireTripMember(tripId, userId);
    Photo photo = requirePhoto(tripId, photoId);
    if (dto.containsKey("tripDayId")) {
      String tripDayId = optionalString(dto.get("tripDayId"));
      if (tripDayId != null) {
        validateRelations(tripId, tripDayId, null);
      }
      photo.setTripDayId(tripDayId);
    }
    if (dto.containsKey("tripPlaceId")) {
      String tripPlaceId = optionalString(dto.get("tripPlaceId"));
      if (tripPlaceId != null) {
        validateRelations(tripId, null, tripPlaceId);
      }
      photo.setTripPlaceId(tripPlaceId);
    }
    if (dto.containsKey("caption")) {
      photo.setCaption(toNullable(dto.get("caption")));
    }
    if (dto.containsKey("shotAt")) {
      photo.setShotAt(dto.get("shotAt") == null ? null : DateUtil.parse(dto.get("shotAt").toString()));
    }
    if (dto.containsKey("isCover") && dto.get("isCover") != null) {
      photo.setIsCover(Boolean.parseBoolean(dto.get("isCover").toString()));
    }
    photo.setUpdatedAt(new Date());
    photoMapper.updateById(photo);
    if (Boolean.TRUE.equals(photo.getIsCover())) {
      clearOtherCovers(tripId, photo.getId());
    }
    return Map.of("photo", toPhotoResponse(photo, true));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String photoId, String userId) {
    requireTripMember(tripId, userId);
    requirePhoto(tripId, photoId);
    photoMapper.deleteById(photoId);
    return Map.of("success", true);
  }

  private void clearOtherCovers(String tripId, String photoId) {
    for (Photo other : photoMapper.selectList(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getTripId, tripId)
        .eq(Photo::getIsCover, true))) {
      if (!other.getId().equals(photoId)) {
        other.setIsCover(false);
        photoMapper.updateById(other);
      }
    }
  }

  private void requireTripMember(String tripId, String userId) {
    try {
      tripMemberService.requireTripMember(tripId, userId);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.forbidden("No permission to access photos for this trip");
      }
      throw ex;
    }
  }

  private Photo requirePhoto(String tripId, String photoId) {
    Photo photo = photoMapper.selectById(photoId);
    if (photo == null || !photo.getTripId().equals(tripId)) {
      throw ApiException.notFound("照片不存在");
    }
    return photo;
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

  private void validatePhotoUrl(String url) {
    String[] parts = PhotoUtil.parsePhotoDataUrl(url);
    if (parts == null) {
      throw ApiException.badRequest("仅支持 jpg、png、webp 图片");
    }
    if (PhotoUtil.base64Bytes(parts[1]) > PhotoUtil.MAX_PHOTO_BYTES) {
      throw ApiException.badRequest("单张图片不能超过 5MB");
    }
  }

  public Map<String, Object> toPhotoResponse(Photo photo, boolean includeRelations) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", photo.getId());
    map.put("tripId", photo.getTripId());
    map.put("tripDayId", photo.getTripDayId());
    map.put("tripPlaceId", photo.getTripPlaceId());
    map.put("url", photo.getUrl());
    map.put("caption", photo.getCaption());
    map.put("shotAt", photo.getShotAt());
    map.put("isCover", photo.getIsCover());
    map.put("createdAt", photo.getCreatedAt());
    map.put("updatedAt", photo.getUpdatedAt());
    if (includeRelations) {
      if (photo.getTripDayId() != null) {
        TripDay day = tripDayMapper.selectById(photo.getTripDayId());
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
      if (photo.getTripPlaceId() != null) {
        TripPlace place = tripPlaceMapper.selectById(photo.getTripPlaceId());
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
    }
    return map;
  }

  private String string(Object value) {
    return value == null ? null : value.toString();
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
}
