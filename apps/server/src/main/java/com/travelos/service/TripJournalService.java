package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.Photo;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripJournal;
import com.travelos.entity.TripJournalAudience;
import com.travelos.entity.TripJournalPhoto;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripJournalAudienceMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripJournalPhotoMapper;
import com.travelos.mapper.TripMapper;
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
import java.util.function.Supplier;

@Service
public class TripJournalService {

  private static final Set<String> MOODS = Set.of("happy", "excited", "tired", "sad", "relaxed");
  private static final Set<String> VISIBILITIES = Set.of("public", "friends", "private", "specified");

  private final TripJournalMapper journalMapper;
  private final TripJournalPhotoMapper journalPhotoMapper;
  private final TripJournalAudienceMapper audienceMapper;
  private final PhotoMapper photoMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMapper tripMapper;
  private final TripMemberService tripMemberService;
  private final FriendService friendService;
  private final AuditService auditService;

  public TripJournalService(TripJournalMapper journalMapper,
      TripJournalPhotoMapper journalPhotoMapper, TripJournalAudienceMapper audienceMapper,
      PhotoMapper photoMapper, TripDayMapper tripDayMapper, TripPlaceMapper tripPlaceMapper,
      TripMapper tripMapper, TripMemberService tripMemberService, FriendService friendService,
      AuditService auditService) {
    this.journalMapper = journalMapper;
    this.journalPhotoMapper = journalPhotoMapper;
    this.audienceMapper = audienceMapper;
    this.photoMapper = photoMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMapper = tripMapper;
    this.tripMemberService = tripMemberService;
    this.friendService = friendService;
    this.auditService = auditService;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    requireTripMember(tripId, userId);
    List<TripJournal> journals = journalMapper.selectList(new LambdaQueryWrapper<TripJournal>()
        .eq(TripJournal::getTripId, tripId)
        .orderByDesc(TripJournal::getCreatedAt));
    // 可见性过滤：私有仅作者、好友仅互为好友、指定仅名单内；草稿仅作者可见
    return Map.of("journals", journals.stream()
        .filter(journal -> canView(journal, userId))
        .map(journal -> toJournalResponse(journal, true))
        .toList());
  }

  public Map<String, Object> findOne(String tripId, String journalId, String userId) {
    TripJournal journal = requireJournal(tripId, journalId);
    if (!canView(journal, userId)) {
      throw ApiException.forbidden("没有权限查看这篇攻略");
    }
    return Map.of("journal", toJournalResponse(journal, true));
  }

  /**
   * 全局可见攻略流：跨行程返回当前用户按可见性规则能看到的所有攻略，
   * 用于好友/公开攻略的发现（作者自己的草稿也会出现在这里）。
   */
  public Map<String, Object> feed(String userId) {
    return bypassed(() -> {
      List<TripJournal> all = journalMapper.selectList(new LambdaQueryWrapper<TripJournal>()
          .orderByDesc(TripJournal::getCreatedAt));
      List<Map<String, Object>> items = new ArrayList<>();
      for (TripJournal journal : all) {
        if (!canView(journal, userId)) {
          continue;
        }
        Map<String, Object> item = toJournalResponse(journal, false);
        Trip trip = tripMapper.selectById(journal.getTripId());
        if (trip == null || trip.getDeletedAt() != null) {
          continue; // 行程已删除的攻略不出现在攻略流
        }
        item.put("tripTitle", trip.getTitle());
        item.put("tripDestination", trip.getDestination());
        items.add(item);
      }
      return Map.of("journals", items);
    });
  }

  /** 全局单篇详情（不要求行程成员身份），按可见性规则校验。 */
  public Map<String, Object> findGlobally(String journalId, String userId) {
    TripJournal journal = bypassed(() -> journalMapper.selectById(journalId));
    if (journal == null) {
      throw ApiException.notFound("攻略不存在");
    }
    if (!canView(journal, userId)) {
      throw ApiException.forbidden("没有权限查看这篇攻略");
    }
    boolean isAuthor = journal.getAuthorId() != null && journal.getAuthorId().equals(userId);
    boolean canEdit = isAuthor
        || (!Boolean.TRUE.equals(journal.getIsDraft())
            && friendService.isMutualFriend(journal.getAuthorId(), userId));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("journal", toJournalResponse(journal, true));
    body.put("canEdit", canEdit);
    Trip trip = bypassed(() -> tripMapper.selectById(journal.getTripId()));
    if (trip != null) {
      Map<String, Object> tripMap = new LinkedHashMap<>();
      tripMap.put("id", trip.getId());
      tripMap.put("title", trip.getTitle());
      tripMap.put("destination", trip.getDestination());
      body.put("trip", tripMap);
    }
    return body;
  }

  /** 按 ID 加载攻略（bypass，供共同编辑等跨成员场景使用；不存在抛 404）。 */
  public TripJournal requireJournalGlobal(String journalId) {
    return bypassed(() -> {
      TripJournal journal = journalMapper.selectById(journalId);
      if (journal == null) {
        throw ApiException.notFound("攻略不存在");
      }
      return journal;
    });
  }

  /**
   * 全局更新（共同编辑入口，不要求行程成员身份）：
   *   - 编辑资格：作者或互为好友（且对该攻略可见）；
   *   - 互斥约束：必须持有该攻略的编辑锁（JournalCollabService.requireLockHolder）；
   *   - 可见性/名单仅作者可改；草稿状态仅作者可改。
   */
  @Transactional
  public Map<String, Object> updateGlobally(String journalId, Map<String, Object> dto, String userId) {
    TripJournal journal = requireJournalGlobal(journalId);
    boolean isAuthor = journal.getAuthorId() != null && journal.getAuthorId().equals(userId);
    requireEditor(journal, userId);
    if (dto.containsKey("title") && dto.get("title") != null) {
      journal.setTitle(normalizeTitle(string(dto.get("title"))));
    }
    if (dto.containsKey("content") && dto.get("content") != null) {
      journal.setContent(validateContent(string(dto.get("content"))));
    }
    if (dto.containsKey("mood")) {
      journal.setMood(validateMood(optionalString(dto.get("mood"))));
    }
    if (isAuthor) {
      if (dto.containsKey("isDraft") && dto.get("isDraft") != null) {
        journal.setIsDraft(Boolean.parseBoolean(dto.get("isDraft").toString()));
      }
      if (dto.containsKey("visibility") && dto.get("visibility") != null) {
        journal.setVisibility(normalizeVisibility(dto.get("visibility")));
      }
    }
    journal.setUpdatedAt(new Date());
    bypassed(() -> {
      journalMapper.updateById(journal);
      return null;
    });
    if (isAuthor && dto.containsKey("photoIds")) {
      List<String> photoIds = validatePhotos(journal.getTripId(), stringList(dto.get("photoIds")));
      saveJournalPhotos(journalId, photoIds);
    }
    if (isAuthor && (dto.containsKey("visibility") || dto.containsKey("visibleFriendIds"))) {
      String visibility = journal.getVisibility() == null ? "private" : journal.getVisibility();
      if ("specified".equals(visibility)) {
        saveAudience(journalId, normalizeAudience(journal.getAuthorId(), stringList(dto.get("visibleFriendIds"))));
      } else {
        saveAudience(journalId, List.of());
      }
    }
    return Map.of("journal", toJournalResponse(journal, true));
  }

  private void requireEditor(TripJournal journal, String userId) {
    boolean isAuthor = journal.getAuthorId() != null && journal.getAuthorId().equals(userId);
    if (isAuthor) {
      return;
    }
    boolean canView = canView(journal, userId);
    boolean mutual = friendService.isMutualFriend(journal.getAuthorId(), userId);
    if (!(canView && mutual)) {
      throw ApiException.forbidden("只有作者与互为好友（且对 TA 可见）的用户可以共同编辑这篇攻略");
    }
  }

  // ==================== 可见性 ====================

  /** 可见性判定（data-permission 过滤会干扰跨行程读取，统一在 bypass 中执行查询）。 */
  public boolean canView(TripJournal journal, String viewerId) {
    if (journal.getAuthorId() != null && journal.getAuthorId().equals(viewerId)) {
      return true; // 作者本人
    }
    if (Boolean.TRUE.equals(journal.getIsDraft())) {
      return false; // 草稿仅作者可见
    }
    String visibility = journal.getVisibility() == null ? "private" : journal.getVisibility();
    return bypassed(() -> switch (visibility) {
      case "public" -> true; // 公开：所有人可见
      case "friends" -> friendService.isMutualFriend(journal.getAuthorId(), viewerId); // 好友可见
      case "specified" -> audienceIds(journal.getId()).contains(viewerId); // 指定好友可见
      default -> false; // 私有：仅自己
    });
  }

  private String normalizeVisibility(Object raw) {
    String value = raw == null ? "private" : raw.toString().trim();
    if (!VISIBILITIES.contains(value)) {
      throw ApiException.badRequest("不支持的可见性设置");
    }
    return value;
  }

  /** 指定好友名单：只保留与作者是互为好友的用户（防越权指定）。 */
  private List<String> normalizeAudience(String authorId, List<String> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      throw ApiException.badRequest("指定好友可见时，请至少选择一位好友");
    }
    List<String> valid = userIds.stream().distinct()
        .filter(id -> friendService.isMutualFriend(authorId, id))
        .toList();
    if (valid.isEmpty()) {
      throw ApiException.badRequest("所选用户均不是你的好友，请先添加好友");
    }
    return valid;
  }

  private void saveAudience(String journalId, List<String> userIds) {
    audienceMapper.delete(new LambdaQueryWrapper<TripJournalAudience>()
        .eq(TripJournalAudience::getJournalId, journalId));
    for (String userId : userIds) {
      TripJournalAudience row = new TripJournalAudience();
      row.setJournalId(journalId);
      row.setUserId(userId);
      row.setCreatedAt(new Date());
      audienceMapper.insert(row);
    }
  }

  private List<String> audienceIds(String journalId) {
    return audienceMapper.selectList(new LambdaQueryWrapper<TripJournalAudience>()
            .eq(TripJournalAudience::getJournalId, journalId))
        .stream().map(TripJournalAudience::getUserId).toList();
  }

  /** 绕过行程数据范围过滤执行查询（好友/公开可见的读者不是行程成员）。 */
  private <T> T bypassed(Supplier<T> action) {
    boolean prev = TripScopeContext.isBypassed();
    TripScopeContext.setBypassed(true);
    try {
      return action.get();
    } finally {
      TripScopeContext.setBypassed(prev);
    }
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
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
    // 作者以当前登录用户为准，禁止信任前端传入的 authorId 等字段
    journal.setAuthorId(userId);
    String visibility = normalizeVisibility(dto.get("visibility"));
    journal.setVisibility(visibility);
    journal.setCreatedAt(new Date());
    journal.setUpdatedAt(new Date());
    journalMapper.insert(journal);
    saveJournalPhotos(journal.getId(), photoIds);
    if ("specified".equals(visibility)) {
      saveAudience(journal.getId(), normalizeAudience(userId, stringList(dto.get("visibleFriendIds"))));
    }
    return Map.of("journal", toJournalResponse(journal, true));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String journalId, Map<String, Object> dto,
      String userId) {
    TripJournal journal = requireJournal(tripId, journalId);
    // 数据隔离：仅作者本人或行程 owner/admin 可修改（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, journal.getAuthorId(), userId, "作者");
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
    if (dto.containsKey("visibility") && dto.get("visibility") != null) {
      journal.setVisibility(normalizeVisibility(dto.get("visibility")));
    }
    journal.setUpdatedAt(new Date());
    journalMapper.updateById(journal);

    if (dto.containsKey("photoIds")) {
      List<String> photoIds = validatePhotos(tripId, stringList(dto.get("photoIds")));
      saveJournalPhotos(journalId, photoIds);
    }
    if (dto.containsKey("visibility") || dto.containsKey("visibleFriendIds")) {
      String visibility = journal.getVisibility() == null ? "private" : journal.getVisibility();
      if ("specified".equals(visibility)) {
        // 名单以作者的好友关系为基准（owner/admin 代改时同样只能指定作者的好友）
        saveAudience(journalId, normalizeAudience(journal.getAuthorId(), stringList(dto.get("visibleFriendIds"))));
      } else {
        saveAudience(journalId, List.of()); // 非指定模式清空名单
      }
    }
    return Map.of("journal", toJournalResponse(journal, true));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String journalId, String userId) {
    TripJournal journal = requireJournal(tripId, journalId);
    // 数据隔离：仅作者本人或行程 owner/admin 可删除（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, journal.getAuthorId(), userId, "作者");
    journalPhotoMapper.delete(new LambdaQueryWrapper<TripJournalPhoto>()
        .eq(TripJournalPhoto::getJournalId, journalId));
    audienceMapper.delete(new LambdaQueryWrapper<TripJournalAudience>()
        .eq(TripJournalAudience::getJournalId, journalId));
    journalMapper.deleteById(journalId);
    auditService.record(userId, tripId, "journal_deleted", "trip_journal", journalId,
        Map.of("title", journal.getTitle()));
    return Map.of("success", true);
  }

  @Transactional
  public Map<String, Object> reorderPhotos(String tripId, String journalId, List<String> photoIds,
      String userId) {
    TripJournal journal = requireJournal(tripId, journalId);
    // 数据隔离：仅作者本人或行程 owner/admin 可调整照片顺序（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, journal.getAuthorId(), userId, "作者");
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
      // trip_journal_photos 是复合主键（journal_id, photo_id），没有 id 列，
      // 因此不能用 MyBatis-Plus 的 updateById（会报 Invalid bound statement），
      // 必须按复合键 UPDATE。
      journalPhotoMapper.update(null, new LambdaUpdateWrapper<TripJournalPhoto>()
          .eq(TripJournalPhoto::getJournalId, journalId)
          .eq(TripJournalPhoto::getPhotoId, photoIds.get(index))
          .set(TripJournalPhoto::getOrderIndex, index));
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
    // 辅助信息（行程天/地点/照片）在 bypass 中读取：好友等非成员读者也需要完整内容，
    // 访问权限已由 canView 统一判定
    return bypassed(() -> toJournalResponseInner(journal, includePhotos));
  }

  private Map<String, Object> toJournalResponseInner(TripJournal journal, boolean includePhotos) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", journal.getId());
    map.put("tripId", journal.getTripId());
    map.put("tripDayId", journal.getTripDayId());
    map.put("tripPlaceId", journal.getTripPlaceId());
    map.put("title", journal.getTitle());
    map.put("content", journal.getContent());
    map.put("isDraft", journal.getIsDraft());
    map.put("mood", journal.getMood());
    map.put("visibility", journal.getVisibility() == null ? "private" : journal.getVisibility());
    map.put("authorId", journal.getAuthorId());
    if ("specified".equals(journal.getVisibility())) {
      map.put("visibleFriendIds", audienceIds(journal.getId()));
    }
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
