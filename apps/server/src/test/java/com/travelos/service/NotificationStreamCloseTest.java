package com.travelos.service;

import com.travelos.auth.AuthService;
import com.travelos.auth.UserContext;
import com.travelos.entity.User;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话撤销（改密 / 注销 / 管理员禁用）时主动关闭该用户既有通知 SSE 的行为测试
 * （无 Spring 上下文/数据库：真实 AuthService/AdminService/NotificationStreamService + mock 数据层）。
 *
 * <p>「已关闭」的可观测断言：ResponseBodyEmitter.complete() 置位后 send() 抛
 * IllegalStateException（javap 已核对 spring-webmvc 5.3.31 实现）；注册表清理用
 * connectionCount 断言。新请求侧的 Bearer/Cookie 失效回归见 {@code AuthSessionWebTest}。</p>
 */
class NotificationStreamCloseTest {

  private static final String SECRET = "test-jwt-secret-for-stream-close-tests";
  private static final String RAW_PASSWORD = "pass1234";

  private UserService userService;
  private AuditService auditService;
  private NotificationStreamService streamService;
  private AuthService authService;
  private AdminService adminService;
  private UserMapper userMapper;

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    auditService = mock(AuditService.class);
    streamService = new NotificationStreamService();
    authService = new AuthService(userService, auditService, mock(FileStorageService.class),
        streamService,
        new com.travelos.auth.AuthRateLimiter(
            new com.travelos.auth.AuthRateLimiter.Limits(300, 30, 10, 600, 600, 10, 20000),
            java.time.Clock.systemUTC()),
        SECRET, "access_token", false);
    userMapper = mock(UserMapper.class);
    adminService = new AdminService(userMapper, mock(TripMapper.class),
        mock(TripJournalMapper.class), mock(AiMessageMapper.class), streamService);
  }

  private User activeUser(String id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setDisplayName(username);
    user.setRole("user");
    user.setStatus("active");
    user.setPasswordHash(new BCryptPasswordEncoder().encode(RAW_PASSWORD));
    return user;
  }

  private UserContext contextOf(User user) {
    return new UserContext(user.getId(), user.getUsername(), user.getRole());
  }

  /** 已关闭的连接：send 被拒绝（complete 置位）。 */
  private static void assertClosed(SseEmitter emitter) {
    assertThrows(IllegalStateException.class,
        () -> emitter.send(SseEmitter.event().name("probe").data("x")),
        "关闭后的连接应拒绝 send");
  }

  /** 仍打开的连接：send 可用（未初始化时缓冲 early send，不抛异常）。 */
  private static void assertOpen(SseEmitter emitter) {
    assertDoesNotThrow(() -> emitter.send(SseEmitter.event().name("probe").data("x")),
        "未受影响的连接应可继续 send");
  }

  // ==================== 1：改密后目标用户既有 SSE 被关闭 ====================

  @Test
  void changePasswordClosesExistingStreams() {
    User user = activeUser("u-alice", "alice");
    when(userService.findById("u-alice")).thenReturn(user);
    SseEmitter emitter = streamService.subscribe("u-alice");
    assertEquals(1, streamService.connectionCount("u-alice"));

    authService.changePassword(contextOf(user), RAW_PASSWORD, "newpass99");

    assertEquals(0, streamService.connectionCount("u-alice"), "注册表应清理");
    assertClosed(emitter);
    assertNotNull(user.getTokenValidAfter(), "令牌水位应刷新");
  }

  // ==================== 2：注销后目标用户既有 SSE 被关闭 ====================

  @Test
  void deactivateClosesExistingStreams() {
    User user = activeUser("u-alice", "alice");
    when(userService.findById("u-alice")).thenReturn(user);
    SseEmitter emitter = streamService.subscribe("u-alice");

    authService.deactivate(contextOf(user), RAW_PASSWORD);

    assertEquals(0, streamService.connectionCount("u-alice"));
    assertClosed(emitter);
    assertEquals("deleted", user.getStatus());
  }

  // ==================== 3：管理员禁用后目标用户既有 SSE 被关闭 ====================

  @Test
  void adminDisableClosesExistingStreams() {
    User target = activeUser("u-victim", "victim");
    when(userMapper.selectById("u-victim")).thenReturn(target);
    SseEmitter emitter = streamService.subscribe("u-victim");

    adminService.updateStatus("u-admin", "u-victim", "disabled");

    assertEquals(0, streamService.connectionCount("u-victim"));
    assertClosed(emitter);
    assertEquals("disabled", target.getStatus());
    assertNotNull(target.getTokenValidAfter(), "禁用应刷新令牌水位");
  }

  // ==================== 4：同一用户的多个连接全部处理 ====================

  @Test
  void allConnectionsOfSameUserAreClosed() {
    User user = activeUser("u-tabs", "tabs");
    when(userService.findById("u-tabs")).thenReturn(user);
    SseEmitter tab1 = streamService.subscribe("u-tabs");
    SseEmitter tab2 = streamService.subscribe("u-tabs");
    SseEmitter tab3 = streamService.subscribe("u-tabs");
    assertEquals(3, streamService.connectionCount("u-tabs"));

    authService.changePassword(contextOf(user), RAW_PASSWORD, "newpass99");

    assertEquals(0, streamService.connectionCount("u-tabs"), "多标签页连接全部移出注册表");
    assertClosed(tab1);
    assertClosed(tab2);
    assertClosed(tab3);
  }

  // ==================== 5：其他用户的连接不受影响 ====================

  @Test
  void otherUsersStreamsRemainOpen() {
    User victim = activeUser("u-victim", "victim");
    User bystander = activeUser("u-bystander", "bystander");
    when(userService.findById("u-victim")).thenReturn(victim);
    SseEmitter victim1 = streamService.subscribe("u-victim");
    SseEmitter victim2 = streamService.subscribe("u-victim");
    SseEmitter other = streamService.subscribe("u-bystander");

    authService.changePassword(contextOf(victim), RAW_PASSWORD, "newpass99");

    assertEquals(0, streamService.connectionCount("u-victim"));
    assertClosed(victim1);
    assertClosed(victim2);
    assertEquals(1, streamService.connectionCount("u-bystander"), "他人连接不受影响");
    assertOpen(other);
  }

  // ==================== 6：关闭后注册表清理，可重新订阅 ====================

  @Test
  void registryCleanedAndResubscribeWorks() {
    User user = activeUser("u-alice", "alice");
    when(userService.findById("u-alice")).thenReturn(user);
    streamService.subscribe("u-alice");

    authService.deactivate(contextOf(user), RAW_PASSWORD);

    assertEquals(0, streamService.connectionCount("u-alice"));
    // 注册表已无该用户条目：推送是无操作，不会触碰已关闭的 emitter
    assertDoesNotThrow(() -> streamService.notifyUnreadChanged("u-alice", "test"));
    // 重新订阅（如同一账号重新登录后）正常工作
    SseEmitter fresh = streamService.subscribe("u-alice");
    assertEquals(1, streamService.connectionCount("u-alice"));
    assertOpen(fresh);
  }

  // ==================== 7：无连接时撤销是安全无操作 ====================

  @Test
  void revokeWithoutConnectionsIsNoop() {
    User user = activeUser("u-alice", "alice");
    when(userService.findById("u-alice")).thenReturn(user);

    authService.changePassword(contextOf(user), RAW_PASSWORD, "newpass99");

    assertEquals(0, streamService.connectionCount("u-alice"));
    // 禁用流程对未在线用户同样安全
    User target = activeUser("u-offline", "offline");
    when(userMapper.selectById("u-offline")).thenReturn(target);
    adminService.updateStatus("u-admin", "u-offline", "disabled");
    assertEquals(0, streamService.connectionCount("u-offline"));
    assertTrue("disabled".equals(target.getStatus()));
  }
}
