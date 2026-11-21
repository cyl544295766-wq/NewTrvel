package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.travelos.auth.AuthInterceptor;
import com.travelos.common.GlobalExceptionHandler;
import com.travelos.common.JwtUtil;
import com.travelos.controller.FileController;
import com.travelos.entity.Booking;
import com.travelos.entity.FileMetadata;
import com.travelos.entity.Photo;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripMember;
import com.travelos.entity.User;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.FileMetadataMapper;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.UserMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.handler.MappedInterceptor;

import javax.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /files/** 对象级授权回归（任务五）：
 *
 * <p>接线说明：真实 AuthInterceptor + 真实 FileController + 真实 FileAccessService +
 * 真实 FileStorageService（临时目录）+ 真实 TripMemberService（mock Mapper），
 * 数据访问层（Mapper/TripJournalService/FriendService）用 mock 提供确定的归属引用；
 * MappedInterceptor 的 include/exclude 镜像 WebConfig.addInterceptors 生产配置。</p>
 *
 * <p>覆盖：基础鉴权 5 项、行程对象权限 10 项、其他文件类型 12 项、路径/响应安全 11 项。</p>
 */
class FileAccessWebTest {

  private static final String SECRET = "file-access-web-test-secret-0123456789";
  private static final String COOKIE_NAME = "access_token";
  private static final String USER_ID = "u-alice";
  private static final String USERNAME = "alice";
  private static final String FRIEND_ID = "u-bob";

  // 32 位 hex 受管文件名（各类型）
  private static final String PHOTO_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg";
  private static final String DOC_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.pdf";
  private static final String ATTACH_NAME = "cccccccccccccccccccccccccccccccc.png";
  private static final String RECEIPT_NAME = "dddddddddddddddddddddddddddddddd.jpg";
  private static final String AVATAR_NAME = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee.png";
  private static final String COVER_NAME = "ffffffffffffffffffffffffffffffff.jpg";
  private static final String JOURNAL_PHOTO_NAME = "11111111111111111111111111111111.jpg";
  private static final String MISSING_NAME = "22222222222222222222222222222222.jpg";
  private static final String SYMLINK_NAME = "33333333333333333333333333333333.jpg";

  private static final String TRIP_A = "t-trip-a";
  private static final String TRIP_B = "t-trip-b";

  @TempDir
  Path filesDir;

  private UserService userService;
  private FileMetadataMapper fileMetadataMapper;
  private UserMapper userMapper;
  private TripMapper tripMapper;
  private PhotoMapper photoMapper;
  private TravelDocumentMapper travelDocumentMapper;
  private BookingMapper bookingMapper;
  private TripExpenseMapper tripExpenseMapper;
  private TripMemberMapper tripMemberMapper;
  private TripMapper tripMapperForMember;
  private FriendService friendService;
  private TripJournalService tripJournalService;
  private TripMemberService tripMemberService;
  private FileStorageService fileStorageService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    fileMetadataMapper = mock(FileMetadataMapper.class);
    userMapper = mock(UserMapper.class);
    tripMapper = mock(TripMapper.class);
    photoMapper = mock(PhotoMapper.class);
    travelDocumentMapper = mock(TravelDocumentMapper.class);
    bookingMapper = mock(BookingMapper.class);
    tripExpenseMapper = mock(TripExpenseMapper.class);
    tripMemberMapper = mock(TripMemberMapper.class);
    tripMapperForMember = mock(TripMapper.class);
    friendService = mock(FriendService.class);
    tripJournalService = mock(TripJournalService.class);

    when(userService.findById(USER_ID)).thenReturn(activeUser(USER_ID, USERNAME, "user"));

    // 真实成员服务（走生产 isTripMember/sharesTrip 逻辑），Mapper 用 mock 返回确定结果
    tripMemberService = new TripMemberService(tripMemberMapper, tripMapperForMember, userMapper,
        mock(NotificationService.class), mock(AuditService.class), friendService);

    fileStorageService = new FileStorageService(filesDir.toString(), fileMetadataMapper);
    FileAccessService fileAccessService = new FileAccessService(fileMetadataMapper, userMapper,
        tripMapper, photoMapper, travelDocumentMapper, bookingMapper, tripExpenseMapper,
        tripMemberService, friendService, tripJournalService, fileStorageService);

    AuthInterceptor interceptor = new AuthInterceptor(SECRET, COOKIE_NAME, userService);
    // 与 WebConfig.addInterceptors 的 include/exclude 保持一致（镜像）
    MappedInterceptor guard = new MappedInterceptor(
        new String[]{"/api/**", "/files/**"},
        new String[]{"/api/health", "/api/health/database",
            "/api/auth/login", "/api/auth/register", "/api/auth/logout"},
        interceptor);

    mockMvc = MockMvcBuilders.standaloneSetup(new FileController(fileAccessService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(interceptor)
        .addInterceptors(guard)
        .build();

    denyAllReferences();
  }

  // ==================== 工具 ====================

  @SuppressWarnings("unchecked")
  private static <T> Wrapper<T> anyWrapper() {
    return (Wrapper<T>) ArgumentMatchers.any(Wrapper.class);
  }

  /** 默认拒绝：所有业务引用查询返回空、无元数据、无成员关系。 */
  private void denyAllReferences() {
    when(userMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(tripMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(photoMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(travelDocumentMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(bookingMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(tripExpenseMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(tripMemberMapper.selectCount(anyWrapper())).thenReturn(0L);
    when(tripMemberMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(tripMapperForMember.selectList(anyWrapper())).thenReturn(List.of());
    when(fileMetadataMapper.selectById(ArgumentMatchers.any(String.class))).thenReturn(null);
  }

  /** 受访者已登录（alice）。 */
  private MockHttpServletRequestBuilder authed(String url) {
    return get(url).cookie(new Cookie(COOKIE_NAME, token()));
  }

  private MockHttpServletRequestBuilder authedHead(String url) {
    return head(url).cookie(new Cookie(COOKIE_NAME, token()));
  }

  /** Bearer 通道（与 Cookie 通道须给出完全相同的授权结果）。 */
  private MockHttpServletRequestBuilder bearer(String url) {
    return get(url).header("Authorization", "Bearer " + token());
  }

  /**
   * 按行程区分的成员关系（真实 isTripMember 语义）：命中 memberTripId 的查询返回 count，
   * 其余行程一律 0——用于验证成员资格按行程隔离、角色变化不外溢。
   *
   * <p>MyBatis-Plus 的 Lambda 条件在生成 SQL 段时才登记参数，且依赖实体元数据；
   * 单测没有 Spring 上下文，这里手工初始化 TripMember 的 TableInfo 后再取参。</p>
   */
  private void membershipByTrip(String memberTripId, long count) {
    initTableMetadata(TripMember.class);
    when(tripMemberMapper.selectCount(anyWrapper())).thenAnswer(inv ->
        queryContains(inv.getArgument(0), memberTripId) ? count : 0L);
  }

  /**
   * 触发 SQL 段生成后判断条件参数是否包含指定值（MyBatis-Plus 惰性登记参数）。
   * 解析失败或参数缺失一律返回 false（保守拒绝）。
   */
  private static boolean queryContains(Wrapper<?> wrapper, Object value) {
    try {
      wrapper.getSqlSegment(); // 依赖 initTableMetadata 注册的实体元数据
    } catch (RuntimeException ignored) {
      return false;
    }
    return wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> aw
        && aw.getParamNameValuePairs().values().contains(value);
  }

  private static final java.util.Set<Class<?>> METADATA_READY =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** 幂等初始化实体的 MyBatis-Plus 元数据（单测无 Spring 上下文，手工注册）。 */
  private static void initTableMetadata(Class<?>... entities) {
    for (Class<?> entity : entities) {
      if (!METADATA_READY.add(entity)) {
        continue;
      }
      var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
      var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
      assistant.setCurrentNamespace("com.travelos.service.FileAccessWebTest");
      com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, entity);
    }
  }

  private String token() {
    return JwtUtil.sign(SECRET, java.util.Map.of("sub", USER_ID, "username", USERNAME), 3600);
  }

  private User activeUser(String id, String username, String role) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setDisplayName(username);
    user.setStatus("active");
    user.setRole(role);
    return user;
  }

  private Trip trip(String id, String ownerId, String visibility) {
    Trip trip = new Trip();
    trip.setId(id);
    trip.setOwnerId(ownerId);
    trip.setVisibility(visibility);
    trip.setTitle(id);
    return trip;
  }

  private Photo photo(String id, String tripId, String name) {
    Photo photo = new Photo();
    photo.setId(id);
    photo.setTripId(tripId);
    photo.setUrl("/files/" + name);
    photo.setUploaderId(USER_ID);
    return photo;
  }

  private void writeManagedFile(String name, byte[] bytes) throws Exception {
    Files.createDirectories(filesDir);
    Files.write(filesDir.resolve(name), bytes);
  }

  /** 成员存在（owner/admin/member/viewer 均为一条 trip_members 记录，仅判定存在性）。 */
  private void grantMembership() {
    when(tripMemberMapper.selectCount(anyWrapper())).thenReturn(1L);
  }

  private void denyMembership() {
    when(tripMemberMapper.selectCount(anyWrapper())).thenReturn(0L);
  }

  // ==================== 一、基础鉴权 5 项 ====================

  @Test
  void unauthenticatedGetReturns401() throws Exception {
    mockMvc.perform(get("/files/" + PHOTO_NAME))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.statusCode").value(401));
  }

  @Test
  void invalidCookieReturns401() throws Exception {
    mockMvc.perform(get("/files/" + PHOTO_NAME)
            .cookie(new Cookie(COOKIE_NAME, "not-a-valid-jwt")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void authorizedGetReturns200WithPrivateCacheHeadersAndContent() throws Exception {
    byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
    writeManagedFile(PHOTO_NAME, payload);
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, USER_ID, "private"));
    grantMembership();

    mockMvc.perform(authed("/files/" + PHOTO_NAME))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_JPEG))
        .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
        .andExpect(header().string("Pragma", "no-cache"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Length", "10"))
        .andExpect(content().string("0123456789"));
  }

  @Test
  void headUsesSameAuthorizationAndReturnsSameHeaders() throws Exception {
    writeManagedFile(PHOTO_NAME, "0123456789".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, USER_ID, "private"));
    grantMembership();

    // HEAD：授权通过 → 200 + 与 GET 一致的私有缓存头 + Content-Length
    mockMvc.perform(authedHead("/files/" + PHOTO_NAME))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
        .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
        .andExpect(header().string("Content-Length", "10"));

    // HEAD：未登录同样 401（与 GET 同一鉴权入口）
    mockMvc.perform(head("/files/" + PHOTO_NAME))
        .andExpect(status().isUnauthorized());

    // HEAD：已登录但非成员同样 404（与 GET 同权限）
    denyMembership();
    mockMvc.perform(authedHead("/files/" + PHOTO_NAME))
        .andExpect(status().isNotFound());
  }

  @Test
  void authorizedButMissingOnDiskReturns404Not500() throws Exception {
    // 引用存在、授权通过，但磁盘文件缺失（无索引/被清理/迁移遗留）
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, MISSING_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, USER_ID, "private"));
    grantMembership();

    mockMvc.perform(authed("/files/" + MISSING_NAME))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404));
  }

  // ==================== 二、行程对象权限 10 项 ====================

  private void stubTripPhoto(String name) throws Exception {
    writeManagedFile(name, "photo-bytes".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, name)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
  }

  @Test
  void tripOwnerCanReadPhoto() throws Exception {
    stubTripPhoto(PHOTO_NAME);
    grantMembership(); // owner 行存在
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());
  }

  @Test
  void tripAdminCanReadPhoto() throws Exception {
    stubTripPhoto(PHOTO_NAME);
    grantMembership(); // admin 行存在
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());
  }

  @Test
  void tripMemberCanReadPhoto() throws Exception {
    stubTripPhoto(PHOTO_NAME);
    grantMembership(); // member 行存在
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());
  }

  @Test
  void tripViewerCanReadPhoto() throws Exception {
    stubTripPhoto(PHOTO_NAME);
    grantMembership(); // viewer 行存在：只读角色同样可读（授权只判定成员关系）
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());
  }

  @Test
  void nonMemberWhoKnowsUrlGets404() throws Exception {
    stubTripPhoto(PHOTO_NAME);
    denyMembership();
    mockMvc.perform(authed("/files/" + PHOTO_NAME))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404));
  }

  @Test
  void removedMemberLosesAccessImmediately() throws Exception {
    stubTripPhoto(PHOTO_NAME);
    AtomicBoolean stillMember = new AtomicBoolean(true);
    when(tripMemberMapper.selectCount(anyWrapper()))
        .thenAnswer(inv -> stillMember.get() ? 1L : 0L);

    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());

    stillMember.set(false); // 被移出成员
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void crossTripDocumentDeniedForOtherTripMember() throws Exception {
    // 文档属于 TRIP_B，来访者不是 TRIP_B 成员（即便 TA 是别的行程成员也必须拒绝）
    writeManagedFile(DOC_NAME, "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    TravelDocument document = new TravelDocument();
    document.setId("d1");
    document.setTripId(TRIP_B);
    document.setUrl("/files/" + DOC_NAME);
    when(travelDocumentMapper.selectList(anyWrapper())).thenReturn(List.of(document));
    when(tripMapper.selectById(TRIP_B)).thenReturn(trip(TRIP_B, FRIEND_ID, "private"));
    denyMembership(); // isTripMember(TRIP_B, alice) = false

    mockMvc.perform(authed("/files/" + DOC_NAME))
        .andExpect(status().isNotFound());
  }

  @Test
  void archivedTripStillReadableByMember() throws Exception {
    writeManagedFile(DOC_NAME, "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    TravelDocument document = new TravelDocument();
    document.setId("d1");
    document.setTripId(TRIP_A);
    document.setUrl("/files/" + DOC_NAME);
    when(travelDocumentMapper.selectList(anyWrapper())).thenReturn(List.of(document));
    Trip archived = trip(TRIP_A, USER_ID, "private");
    archived.setArchivedAt(new Date());
    when(tripMapper.selectById(TRIP_A)).thenReturn(archived);
    grantMembership();

    mockMvc.perform(authed("/files/" + DOC_NAME)).andExpect(status().isOk());
  }

  @Test
  void deletedTripDeniesEvenMembers() throws Exception {
    writeManagedFile(DOC_NAME, "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    TravelDocument document = new TravelDocument();
    document.setId("d1");
    document.setTripId(TRIP_A);
    document.setUrl("/files/" + DOC_NAME);
    when(travelDocumentMapper.selectList(anyWrapper())).thenReturn(List.of(document));
    Trip deleted = trip(TRIP_A, USER_ID, "private");
    deleted.setDeletedAt(new Date());
    when(tripMapper.selectById(TRIP_A)).thenReturn(deleted);
    grantMembership();

    mockMvc.perform(authed("/files/" + DOC_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void privateTripReceiptDeniedForNonMember() throws Exception {
    writeManagedFile(RECEIPT_NAME, "receipt".getBytes(StandardCharsets.UTF_8));
    TripExpense expense = new TripExpense();
    expense.setId("e1");
    expense.setTripId(TRIP_A);
    expense.setReceiptUrl("/files/" + RECEIPT_NAME);
    when(tripExpenseMapper.selectList(anyWrapper())).thenReturn(List.of(expense));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    denyMembership();

    mockMvc.perform(authed("/files/" + RECEIPT_NAME))
        .andExpect(status().isNotFound());
  }

  // ==================== 三、其他文件类型 12 项 ====================

  private void stubAvatar(String name, User owner) throws Exception {
    writeManagedFile(name, "avatar".getBytes(StandardCharsets.UTF_8));
    owner.setAvatarUrl("/files/" + name);
    when(userMapper.selectList(anyWrapper())).thenReturn(List.of(owner));
    when(tripMapper.selectList(anyWrapper())).thenReturn(List.of());
    when(tripMemberMapper.selectList(anyWrapper())).thenReturn(List.of());
  }

  @Test
  void avatarVisibleToOwner() throws Exception {
    stubAvatar(AVATAR_NAME, activeUser(USER_ID, USERNAME, "user"));
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isOk());
  }

  @Test
  void avatarVisibleToSuperAdmin() throws Exception {
    when(userService.findById(USER_ID)).thenReturn(activeUser(USER_ID, USERNAME, "super_admin"));
    stubAvatar(AVATAR_NAME, activeUser(FRIEND_ID, "bob", "user"));
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isOk());
  }

  @Test
  void avatarVisibleToMutualFriend() throws Exception {
    stubAvatar(AVATAR_NAME, activeUser(FRIEND_ID, "bob", "user"));
    when(friendService.isMutualFriend(FRIEND_ID, USER_ID)).thenReturn(true);
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isOk());
  }

  @Test
  void avatarVisibleToCoMember() throws Exception {
    stubAvatar(AVATAR_NAME, activeUser(FRIEND_ID, "bob", "user"));
    // sharesTrip(bob, alice)：bob 在 TRIP_A，alice 也在 TRIP_A，且 TRIP_A 未删除
    TripMember bobMembership = new TripMember();
    bobMembership.setTripId(TRIP_A);
    bobMembership.setUserId(FRIEND_ID);
    when(tripMemberMapper.selectList(anyWrapper())).thenReturn(List.of(bobMembership));
    when(tripMapperForMember.selectList(anyWrapper()))
        .thenReturn(List.of(trip(TRIP_A, FRIEND_ID, "private")));
    when(tripMemberMapper.selectCount(anyWrapper())).thenReturn(1L);

    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isOk());
  }

  @Test
  void avatarVisibleToPublicJournalAuthorViewer() throws Exception {
    stubAvatar(AVATAR_NAME, activeUser(FRIEND_ID, "bob", "user"));
    when(tripJournalService.canViewAnyJournalByAuthor(FRIEND_ID, USER_ID)).thenReturn(true);
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isOk());
  }

  @Test
  void avatarDeniedToStranger() throws Exception {
    stubAvatar(AVATAR_NAME, activeUser(FRIEND_ID, "bob", "user"));
    denyMembership();
    when(tripMemberMapper.selectList(anyWrapper())).thenReturn(List.of());
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void avatarDeniedWhenMetadataOwnerConflicts() throws Exception {
    // 头像引用存在，但 file_metadata 属主与头像属主不一致 → 防御性拒绝
    stubAvatar(AVATAR_NAME, activeUser(USER_ID, USERNAME, "user"));
    FileMetadata metadata = new FileMetadata();
    metadata.setName(AVATAR_NAME);
    metadata.setOwnerUserId(FRIEND_ID);
    when(fileMetadataMapper.selectById(AVATAR_NAME)).thenReturn(metadata);

    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isNotFound());
  }

  private void stubCover(String name, Trip coverTrip) throws Exception {
    writeManagedFile(name, "cover-bytes".getBytes(StandardCharsets.UTF_8));
    when(tripMapper.selectList(anyWrapper())).thenReturn(List.of(coverTrip));
    when(tripMapper.selectById(coverTrip.getId())).thenReturn(coverTrip);
  }

  @Test
  void publicTripCoverVisibleToNonMember() throws Exception {
    stubCover(COVER_NAME, trip(TRIP_A, FRIEND_ID, "public"));
    denyMembership();
    mockMvc.perform(authed("/files/" + COVER_NAME)).andExpect(status().isOk());
  }

  @Test
  void privateTripCoverDeniedToNonMember() throws Exception {
    stubCover(COVER_NAME, trip(TRIP_A, FRIEND_ID, "private"));
    denyMembership();
    mockMvc.perform(authed("/files/" + COVER_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void friendsTripCoverVisibleToMutualFriend() throws Exception {
    stubCover(COVER_NAME, trip(TRIP_A, FRIEND_ID, "friends"));
    denyMembership();
    when(friendService.isMutualFriend(FRIEND_ID, USER_ID)).thenReturn(true);
    mockMvc.perform(authed("/files/" + COVER_NAME)).andExpect(status().isOk());
  }

  @Test
  void archivedPublicTripCoverDeniedToNonMember() throws Exception {
    Trip archivedPublic = trip(TRIP_A, FRIEND_ID, "public");
    archivedPublic.setArchivedAt(new Date());
    stubCover(COVER_NAME, archivedPublic);
    denyMembership();
    mockMvc.perform(authed("/files/" + COVER_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void photoReferencedByVisibleJournalVisibleToNonMember() throws Exception {
    writeManagedFile(JOURNAL_PHOTO_NAME, "jpg".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, JOURNAL_PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    denyMembership();
    when(tripJournalService.canViewPhoto("p1", USER_ID)).thenReturn(true);

    mockMvc.perform(authed("/files/" + JOURNAL_PHOTO_NAME)).andExpect(status().isOk());
  }

  @Test
  void photoReferencedOnlyByInvisibleJournalDenied() throws Exception {
    writeManagedFile(JOURNAL_PHOTO_NAME, "jpg".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, JOURNAL_PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    denyMembership();
    when(tripJournalService.canViewPhoto("p1", USER_ID)).thenReturn(false);

    mockMvc.perform(authed("/files/" + JOURNAL_PHOTO_NAME))
        .andExpect(status().isNotFound());
  }

  @Test
  void bookingAttachmentVisibleToTripMemberOnly() throws Exception {
    writeManagedFile(ATTACH_NAME, "attach".getBytes(StandardCharsets.UTF_8));
    Booking booking = new Booking();
    booking.setId("b1");
    booking.setTripId(TRIP_A);
    booking.setAttachmentUrl("/files/" + ATTACH_NAME);
    when(bookingMapper.selectList(anyWrapper())).thenReturn(List.of(booking));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, USER_ID, "private"));

    grantMembership();
    mockMvc.perform(authed("/files/" + ATTACH_NAME)).andExpect(status().isOk());

    denyMembership();
    mockMvc.perform(authed("/files/" + ATTACH_NAME)).andExpect(status().isNotFound());
  }

  // ==================== 四、路径/响应安全 11 项 ====================

  private void grantPhotoAccess(String name) throws Exception {
    writeManagedFile(name, "content".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, name)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, USER_ID, "private"));
    grantMembership();
  }

  @Test
  void uppercaseExtensionRejected404() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    String upper = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.JPG";
    mockMvc.perform(authed("/files/" + upper)).andExpect(status().isNotFound());
  }

  @Test
  void unknownExtensionRejected404() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    mockMvc.perform(authed("/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.txt"))
        .andExpect(status().isNotFound());
    mockMvc.perform(authed("/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.exe"))
        .andExpect(status().isNotFound());
  }

  @Test
  void nonManagedLengthNameRejected404() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    // 31 位 hex（长度不足）与带空格的文件名都必须 404
    mockMvc.perform(authed("/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg"))
        .andExpect(status().isNotFound());
    mockMvc.perform(authed("/files/" + PHOTO_NAME + "%20x"))
        .andExpect(status().isNotFound());
  }

  @Test
  void traversalSegmentRejected404() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    mockMvc.perform(authed("/files/..%2F..%2Fsecret.jpg"))
        .andExpect(status().isNotFound());
    mockMvc.perform(authed("/files/%2e%2e%2fsecret.jpg"))
        .andExpect(status().isNotFound());
  }

  @Test
  void backslashOrAbsolutePathRejected404() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    mockMvc.perform(authed("/files/..%5C..%5Csecret.jpg"))
        .andExpect(status().isNotFound());
    mockMvc.perform(authed("/files/C%3A%5Csecret.jpg"))
        .andExpect(status().isNotFound());
  }

  @Test
  void nestedPathRejected404() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    mockMvc.perform(authed("/files/sub/dir/" + PHOTO_NAME))
        .andExpect(status().isNotFound());
    mockMvc.perform(authed("/files/"))
        .andExpect(status().isNotFound());
  }

  @Test
  void symlinkedFileRejected404() throws Exception {
    grantPhotoAccess(SYMLINK_NAME);
    Path target = filesDir.resolve("symlink-target.txt");
    Files.write(target, "secret".getBytes(StandardCharsets.UTF_8));
    Path link = filesDir.resolve(SYMLINK_NAME);
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | SecurityException | java.io.IOException ex) {
      Assumptions.assumeTrue(false, "当前环境不支持符号链接，跳过该用例：" + ex.getMessage());
    }
    mockMvc.perform(authed("/files/" + SYMLINK_NAME))
        .andExpect(status().isNotFound());
  }

  @Test
  void deniedAndMissingResponsesAreIdenticalNoPathLeak() throws Exception {
    // 场景 1：文件真实存在但无权限
    writeManagedFile(PHOTO_NAME, "secret-bytes".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    denyMembership();
    String deniedBody = mockMvc.perform(authed("/files/" + PHOTO_NAME))
        .andExpect(status().isNotFound())
        .andReturn().getResponse().getContentAsString();

    // 场景 2：文件根本不存在
    denyAllReferences();
    String missingBody = mockMvc.perform(authed("/files/" + MISSING_NAME))
        .andExpect(status().isNotFound())
        .andReturn().getResponse().getContentAsString();

    assertNotEquals("", deniedBody);
    assertFalse(deniedBody.contains(filesDir.toString()), "错误响应不得泄露磁盘路径：" + deniedBody);
    assertFalse(deniedBody.contains(filesDir.toAbsolutePath().toString()),
        "错误响应不得泄露磁盘路径：" + missingBody);
    assertFalse(deniedBody.contains(USER_ID) || deniedBody.contains(FRIEND_ID),
        "错误响应不得泄露属主：" + deniedBody);
    // 防枚举：无权限与不存在返回完全一致的响应体
    org.junit.jupiter.api.Assertions.assertEquals(missingBody, deniedBody);
  }

  @Test
  void rangeRequestReturns206PartialContent() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    mockMvc.perform(authed("/files/" + PHOTO_NAME).header("Range", "bytes=0-3"))
        .andExpect(status().isPartialContent())
        .andExpect(content().string("cont"));
  }

  @Test
  void getAndHeadDenyNonMemberIdentically() throws Exception {
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    denyMembership();
    writeManagedFile(PHOTO_NAME, "content".getBytes(StandardCharsets.UTF_8));

    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isNotFound());
    mockMvc.perform(authedHead("/files/" + PHOTO_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void invalidManagedNameNeverReadsDiskOrReturns500() throws Exception {
    grantPhotoAccess(PHOTO_NAME);
    for (String bad : new String[]{
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg.jpg",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.JPG",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "%20aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pd%66"}) {
      mockMvc.perform(authed("/files/" + bad)).andExpect(status().isNotFound());
    }
  }

  // ==================== 五、补充：规格明确要求的缺口用例 ====================

  @Test
  void expiredCredentialReturns401() throws Exception {
    String expired = JwtUtil.sign(SECRET,
        java.util.Map.of("sub", USER_ID, "username", USERNAME), -60L);
    mockMvc.perform(get("/files/" + PHOTO_NAME).cookie(new Cookie(COOKIE_NAME, expired)))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/files/" + PHOTO_NAME).header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bearerAndCookieChannelReturnIdenticalResults() throws Exception {
    writeManagedFile(PHOTO_NAME, "0123456789".getBytes(StandardCharsets.UTF_8));
    when(photoMapper.selectList(anyWrapper()))
        .thenReturn(List.of(photo("p1", TRIP_A, PHOTO_NAME)));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    grantMembership();

    // 授权通过：两通道状态与关键响应头一致
    MvcResult cookieOk = mockMvc.perform(authed("/files/" + PHOTO_NAME))
        .andExpect(status().isOk()).andReturn();
    MvcResult bearerOk = mockMvc.perform(bearer("/files/" + PHOTO_NAME))
        .andExpect(status().isOk()).andReturn();
    var cr = cookieOk.getResponse();
    var br = bearerOk.getResponse();
    assertEquals(cr.getStatus(), br.getStatus(), "GET 授权结果必须与通道无关");
    assertEquals(cr.getHeader("Content-Length"), br.getHeader("Content-Length"));
    assertEquals(cr.getHeader("Content-Type"), br.getHeader("Content-Type"));
    assertEquals(cr.getHeader("Cache-Control"), br.getHeader("Cache-Control"));

    // 拒绝：两通道状态与响应体完全一致（防枚举）
    denyMembership();
    MvcResult cookieDenied = mockMvc.perform(authed("/files/" + PHOTO_NAME))
        .andExpect(status().isNotFound()).andReturn();
    MvcResult bearerDenied = mockMvc.perform(bearer("/files/" + PHOTO_NAME))
        .andExpect(status().isNotFound()).andReturn();
    assertEquals(cookieDenied.getResponse().getStatus(), bearerDenied.getResponse().getStatus());
    assertEquals(cookieDenied.getResponse().getContentAsString(),
        bearerDenied.getResponse().getContentAsString(), "无权限响应体不得因通道而异");

    // HEAD 同样一致
    MvcResult cookieHead = mockMvc.perform(authedHead("/files/" + PHOTO_NAME))
        .andExpect(status().isNotFound()).andReturn();
    MvcResult bearerHead = mockMvc.perform(
            head("/files/" + PHOTO_NAME).header("Authorization", "Bearer " + token()))
        .andExpect(status().isNotFound()).andReturn();
    assertEquals(cookieHead.getResponse().getStatus(), bearerHead.getResponse().getStatus());
  }

  @Test
  void diskFileWithoutAnyBusinessReferenceDenied() throws Exception {
    // 磁盘存在、文件名合法、已登录，但数据库中无任何业务引用（孤儿/未归属文件）→ 404
    writeManagedFile(MISSING_NAME, "orphan-bytes".getBytes(StandardCharsets.UTF_8));
    String body = mockMvc.perform(authed("/files/" + MISSING_NAME))
        .andExpect(status().isNotFound())
        .andReturn().getResponse().getContentAsString();
    assertFalse(body.contains(filesDir.toString()), "错误响应不得泄露磁盘路径：" + body);
  }

  @Test
  void legacyFileWithoutMetadataStillAuthorizedByBusinessReference() throws Exception {
    // 存量兼容：file_metadata 缺失（迁移前的文件）→ 不拒绝、也不放行给所有人，
    // 而是按「业务引用 + 行程成员关系」保守授权
    when(fileMetadataMapper.selectById(AVATAR_NAME)).thenReturn(null);
    writeManagedFile(AVATAR_NAME, "legacy".getBytes(StandardCharsets.UTF_8));
    TravelDocument document = new TravelDocument();
    document.setId("legacy-1");
    document.setTripId(TRIP_A);
    document.setUrl("/files/" + AVATAR_NAME);
    when(travelDocumentMapper.selectList(anyWrapper())).thenReturn(List.of(document));
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));

    grantMembership();
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isOk());

    denyMembership(); // 无元数据 ≠ 对所有登录用户放行
    mockMvc.perform(authed("/files/" + AVATAR_NAME)).andExpect(status().isNotFound());
  }

  @Test
  void roleChangeDoesNotWidenCrossTripAccess() throws Exception {
    // 用户在 TRIP_A 为 viewer（成员行存在即可读）；TRIP_B 无成员关系。
    // 各 Mapper 桩按「被查询的 URL」返回引用（真实 DB 语义），避免跨文件串扰。
    writeManagedFile(PHOTO_NAME, "photo".getBytes(StandardCharsets.UTF_8));
    writeManagedFile(DOC_NAME, "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8));
    initTableMetadata(Photo.class, TravelDocument.class);
    when(photoMapper.selectList(anyWrapper())).thenAnswer(inv ->
        queryContains(inv.getArgument(0), "/files/" + PHOTO_NAME)
            ? List.of(photo("p1", TRIP_A, PHOTO_NAME)) : List.of());
    when(tripMapper.selectById(TRIP_A)).thenReturn(trip(TRIP_A, FRIEND_ID, "private"));
    TravelDocument document = new TravelDocument();
    document.setId("d1");
    document.setTripId(TRIP_B);
    document.setUrl("/files/" + DOC_NAME);
    when(travelDocumentMapper.selectList(anyWrapper())).thenAnswer(inv ->
        queryContains(inv.getArgument(0), "/files/" + DOC_NAME) ? List.of(document) : List.of());
    when(tripMapper.selectById(TRIP_B)).thenReturn(trip(TRIP_B, FRIEND_ID, "private"));
    membershipByTrip(TRIP_A, 1L);

    // viewer：本行程 200、跨行程 404
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());
    mockMvc.perform(authed("/files/" + DOC_NAME)).andExpect(status().isNotFound());

    // 「升级」为 admin/owner（成员资格与角色值不进入授权分支，跨行程仍必须拒绝）
    membershipByTrip(TRIP_A, 1L);
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isOk());
    mockMvc.perform(authed("/files/" + DOC_NAME)).andExpect(status().isNotFound());

    // 降级为非成员：本行程也立即失效
    membershipByTrip(TRIP_A, 0L);
    mockMvc.perform(authed("/files/" + PHOTO_NAME)).andExpect(status().isNotFound());
  }
}
