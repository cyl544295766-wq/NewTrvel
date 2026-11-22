package com.travelos.controller;

import com.travelos.auth.AuthInterceptor;
import com.travelos.auth.AuthRateLimiter;
import com.travelos.auth.AuthService;
import com.travelos.auth.ClientIpResolver;
import com.travelos.auth.LoginRequest;
import com.travelos.auth.UserContext;
import com.travelos.auth.CurrentUser;
import com.travelos.service.UserPreferenceService;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;
  private final UserPreferenceService userPreferenceService;
  private final ClientIpResolver clientIpResolver;
  private final AuthRateLimiter rateLimiter;

  public AuthController(AuthService authService, UserPreferenceService userPreferenceService,
      ClientIpResolver clientIpResolver, AuthRateLimiter rateLimiter) {
    this.authService = authService;
    this.userPreferenceService = userPreferenceService;
    this.clientIpResolver = clientIpResolver;
    this.rateLimiter = rateLimiter;
  }

  /** 注册请求体：确认密码在前端完成，不传后端。 */
  public record RegisterRequest(
      @NotBlank @Size(max = 20) String username,
      @NotBlank @Size(max = 64) String password) {
  }

  public record DeactivateRequest(@NotBlank String password) {
  }

  public record PasswordRequest(
      @NotBlank String oldPassword,
      @NotBlank @Size(max = 64) String newPassword) {
  }

  /** 资料更新：displayName / avatarUrl 传 null 表示该字段保持不变。 */
  public record ProfileRequest(String displayName, String avatarUrl) {
  }

  @PostMapping("/register")
  public Map<String, Object> register(@Valid @RequestBody RegisterRequest request,
      javax.servlet.http.HttpServletRequest httpRequest, HttpServletResponse response) {
    // 注册限流：仅来源 IP 维度，计数与阈值判定原子完成（超限 429 + Retry-After）；
    // 阈值内的业务响应（含用户名重复 409）保持原状
    rateLimiter.attemptRegister(clientIpResolver.resolve(httpRequest));
    Map<String, Object> result = authService.register(request.username(), request.password());
    ResponseCookie cookie = authService.buildAuthCookie((String) result.get("token"));
    response.addHeader("Set-Cookie", cookie.toString());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", result.get("user"));
    body.put("token", result.get("token"));
    return body;
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody LoginRequest request,
      javax.servlet.http.HttpServletRequest httpRequest, HttpServletResponse response) {
    // 登录限流与防枚举统一在 AuthService.login 内完成（需要用户名维度与失败计数）
    Map<String, Object> result = authService.login(request.username(), request.password(),
        clientIpResolver.resolve(httpRequest));
    ResponseCookie cookie = authService.buildAuthCookie((String) result.get("token"));
    response.addHeader("Set-Cookie", cookie.toString());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", result.get("user"));
    body.put("token", result.get("token"));
    return body;
  }

  @GetMapping("/me")
  public Map<String, Object> getMe(@CurrentUser UserContext currentUser) {
    return authService.getMe(currentUser);
  }

  /** 更新账户资料：显示名与头像（data URL 上传，服务端落盘转受管地址）。 */
  @PutMapping("/profile")
  public Map<String, Object> updateProfile(@RequestBody ProfileRequest request,
      @CurrentUser UserContext currentUser) {
    return authService.updateProfile(currentUser, request.displayName(), request.avatarUrl());
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletResponse response) {
    response.addHeader("Set-Cookie", authService.buildClearCookie().toString());
    // 同步清掉前端可读的登录标记（华为网页登录下发）
    response.addHeader("Set-Cookie", org.springframework.http.ResponseCookie
        .from("travelos_sso", "").path("/").maxAge(0).build().toString());
    return Map.of("success", true);
  }

  /**
   * Web Token-only 会话兼容（POST /api/auth/session）：
   * Web 端历史会话只有 localStorage 中的 Bearer Token、没有 Cookie（修复发布前登录的用户），
   * 导致依赖 Cookie 的图片/文档/通知 SSE 持续 401。本接口在拦截器校验通过后，
   * 用**当前请求的同一令牌**重设 HttpOnly Cookie——不签发新 Token、不在响应体返回 Token。
   * 无效/过期令牌由拦截器统一 401（遵循与 Bearer 相同的会话失效规则）。
   */
  @PostMapping("/session")
  public Map<String, Object> establishSession(@CurrentUser UserContext currentUser,
      javax.servlet.http.HttpServletRequest request, HttpServletResponse response) {
    String token = (String) request.getAttribute(AuthInterceptor.TOKEN_ATTRIBUTE);
    if (token == null || token.isBlank()) {
      throw com.travelos.common.ApiException.unauthorized("Unauthorized");
    }
    response.addHeader("Set-Cookie", authService.buildAuthCookie(token).toString());
    return Map.of("success", true);
  }

  /** 注销账户：需登录 + 密码确认；软删除并立即清除 Cookie。 */
  @PostMapping("/deactivate")
  public Map<String, Object> deactivate(@Valid @RequestBody DeactivateRequest request,
      @CurrentUser UserContext currentUser, HttpServletResponse response) {
    authService.deactivate(currentUser, request.password());
    response.addHeader("Set-Cookie", authService.buildClearCookie().toString());
    return Map.of("success", true);
  }

  /** 修改密码：验证当前密码 + 新密码规则；当前会话保持有效，其他设备不强制下线。 */
  @PostMapping("/password")
  public Map<String, Object> changePassword(@Valid @RequestBody PasswordRequest request,
      @CurrentUser UserContext currentUser) {
    authService.changePassword(currentUser, request.oldPassword(), request.newPassword());
    return Map.of("success", true);
  }

  /** 读取偏好设置（首次访问按默认值创建）。 */
  @GetMapping("/preferences")
  public Map<String, Object> getPreferences(@CurrentUser UserContext currentUser) {
    return userPreferenceService.get(currentUser);
  }

  /** 更新偏好设置：只更新传入的字段，null = 保持不变。 */
  @PutMapping("/preferences")
  public Map<String, Object> updatePreferences(
      @RequestBody UserPreferenceService.UpdateRequest request,
      @CurrentUser UserContext currentUser) {
    return userPreferenceService.update(currentUser, request);
  }
}
