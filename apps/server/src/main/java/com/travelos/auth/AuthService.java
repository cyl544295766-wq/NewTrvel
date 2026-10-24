package com.travelos.auth;

import com.travelos.common.ApiException;
import com.travelos.common.JwtUtil;
import com.travelos.entity.User;
import com.travelos.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AuthService {

  private static final long SEVEN_DAYS_SECONDS = 7 * 24 * 60 * 60L;
  private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

  private final UserService userService;
  private final com.travelos.service.AuditService auditService;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final String jwtSecret;
  private final String cookieName;
  private final boolean secureCookie;

  public AuthService(UserService userService,
      com.travelos.service.AuditService auditService,
      @Value("${jwt.secret}") String jwtSecret,
      @Value("${auth.cookie-name:access_token}") String cookieName,
      @Value("${auth.secure-cookie:false}") boolean secureCookie) {
    this.userService = userService;
    this.auditService = auditService;
    this.jwtSecret = jwtSecret;
    this.cookieName = cookieName;
    this.secureCookie = secureCookie;
  }

  /** 注册：校验用户名/密码规则与唯一性，BCrypt 存储密码，注册即自动登录。 */
  @Transactional
  public Map<String, Object> register(String username, String password) {
    String normalized = username == null ? "" : username.trim();
    if (!USERNAME_PATTERN.matcher(normalized).matches()) {
      throw ApiException.badRequest("用户名须为 3-20 位字母、数字或下划线");
    }
    if (!isPasswordValid(password)) {
      throw ApiException.badRequest("密码至少 8 位，且须同时包含字母和数字");
    }
    String lower = normalized.toLowerCase(java.util.Locale.ROOT);
    Long exists = userService.countByUsernameIgnoreCase(lower);
    if (exists != null && exists > 0) {
      throw new ApiException(409, "该用户名已被注册");
    }

    User user = new User();
    user.setUsername(normalized);
    user.setDisplayName(normalized);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setStatus("active");
    user.setRole("user");
    userService.createUser(user);

    String token = JwtUtil.sign(jwtSecret, Map.of("sub", user.getId(), "username", user.getUsername()),
        SEVEN_DAYS_SECONDS);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", userService.toPublicUser(user));
    body.put("token", token);
    return body;
  }

  public boolean isPasswordValid(String password) {
    if (password == null || password.length() < 8) {
      return false;
    }
    return password.chars().anyMatch(Character::isLetter)
        && password.chars().anyMatch(Character::isDigit);
  }

  /** 登录：区分「用户不存在 / 密码错误 / 已注销 / 已禁用」。 */
  public Map<String, Object> login(String username, String password) {
    User user = userService.findByUsername(username);
    if (user == null) {
      throw ApiException.badRequest("用户不存在");
    }
    if ("deleted".equals(user.getStatus())) {
      throw ApiException.forbidden("账户已注销");
    }
    if ("disabled".equals(user.getStatus())) {
      throw ApiException.forbidden("账户已被禁用");
    }
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw ApiException.badRequest("密码错误");
    }
    userService.updateLastLoginAt(user.getId());
    String token = JwtUtil.sign(jwtSecret, Map.of("sub", user.getId(), "username", user.getUsername()),
        SEVEN_DAYS_SECONDS);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", userService.toPublicUser(user));
    body.put("token", token);
    return body;
  }

  /** 注销：需密码确认；软删除（status=deleted + deleted_at），Cookie 由 Controller 清除。 */
  @Transactional
  public void deactivate(UserContext currentUser, String password) {
    User user = userService.findById(currentUser.id());
    if (user == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
      throw ApiException.badRequest("密码错误，无法注销");
    }
    user.setStatus("deleted");
    user.setDeletedAt(new Date());
    user.setTokenValidAfter(new Date()); // P2-1：作废全部存量令牌（防复活的边界场景）
    userService.updateUser(user);
    auditService.record(user.getId(), null, "account_deactivated", "user", user.getId(),
        Map.of("username", user.getUsername()));
  }

  /**
   * 修改密码：验证当前密码 → 校验新密码规则 → 拒绝与当前相同 → BCrypt 保存。
   * P2-1：同时把 token_valid_after 刷新为当前时间——所有早于该时刻签发的 JWT
   * （包括其他设备与可能已泄露的令牌）立即失效，需重新登录；当前设备也会被登出。
   */
  @Transactional
  public void changePassword(UserContext currentUser, String oldPassword, String newPassword) {
    User user = userService.findById(currentUser.id());
    if (user == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    if (!passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPasswordHash())) {
      throw ApiException.badRequest("当前密码不正确");
    }
    if (!isPasswordValid(newPassword)) {
      throw ApiException.badRequest("新密码至少 8 位，且须同时包含字母和数字");
    }
    if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
      throw ApiException.badRequest("新密码不能与当前密码相同");
    }
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setTokenValidAfter(new Date());
    userService.updateUser(user);
    auditService.record(user.getId(), null, "password_changed", "user", user.getId(),
        Map.of("username", user.getUsername()));
  }

  public Map<String, Object> getMe(UserContext currentUser) {
    User user = userService.findById(currentUser.id());
    if (user == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    if (!"active".equals(user.getStatus())) {
      throw ApiException.forbidden("账户不可用");
    }
    return Map.of("user", userService.toPublicUser(user));
  }

  public ResponseCookie buildAuthCookie(String token) {
    return ResponseCookie.from(cookieName, token)
        .httpOnly(true)
        .sameSite("Lax")
        .secure(secureCookie)
        .maxAge(SEVEN_DAYS_SECONDS)
        .path("/")
        .build();
  }

  public ResponseCookie buildClearCookie() {
    return ResponseCookie.from(cookieName, "")
        .httpOnly(true)
        .sameSite("Lax")
        .secure(secureCookie)
        .maxAge(0)
        .path("/")
        .build();
  }
}
