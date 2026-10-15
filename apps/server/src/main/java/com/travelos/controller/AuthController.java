package com.travelos.controller;

import com.travelos.auth.AuthService;
import com.travelos.auth.LoginRequest;
import com.travelos.auth.UserContext;
import com.travelos.auth.CurrentUser;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  /** 注册请求体：确认密码在前端完成，不传后端。 */
  public record RegisterRequest(
      @NotBlank @Size(max = 20) String username,
      @NotBlank @Size(max = 64) String password) {
  }

  public record DeactivateRequest(@NotBlank String password) {
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
}
