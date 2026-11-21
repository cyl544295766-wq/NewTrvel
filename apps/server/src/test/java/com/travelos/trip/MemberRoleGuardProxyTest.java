package com.travelos.trip;

import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.TripScopeContext;
import com.travelos.controller.TripMemberController;
import com.travelos.service.TripMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成员管理接口的切面防线行为测试（无 Spring 上下文/数据库）：
 * 用 AspectJProxyFactory 为真实 TripMemberController 织入真实 TripRoleAspect，
 * TripMemberService 为 mock，验证三个写接口对 member/viewer/非成员的
 * 实际拒绝行为（403，非成员由 404 转换）、owner 放行，以及 GET 成员列表仍对成员开放。
 */
@SuppressWarnings("unchecked")
class MemberRoleGuardProxyTest {

  private static final String TRIP_ID = "trip-1";
  private static final String USER_ID = "u-1";
  private static final List<String> MANAGER_ROLES = List.of("owner", "admin");

  private TripMemberService service;
  private TripMemberController controller;

  @BeforeEach
  void setUp() {
    service = mock(TripMemberService.class);
    AspectJProxyFactory factory = new AspectJProxyFactory(new TripMemberController(service));
    factory.addAspect(new TripRoleAspect(service));
    controller = factory.getProxy();
  }

  @AfterEach
  void tearDown() {
    TripScopeContext.clear();
  }

  private void managerCheckThrows(ApiException ex) {
    when(service.requireTripRole(eq(TRIP_ID), eq(USER_ID), eq(MANAGER_ROLES))).thenThrow(ex);
  }

  private void managerCheckPasses() {
    when(service.requireTripRole(eq(TRIP_ID), eq(USER_ID), eq(MANAGER_ROLES))).thenReturn(null);
  }

  // ==================== invite：POST /api/trips/{tripId}/members ====================

  @Test
  void inviteBlocksMember() {
    managerCheckThrows(ApiException.forbidden("没有足够的旅行权限"));
    TripScopeContext.setUserId(USER_ID);

    ApiException ex = assertThrows(ApiException.class, () -> controller.invite(
        TRIP_ID, Map.of("username", "bob", "role", "member"), userContext()));

    assertEquals(403, ex.getStatusCode());
    verify(service, never()).invite(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void inviteBlocksViewer() {
    managerCheckThrows(ApiException.forbidden("没有足够的旅行权限"));
    TripScopeContext.setUserId(USER_ID);

    ApiException ex = assertThrows(ApiException.class, () -> controller.invite(
        TRIP_ID, Map.of("username", "bob", "role", "member"), userContext()));

    assertEquals(403, ex.getStatusCode());
    verify(service, never()).invite(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void inviteBlocksNonMemberAndHidesExistence() {
    // 非成员在 Service 层表现为 404；切面统一转为 403，不暴露资源存在性
    managerCheckThrows(ApiException.notFound("旅行不存在"));
    TripScopeContext.setUserId(USER_ID);

    ApiException ex = assertThrows(ApiException.class, () -> controller.invite(
        TRIP_ID, Map.of("username", "bob", "role", "member"), userContext()));

    assertEquals(403, ex.getStatusCode());
    assertEquals("没有权限访问该旅行资源", ex.getMessage());
    verify(service, never()).invite(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void inviteAllowsOwnerAndEnforcesManagerRoles() {
    managerCheckPasses();
    when(service.invite(TRIP_ID, USER_ID, "bob", "member"))
        .thenReturn(Map.of("members", List.of()));
    TripScopeContext.setUserId(USER_ID);

    Map<String, Object> result = controller.invite(
        TRIP_ID, Map.of("username", "bob", "role", "member"), userContext());

    assertNotNull(result);
    ArgumentCaptor<List<String>> roles = ArgumentCaptor.forClass(List.class);
    verify(service).requireTripRole(eq(TRIP_ID), eq(USER_ID), roles.capture());
    assertEquals(MANAGER_ROLES, roles.getValue());
    verify(service).invite(TRIP_ID, USER_ID, "bob", "member");
  }

  // ==================== updateRole：PATCH /{memberId}/role ====================

  @Test
  void updateRoleBlocksMember() {
    managerCheckThrows(ApiException.forbidden("没有足够的旅行权限"));
    TripScopeContext.setUserId(USER_ID);

    ApiException ex = assertThrows(ApiException.class, () -> controller.updateRole(
        TRIP_ID, "m-2", Map.of("role", "admin"), userContext()));

    assertEquals(403, ex.getStatusCode());
    verify(service, never()).updateRole(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void updateRoleAllowsAdmin() {
    managerCheckPasses();
    when(service.updateRole(TRIP_ID, "m-2", "admin", USER_ID))
        .thenReturn(Map.of("members", List.of()));
    TripScopeContext.setUserId(USER_ID);

    Map<String, Object> result = controller.updateRole(
        TRIP_ID, "m-2", Map.of("role", "admin"), userContext());

    assertNotNull(result);
    verify(service).updateRole(TRIP_ID, "m-2", "admin", USER_ID);
  }

  // ==================== remove：DELETE /{memberId} ====================

  @Test
  void removeBlocksViewer() {
    managerCheckThrows(ApiException.forbidden("没有足够的旅行权限"));
    TripScopeContext.setUserId(USER_ID);

    ApiException ex = assertThrows(ApiException.class, () -> controller.remove(
        TRIP_ID, "m-2", userContext()));

    assertEquals(403, ex.getStatusCode());
    verify(service, never()).remove(anyString(), anyString(), anyString());
  }

  @Test
  void removeAllowsOwner() {
    managerCheckPasses();
    when(service.remove(TRIP_ID, "m-2", USER_ID)).thenReturn(Map.of("members", List.of()));
    TripScopeContext.setUserId(USER_ID);

    Map<String, Object> result = controller.remove(TRIP_ID, "m-2", userContext());

    assertNotNull(result);
    verify(service).remove(TRIP_ID, "m-2", USER_ID);
  }

  // ==================== GET 成员列表：成员仍可访问（不误改） ====================

  @Test
  void findMembersRemainsAccessibleForMember() {
    when(service.findMembers(TRIP_ID, USER_ID)).thenReturn(Map.of("members", List.of()));
    TripScopeContext.setUserId(USER_ID);

    Map<String, Object> result = controller.findMembers(TRIP_ID, userContext());

    assertNotNull(result);
    // GET 无 @RequireTripRole 注解，切面不应介入
    verify(service, never()).requireTripRole(anyString(), anyString(), anyList());
    verify(service).findMembers(TRIP_ID, USER_ID);
  }

  private UserContext userContext() {
    return new UserContext(USER_ID, "operator", "user");
  }
}
