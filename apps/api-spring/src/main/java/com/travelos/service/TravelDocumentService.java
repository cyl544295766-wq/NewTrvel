package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.PhotoUtil;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TravelDocumentService {

  private static final int MAX_DOCUMENTS_PER_TRIP = 100;

  private final TravelDocumentMapper documentMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;

  public TravelDocumentService(TravelDocumentMapper documentMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberService tripMemberService) {
    this.documentMapper = documentMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    requireTripMember(tripId, userId);
    return Map.of("documents", findDocumentsByTrip(tripId).stream()
        .map(document -> toDocumentResponse(document, true))
        .toList());
  }

  public Map<String, Object> findOne(String tripId, String documentId, String userId) {
    requireTripMember(tripId, userId);
    return Map.of("document", toDocumentResponse(requireDocument(tripId, documentId), true));
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    requireEditableMember(tripId, userId);
    String url = string(dto.get("url"));
    validateDocumentUrl(url);
    long count = documentMapper.selectCount(new LambdaQueryWrapper<TravelDocument>()
        .eq(TravelDocument::getTripId, tripId));
    if (count >= MAX_DOCUMENTS_PER_TRIP) {
      throw ApiException.badRequest("每个旅行最多上传 100 份文档");
    }
    String tripDayId = optionalString(dto.get("tripDayId"));
    String tripPlaceId = optionalString(dto.get("tripPlaceId"));
    validateRelations(tripId, tripDayId, tripPlaceId);

    TravelDocument document = new TravelDocument();
    document.setTripId(tripId);
    document.setTripDayId(tripDayId);
    document.setTripPlaceId(tripPlaceId);
    document.setType(requiredString(dto.get("type"), "type should not be empty"));
    document.setTitle(normalizeTitle(string(dto.get("title"))));
    document.setUrl(url);
    document.setNotes(toNullable(dto.get("notes")));
    document.setExpiredAt(dto.get("expiredAt") == null ? null
        : DateUtil.parse(dto.get("expiredAt").toString()));
    document.setIsReminder(dto.get("isReminder") != null
        && Boolean.parseBoolean(dto.get("isReminder").toString()));
    // 属主以当前登录用户为准，禁止信任前端传入的 uploaderId 等字段
    document.setUploaderId(userId);
    document.setCreatedAt(new Date());
    document.setUpdatedAt(new Date());
    documentMapper.insert(document);
    return Map.of("document", toDocumentResponse(document, true));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String documentId, Map<String, Object> dto,
      String userId) {
    requireEditableMember(tripId, userId);
    TravelDocument document = requireDocument(tripId, documentId);
    // 数据隔离：仅上传者本人或行程 owner/admin 可修改（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, document.getUploaderId(), userId, "上传者");
    if (dto.containsKey("url") && dto.get("url") != null) {
      validateDocumentUrl(dto.get("url").toString());
      document.setUrl(dto.get("url").toString());
    }
    if (dto.containsKey("tripDayId")) {
      String tripDayId = optionalString(dto.get("tripDayId"));
      if (tripDayId != null) {
        validateRelations(tripId, tripDayId, null);
      }
      document.setTripDayId(tripDayId);
    }
    if (dto.containsKey("tripPlaceId")) {
      String tripPlaceId = optionalString(dto.get("tripPlaceId"));
      if (tripPlaceId != null) {
        validateRelations(tripId, null, tripPlaceId);
      }
      document.setTripPlaceId(tripPlaceId);
    }
    if (dto.containsKey("type") && dto.get("type") != null) {
      document.setType(dto.get("type").toString());
    }
    if (dto.containsKey("title") && dto.get("title") != null) {
      document.setTitle(normalizeTitle(string(dto.get("title"))));
    }
    if (dto.containsKey("notes")) {
      document.setNotes(toNullable(dto.get("notes")));
    }
    if (dto.containsKey("expiredAt")) {
      document.setExpiredAt(dto.get("expiredAt") == null ? null
          : DateUtil.parse(dto.get("expiredAt").toString()));
    }
    if (dto.containsKey("isReminder") && dto.get("isReminder") != null) {
      document.setIsReminder(Boolean.parseBoolean(dto.get("isReminder").toString()));
    }
    document.setUpdatedAt(new Date());
    documentMapper.updateById(document);
    return Map.of("document", toDocumentResponse(document, true));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String documentId, String userId) {
    requireEditableMember(tripId, userId);
    TravelDocument document = requireDocument(tripId, documentId);
    // 数据隔离：仅上传者本人或行程 owner/admin 可删除（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, document.getUploaderId(), userId, "上传者");
    documentMapper.deleteById(documentId);
    return Map.of("success", true);
  }

  public List<TravelDocument> findDocumentsByTrip(String tripId) {
    return documentMapper.selectList(new LambdaQueryWrapper<TravelDocument>()
        .eq(TravelDocument::getTripId, tripId)
        .orderByDesc(TravelDocument::getCreatedAt));
  }

  private void requireTripMember(String tripId, String userId) {
    try {
      tripMemberService.requireTripMember(tripId, userId);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.forbidden("没有权限访问该旅行的文档");
      }
      throw ex;
    }
  }

  /** 写操作统一角色收紧：viewer（只读角色）一律 403。 */
  private void requireEditableMember(String tripId, String userId) {
    try {
      tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.forbidden("没有权限访问该旅行的文档");
      }
      throw ex;
    }
  }

  private TravelDocument requireDocument(String tripId, String documentId) {
    TravelDocument document = documentMapper.selectById(documentId);
    if (document == null || !document.getTripId().equals(tripId)) {
      throw ApiException.notFound("文档不存在");
    }
    return document;
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

  private void validateDocumentUrl(String url) {
    String[] parts = PhotoUtil.parseDocumentDataUrl(url);
    if (parts == null) {
      throw ApiException.badRequest("仅支持 PDF、jpg、png、webp 文件");
    }
    if (PhotoUtil.base64Bytes(parts[1]) > PhotoUtil.MAX_DOCUMENT_BYTES) {
      throw ApiException.badRequest("单个文档不能超过 10MB");
    }
  }

  public Map<String, Object> toDocumentResponse(TravelDocument document, boolean includeRelations) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", document.getId());
    map.put("tripId", document.getTripId());
    map.put("tripDayId", document.getTripDayId());
    map.put("tripPlaceId", document.getTripPlaceId());
    map.put("type", document.getType());
    map.put("title", document.getTitle());
    map.put("url", document.getUrl());
    map.put("notes", document.getNotes());
    map.put("expiredAt", document.getExpiredAt());
    map.put("isReminder", document.getIsReminder());
    map.put("createdAt", document.getCreatedAt());
    map.put("updatedAt", document.getUpdatedAt());
    if (includeRelations) {
      if (document.getTripDayId() != null) {
        TripDay day = tripDayMapper.selectById(document.getTripDayId());
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
      if (document.getTripPlaceId() != null) {
        TripPlace place = tripPlaceMapper.selectById(document.getTripPlaceId());
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

  private String normalizeTitle(String value) {
    String title = value == null ? "" : value.trim();
    if (title.isEmpty() || title.length() > 100) {
      throw ApiException.badRequest("文档标题长度必须为 1-100 个字符");
    }
    return title;
  }

  private String string(Object value) {
    return value == null ? null : value.toString();
  }

  private String optionalString(Object value) {
    return value == null ? null : value.toString();
  }

  private String requiredString(Object value, String message) {
    if (value == null || value.toString().isBlank()) {
      throw ApiException.badRequest(message);
    }
    return value.toString();
  }

  private String toNullable(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
