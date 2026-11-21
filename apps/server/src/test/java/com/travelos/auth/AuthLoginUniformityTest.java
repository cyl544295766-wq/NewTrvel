package com.travelos.auth;

import com.travelos.common.ApiException;
import com.travelos.entity.User;
import com.travelos.service.AuditService;
import com.travelos.service.FileStorageService;
import com.travelos.service.NotificationStreamService;
import com.travelos.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录防枚举：所有失败模式（不存在 / 口令错误 / 已禁用 / 已注销）
 * 返回完全一致的状态码与文案；限流在凭据校验之前生效。
 */
class AuthLoginUniformityTest {

  private UserService userService;
  private AuthRateLimiter limiter;
  private AuthService authService;

  private static final String IP = "203.0.113.8";
  private static final String USER = "target";

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    limiter = new AuthRateLimiter(
        new AuthRateLimiter.Limits(60, 50, 3, 500, 60, 10, 1000),
        new MutableClock());
    authService = new AuthService(userService, mock(AuditService.class),
        mock(FileStorageService.class), mock(NotificationStreamService.class),
        limiter, "test-secret", "access_token", false);
    when(userService.toPublicUser(any(User.class))).thenReturn(java.util.Map.of("username", USER));
  }

  private static User user(String status, String password) {
    User u = new User();
    u.setId("u1");
    u.setUsername(USER);
    u.setStatus(status);
    u.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        .encode(password));
    return u;
  }

  private ApiException failure(String username, String password) {
    ApiException ex = assertThrows(ApiException.class,
        () -> authService.login(username, password, IP));
    return ex;
  }

  @Test
  void allFailureModesReturnIdenticalStatusAndMessage() {
    // 1) 用户不存在
    when(userService.findByUsername("ghost")).thenReturn(null);
    ApiException ghost = failure("ghost", "whatever123");

    // 2) 口令错误（账户存在且 active）
    when(userService.findByUsername(USER)).thenReturn(user("active", "Right-Pass-9"));
    ApiException wrongPw = failure(USER, "Wrong-Pass-9");

    // 3) 已禁用账户 + 正确口令
    when(userService.findByUsername(USER)).thenReturn(user("disabled", "Right-Pass-9"));
    ApiException disabled = failure(USER, "Right-Pass-9");

    // 4) 已注销账户 + 正确口令
    when(userService.findByUsername(USER)).thenReturn(user("deleted", "Right-Pass-9"));
    ApiException deleted = failure(USER, "Right-Pass-9");

    ApiException[] all = {ghost, wrongPw, disabled, deleted};
    for (ApiException ex : all) {
      assertEquals(400, ex.getStatusCode(), "所有失败必须同为 400");
      assertEquals("用户名或密码错误", ex.getMessage(), "所有失败必须同一文案");
    }
    // 逐字一致（状态码 + 文案 = 对外响应体完全相同）
    for (int i = 1; i < all.length; i++) {
      assertEquals(all[0].getStatusCode(), all[i].getStatusCode());
      assertEquals(all[0].getMessage(), all[i].getMessage());
    }
    assertFalse(all[0].getMessage().contains(USER));
    assertFalse(all[0].getMessage().contains("disabled"));
  }

  @Test
  void validLoginStillSucceeds() {
    when(userService.findByUsername(USER)).thenReturn(user("active", "Right-Pass-9"));
    var body = authService.login(USER, "Right-Pass-9", IP);
    assertNotNull(body.get("token"), "正常登录必须返回 Token");
    assertNotNull(body.get("user"));
  }

  @Test
  void failuresAreCountedAndCorrectPasswordBlockedAtThreshold() {
    when(userService.findByUsername(USER)).thenReturn(user("active", "Right-Pass-9"));
    // 账号+IP 上限 3：3 次失败均按 400 处理并计数
    for (int i = 0; i < 3; i++) {
      assertEquals(400, failure(USER, "Wrong-Pass-" + i).getStatusCode());
    }
    assertEquals(3, limiter.accountFailureCount(USER, IP));
    // 达到阈值后：即使口令正确也只能 429（不能借正确口令继续尝试）
    ApiException blocked = assertThrows(ApiException.class,
        () -> authService.login(USER, "Right-Pass-9", IP));
    assertEquals(429, blocked.getStatusCode());
    assertNotNull(blocked.getRetryAfterSeconds());
    assertEquals(AuthRateLimiter.TOO_MANY_REQUESTS_MESSAGE, blocked.getMessage());
    // 不同来源不受该账号在本来源的限制
    when(userService.findByUsername(USER)).thenReturn(user("active", "Right-Pass-9"));
    var body = authService.login(USER, "Right-Pass-9", "198.51.100.77");
    assertNotNull(body.get("token"));
  }

  @Test
  void successfulLoginDoesNotConsumeOtherAccountsFailureBudget() {
    when(userService.findByUsername(USER)).thenReturn(user("active", "Right-Pass-9"));
    // alice 触发 3 次失败
    when(userService.findByUsername("alice")).thenReturn(null);
    for (int i = 0; i < 3; i++) {
      failure("alice", "x-pass-12345");
    }
    // bob 成功登录：只清自己的计数（本就没有），alice 计数不受影响
    authService.login(USER, "Right-Pass-9", IP);
    assertEquals(3, limiter.accountFailureCount("alice", IP));
    // alice 仍被限流
    ApiException blocked = assertThrows(ApiException.class,
        () -> authService.login("alice", "Right-Pass-9", IP));
    assertEquals(429, blocked.getStatusCode());
  }
}
