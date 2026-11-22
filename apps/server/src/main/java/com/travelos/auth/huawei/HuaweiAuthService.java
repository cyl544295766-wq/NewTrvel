package com.travelos.auth.huawei;

import com.travelos.common.ApiException;
import com.travelos.common.JwtUtil;
import com.travelos.entity.User;
import com.travelos.service.AuditService;
import com.travelos.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 华为账号登录：授权码 → 华为令牌 → 验签 → 用户信息 → 本地用户 → 项目 JWT。
 *
 * <p>本地用户以 openID 为唯一身份锚点（同一用户在同一应用内恒定）。首次登录自动建号，
 * 生成不可用的随机密码哈希：该账号只能走华为登录，密码登录会明确提示改用华为账号。</p>
 */
@Service
public class HuaweiAuthService {

  /** 与账号密码登录保持一致的会话时长（7 天） */
  private static final long SESSION_SECONDS = 7 * 24 * 60 * 60L;
  private static final int USERNAME_MAX_ATTEMPTS = 5;

  private final HuaweiAccountProperties properties;
  private final HuaweiTokenClient tokenClient;
  private final HuaweiIdTokenVerifier idTokenVerifier;
  private final HuaweiUserInfoClient userInfoClient;
  private final UserService userService;
  private final AuditService auditService;
  private final String jwtSecret;
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public HuaweiAuthService(HuaweiAccountProperties properties,
      HuaweiTokenClient tokenClient,
      HuaweiIdTokenVerifier idTokenVerifier,
      HuaweiUserInfoClient userInfoClient,
      UserService userService,
      AuditService auditService,
      @Value("${jwt.secret}") String jwtSecret) {
    this.properties = properties;
    this.tokenClient = tokenClient;
    this.idTokenVerifier = idTokenVerifier;
    this.userInfoClient = userInfoClient;
    this.userService = userService;
    this.auditService = auditService;
    this.jwtSecret = jwtSecret;
  }

  @Transactional
  public Map<String, Object> login(String authCode) {
    return login(authCode, null);
  }

  /**
   * @param redirectUri 网页 OAuth 流程的授权码绑定该地址，兑换时必须原样回传；App 流程传 null
   */
  @Transactional
  public Map<String, Object> login(String authCode, String redirectUri) {
    if (!properties.isConfigured()) {
      throw ApiException.serviceUnavailable("华为账号登录尚未配置");
    }
    HuaweiTokenClient.HuaweiTokenResponse tokens =
        tokenClient.exchangeAuthorizationCode(authCode, redirectUri);
    HuaweiIdTokenVerifier.HuaweiIdTokenClaims claims = idTokenVerifier.verify(tokens.idToken());
    HuaweiUserInfoClient.HuaweiUserInfo info = userInfoClient.fetch(tokens.accessToken());

    // 双通道交叉校验：ID Token 若带 openid 声明，必须与 userinfo 的 openID 一致，否则视为令牌错配
    if (!claims.openId().isEmpty() && !claims.openId().equals(info.openId())) {
      throw ApiException.unauthorized("华为身份信息不一致，请重新登录");
    }

    User existing = userService.findByHuaweiOpenId(info.openId());
    User user = existing != null ? existing : createUser(info, claims);
    assertUsable(user);
    syncProfile(user, info, claims);

    userService.updateLastLoginAt(user.getId());
    auditService.record(user.getId(), null, "login_huawei", "user", user.getId(),
        Map.of("provider", "huawei"));

    String token = JwtUtil.sign(jwtSecret,
        Map.of("sub", user.getId(), "username", user.getUsername()), SESSION_SECONDS);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", userService.toPublicUser(user));
    body.put("token", token);
    return body;
  }

  /** 头像优先用 userinfo 的 headPictureURL，缺失时回退 ID Token 的 picture */
  private static String avatarOf(HuaweiUserInfoClient.HuaweiUserInfo info,
      HuaweiIdTokenVerifier.HuaweiIdTokenClaims claims) {
    String fromInfo = info.avatarUrl();
    if (fromInfo != null && !fromInfo.isEmpty()) {
      return fromInfo;
    }
    String fromClaims = claims.picture();
    return fromClaims == null || fromClaims.isEmpty() ? null : fromClaims;
  }

  /** 与密码登录保持一致的账户状态语义：已注销 / 已禁用分别给出明确提示。 */
  private void assertUsable(User user) {
    if ("deleted".equals(user.getStatus())) {
      throw ApiException.forbidden("账户已注销");
    }
    if ("disabled".equals(user.getStatus())) {
      throw ApiException.forbidden("账户已被禁用");
    }
    if (!"active".equals(user.getStatus())) {
      throw ApiException.forbidden("账户不可用");
    }
  }

  private User createUser(HuaweiUserInfoClient.HuaweiUserInfo info,
      HuaweiIdTokenVerifier.HuaweiIdTokenClaims claims) {
    String displayName = firstNonEmpty(claims.displayName(), info.displayName());
    User user = new User();
    user.setUsername(uniqueUsername(info.openId()));
    user.setDisplayName(!displayName.isEmpty() ? displayName : "华为用户");
    // 随机密码：列约束仍为 NOT NULL，但任何人都无法用密码登录该账号
    user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
    user.setStatus("active");
    user.setRole("user");
    user.setHuaweiOpenId(info.openId());
    user.setHuaweiUnionId(info.unionId().isEmpty() ? null : info.unionId());
    user.setAvatarUrl(avatarOf(info, claims));
    user.setPhoneNumber(info.phoneNumber().isEmpty() ? null : info.phoneNumber());
    userService.createUser(user);
    return user;
  }

  /**
   * 每次登录把华为侧资料同步到本地：昵称与手机号以华为为准；
   * 头像只在本地没有自定义头像（受管文件/data URL）时才跟随华为，避免覆盖用户在应用内上传的头像。
   */
  private void syncProfile(User user, HuaweiUserInfoClient.HuaweiUserInfo info,
      HuaweiIdTokenVerifier.HuaweiIdTokenClaims claims) {
    boolean changed = false;
    String displayName = firstNonEmpty(claims.displayName(), info.displayName());
    if (!displayName.isEmpty() && !displayName.equals(user.getDisplayName())) {
      user.setDisplayName(displayName);
      changed = true;
    }
    String avatar = avatarOf(info, claims);
    if (avatar != null) {
      String current = user.getAvatarUrl();
      boolean noCustomAvatar = current == null || current.isEmpty() || current.startsWith("http");
      if (noCustomAvatar && !avatar.equals(current)) {
        user.setAvatarUrl(avatar);
        changed = true;
      }
    }
    if (!info.phoneNumber().isEmpty() && !info.phoneNumber().equals(user.getPhoneNumber())) {
      user.setPhoneNumber(info.phoneNumber());
      changed = true;
    }
    if (changed) {
      userService.updateUser(user);
    }
  }

  /**
   * 用 openID 摘要生成稳定用户名：openID 本身不可读且长度不定，直接拼进用户名既不好看也可能超长。
   * 摘要相同但 openID 不同（理论上）时加序号，最多尝试 5 次。
   */
  private String uniqueUsername(String openId) {
    String base = "hw_" + shortDigest(openId);
    String candidate = base;
    for (int attempt = 0; attempt < USERNAME_MAX_ATTEMPTS; attempt++) {
      Long exists = userService.countByUsernameIgnoreCase(candidate.toLowerCase(Locale.ROOT));
      if (exists == null || exists == 0) {
        return candidate;
      }
      candidate = base + "_" + (attempt + 1);
    }
    throw ApiException.conflict("账号创建失败，请稍后重试");
  }

  private static String shortDigest(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(12);
      for (int i = 0; i < 6; i++) {
        builder.append(String.format("%02x", digest[i]));
      }
      return builder.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String firstNonEmpty(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    return second == null ? "" : second.trim();
  }
}
