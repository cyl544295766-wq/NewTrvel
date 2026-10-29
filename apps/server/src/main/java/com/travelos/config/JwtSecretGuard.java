package com.travelos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * JWT 密钥安全守卫（P2-4）：
 * 启动时校验 jwt.secret 不得仍是代码默认值——否则任何人都能用公开可知的密钥
 * 伪造任意用户（包括管理员）的登录令牌。
 * 逻辑：jwt.allow-default-secret=true（默认，本地开发友好）→ 仅打警告；
 * 生产在 app.env 设置 JWT_ALLOW_DEFAULT=false → 仍为默认密钥则直接拒绝启动（fail-fast）。
 */
@Component
@Order(1) // 先于其他 runner 执行
public class JwtSecretGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(JwtSecretGuard.class);
  private static final String DEFAULT_SECRET = "change_me_in_local_env";

  private final String secret;
  private final boolean allowDefault;

  public JwtSecretGuard(@Value("${jwt.secret}") String secret,
      @Value("${jwt.allow-default-secret:true}") boolean allowDefault) {
    this.secret = secret;
    this.allowDefault = allowDefault;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean isDefault = secret == null || secret.isBlank() || DEFAULT_SECRET.equals(secret);
    if (!isDefault) {
      log.info("JWT secret guard: 自定义密钥已启用（长度 {}）", secret.length());
      return;
    }
    if (allowDefault) {
      log.warn("JWT 密钥仍为代码默认值——仅限本地开发使用！"
          + "生产环境请设置强随机 JWT_SECRET 并置 JWT_ALLOW_DEFAULT=false");
      return;
    }
    throw new IllegalStateException(
        "检测到 JWT_SECRET 仍为代码默认值且 jwt.allow-default-secret=false，"
            + "拒绝启动：请生成强随机密钥（如 openssl rand -hex 32）写入应用环境变量 JWT_SECRET。");
  }
}
