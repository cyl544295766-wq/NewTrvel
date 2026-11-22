package com.travelos.controller;

import com.travelos.auth.AuthService;
import com.travelos.auth.huawei.HuaweiAuthService;
import org.springframework.http.ResponseCookie;
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

/**
 * 华为账号（Account Kit）登录。
 *
 * <p>客户端只负责拿到授权码；换取令牌、验签、建号、签发本应用 JWT 全部在服务端完成，
 * 客户端不接触任何华为令牌。响应体返回 token 供原生端保存，同时写 HttpOnly Cookie
 * 以便与现有 Web 前端保持同一套会话方式。</p>
 */
@RestController
@RequestMapping("/api/auth/huawei")
public class HuaweiAuthController {

  private final HuaweiAuthService huaweiAuthService;
  private final AuthService authService;

  public HuaweiAuthController(HuaweiAuthService huaweiAuthService, AuthService authService) {
    this.huaweiAuthService = huaweiAuthService;
    this.authService = authService;
  }

  /** 授权码为一次性短期凭证，长度上限与华为侧约定一致（1–1024）。 */
  public record HuaweiLoginRequest(@NotBlank @Size(max = 1024) String authCode) {
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody HuaweiLoginRequest request,
      HttpServletResponse response) {
    Map<String, Object> result = huaweiAuthService.login(request.authCode());
    ResponseCookie cookie = authService.buildAuthCookie((String) result.get("token"));
    response.addHeader("Set-Cookie", cookie.toString());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", result.get("user"));
    body.put("token", result.get("token"));
    return body;
  }
}
