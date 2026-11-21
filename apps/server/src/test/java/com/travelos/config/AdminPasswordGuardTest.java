package com.travelos.config;

import com.travelos.entity.User;
import com.travelos.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产默认管理员凭据守卫（AdminPasswordGuard 即启动链路中执行的组件）。
 */
class AdminPasswordGuardTest {

  private UserMapper userMapper;

  @BeforeEach
  void setUp() {
    userMapper = mock(UserMapper.class); // selectOne 默认 null = 管理员不存在
  }

  private AdminPasswordGuard guard(String configuredPassword, boolean allowDefault) {
    return new AdminPasswordGuard(userMapper, "admin", configuredPassword, allowDefault);
  }

  private void adminExistsWithPassword(String password) {
    User admin = new User();
    admin.setUsername("admin");
    admin.setRole("super_admin");
    admin.setStatus("active");
    admin.setPasswordHash(new BCryptPasswordEncoder().encode(password));
    when(userMapper.selectOne(any())).thenReturn(admin);
  }

  @Test
  void productionRejectsInitializationWithDefaultPassword() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> guard("admin123", false).run(null));
    assertTrue(ex.getMessage().contains("SEED_ADMIN_PASSWORD"),
        "错误提示应指向环境变量：" + ex.getMessage());
    assertFalse(ex.getMessage().contains("admin123"), "日志/异常不得包含真实口令");
    assertFalse(ex.getMessage().contains("admin123".toUpperCase()));
  }

  @Test
  void productionRejectsInitializationWithBlankPassword() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> guard("   ", false).run(null));
    assertTrue(ex.getMessage().contains("SEED_ADMIN_PASSWORD"));
    assertFalse(ex.getMessage().contains("   "));
  }

  @Test
  void productionAcceptsStrongPasswordForMissingAdmin() {
    assertDoesNotThrow(() -> guard("Str0ng-Probe-2026", false).run(null));
  }

  @Test
  void productionRejectsExistingAdminStillOnDefaultPassword() {
    adminExistsWithPassword("admin123");
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> guard("Str0ng-Configured-Value", false).run(null));
    assertTrue(ex.getMessage().contains("管理员"), ex.getMessage());
    assertFalse(ex.getMessage().contains("admin123"), "异常不得包含口令");
    assertFalse(ex.getMessage().contains("Str0ng-Configured-Value"), "异常不得包含配置口令");
  }

  @Test
  void productionAcceptsExistingAdminWithStrongPasswordEvenIfConfigStillDefault() {
    // 配置仍是默认值，但账户已改强：不误伤、不重置、正常启动
    adminExistsWithPassword("Str0ng-Already-Changed");
    assertDoesNotThrow(() -> guard("admin123", false).run(null));
  }

  @Test
  void developmentModeOnlyWarnsNeverThrows() {
    // 本地开发默认（allowDefault=true）：两种弱口令场景都不拦截
    assertDoesNotThrow(() -> guard("admin123", true).run(null));
    adminExistsWithPassword("admin123");
    assertDoesNotThrow(() -> guard("admin123", true).run(null));
  }

  @Test
  void weakPasswordClassification() {
    assertTrue(AdminPasswordGuard.isWeak(null, "admin"));
    assertTrue(AdminPasswordGuard.isWeak("", "admin"));
    assertTrue(AdminPasswordGuard.isWeak("admin123", "admin"));
    assertTrue(AdminPasswordGuard.isWeak("Admin123", "admin"));
    assertTrue(AdminPasswordGuard.isWeak("admin", "someone"));       // 常见默认值
    assertTrue(AdminPasswordGuard.isWeak("myusername", "MyUserName")); // 与用户名相同
    assertTrue(AdminPasswordGuard.isWeak("12345678", "admin"));
    assertFalse(AdminPasswordGuard.isWeak("Str0ng-Probe-2026", "admin"));
    assertFalse(AdminPasswordGuard.isWeak("travel-os-9f3c2b77", "admin"));
  }
}
