package com.travelos.auth;

import com.travelos.common.ApiException;
import com.travelos.common.JwtUtil;
import com.travelos.controller.AuthController;
import com.travelos.controller.NotificationController;
import com.travelos.common.GlobalExceptionHandler;
import com.travelos.entity.User;
import com.travelos.service.AuditService;
import com.travelos.service.FileStorageService;
import com.travelos.service.NotificationService;
import com.travelos.service.NotificationStreamService;
import com.travelos.service.UserPreferenceService;
import com.travelos.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.Cookie;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 会话（Cookie + Bearer 双通道）HTTP 层行为测试（standalone MockMvc，无 Spring 上下文/数据库）：
 * 覆盖登录/注册 Set-Cookie、Token-only 兼容会话建立（POST /api/auth/session）、
 * /files/** 与通知 SSE 的 Cookie 鉴权（有效放行/无凭证 401）、登出清 Cookie、
 * 以及改密/注销/禁用后旧 Cookie 与旧 Bearer 同一水位失效。
 *
 * 接线说明：使用真实 AuthService / AuthInterceptor / GlobalExceptionHandler 与 mock 数据层；
 * MappedInterceptor 的 include/exclude 列表镜像 WebConfig.addInterceptors 的生产配置
 * （/api/** 与 /files/** 全守护，health/login/register/logout 例外）；
 * /files/** 命中由 FileProbeController 测试替身代替 ResourceHttpRequestHandler——
 * 资源静态映射本身是 StaticResourceConfig 既有能力，本测试验证的是鉴权层放行/拒绝行为。
 */
class AuthSessionWebTest {

  private static final String SECRET = "test-jwt-secret-for-webmvc-session-tests";
  private static final String COOKIE_NAME = "access_token";
  private static final long SEVEN_DAYS_SECONDS = 7 * 24 * 60 * 60L;
  private static final String USER_ID = "u-alice";
  private static final String USERNAME = "alice";
  private static final String RAW_PASSWORD = "pass1234";
  private static final String MANAGED_FILE = "/files/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.txt";

  private UserService userService;
  private NotificationService notificationService;
  private NotificationStreamService notificationStreamService;
  private User activeUser;
  private MockMvc mockMvc;

  /** /files/** 鉴权放行后的处理器替身（代替静态资源 handler）。 */
  @RestController
  static class FileProbeController {
    @GetMapping("/files/**")
    public Map<String, Object> serve() {
      return Map.of("ok", true);
    }
  }

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    AuditService auditService = mock(AuditService.class);
    UserPreferenceService preferenceService = mock(UserPreferenceService.class);
    notificationService = mock(NotificationService.class);
    notificationStreamService = mock(NotificationStreamService.class);

    activeUser = activeUser();
    when(userService.findById(USER_ID)).thenReturn(activeUser);
    when(userService.toPublicUser(any(User.class))).thenReturn(Map.of("username", USERNAME));

    // 限流器每测试新建（计数隔离）；阈值取宽松默认，不影响既有用例
    com.travelos.auth.AuthRateLimiter rateLimiter = new com.travelos.auth.AuthRateLimiter(
        new com.travelos.auth.AuthRateLimiter.Limits(300, 30, 10, 600, 600, 10, 20000),
        java.time.Clock.systemUTC());
    AuthService authService = new AuthService(userService, auditService,
        mock(FileStorageService.class), notificationStreamService, rateLimiter, SECRET, COOKIE_NAME, false);
    AuthInterceptor interceptor = new AuthInterceptor(SECRET, COOKIE_NAME, userService);
    AuthController authController = new AuthController(authService, preferenceService,
        new com.travelos.auth.ClientIpResolver(""), rateLimiter);
    NotificationController notificationController =
        new NotificationController(notificationService, notificationStreamService);

    // 与 WebConfig.addInterceptors 的 include/exclude 保持一致（镜像）
    MappedInterceptor guard = new MappedInterceptor(
        new String[]{"/api/**", "/files/**"},
        new String[]{"/api/health", "/api/health/database",
            "/api/auth/login", "/api/auth/register", "/api/auth/logout"},
        interceptor);

    mockMvc = MockMvcBuilders.standaloneSetup(authController, notificationController,
            new FileProbeController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .setCustomArgumentResolvers(interceptor)
        .addInterceptors(guard)
        .build();
  }

  private User activeUser() {
    User user = new User();
    user.setId(USER_ID);
    user.setUsername(USERNAME);
    user.setDisplayName(USERNAME);
    user.setStatus("active");
    user.setRole("user");
    user.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        .encode(RAW_PASSWORD));
    return user;
  }

  private String validToken() {
    return JwtUtil.sign(SECRET, Map.of("sub", USER_ID, "username", USERNAME), 3600);
  }

  // ==================== 1/2：登录、注册建立 HttpOnly Cookie 会话 ====================

  @Test
  void loginSetsHttpOnlyCookie() throws Exception {
    when(userService.findByUsername(USERNAME)).thenReturn(activeUser);

    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"pass1234\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie, "登录响应必须下发 Set-Cookie");
    assertTrue(setCookie.startsWith(COOKIE_NAME + "="));
    assertTrue(setCookie.contains("HttpOnly"), "Cookie 必须 HttpOnly：" + setCookie);
    assertTrue(setCookie.contains("SameSite=Lax"), "Cookie 必须 SameSite=Lax：" + setCookie);
    assertTrue(setCookie.contains("Path=/"), "Cookie 必须 Path=/：" + setCookie);
    assertTrue(setCookie.contains("Max-Age=" + SEVEN_DAYS_SECONDS), "有效期 7 天：" + setCookie);
    assertFalse(setCookie.contains("Secure"), "本地 HTTP 环境不应带 Secure：" + setCookie);
  }

  @Test
  void registerSetsHttpOnlyCookie() throws Exception {
    when(userService.countByUsernameIgnoreCase(USERNAME.toLowerCase())).thenReturn(0L);
    doAnswer(inv -> {
      User u = inv.getArgument(0);
      u.setId("u-new");
      return null;
    }).when(userService).createUser(any(User.class));

    MvcResult result = mockMvc.perform(post("/api/auth/register")
            .contentType("application/json")
            .content("{\"username\":\"alice\",\"password\":\"pass1234\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie, "注册响应必须下发 Set-Cookie");
    assertTrue(setCookie.contains("HttpOnly"));
    assertTrue(setCookie.contains("SameSite=Lax"));
  }

  // ==================== 3/4：Token-only 兼容会话建立 ====================

  @Test
  void sessionEstablishesCookieWithSameTokenWithoutIssuingNewOne() throws Exception {
    String token = validToken();

    MvcResult result = mockMvc.perform(post("/api/auth/session")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").doesNotExist()) // 不签发/不返回新长期 Token
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie);
    // 重设的是同一令牌：Cookie 值与请求中的 Bearer 完全一致
    assertEquals(COOKIE_NAME + "=" + token, setCookie.split(";")[0]);
    assertTrue(setCookie.contains("HttpOnly"));
    assertTrue(setCookie.contains("SameSite=Lax"));
  }

  @Test
  void sessionRejectsInvalidOrExpiredToken() throws Exception {
    String tampered = validToken();
    tampered = tampered.substring(0, tampered.length() - 4) + "AAAA";

    MvcResult result = mockMvc.perform(post("/api/auth/session")
            .header("Authorization", "Bearer " + tampered))
        .andExpect(status().isUnauthorized())
        .andReturn();

    assertNull(result.getResponse().getHeader("Set-Cookie"),
        "无效令牌不得建立 Cookie 会话");
  }

  // ==================== 5/6：/files/** 的 Cookie 鉴权 ====================

  @Test
  void fileRequestWithValidCookieIsAllowed() throws Exception {
    mockMvc.perform(get(MANAGED_FILE).cookie(new Cookie(COOKIE_NAME, validToken())))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"ok\":true}"));
  }

  @Test
  void fileRequestWithoutAnyCredentialIsUnauthorized() throws Exception {
    mockMvc.perform(get(MANAGED_FILE))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void fileRequestWithInvalidCookieIsUnauthorized() throws Exception {
    mockMvc.perform(get(MANAGED_FILE).cookie(new Cookie(COOKIE_NAME, "not-a-valid-jwt")))
        .andExpect(status().isUnauthorized());
  }

  /**
   * 静态资源 handler（ResourceHttpRequestHandler，非 HandlerMethod）不走
   * {@code GlobalExceptionHandler} 的 advice 通道：拦截器必须直接以 401 结束响应，
   * 而不是抛出未处理异常被容器变成 500。
   */
  @Test
  void resourceHandlerRejectionRespondsWithoutThrowing() {
    AuthInterceptor interceptor = new AuthInterceptor(SECRET, COOKIE_NAME, userService);
    org.springframework.mock.web.MockHttpServletRequest req =
        new org.springframework.mock.web.MockHttpServletRequest("GET", "/files/x.png");
    org.springframework.mock.web.MockHttpServletResponse res =
        new org.springframework.mock.web.MockHttpServletResponse();

    boolean proceed = interceptor.preHandle(req, res, new Object());

    assertFalse(proceed, "无凭证必须拒绝放行");
    assertEquals(401, res.getStatus(), "静态资源无凭证应直接 401（而非异常上抛变 500）");
  }

  // ==================== 7/8：通知 SSE 的 Cookie 鉴权 ====================

  @Test
  void notificationStreamWithValidCookieConnects() throws Exception {
    when(notificationStreamService.subscribe(USER_ID)).thenReturn(new SseEmitter());
    when(notificationService.getUnreadCount(USER_ID)).thenReturn(Map.of("count", 3));

    mockMvc.perform(get("/api/notifications/stream")
            .cookie(new Cookie(COOKIE_NAME, validToken())))
        .andExpect(request().asyncStarted());
  }

  @Test
  void notificationStreamWithoutCredentialIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/notifications/stream"))
        .andExpect(status().isUnauthorized());
  }

  // ==================== 9：退出清除 Cookie ====================

  @Test
  void logoutClearsCookie() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/logout"))
        .andExpect(status().isOk())
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("Max-Age=0"), "登出必须清除 Cookie：" + setCookie);
  }

  // ==================== 10：改密/注销/禁用后旧 Cookie 与旧 Bearer 一并失效 ====================

  @Test
  void changePasswordRevokesOldTokenForBothBearerAndCookieChannels() throws Exception {
    String token = validToken();
    // 消除同秒边界竞态：失效判定是 iat < floor(water/1000)（严格小于），
    // 令牌签发与改密若落在同一秒则不会失效——等到进入下一秒再触发改密
    long iat = ((Number) JwtUtil.verify(SECRET, token).get("iat")).longValue();
    while (System.currentTimeMillis() / 1000 <= iat) {
      Thread.sleep(25);
    }

    mockMvc.perform(post("/api/auth/password")
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("{\"oldPassword\":\"pass1234\",\"newPassword\":\"newpass99\"}"))
        .andExpect(status().isOk());

    // stub 返回的同一 User 对象已被 changePassword 设置 token_valid_after 水位：
    // 同一 JWT（Bearer 或 Cookie 通道）都立即 401
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(MANAGED_FILE).cookie(new Cookie(COOKIE_NAME, token)))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/notifications/stream").cookie(new Cookie(COOKIE_NAME, token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deactivateRevokesTokenAndClearsCookie() throws Exception {
    String token = validToken();

    MvcResult result = mockMvc.perform(post("/api/auth/deactivate")
            .header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("{\"password\":\"pass1234\"}"))
        .andExpect(status().isOk())
        .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie);
    assertTrue(setCookie.contains("Max-Age=0"), "注销必须清除 Cookie");
    assertEquals("deleted", activeUser.getStatus());
    // 同一令牌此后全部失效（status=deleted + 水位作废）
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(MANAGED_FILE).cookie(new Cookie(COOKIE_NAME, token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disabledAccountIsUnauthorizedOnAllChannels() throws Exception {
    activeUser.setStatus("disabled");
    String token = validToken();

    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(MANAGED_FILE).cookie(new Cookie(COOKIE_NAME, token)))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/notifications/stream").cookie(new Cookie(COOKIE_NAME, token)))
        .andExpect(status().isUnauthorized());
  }

  // ==================== 普通 API 双通道回归 ====================

  @Test
  void cookieChannelWorksForPlainApiRequests() throws Exception {
    mockMvc.perform(get("/api/auth/me").cookie(new Cookie(COOKIE_NAME, validToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value(USERNAME));
  }

  @Test
  void bearerChannelStillWorksForPlainApiRequests() throws Exception {
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + validToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value(USERNAME));
  }
}
