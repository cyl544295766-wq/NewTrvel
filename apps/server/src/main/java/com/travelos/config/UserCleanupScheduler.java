package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.entity.AiPreference;
import com.travelos.entity.AuditLog;
import com.travelos.entity.Booking;
import com.travelos.entity.EditLock;
import com.travelos.entity.FriendMessage;
import com.travelos.entity.Notification;
import com.travelos.entity.PackingItem;
import com.travelos.entity.PackingList;
import com.travelos.entity.Photo;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.Trip;
import com.travelos.entity.TripBudget;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripExpenseShare;
import com.travelos.entity.TripJournal;
import com.travelos.entity.TripJournalAudience;
import com.travelos.entity.TripJournalPhoto;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.entity.TripSettlement;
import com.travelos.entity.TripWeather;
import com.travelos.entity.User;
import com.travelos.entity.UserFriend;
import com.travelos.entity.UserPreference;
import com.travelos.mapper.AiConversationMapper;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.mapper.AiPreferenceMapper;
import com.travelos.mapper.AuditLogMapper;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.EditLockMapper;
import com.travelos.mapper.FriendMessageMapper;
import com.travelos.mapper.NotificationMapper;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripBudgetMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripExpenseShareMapper;
import com.travelos.mapper.TripJournalAudienceMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripJournalPhotoMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.mapper.TripSettlementMapper;
import com.travelos.mapper.TripWeatherMapper;
import com.travelos.mapper.UserFriendMapper;
import com.travelos.mapper.UserMapper;
import com.travelos.mapper.UserPreferenceMapper;
import com.travelos.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 注销账户 30 天保留期后的清理定时任务（设计文档 3.1.1）。
 * 配置 app.user-cleanup.enabled=true 启用（默认开启，保留期 30 天）。
 *
 * 策略（业务外键多为 RESTRICT，按依赖逆序清理）：
 *  1. 独占行程（仅本人是成员）→ 整图物理删除（天/地点/费用/分摊/预算/预订/打包/照片/
 *     文档/游记/结算/天气/审计/AI 会话/编辑锁/成员/行程），受管文件一并清理；
 *  2. 共享行程 → 移除成员身份、本人分摊份额与游记可见性；
 *  3. 个人数据 → 通知、好友关系与私信、AI 会话/消息/偏好、用户偏好、持有的编辑锁；
 *  4. 账户本体 → 无残留引用则物理删除；仍被引用（共享行程留痕）则匿名化为墓碑
 *     （脱敏用户名与昵称、清空头像与口令、tokenValidAfter=now 使全部会话失效）。
 */
@Component
@ConditionalOnProperty(value = "app.user-cleanup.enabled", havingValue = "true")
public class UserCleanupScheduler {

  private static final Logger log = LoggerFactory.getLogger(UserCleanupScheduler.class);
  private static final int RETENTION_DAYS = 30;

  private final UserMapper userMapper;
  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripExpenseMapper tripExpenseMapper;
  private final TripExpenseShareMapper tripExpenseShareMapper;
  private final TripBudgetMapper tripBudgetMapper;
  private final BookingMapper bookingMapper;
  private final PackingListMapper packingListMapper;
  private final PackingItemMapper packingItemMapper;
  private final PhotoMapper photoMapper;
  private final TravelDocumentMapper travelDocumentMapper;
  private final TripJournalMapper tripJournalMapper;
  private final TripJournalPhotoMapper tripJournalPhotoMapper;
  private final TripJournalAudienceMapper tripJournalAudienceMapper;
  private final TripSettlementMapper tripSettlementMapper;
  private final TripWeatherMapper tripWeatherMapper;
  private final AuditLogMapper auditLogMapper;
  private final AiConversationMapper aiConversationMapper;
  private final AiMessageMapper aiMessageMapper;
  private final AiPreferenceMapper aiPreferenceMapper;
  private final UserPreferenceMapper userPreferenceMapper;
  private final UserFriendMapper userFriendMapper;
  private final FriendMessageMapper friendMessageMapper;
  private final NotificationMapper notificationMapper;
  private final EditLockMapper editLockMapper;
  private final FileStorageService fileStorageService;

  public UserCleanupScheduler(UserMapper userMapper, TripMapper tripMapper,
      TripMemberMapper tripMemberMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripExpenseMapper tripExpenseMapper,
      TripExpenseShareMapper tripExpenseShareMapper, TripBudgetMapper tripBudgetMapper,
      BookingMapper bookingMapper, PackingListMapper packingListMapper,
      PackingItemMapper packingItemMapper, PhotoMapper photoMapper,
      TravelDocumentMapper travelDocumentMapper, TripJournalMapper tripJournalMapper,
      TripJournalPhotoMapper tripJournalPhotoMapper,
      TripJournalAudienceMapper tripJournalAudienceMapper,
      TripSettlementMapper tripSettlementMapper, TripWeatherMapper tripWeatherMapper,
      AuditLogMapper auditLogMapper, AiConversationMapper aiConversationMapper,
      AiMessageMapper aiMessageMapper, AiPreferenceMapper aiPreferenceMapper,
      UserPreferenceMapper userPreferenceMapper, UserFriendMapper userFriendMapper,
      FriendMessageMapper friendMessageMapper, NotificationMapper notificationMapper,
      EditLockMapper editLockMapper, FileStorageService fileStorageService) {
    this.userMapper = userMapper;
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripExpenseMapper = tripExpenseMapper;
    this.tripExpenseShareMapper = tripExpenseShareMapper;
    this.tripBudgetMapper = tripBudgetMapper;
    this.bookingMapper = bookingMapper;
    this.packingListMapper = packingListMapper;
    this.packingItemMapper = packingItemMapper;
    this.photoMapper = photoMapper;
    this.travelDocumentMapper = travelDocumentMapper;
    this.tripJournalMapper = tripJournalMapper;
    this.tripJournalPhotoMapper = tripJournalPhotoMapper;
    this.tripJournalAudienceMapper = tripJournalAudienceMapper;
    this.tripSettlementMapper = tripSettlementMapper;
    this.tripWeatherMapper = tripWeatherMapper;
    this.auditLogMapper = auditLogMapper;
    this.aiConversationMapper = aiConversationMapper;
    this.aiMessageMapper = aiMessageMapper;
    this.aiPreferenceMapper = aiPreferenceMapper;
    this.userPreferenceMapper = userPreferenceMapper;
    this.userFriendMapper = userFriendMapper;
    this.friendMessageMapper = friendMessageMapper;
    this.notificationMapper = notificationMapper;
    this.editLockMapper = editLockMapper;
    this.fileStorageService = fileStorageService;
  }

  /** 每天凌晨 3 点执行：清理注销超过 30 天的账户。 */
  @Scheduled(cron = "0 0 3 * * ?")
  @Transactional
  public void purgeExpiredDeactivatedUsers() {
    // 定时任务无请求上下文，显式绕过行程数据范围过滤
    com.travelos.common.TripScopeContext.runBypassed(this::purge);
  }

  private void purge() {
    Calendar calendar = Calendar.getInstance();
    calendar.add(Calendar.DATE, -RETENTION_DAYS);
    Date threshold = calendar.getTime();

    List<User> expired = userMapper.selectList(new LambdaQueryWrapper<User>()
        .eq(User::getStatus, "deleted")
        .isNotNull(User::getDeletedAt)
        .lt(User::getDeletedAt, threshold));

    for (User user : expired) {
      try {
        purgeUser(user);
        log.info("Purged deactivated user: {} (deleted at {})", user.getUsername(), user.getDeletedAt());
      } catch (Exception ex) {
        log.error("Purge failed for user {} ({})", user.getUsername(), user.getId(), ex);
      }
    }
  }

  private void purgeUser(User user) {
    String uid = user.getId();

    // 1) 独占行程整图删除
    List<TripMember> memberships = tripMemberMapper.selectList(
        new LambdaQueryWrapper<TripMember>().eq(TripMember::getUserId, uid));
    for (TripMember membership : memberships) {
      Long others = tripMemberMapper.selectCount(new LambdaQueryWrapper<TripMember>()
          .eq(TripMember::getTripId, membership.getTripId())
          .ne(TripMember::getUserId, uid));
      if (others == 0) {
        deleteTripGraph(membership.getTripId());
      }
    }

    // 2) 共享行程：退出成员、移除分摊份额与游记可见性
    tripMemberMapper.delete(new LambdaQueryWrapper<TripMember>().eq(TripMember::getUserId, uid));
    tripExpenseShareMapper.delete(new LambdaQueryWrapper<TripExpenseShare>()
        .eq(TripExpenseShare::getUserId, uid));
    tripJournalAudienceMapper.delete(new LambdaQueryWrapper<TripJournalAudience>()
        .eq(TripJournalAudience::getUserId, uid));

    // 3) 个人数据
    notificationMapper.delete(new LambdaQueryWrapper<Notification>()
        .eq(Notification::getUserId, uid));
    userFriendMapper.delete(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getUserId, uid).or().eq(UserFriend::getFriendId, uid));
    friendMessageMapper.delete(new LambdaQueryWrapper<FriendMessage>()
        .eq(FriendMessage::getSenderId, uid).or().eq(FriendMessage::getReceiverId, uid));
    aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getUserId, uid));
    List<AiConversation> convs = aiConversationMapper.selectList(
        new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getUserId, uid));
    if (!convs.isEmpty()) {
      List<String> convIds = convs.stream().map(AiConversation::getId).collect(Collectors.toList());
      aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>().in(AiMessage::getConversationId, convIds));
      aiConversationMapper.deleteBatchIds(convIds);
    }
    aiPreferenceMapper.delete(new LambdaQueryWrapper<AiPreference>().eq(AiPreference::getUserId, uid));
    userPreferenceMapper.delete(new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, uid));
    editLockMapper.delete(new LambdaQueryWrapper<EditLock>().eq(EditLock::getHolderId, uid));

    // 4) 账户本体：无残留引用则物理删除，否则匿名化为墓碑
    try {
      userMapper.deleteById(uid);
    } catch (DataIntegrityViolationException ex) {
      anonymize(user);
    }
  }

  /** 独占行程：按外键依赖逆序整图删除，并清理受管文件。 */
  private void deleteTripGraph(String tripId) {
    List<TripDay> days = tripDayMapper.selectList(
        new LambdaQueryWrapper<TripDay>().eq(TripDay::getTripId, tripId));
    List<String> dayIds = days.stream().map(TripDay::getId).collect(Collectors.toList());
    if (!dayIds.isEmpty()) {
      tripPlaceMapper.delete(new LambdaQueryWrapper<TripPlace>().in(TripPlace::getTripDayId, dayIds));
    }
    tripDayMapper.delete(new LambdaQueryWrapper<TripDay>().eq(TripDay::getTripId, tripId));

    List<TripExpense> expenses = tripExpenseMapper.selectList(
        new LambdaQueryWrapper<TripExpense>().eq(TripExpense::getTripId, tripId));
    if (!expenses.isEmpty()) {
      List<String> expenseIds = expenses.stream().map(TripExpense::getId).collect(Collectors.toList());
      tripExpenseShareMapper.delete(new LambdaQueryWrapper<TripExpenseShare>()
          .in(TripExpenseShare::getExpenseId, expenseIds));
    }
    tripExpenseMapper.delete(new LambdaQueryWrapper<TripExpense>().eq(TripExpense::getTripId, tripId));
    tripBudgetMapper.delete(new LambdaQueryWrapper<TripBudget>().eq(TripBudget::getTripId, tripId));
    bookingMapper.delete(new LambdaQueryWrapper<Booking>().eq(Booking::getTripId, tripId));

    List<PackingList> lists = packingListMapper.selectList(
        new LambdaQueryWrapper<PackingList>().eq(PackingList::getTripId, tripId));
    if (!lists.isEmpty()) {
      List<String> listIds = lists.stream().map(PackingList::getId).collect(Collectors.toList());
      packingItemMapper.delete(new LambdaQueryWrapper<PackingItem>()
          .in(PackingItem::getPackingListId, listIds));
    }
    packingListMapper.delete(new LambdaQueryWrapper<PackingList>().eq(PackingList::getTripId, tripId));

    List<Photo> photos = photoMapper.selectList(
        new LambdaQueryWrapper<Photo>().eq(Photo::getTripId, tripId));
    photos.forEach(p -> fileStorageService.deleteIfManaged(p.getUrl()));
    photoMapper.delete(new LambdaQueryWrapper<Photo>().eq(Photo::getTripId, tripId));

    List<TravelDocument> docs = travelDocumentMapper.selectList(
        new LambdaQueryWrapper<TravelDocument>().eq(TravelDocument::getTripId, tripId));
    docs.forEach(d -> fileStorageService.deleteIfManaged(d.getUrl()));
    travelDocumentMapper.delete(new LambdaQueryWrapper<TravelDocument>()
        .eq(TravelDocument::getTripId, tripId));

    List<TripJournal> journals = tripJournalMapper.selectList(
        new LambdaQueryWrapper<TripJournal>().eq(TripJournal::getTripId, tripId));
    if (!journals.isEmpty()) {
      List<String> journalIds = journals.stream().map(TripJournal::getId).collect(Collectors.toList());
      tripJournalPhotoMapper.delete(new LambdaQueryWrapper<TripJournalPhoto>()
          .in(TripJournalPhoto::getJournalId, journalIds));
      tripJournalAudienceMapper.delete(new LambdaQueryWrapper<TripJournalAudience>()
          .in(TripJournalAudience::getJournalId, journalIds));
      for (String journalId : journalIds) {
        editLockMapper.deleteById("journal:" + journalId);
      }
    }
    tripJournalMapper.delete(new LambdaQueryWrapper<TripJournal>().eq(TripJournal::getTripId, tripId));

    tripSettlementMapper.delete(new LambdaQueryWrapper<TripSettlement>()
        .eq(TripSettlement::getTripId, tripId));
    tripWeatherMapper.delete(new LambdaQueryWrapper<TripWeather>().eq(TripWeather::getTripId, tripId));
    auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>().eq(AuditLog::getTripId, tripId));

    List<AiConversation> convs = aiConversationMapper.selectList(
        new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getTripId, tripId));
    if (!convs.isEmpty()) {
      List<String> convIds = convs.stream().map(AiConversation::getId).collect(Collectors.toList());
      aiMessageMapper.delete(new LambdaQueryWrapper<AiMessage>().in(AiMessage::getConversationId, convIds));
      aiConversationMapper.deleteBatchIds(convIds);
    }

    for (String dayId : dayIds) {
      editLockMapper.deleteById(dayId);
    }

    tripMemberMapper.delete(new LambdaQueryWrapper<TripMember>().eq(TripMember::getTripId, tripId));
    tripMapper.deleteById(tripId);
  }

  /** 墓碑匿名化：清空可识别信息，历史引用归于「已注销用户」，全部会话立即失效。 */
  private void anonymize(User user) {
    userMapper.update(null, new LambdaUpdateWrapper<User>()
        .eq(User::getId, user.getId())
        .set(User::getUsername, "deleted_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
        .set(User::getDisplayName, "已注销用户")
        .set(User::getAvatarUrl, null)
        .set(User::getPasswordHash, "!")
        .set(User::getTokenValidAfter, new Date()));
    log.info("Anonymized (tombstone) user {}", user.getId());
  }
}
