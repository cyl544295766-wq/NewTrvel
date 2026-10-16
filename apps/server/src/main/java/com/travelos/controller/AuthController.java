package com.travelos.controller;

import com.travelos.auth.AuthService;
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

  public AuthController(AuthService authService, UserPreferenceService userPreferenceService) {
    this.authService = authService;
    this.userPreferenceService = userPreferenceService;
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

  @PostMapping("/register")
  public Map<String, Object> register(@Valid @RequestBody RegisterRequest request,
      HttpServletResponse response) {
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
      HttpServletResponse response) {
    Map<String, Object> result = authService.login(request.username(), request.password());
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

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletResponse response) {
    response.addHeader("Set-Cookie", authService.buildClearCookie().toString());
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
