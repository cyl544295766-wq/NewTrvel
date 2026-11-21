package com.travelos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 限流使用的时钟 Bean（系统时钟）。
 *
 * <p>独立成 Bean 是为了让测试可以注入可控时钟（@Primary 覆盖），
 * 用时间推进验证窗口过期与恢复，而不是让 CI 真实睡眠几十秒。</p>
 */
@Configuration
public class AppClockConfig {

  @Bean
  public Clock systemClock() {
    return Clock.systemUTC();
  }
}
