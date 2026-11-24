package com.travelos.service;

import com.travelos.common.ApiException;
import com.travelos.entity.Trip;
import com.travelos.entity.TripMember;
import com.travelos.entity.User;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.UserMapper;
import com.travelos.trip.TripPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 成员管理权限矩阵单元测试（Mockito，不依赖数据库/Spring 上下文）：
 * 覆盖 invite/updateRole/remove 的操作者角色校验（第二道防线）、目标角色白名单、
 * owner 不可被改/移/封以及拒绝路径零副作用（无写入/通知/审计/好友校验触发）。
 */
class TripMemberPermissionTest {

  private static final String TRIP_ID = "trip-1";
  private static final String TRIP_TITLE = "大理丽江";
  private static final String OPERATOR_ID = "u-operator";
  private static final String OPERATOR_MEMBER_ID = "m-operator";
  private static final String TARGET_USER_ID = "u-target";
  private static final String TARGET_MEMBER_ID = "m-target";

  private TripMemberMapper tripMemberMapper;
  private TripMapper tripMapper;
  private UserMapper userMapper;
  private NotificationService notificationService;
  private AuditService auditService;
  private FriendService friendService;
  private TripMemberService service;

  @BeforeEach
  void setUp() {
    tripMemberMapper = mock(TripMemberMapper.class);
    tripMapper = mock(TripMapper.class);
    userMapper = mock(UserMapper.class);
    notificationService = mock(NotificationService.class);
    auditService = mock(AuditService.class);
    friendService = mock(FriendService.class);
    service = new TripMemberService(tripMemberMapper, tripMapper, userMapper,
        notificationService, auditService, friendService);
  }

  // ==================== 场景 1/2：owner、admin 可邀请 ====================

  @Test
  void ownerCanInviteAndNullRoleDefaultsToMember() {
    operatorIs(TripPermission.OWNER);
    stubSuccessEnvironment();

    Map<String, Object> result = service.invite(TRIP_ID, OPERATOR_ID, "bob", null);

    assertNotNull(result);
    ArgumentCaptor<TripMember> captor = ArgumentCaptor.forClass(TripMember.class);
    verify(tripMemberMapper).insert(captor.capture());
    assertEquals(TARGET_USER_ID, captor.getValue().getUserId());
    assertEquals(TripPermission.MEMBER, captor.getValue().getRole());
    // 互为好友校验保持不变
    verify(friendService).requireMutualFriend(OPERATOR_ID, TARGET_USER_ID);
    verify(notificationService).createMemberInvited(TARGET_USER_ID, TRIP_ID, TRIP_TITLE,
        TripPermission.MEMBER, captor.getValue().getId());
    verify(auditService).record(eq(OPERATOR_ID), eq(TRIP_ID), eq("member_invited"), eq("user"),
        eq(TARGET_USER_ID), eq(Map.of("username", "bob", "role", TripPermission.MEMBER)));
  }

  @Test
  void adminCanInviteWithExplicitRole() {
    operatorIs(TripPermission.ADMIN);
    stubSuccessEnvironment();

    Map<String, Object> result = service.invite(TRIP_ID, OPERATOR_ID, "bob",
        TripPermission.VIEWER);

    assertNotNull(result);
    ArgumentCaptor<TripMember> captor = ArgumentCaptor.forClass(TripMember.class);
    verify(tripMemberMapper).insert(captor.capture());
    assertEquals(TripPermission.VIEWER, captor.getValue().getRole());
    verify(friendService).requireMutualFriend(OPERATOR_ID, TARGET_USER_ID);
    verify(notificationService).createMemberInvited(TARGET_USER_ID, TRIP_ID, TRIP_TITLE,
        TripPermission.VIEWER, captor.getValue().getId());
  }

  // ==================== 场景 3/4：member、viewer 邀请被拒 ====================

  @Test
  void memberCannotInvite() {
    operatorIs(TripPermission.MEMBER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.invite(TRIP_ID, OPERATOR_ID, "bob", TripPermission.MEMBER));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void viewerCannotInvite() {
    operatorIs(TripPermission.VIEWER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.invite(TRIP_ID, OPERATOR_ID, "bob", TripPermission.MEMBER));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  // ==================== 场景 5：非行程成员操作被拒 ====================

  @Test
  void nonMemberCannotInvite() {
    // Service 层语义为 404（不暴露资源存在性）；Controller 切面统一转 403，
    // 该转换在 MemberRoleGuardProxyTest 中验证。
    operatorIsAbsent();

    ApiException ex = assertThrows(ApiException.class,
        () -> service.invite(TRIP_ID, OPERATOR_ID, "bob", TripPermission.MEMBER));

    assertEquals(404, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void nonMemberCannotUpdateRole() {
    operatorIsAbsent();

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, TripPermission.MEMBER, OPERATOR_ID));

    assertEquals(404, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void nonMemberCannotRemove() {
    operatorIsAbsent();

    ApiException ex = assertThrows(ApiException.class,
        () -> service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID));

    assertEquals(404, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  // ==================== 场景 6/7/8：member 的提权/改他/移除被拒 ====================

  @Test
  void memberCannotPromoteSelfToAdmin() {
    operatorIs(TripPermission.MEMBER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, OPERATOR_MEMBER_ID, TripPermission.ADMIN, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void memberCannotChangeOtherMemberRole() {
    operatorIs(TripPermission.MEMBER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, TripPermission.ADMIN, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void memberCannotRemoveAdmin() {
    operatorIs(TripPermission.MEMBER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void viewerCannotUpdateRole() {
    operatorIs(TripPermission.VIEWER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, TripPermission.MEMBER, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  @Test
  void viewerCannotRemove() {
    operatorIs(TripPermission.VIEWER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertNoWriteSideEffects();
  }

  // ==================== 场景 9/10：admin 改角色/移除普通成员成功 ====================

  @Test
  void adminCanChangeNormalMemberRole() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.MEMBER);
    stubSuccessEnvironment();

    Map<String, Object> result = service.updateRole(TRIP_ID, TARGET_MEMBER_ID,
        TripPermission.ADMIN, OPERATOR_ID);

    assertNotNull(result);
    ArgumentCaptor<TripMember> captor = ArgumentCaptor.forClass(TripMember.class);
    verify(tripMemberMapper).updateById(captor.capture());
    assertEquals(TripPermission.ADMIN, captor.getValue().getRole());
    verify(notificationService).createRoleChanged(TARGET_USER_ID, TRIP_ID, TRIP_TITLE,
        TripPermission.ADMIN);
    verify(auditService).record(eq(OPERATOR_ID), eq(TRIP_ID), eq("role_changed"),
        eq("trip_member"), eq(TARGET_MEMBER_ID),
        eq(Map.of("username", "bob", "role", TripPermission.ADMIN)));
  }

  @Test
  void adminCanRemoveNormalMember() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.MEMBER);
    stubSuccessEnvironment();

    Map<String, Object> result = service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID);

    assertNotNull(result);
    verify(tripMemberMapper).deleteById(TARGET_MEMBER_ID);
    // 移除成员不发通知（现状语义），审计必须留痕
    verifyNoInteractions(notificationService);
    verify(auditService).record(eq(OPERATOR_ID), eq(TRIP_ID), eq("member_removed"),
        eq("trip_member"), eq(TARGET_MEMBER_ID),
        eq(Map.of("username", "bob", "role", TripPermission.MEMBER)));
  }

  // ==================== 管理员同级保护：admin 不能管理 admin，owner 可以 ====================

  @Test
  void adminCannotChangeAnotherAdminRole() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.ADMIN);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, TripPermission.MEMBER, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertEquals("管理员不能管理同级成员", ex.getMessage());
    verify(tripMemberMapper, never()).updateById(any(TripMember.class));
    assertNoNotificationOrAudit();
  }

  @Test
  void adminCannotRemoveAnotherAdmin() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.ADMIN);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    assertEquals("管理员不能管理同级成员", ex.getMessage());
    verify(tripMemberMapper, never()).deleteById(anyString());
    assertNoNotificationOrAudit();
  }

  @Test
  void adminCannotChangeOwnRole() {
    operatorIs(TripPermission.ADMIN);
    when(tripMemberMapper.selectById(OPERATOR_MEMBER_ID))
        .thenReturn(membership(OPERATOR_MEMBER_ID, OPERATOR_ID, TripPermission.ADMIN));

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, OPERATOR_MEMBER_ID, TripPermission.MEMBER, OPERATOR_ID));

    assertEquals(403, ex.getStatusCode());
    verify(tripMemberMapper, never()).updateById(any(TripMember.class));
    assertNoNotificationOrAudit();
  }

  @Test
  void ownerCanChangeAdminRole() {
    operatorIs(TripPermission.OWNER);
    targetIs(TripPermission.ADMIN);
    stubSuccessEnvironment();

    Map<String, Object> result = service.updateRole(TRIP_ID, TARGET_MEMBER_ID,
        TripPermission.MEMBER, OPERATOR_ID);

    assertNotNull(result);
    ArgumentCaptor<TripMember> captor = ArgumentCaptor.forClass(TripMember.class);
    verify(tripMemberMapper).updateById(captor.capture());
    assertEquals(TripPermission.MEMBER, captor.getValue().getRole());
  }

  @Test
  void ownerCanRemoveAdmin() {
    operatorIs(TripPermission.OWNER);
    targetIs(TripPermission.ADMIN);
    stubSuccessEnvironment();

    Map<String, Object> result = service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID);

    assertNotNull(result);
    verify(tripMemberMapper).deleteById(TARGET_MEMBER_ID);
  }

  // ==================== 场景 11/12：owner 不可被改角色/移除 ====================

  @Test
  void cannotChangeOwnerRole() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.OWNER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, TripPermission.MEMBER, OPERATOR_ID));

    assertEquals(400, ex.getStatusCode());
    assertEquals("不能修改所有者角色", ex.getMessage());
    verify(tripMemberMapper, never()).updateById(any(TripMember.class));
    assertNoNotificationOrAudit();
  }

  @Test
  void cannotRemoveOwner() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.OWNER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.remove(TRIP_ID, TARGET_MEMBER_ID, OPERATOR_ID));

    assertEquals(400, ex.getStatusCode());
    assertEquals("不能移除所有者", ex.getMessage());
    verify(tripMemberMapper, never()).deleteById(anyString());
    assertNoNotificationOrAudit();
  }

  // ==================== 场景 13：不能通过邀请/改角色产生 owner ====================

  @Test
  void cannotInviteAsOwner() {
    operatorIs(TripPermission.ADMIN);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.invite(TRIP_ID, OPERATOR_ID, "bob", TripPermission.OWNER));

    assertEquals(400, ex.getStatusCode());
    assertEquals("不能邀请所有者", ex.getMessage());
    assertNoWriteSideEffects();
  }

  @Test
  void cannotUpdateRoleToOwner() {
    operatorIs(TripPermission.ADMIN);
    targetIs(TripPermission.MEMBER);

    ApiException ex = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, TripPermission.OWNER, OPERATOR_ID));

    assertEquals(400, ex.getStatusCode());
    assertEquals("不能将成员改为所有者", ex.getMessage());
    verify(tripMemberMapper, never()).updateById(any(TripMember.class));
    assertNoNotificationOrAudit();
  }

  // ==================== 场景 14：非法角色返回 400 ====================

  @Test
  void inviteRejectsBlankAndUnknownRoles() {
    operatorAlwaysIs(TripPermission.ADMIN);

    ApiException blank = assertThrows(ApiException.class,
        () -> service.invite(TRIP_ID, OPERATOR_ID, "bob", "  "));
    assertEquals(400, blank.getStatusCode());

    ApiException unknown = assertThrows(ApiException.class,
        () -> service.invite(TRIP_ID, OPERATOR_ID, "bob", "superadmin"));
    assertEquals(400, unknown.getStatusCode());

    assertNoWriteSideEffects();
  }

  @Test
  void updateRoleRejectsBlankNullAndUnknownRoles() {
    operatorAlwaysIs(TripPermission.ADMIN);
    targetIs(TripPermission.MEMBER);

    ApiException blank = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, "  ", OPERATOR_ID));
    assertEquals(400, blank.getStatusCode());

    // null 属纵深防御（Controller 已挡 blank），Service 层同样拒绝
    ApiException missing = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, null, OPERATOR_ID));
    assertEquals(400, missing.getStatusCode());

    ApiException unknown = assertThrows(ApiException.class,
        () -> service.updateRole(TRIP_ID, TARGET_MEMBER_ID, "hacker", OPERATOR_ID));
    assertEquals(400, unknown.getStatusCode());

    verify(tripMemberMapper, never()).updateById(any(TripMember.class));
    assertNoNotificationOrAudit();
  }

  // ==================== 场景 15：拒绝路径零副作用已在上述各用例断言 ====================
  // assertNoWriteSideEffects 覆盖：无 insert/updateById/deleteById、无通知、无审计、
  // 好友校验未被触发（避免拒绝请求产生部分副作用）。

  // ==================== stub 工具 ====================

  /** 操作者身份查询（requireTripRole → requireTripMember）总是 selectOne 的第 1 次调用
   *  （操作者校验在方法最前）；invite 后续第 2 次调用为「目标已加入」检查（返回 null）。
   *  注：MP 3.5.7 的 LambdaQueryWrapper 参数值为懒渲染，getParamNameValuePairs 恒空，
   *  不能按 wrapper 内容区分两类查询。 */
  private void operatorIs(String role) {
    TripMember operator = membership(OPERATOR_MEMBER_ID, OPERATOR_ID, role);
    when(tripMemberMapper.selectOne(any())).thenReturn(operator, (TripMember) null);
  }

  private void operatorIsAbsent() {
    when(tripMemberMapper.selectOne(any())).thenReturn(null);
  }

  /** 多轮拒绝类调用共用：操作者恒为该角色。非法角色场景在角色校验处即返回 400，
   *  永远到不了 invite 的第 2 次 selectOne（已加入检查），恒定 stub 不会误伤。 */
  private void operatorAlwaysIs(String role) {
    when(tripMemberMapper.selectOne(any()))
        .thenReturn(membership(OPERATOR_MEMBER_ID, OPERATOR_ID, role));
  }

  private void targetIs(String role) {
    when(tripMemberMapper.selectById(TARGET_MEMBER_ID))
        .thenReturn(membership(TARGET_MEMBER_ID, TARGET_USER_ID, role));
  }

  /** 成功路径所需的其余读/写 stub（手动 mock 为宽松模式，多余 stub 无副作用）。 */
  private void stubSuccessEnvironment() {
    when(tripMemberMapper.selectList(any())).thenReturn(List.of());
    when(tripMemberMapper.insert(any(TripMember.class))).thenReturn(1);
    when(tripMemberMapper.updateById(any(TripMember.class))).thenReturn(1);
    when(tripMemberMapper.deleteById(anyString())).thenReturn(1);
    when(tripMapper.selectById(TRIP_ID)).thenReturn(trip());
    when(userMapper.selectOne(any())).thenReturn(user(TARGET_USER_ID, "bob"));
    when(userMapper.selectById(TARGET_USER_ID)).thenReturn(user(TARGET_USER_ID, "bob"));
  }

  /** 拒绝路径不允许留下任何写副作用：无成员表写入、无通知、无审计、不触发好友校验。 */
  private void assertNoWriteSideEffects() {
    verify(tripMemberMapper, never()).insert(any(TripMember.class));
    verify(tripMemberMapper, never()).updateById(any(TripMember.class));
    verify(tripMemberMapper, never()).deleteById(anyString());
    verifyNoInteractions(notificationService, auditService);
    verify(friendService, never()).requireMutualFriend(anyString(), anyString());
  }

  private void assertNoNotificationOrAudit() {
    verifyNoInteractions(notificationService, auditService);
  }

  private TripMember membership(String memberId, String userId, String role) {
    TripMember member = new TripMember();
    member.setId(memberId);
    member.setTripId(TRIP_ID);
    member.setUserId(userId);
    member.setRole(role);
    member.setCreatedAt(new Date());
    member.setUpdatedAt(new Date());
    return member;
  }

  private User user(String userId, String username) {
    User user = new User();
    user.setId(userId);
    user.setUsername(username);
    user.setDisplayName(username);
    user.setStatus("active");
    return user;
  }

  private Trip trip() {
    Trip trip = new Trip();
    trip.setId(TRIP_ID);
    trip.setTitle(TRIP_TITLE);
    return trip;
  }
}
