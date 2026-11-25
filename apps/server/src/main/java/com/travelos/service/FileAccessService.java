package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.ManagedFileNames;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.Booking;
import com.travelos.entity.FileMetadata;
import com.travelos.entity.Photo;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.entity.User;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.FileMetadataMapper;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * /files/** 的对象级授权唯一入口（GET/HEAD 共用）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>文件名白名单（{@link ManagedFileNames}）先行：大小写/扩展名/编码/路径变形一律 404；</li>
 *   <li>先数据库授权、后落盘解析——未授权请求不触碰磁盘；</li>
 *   <li>全部判定在 {@link TripScopeContext#callBypassed} 中执行：数据权限拦截器会按
 *       「当前用户是成员」预过滤，跨行程的显式授权必须绕开，随后本类自行判定成员关系；</li>
 *   <li>查询一律有界（LIMIT），避免单请求全表扫描；</li>
 *   <li>「已登录但无权限」与「不存在」统一 404，防枚举；未登录由 AuthInterceptor 返回 401。</li>
 * </ul>
 *
 * <p>访问矩阵：</p>
 * <ul>
 *   <li>行程照片：行程成员（行程未删除）；非成员仅当照片被其可见游记引用，或该照片所在行程
 *       对其可见（public / friends 互为好友，且未删除未归档）；</li>
 *   <li>行程文档 / 预订附件 / 报销凭证：仅行程成员，且行程未删除；</li>
 *   <li>游记照片：行程成员，或任一引用该照片的游记对其可见（作者/公开/好友/指定好友，非草稿）；</li>
 *   <li>头像：本人、超级管理员、互为好友、共同行程成员、拥有对查看者可见行程或攻略的作者，
 *       且 file_metadata 属主与头像属主一致（元数据存在时）；</li>
 *   <li>行程封面：行程成员；非成员仅在行程 public 或 friends（互为好友）且未删除未归档时；</li>
 *   <li>无任何业务引用、元数据属主冲突、磁盘缺失、非法路径：统一 404。</li>
 * </ul>
 */
@Service
public class FileAccessService {

  /** 单类业务引用查询上限（同一 URL 正常只被引用一次，多引用取有界窗口内任一放行） */
  private static final int MAX_REFS = 20;
  /** 属主可见行程 / 攻略扫描上限 */
  private static final int MAX_VISIBILITIES = 50;

  private final FileMetadataMapper fileMetadataMapper;
  private final UserMapper userMapper;
  private final TripMapper tripMapper;
  private final PhotoMapper photoMapper;
  private final TravelDocumentMapper travelDocumentMapper;
  private final BookingMapper bookingMapper;
  private final TripExpenseMapper tripExpenseMapper;
  private final TripMemberService tripMemberService;
  private final FriendService friendService;
  private final TripJournalService tripJournalService;
  private final FileStorageService fileStorageService;

  public FileAccessService(FileMetadataMapper fileMetadataMapper, UserMapper userMapper,
      TripMapper tripMapper, PhotoMapper photoMapper, TravelDocumentMapper travelDocumentMapper,
      BookingMapper bookingMapper, TripExpenseMapper tripExpenseMapper,
      TripMemberService tripMemberService, FriendService friendService,
      TripJournalService tripJournalService, FileStorageService fileStorageService) {
    this.fileMetadataMapper = fileMetadataMapper;
    this.userMapper = userMapper;
    this.tripMapper = tripMapper;
    this.photoMapper = photoMapper;
    this.travelDocumentMapper = travelDocumentMapper;
    this.bookingMapper = bookingMapper;
    this.tripExpenseMapper = tripExpenseMapper;
    this.tripMemberService = tripMemberService;
    this.friendService = friendService;
    this.tripJournalService = tripJournalService;
    this.fileStorageService = fileStorageService;
  }

  /** 授权通过的文件：已解析的磁盘真实路径。 */
  public record AuthorizedFile(Path path, String filename) {
  }

  /**
   * @throws com.travelos.common.ApiException 404——文件名非法、无权限、无引用或磁盘缺失
   */
  public AuthorizedFile authorize(String filename, UserContext viewer) {
    if (viewer == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    if (!ManagedFileNames.isValidName(filename)) {
      throw ApiException.notFound("文件不存在");
    }
    String url = "/files/" + filename;
    boolean allowed = TripScopeContext.callBypassed(() -> isAllowed(filename, url, viewer));
    if (!allowed) {
      throw ApiException.notFound("文件不存在");
    }
    Path path = fileStorageService.resolveByName(filename);
    if (path == null) {
      throw ApiException.notFound("文件不存在");
    }
    return new AuthorizedFile(path, filename);
  }

  /** 识别仅接受当前行程中已有的票据、预订附件或照片。 */
  public AuthorizedFile authorizeReceiptImage(String url, String tripId, UserContext viewer) {
    String name = ManagedFileNames.nameFromUrl(url);
    if (name == null || !(bookingMapper.exists(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getTripId, tripId).eq(Booking::getAttachmentUrl, url))
        || tripExpenseMapper.exists(new LambdaQueryWrapper<TripExpense>()
            .eq(TripExpense::getTripId, tripId).eq(TripExpense::getReceiptUrl, url))
        || photoMapper.exists(new LambdaQueryWrapper<Photo>()
            .eq(Photo::getTripId, tripId).eq(Photo::getUrl, url)))) {
      throw ApiException.notFound("图片不存在");
    }
    return authorize(name, viewer);
  }

  private boolean isAllowed(String filename, String url, UserContext viewer) {
    FileMetadata metadata = fileMetadataMapper.selectById(filename);

    // 1) 头像：users.avatar_url 直接引用
    for (User owner : userMapper.selectList(new LambdaQueryWrapper<User>()
        .eq(User::getAvatarUrl, url).last("LIMIT 5"))) {
      if (canViewAvatar(owner, viewer, metadata)) {
        return true;
      }
    }

    // 2) 行程照片（含行程封面引用的照片、游记引用的照片）
    for (Photo photo : photoMapper.selectList(new LambdaQueryWrapper<Photo>()
        .eq(Photo::getUrl, url).last("LIMIT " + MAX_REFS))) {
      if (canViewPhoto(photo, viewer)) {
        return true;
      }
    }

    // 3) 行程封面
    for (Trip trip : tripMapper.selectList(new LambdaQueryWrapper<Trip>()
        .eq(Trip::getCoverImageUrl, url).last("LIMIT " + MAX_REFS))) {
      if (canViewCover(trip, viewer)) {
        return true;
      }
    }

    // 4) 行程文档（证件/票据等）
    for (TravelDocument document : travelDocumentMapper.selectList(
        new LambdaQueryWrapper<TravelDocument>()
            .eq(TravelDocument::getUrl, url).last("LIMIT " + MAX_REFS))) {
      if (canViewTripScoped(document.getTripId(), viewer)) {
        return true;
      }
    }

    // 5) 预订附件
    for (Booking booking : bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getAttachmentUrl, url).last("LIMIT " + MAX_REFS))) {
      if (canViewTripScoped(booking.getTripId(), viewer)) {
        return true;
      }
    }

    // 6) 报销凭证
    for (TripExpense expense : tripExpenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
        .eq(TripExpense::getReceiptUrl, url).last("LIMIT " + MAX_REFS))) {
      if (canViewTripScoped(expense.getTripId(), viewer)) {
        return true;
      }
    }

    return false;
  }

  // ==================== 各类型判定 ====================

  /** 行程成员（owner/admin/member/viewer 含 viewer），且行程未删除。 */
  private boolean canViewTripScoped(String tripId, UserContext viewer) {
    Trip trip = tripMapper.selectById(tripId);
    return trip != null && trip.getDeletedAt() == null
        && tripMemberService.isTripMember(tripId, viewer.id());
  }

  private boolean canViewPhoto(Photo photo, UserContext viewer) {
    if (canViewTripScoped(photo.getTripId(), viewer)) {
      return true;
    }
    // 非成员：仅当照片被一篇对其可见的游记引用（合法共享，写入时已校验照片必须属于该行程）
    return tripJournalService.canViewPhoto(photo.getId(), viewer.id());
  }

  /** 行程封面：成员可见；非成员仅 public 或 friends（互为好友），且未删除未归档。 */
  private boolean canViewCover(Trip trip, UserContext viewer) {
    if (trip == null || trip.getDeletedAt() != null) {
      return false;
    }
    if (tripMemberService.isTripMember(trip.getId(), viewer.id())) {
      return true;
    }
    if (trip.getArchivedAt() != null) {
      return false;
    }
    String visibility = trip.getVisibility() == null ? "private" : trip.getVisibility();
    if ("public".equals(visibility)) {
      return true;
    }
    if ("friends".equals(visibility)) {
      return friendService.isMutualFriend(trip.getOwnerId(), viewer.id());
    }
    return false;
  }

  private boolean canViewAvatar(User owner, UserContext viewer, FileMetadata metadata) {
    if (owner == null || owner.getId() == null) {
      return false;
    }
    // 防御性属主校验：元数据存在且与头像属主不一致 → 视为不可达（不信任脏数据）
    if (metadata != null && !owner.getId().equals(metadata.getOwnerUserId())) {
      return false;
    }
    if (owner.getId().equals(viewer.id())) {
      return true;
    }
    if (viewer.isSuperAdmin()) {
      return true;
    }
    if (friendService.isMutualFriend(owner.getId(), viewer.id())) {
      return true;
    }
    if (tripMemberService.sharesTrip(owner.getId(), viewer.id())) {
      return true;
    }
    // 拥有对查看者可见行程（public / friends）的作者
    for (Trip owned : tripMapper.selectList(new LambdaQueryWrapper<Trip>()
        .eq(Trip::getOwnerId, owner.getId())
        .isNull(Trip::getDeletedAt)
        .orderByDesc(Trip::getUpdatedAt)
        .last("LIMIT " + MAX_VISIBILITIES))) {
      if (canViewCover(owned, viewer)) {
        return true;
      }
    }
    // 公开攻略作者头像
    return tripJournalService.canViewAnyJournalByAuthor(owner.getId(), viewer.id());
  }
}
