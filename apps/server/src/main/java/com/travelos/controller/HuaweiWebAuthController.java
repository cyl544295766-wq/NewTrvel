package com.travelos.controller;

import com.travelos.auth.AuthService;
import com.travelos.auth.huawei.HuaweiAuthService;
import com.travelos.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 网页端华为账号登录（浏览器 OAuth 跳转）。
 *
 * <p>与 App 端共用同一个 client_id 与同一套 ID Token 校验逻辑，因此两端的
 * openID 完全一致，天然落在同一条 users 记录上——这是「账号数据互通」的基础。</p>
 *
 * <p>安全点：state 放 HttpOnly Cookie 防 CSRF；授权码一次性使用；回调只接受
 * 与 Cookie 中 state 相同的请求，失败不建号。</p>
 */
@RestController
@RequestMapping("/api/auth/huawei/web")
public class HuaweiWebAuthController {

  private static final String STATE_COOKIE = "hw_web_state";
  /** JS 可读的登录标记：网页前端靠 localStorage 判断登录态，华为流程不经过前端，
   *  故额外下发这个非 HttpOnly 标记，让 /home/ 等页面能同步识别「已登录」。 */
  private static final String SSO_MARKER_COOKIE = "travelos_sso";
  private static final Logger log = LoggerFactory.getLogger(HuaweiWebAuthController.class);
  private static final String AUTHORIZE_ENDPOINT =
      "https://oauth-login.cloud.huawei.com/oauth2/v3/authorize";
  private static final int STATE_TTL_SECONDS = 600;

  private final HuaweiAuthService huaweiAuthService;
  private final AuthService authService;
  private final String clientId;
  private final String callbackUrl;
  private final String successRedirect;
  private final boolean configured;

  public HuaweiWebAuthController(HuaweiAuthService huaweiAuthService, AuthService authService,
      @Value("${huawei.account.client-id:}") String clientId,
      @Value("${huawei.web.callback-url}") String callbackUrl,
      @Value("${huawei.web.success-redirect}") String successRedirect) {
    this.huaweiAuthService = huaweiAuthService;
    this.authService = authService;
    this.clientId = clientId == null ? "" : clientId.trim();
    this.callbackUrl = callbackUrl;
    this.successRedirect = normalizeRedirect(successRedirect);
    this.configured = !this.clientId.isEmpty();
  }

  /**
   * 静态资源目录必须带尾斜杠（/home 会 404，/home/ 才命中 index.html）。
   * 这里做一次兜底，避免配置写漏斜杠导致登录成功后落到 404。
   */
  private static String normalizeRedirect(String value) {
    if (value == null || value.isBlank()) {
      return "/home/";
    }
    String target = value.trim();
    if (target.startsWith("http://") || target.startsWith("https://") || target.endsWith("/")) {
      return target;
    }
    // 带扩展名视为具体文件，不加斜杠
    return target.contains(".") ? target : target + "/";
  }

  /** 登录页「华为账号登录」直接跳到这个地址 */
  @GetMapping("/authorize")
  public void authorize(HttpServletResponse response) throws IOException {
    if (!configured) {
      throw ApiException.serviceUnavailable("华为账号登录尚未配置");
    }
    String state = UUID.randomUUID().toString().replace("-", "");
    ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, state)
        .httpOnly(true)
        .path("/")
        .maxAge(STATE_TTL_SECONDS)
        .sameSite("Lax")
        .build();
    response.addHeader("Set-Cookie", cookie.toString());
    response.sendRedirect(buildAuthorizeUrl(state));
  }

  @GetMapping("/callback")
  public void callback(@RequestParam(value = "code", required = false) String code,
      @RequestParam(value = "state", required = false) String state,
      @CookieValue(value = STATE_COOKIE, required = false) String cookieState,
      HttpServletResponse response) throws IOException {
    if (code == null || code.isBlank() || state == null || cookieState == null
        || !state.equals(cookieState)) {
      throw ApiException.badRequest("授权状态校验失败，请重新发起登录");
    }
    Map<String, Object> result;
    try {
      // 网页授权码绑定 redirect_uri，必须原样回传（App 走 SDK，没有这一步）
      result = huaweiAuthService.login(code, callbackUrl);
    } catch (ApiException ex) {
      // 只记状态码与原因，不记授权码/令牌，便于定位网页链路与 App 链路的差异
      log.warn("网页华为登录失败: status={}, reason={}", ex.getStatusCode(), ex.getMessage());
      throw ex;
    }
    String token = (String) result.get("token");
    response.addHeader("Set-Cookie", authService.buildAuthCookie(token).toString());
    // 登录标记：非 HttpOnly，供前端同步判断登录态（真正的鉴权仍由 HttpOnly 会话 Cookie 承担）
    response.addHeader("Set-Cookie", ResponseCookie.from(SSO_MARKER_COOKIE, "1")
        .httpOnly(false).path("/").maxAge(7 * 24 * 60 * 60).sameSite("Lax").build().toString());
    // state 用完即清，避免重放
    response.addHeader("Set-Cookie", ResponseCookie.from(STATE_COOKIE, "")
        .httpOnly(true).path("/").maxAge(0).build().toString());
    response.sendRedirect(successRedirect);
  }

  private String buildAuthorizeUrl(String state) {
    String scope = URLEncoder.encode("openid profile", StandardCharsets.UTF_8);
    return AUTHORIZE_ENDPOINT
        + "?response_type=code"
        + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        + "&redirect_uri=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8)
        + "&scope=" + scope
        + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
  }
}
