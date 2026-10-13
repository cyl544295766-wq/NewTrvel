package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.entity.User;
import com.travelos.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 注销账户 30 天保留期后的物理清理定时任务（设计文档 3.1.1）。
 * 默认关闭，配置 app.user-cleanup.enabled=true 后启用。
 * 物理删除涉及外键级联（notifications / trip_members / trip_expenses 等均 RESTRICT），
 * 启用前需先处理关联数据或调整外键策略——此处保留扩展点。
 */
@Component
@ConditionalOnProperty(value = "app.user-cleanup.enabled", havingValue = "true")
public class UserCleanupScheduler {

  private static final Logger log = LoggerFactory.getLogger(UserCleanupScheduler.class);
  private static final int RETENTION_DAYS = 30;

  private final UserMapper userMapper;

  public UserCleanupScheduler(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  /** 每天凌晨 3 点执行：物理删除注销超过 30 天的账户。 */
  @Scheduled(cron = "0 0 3 * * ?")
  @Transactional
  public void purgeExpiredDeactivatedUsers() {
    // 定时任务无请求上下文，显式绕过行程数据范围过滤
    com.travelos.common.TripScopeContext.runBypassed(this::purge);
  }

  private void purge() {
    Calendar calendar = Calendar.getInstance();
    calendar.add(Calendar.DATE, -RETENTION_DAYS);
    Date threshold = calendar.getTime();

    List<User> expired = userMapper.selectList(new LambdaQueryWrapper<User>()
        .eq(User::getStatus, "deleted")
        .isNotNull(User::getDeletedAt)
        .lt(User::getDeletedAt, threshold));

    for (User user : expired) {
      // TODO: 物理删除前先清理/匿名化该用户的 trips 等关联业务数据（外键为 RESTRICT），
      //       具体策略由数据隔离改造（见《数据隔离改造清单.md》）完成后确定。
      log.info("Detected expired deactivated user: {} (deleted at {})", user.getUsername(),
          user.getDeletedAt());
    }
  }
}
