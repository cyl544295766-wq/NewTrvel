package com.travelos.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：通过 {@link #advance(Duration)} 推进时间，
 * 验证限流窗口过期与恢复，而不是让 CI 真实睡眠。
 */
public class MutableClock extends Clock {

  private volatile Instant current;

  public MutableClock() {
    this(Instant.now());
  }

  public MutableClock(Instant start) {
    this.current = start;
  }

  /** 推进时钟（可反复调用）。 */
  public void advance(Duration duration) {
    synchronized (this) {
      current = current.plus(duration);
    }
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return current;
  }
}
