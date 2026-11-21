package com.travelos.auth;

import com.travelos.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录/注册限流核心行为（原子预占模型）：阈值、维度隔离、恢复策略、
 * 并发计数、过期与容量回收。
 *
 * <p>登录的「失败尝试」= {@link AuthRateLimiter#beginLoginAttempt} 成功预占（认证失败不回退）；
 * 「成功」= 预占后 {@link AuthRateLimiter#completeLoginSuccess} 结算。</p>
 */
class AuthRateLimiterTest {

  private MutableClock clock;
  private AuthRateLimiter limiter;

  private static AuthRateLimiter.Limits limits(int ipMax, int accountIpMax, int globalMax,
      int registerIpMax, int windowSeconds, int registerWindow, int maxKeys) {
    return new AuthRateLimiter.Limits(windowSeconds, ipMax, accountIpMax, globalMax,
        registerWindow, registerIpMax, maxKeys);
  }

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    // IP=5 / 账号+IP=3 / 全局=100 / 注册=4；窗口 60s；容量 50
    limiter = new AuthRateLimiter(limits(5, 3, 100, 4, 60, 60, 50), clock);
  }

  /** 一次失败登录 = 预占成功（认证失败不结算，计数保留）。 */
  private void failedLogin(String ip, String user) {
    limiter.beginLoginAttempt(ip, user);
  }

  private static ApiException expect429(Runnable action) {
    ApiException ex = assertThrows(ApiException.class, action::run);
    assertEquals(429, ex.getStatusCode());
    assertNotNull(ex.getRetryAfterSeconds());
    assertTrue(ex.getRetryAfterSeconds() >= 1, "Retry-After 至少 1 秒");
    assertTrue(ex.getRetryAfterSeconds() <= 60, "Retry-After 不超过窗口");
    assertEquals(AuthRateLimiter.TOO_MANY_REQUESTS_MESSAGE, ex.getMessage());
    assertFalse(ex.getMessage().contains("user"), "429 文案不得泄露账号信息");
    return ex;
  }

  // ==================== 阈值与维度 ====================

  @Test
  void belowThresholdStaysNormalThen429AtThreshold() {
    for (int i = 0; i < 3; i++) { // 账号+IP 上限 3：前 3 次预占成功（按正常认证失败计数）
      failedLogin("1.1.1.1", "alice");
    }
    expect429(() -> limiter.beginLoginAttempt("1.1.1.1", "alice")); // 第 4 次起 429
    assertEquals(3, limiter.accountFailureCount("alice", "1.1.1.1"));
  }

  @Test
  void accountLimitDoesNotLockOtherAccountsOnSameIp() {
    for (int i = 0; i < 3; i++) {
      failedLogin("1.1.1.1", "alice");
    }
    expect429(() -> limiter.beginLoginAttempt("1.1.1.1", "alice"));
    // 同一 IP 上的其他账号不受影响（账号+IP 维度隔离）
    limiter.beginLoginAttempt("1.1.1.1", "bob");
    limiter.beginLoginAttempt("1.1.1.1", "bob");
    assertEquals(2, limiter.accountFailureCount("bob", "1.1.1.1"));
  }

  @Test
  void ipDimensionLimitsTraversalAcrossManyAccounts() {
    for (int i = 0; i < 5; i++) { // IP 上限 5：分散在 5 个不同账号上的失败
      failedLogin("2.2.2.2", "victim" + i);
    }
    expect429(() -> limiter.beginLoginAttempt("2.2.2.2", "fresh"));
    // 其他来源不受影响（IP 维度按来源隔离）
    limiter.beginLoginAttempt("3.3.3.3", "fresh");
    assertEquals(1, limiter.accountFailureCount("fresh", "3.3.3.3"));
  }

  @Test
  void accountLimitedOnOneSourceStillWorksFromOtherSources() {
    for (int i = 0; i < 3; i++) {
      failedLogin("1.1.1.1", "alice");
    }
    expect429(() -> limiter.beginLoginAttempt("1.1.1.1", "alice"));
    // 同一账号从另一个来源立即可用：不存在“全来源永久锁死”
    limiter.beginLoginAttempt("9.9.9.9", "alice");
  }

  @Test
  void globalCeilingEngagesButIsFarAboveNormalPeak() {
    AuthRateLimiter small = new AuthRateLimiter(limits(50, 50, 4, 100, 60, 60, 50), clock);
    for (int i = 0; i < 3; i++) { // 单 IP/单账号均远未触顶，只有全局在累积
      small.beginLoginAttempt("1.1.1.1", "user" + i);
    }
    small.beginLoginAttempt("1.1.1.1", "user3"); // global=3 < 4 → 占用到 4
    // 全新来源/账号：IP 与账号维度都新鲜，仅全局触顶 → 429（全局兜底生效）
    expect429(() -> small.beginLoginAttempt("5.5.5.5", "someone"));
  }

  @Test
  void rejectedAttemptDoesNotConsumeAnyBudget() {
    for (int i = 0; i < 3; i++) {
      failedLogin("1.1.1.1", "alice");
    }
    int ipBefore = limiter.ipFailureCount("1.1.1.1");
    for (int i = 0; i < 20; i++) { // 被限流期间反复尝试：不占用、不清除、不膨胀
      expect429(() -> limiter.beginLoginAttempt("1.1.1.1", "alice"));
    }
    assertEquals(ipBefore, limiter.ipFailureCount("1.1.1.1"));
    assertEquals(3, limiter.accountFailureCount("alice", "1.1.1.1"));
  }

  // ==================== 恢复策略 ====================

  @Test
  void successClearsOnlyOwnAccountSourceCounter() {
    // 本用例放宽 IP 维度（50），只观察账号维度与成功恢复语义
    AuthRateLimiter l = new AuthRateLimiter(limits(50, 3, 100, 100, 60, 60, 50), clock);
    for (int i = 0; i < 2; i++) {
      l.beginLoginAttempt("1.1.1.1", "alice");
    }
    for (int i = 0; i < 3; i++) {
      l.beginLoginAttempt("1.1.1.1", "bob");
    }
    // alice 成功登录：预占 1 后结算 → 自己的账号计数清零，IP 只回退自己的 1
    AuthRateLimiter.LoginPermit permit = l.beginLoginAttempt("1.1.1.1", "alice");
    l.completeLoginSuccess(permit);
    assertEquals(0, l.accountFailureCount("alice", "1.1.1.1"));
    assertEquals(3, l.accountFailureCount("bob", "1.1.1.1"), "成功不得清空其他账号");
    // IP 计数 = alice 失败2 + bob 失败3 + alice 成功占用1 - alice 成功回退1 = 5（成功不消耗 IP 额度）
    assertEquals(5, l.ipFailureCount("1.1.1.1"), "成功只撤销自己的预占，不误减他人计数");
    l.beginLoginAttempt("1.1.1.1", "alice"); // alice 恢复正常（可再次进入认证）
    expect429(() -> l.beginLoginAttempt("1.1.1.1", "bob")); // bob 仍触顶
  }

  @Test
  void windowExpiryRestoresAccessWithoutManualUnlock() {
    for (int i = 0; i < 3; i++) {
      failedLogin("1.1.1.1", "alice");
    }
    expect429(() -> limiter.beginLoginAttempt("1.1.1.1", "alice"));
    clock.advance(Duration.ofSeconds(61)); // 推进越过窗口
    assertEquals(0, limiter.accountFailureCount("alice", "1.1.1.1"), "过期后计数归零");
    limiter.beginLoginAttempt("1.1.1.1", "alice"); // 恢复进入认证
  }

  @Test
  void settleAfterWindowSwitchDoesNotTouchNewWindow() {
    // 旧窗口预占 → 窗口切换后新建同键计数 → 旧凭证结算不得回退新窗口
    AuthRateLimiter.LoginPermit old = limiter.beginLoginAttempt("1.1.1.1", "alice");
    clock.advance(Duration.ofSeconds(61));
    limiter.beginLoginAttempt("1.1.1.1", "alice"); // 新窗口计数 =1
    assertEquals(1, limiter.accountFailureCount("alice", "1.1.1.1"));
    limiter.completeLoginSuccess(old); // 旧窗口凭证结算：同窗口校验失败 → 不触碰
    assertEquals(1, limiter.accountFailureCount("alice", "1.1.1.1"),
        "旧窗口的回退绝不能打到新窗口");
    limiter.abandonLoginAttempt(old);
    assertEquals(1, limiter.accountFailureCount("alice", "1.1.1.1"));
  }

  @Test
  void abandonRefundsOnlyOwnOccupancy() {
    AuthRateLimiter.LoginPermit a = limiter.beginLoginAttempt("1.1.1.1", "alice");
    failedLogin("1.1.1.1", "alice"); // 另一次失败（不结算）
    limiter.abandonLoginAttempt(a); // 只回退 a 自己的预占
    assertEquals(1, limiter.accountFailureCount("alice", "1.1.1.1"));
    assertEquals(1, limiter.ipFailureCount("1.1.1.1"));
  }

  // ==================== 注册 ====================

  @Test
  void registerAllowsUpToThresholdThen429() {
    for (int i = 0; i < 4; i++) { // 注册上限 4：第 1..4 次放行
      limiter.attemptRegister("4.4.4.4");
    }
    expect429(() -> limiter.attemptRegister("4.4.4.4"));
    // 窗口过期后恢复
    clock.advance(Duration.ofSeconds(61));
    limiter.attemptRegister("4.4.4.4");
  }

  @Test
  void registerLimitIsolatedPerSource() {
    for (int i = 0; i < 4; i++) { // 注册上限 4
      limiter.attemptRegister("4.4.4.4");
    }
    expect429(() -> limiter.attemptRegister("4.4.4.4"));
    limiter.attemptRegister("6.6.6.6"); // 其他来源不受影响
    assertEquals(1, limiter.registerAttemptCount("6.6.6.6"));
  }

  // ==================== 并发与有界性 ====================

  @Test
  void concurrentBeginAttemptsCountExactly() throws Exception {
    AuthRateLimiter high = new AuthRateLimiter(limits(100, 100, 1000, 100, 60, 60, 50), clock);
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger unexpected = new AtomicInteger();
    AtomicInteger granted = new AtomicInteger();
    for (int i = 0; i < threads; i++) {
      pool.execute(() -> {
        try {
          start.await();
          high.beginLoginAttempt("7.7.7.7", "alice"); // 原子预占：32 次全部计上
          granted.incrementAndGet();
        } catch (Exception ex) {
          unexpected.incrementAndGet();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    pool.shutdownNow();
    assertEquals(0, unexpected.get());
    assertEquals(threads, granted.get());
    assertEquals(threads, high.accountFailureCount("alice", "7.7.7.7"), "并发预占不得丢计数");
  }

  @Test
  void expiredKeysAreReclaimedAndCapacityIsBounded() {
    // 专用宽阈值限流器（容量 50、窗口 60s）：写入 200 个不同来源
    AuthRateLimiter bounded = new AuthRateLimiter(
        limits(1000, 1000, 100000, 1000, 60, 60, 50), clock);
    for (int i = 0; i < 200; i++) {
      bounded.beginLoginAttempt("10.0." + (i / 256) + "." + (i % 256), "u" + i);
    }
    assertTrue(bounded.trackedKeys() <= 50,
        "键总数必须被容量上限约束，实际 " + bounded.trackedKeys());
    // 过期回收：推进窗口 + 清扫间隔后，过期键被删除（新注册仅留下 1 个新键）
    clock.advance(Duration.ofSeconds(70));
    bounded.attemptRegister("8.8.8.8");
    assertEquals(1, bounded.trackedKeys(), "过期键应被回收，仅剩新键");
  }

  @Test
  void blockedRequestsDoNotResetOrGrowForever() {
    for (int i = 0; i < 3; i++) {
      failedLogin("1.1.1.1", "alice");
    }
    for (int i = 0; i < 50; i++) { // 被限流期间反复请求：计数不无限增长（保持在上限）
      expect429(() -> limiter.beginLoginAttempt("1.1.1.1", "alice"));
    }
    assertEquals(3, limiter.accountFailureCount("alice", "1.1.1.1"));
  }

  @Test
  void usernameCaseVariantsShareTheSameBudget() {
    failedLogin("1.1.1.1", "Alice");
    failedLogin("1.1.1.1", "alice");
    failedLogin("1.1.1.1", "ALICE");
    assertEquals(3, limiter.accountFailureCount("alice", "1.1.1.1"));
  }

  @Test
  void nullSafeInputsDoNotThrow() {
    limiter.beginLoginAttempt(null, null);
    limiter.completeLoginSuccess(null);
    limiter.abandonLoginAttempt(null);
    limiter.beginLoginAttempt("", "  ");
    List<String> unused = List.of();
    assertNotNull(unused);
  }
}
