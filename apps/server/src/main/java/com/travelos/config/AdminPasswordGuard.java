package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.entity.User;
import com.travelos.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 生产默认管理员凭据守卫（与 {@link JwtSecretGuard} 同一套配置方式）：
 *
 * <p>逻辑（本地开发默认友好，生产通过环境变量收紧）：</p>
 * <ul>
 *   <li>{@code seed.allow-default-admin-password=true}（默认，本地/自动化友好）：
 *       检测到默认/弱口令仅打警告，绝不打印口令本身；</li>
 *   <li>{@code =false}（生产置 SEED_ALLOW_DEFAULT_ADMIN_PASSWORD=false）：
 *       <ul>
 *         <li>管理员尚不存在且 {@code seed.admin-password} 仍是默认/弱口令
 *             → <b>拒绝初始化</b>（抛出导致启动失败）；</li>
 *         <li>管理员已存在且其当前口令仍是默认/弱口令（如 admin123）
 *             → <b>拒绝启动</b>（fail-fast，强制先改密）；</li>
 *         <li>管理员口令已改强 → 正常启动，<b>不触碰、不重置</b>该口令
 *             （配置里的弱默认值此时不再使用，不误伤）。</li>
 *       </ul></li>
 * </ul>
 *
 * <p>错误提示只说明要设置哪个环境变量，<b>绝不输出任何口令明文</b>。</p>
 */
@Component
@Order(0) // 先于 JwtSecretGuard(1) 与 SeedRunner(默认最低优先级)
public class AdminPasswordGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminPasswordGuard.class);
  /** 明显的默认/弱口令（与本项目种子与常见占位一致）——只用于比对，不进日志 */
  static final List<String> WEAK_PASSWORDS =
      List.of("admin123", "admin", "password", "passw0rd", "12345678", "qwerty123", "travelos");

  private final UserMapper userMapper;
  private final String adminUsername;
  private final String configuredPassword;
  private final boolean allowDefault;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AdminPasswordGuard(UserMapper userMapper,
      @Value("${seed.admin-username}") String adminUsername,
      @Value("${seed.admin-password}") String configuredPassword,
      @Value("${seed.allow-default-admin-password:true}") boolean allowDefault) {
    this.userMapper = userMapper;
    this.adminUsername = adminUsername;
    this.configuredPassword = configuredPassword;
    this.allowDefault = allowDefault;
  }

  @Override
  public void run(ApplicationArguments args) {
    User admin = userMapper.selectOne(new LambdaQueryWrapper<User>()
        .eq(User::getUsername, adminUsername)
        .last("LIMIT 1"));
    if (admin == null) {
      // 尚未初始化：只有「将以弱口令创建」时才需要拦截
      if (isWeak(configuredPassword, adminUsername)) {
        if (allowDefault) {
          log.warn("管理员账户将使用种子默认口令初始化——仅限本地开发！"
              + "生产请设置 SEED_ADMIN_PASSWORD，并置 SEED_ALLOW_DEFAULT_ADMIN_PASSWORD=false");
          return;
        }
        throw new IllegalStateException(
            "拒绝初始化管理员：seed.admin-password 仍是默认/弱口令。生产环境请通过环境变量 "
                + "SEED_ADMIN_PASSWORD 提供强密码（不得写入代码库），或将 seed.allow-default-admin-password "
                + "设为 true 仅用于本地开发。");
      }
      return; // 将以非弱口令初始化，正常继续
    }
    // 管理员已存在：检查其**当前口令**是否仍为默认/弱口令（与配置值无关，避免误伤已改密账户）
    if (!matchesAnyWeak(admin.getPasswordHash())) {
      return;
    }
    if (allowDefault) {
      log.warn("管理员账户 {} 仍使用默认/弱口令——仅限本地开发！请尽快在「账户设置」修改", adminUsername);
      return;
    }
    throw new IllegalStateException(
        "拒绝启动：生产模式（seed.allow-default-admin-password=false）下管理员账户仍使用默认口令。"
            + "请先在「账户设置」修改管理员密码后重启（本提示不包含任何口令内容）。");
  }

  /** 配置口令是否为默认/弱口令：空、常见默认值、或与管理员用户名相同。 */
  static boolean isWeak(String password, String username) {
    if (password == null || password.isBlank()) {
      return true;
    }
    String lower = password.trim().toLowerCase(Locale.ROOT);
    if (WEAK_PASSWORDS.contains(lower)) {
      return true;
    }
    return username != null && password.trim().equalsIgnoreCase(username.trim());
  }

  /** 账户当前口令是否匹配任一已知弱口令（仅比对、不回显）。 */
  private boolean matchesAnyWeak(String passwordHash) {
    if (passwordHash == null || passwordHash.isBlank()) {
      return true; // 无有效口令 → 视为不可接受的默认态
    }
    if (!passwordHash.startsWith("$2")) {
      return false; // 非 BCrypt（历史格式）：不在此处判定，交由登录流程
    }
    for (String weak : WEAK_PASSWORDS) {
      try {
        if (encoder.matches(weak, passwordHash)) {
          return true;
        }
      } catch (RuntimeException ignored) {
        return false; // 哈希异常按「不判定」处理，避免误伤正常账户
      }
    }
    return false;
  }
}
