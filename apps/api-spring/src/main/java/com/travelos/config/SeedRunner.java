package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.entity.SystemSetting;
import com.travelos.entity.User;
import com.travelos.mapper.SystemSettingMapper;
import com.travelos.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

  private final SystemSettingMapper systemSettingMapper;
  private final UserMapper userMapper;
  private final String adminUsername;
  private final String adminDisplayName;
  private final String adminPassword;

  public SeedRunner(SystemSettingMapper systemSettingMapper, UserMapper userMapper,
      @Value("${seed.admin-username}") String adminUsername,
      @Value("${seed.admin-display-name}") String adminDisplayName,
      @Value("${seed.admin-password}") String adminPassword) {
    this.systemSettingMapper = systemSettingMapper;
    this.userMapper = userMapper;
    this.adminUsername = adminUsername;
    this.adminDisplayName = adminDisplayName;
    this.adminPassword = adminPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    // 种子初始化无请求上下文，显式绕过行程数据范围过滤
    com.travelos.common.TripScopeContext.runBypassed(() -> {
      try {
        seed();
      } catch (Exception ex) {
        log.warn("Seed skipped: {}", ex.getMessage());
      }
    });
  }

  private void seed() {
      SystemSetting existing = systemSettingMapper
          .selectOne(new LambdaQueryWrapper<SystemSetting>().eq(SystemSetting::getKey, "app.name"));
      if (existing == null) {
        SystemSetting setting = new SystemSetting();
        setting.setKey("app.name");
        setting.setValue("Travel OS");
        systemSettingMapper.insert(setting);
      } else {
        existing.setValue("Travel OS");
        systemSettingMapper.updateById(existing);
      }

      User admin = userMapper
          .selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, adminUsername));
      User toSave = admin == null ? new User() : admin;
      toSave.setUsername(adminUsername);
      toSave.setDisplayName(adminDisplayName);
      toSave.setPasswordHash(new BCryptPasswordEncoder().encode(adminPassword));
      if (toSave.getStatus() == null) {
        toSave.setStatus("active");
      }
      if (admin == null) {
        userMapper.insert(toSave);
      } else {
        userMapper.updateById(toSave);
      }
      log.info("Seed completed (admin user: {})", adminUsername);
  }
}
