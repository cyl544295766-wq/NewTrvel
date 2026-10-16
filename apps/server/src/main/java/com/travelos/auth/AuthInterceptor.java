package com.travelos.auth;

import com.travelos.common.ApiException;
import com.travelos.common.JwtUtil;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * Guards every /api/** route except the public ones, verifies the HttpOnly JWT cookie and
 * exposes UserContext + the @CurrentUser argument resolver.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor, HandlerMethodArgumentResolver {

  public static final String USER_ATTRIBUTE = "travelos.currentUser";

  private final String jwtSecret;
  private final String cookieName;
  private final com.travelos.service.UserService userService;

  public AuthInterceptor(@Value("${jwt.secret}") String jwtSecret,
      @Value("${auth.cookie-name:access_token}") String cookieName,
      com.travelos.service.UserService userService) {
    this.jwtSecret = jwtSecret;
    this.cookieName = cookieName;
    this.userService = userService;
  }

  @Override
  public boolean preHandle(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response, @NonNull Object handler) {
    String token = readToken(request);
    if (token == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    Map<String, Object> payload = JwtUtil.verify(jwtSecret, token);
    if (payload == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    Object sub = payload.get("sub");
    Object username = payload.get("username");
    if (sub == null || username == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    // 已注销/被禁用的账户即使持有未过期 JWT 也立即失效
    com.travelos.entity.User user = userService.findById(sub.toString());
    if (user == null || !"active".equals(user.getStatus())) {
      throw ApiException.unauthorized("账户已注销或不可用");
    }
    // 桥接 MyBatis-Plus 数据权限拦截器（afterCompletion 清理，防线程复用串号）
    com.travelos.common.TripScopeContext.setUserId(user.getId());
    request.setAttribute(USER_ATTRIBUTE, new UserContext(sub.toString(), username.toString(), user.getRole()));
    return true;
  }

  @Override
  public void afterCompletion(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
    com.travelos.common.TripScopeContext.clear();
  }

  private String readToken(HttpServletRequest request) {
    // 1. 优先从 Authorization: Bearer <token> 取（供跨域 / 静态前端用）
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      String t = authHeader.substring(7).trim();
      if (!t.isEmpty()) {
        return t;
      }
    }
    // 2. 回落到 HttpOnly Cookie（与现有 SPA 同源场景保持一致）
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (cookie.getName().equals(cookieName)) {
        return cookie.getValue();
      }
    }
    return null;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(UserContext.class)
        && parameter.hasParameterAnnotation(CurrentUser.class);
  }

  @Override
  public Object resolveArgument(@NonNull MethodParameter parameter,
      ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object user = webRequest.getAttribute(USER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
    if (user == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    return user;
  }
}
