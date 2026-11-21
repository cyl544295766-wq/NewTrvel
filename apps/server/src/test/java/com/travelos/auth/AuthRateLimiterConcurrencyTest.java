package com.travelos.auth;

import com.travelos.common.ApiException;
import com.travelos.entity.User;
import com.travelos.mapper.UserMapper;
import com.travelos.service.AuditService;
import com.travelos.service.FileStorageService;
import com.travelos.service.NotificationStreamService;
import com.travelos.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 并发错误登录绕过阈值的**竞态复现与修复验证**（真实多线程 + 屏障同时入场）。
 *
 * <p>关键断言不是“最终 Map 计数”，而是<b>进入用户查询/BCrypt 的次数不超过阈值</b>
 * （{@link CountingUsers#lookups}），以及其余请求全部 429（带 Retry-After）。</p>
 */
class AuthRateLimiterConcurrencyTest {

  private static final String IP = "203.0.113.50";
  private static final String CORRECT = "Right-Pass-9";
  private static final String WRONG = "Wrong-Pass-9";
  private static final User ACTIVE_USER = buildUser();

  private static User buildUser() {
    User user = new User();
    user.setId("u-conc");
    user.setUsername("victim");
    user.setDisplayName("victim");
    user.setStatus("active");
    user.setRole("user");
    user.setPasswordHash(new BCryptPasswordEncoder().encode(CORRECT));
    return user;
  }

  /** 计数型 UserService：记录真正进入认证流程（用户查询）的次数。 */
  static final class CountingUsers extends UserService {
    final AtomicInteger lookups = new AtomicInteger();
    private final User user;

    CountingUsers(User user) {
      super(mock(UserMapper.class));
      this.user = user;
    }

    @Override
    public User findByUsername(String username) {
      lookups.incrementAndGet(); // 进入用户查询 = 即将执行 BCrypt 的门槛
      return user;
    }

    @Override
    public void updateLastLoginAt(String id) {
      // no-op
    }

    @Override
    public Map<String, Object> toPublicUser(User user) {
      return Map.of("username", user.getUsername());
    }
  }

  private record Harness(AuthService service, AuthRateLimiter limiter, CountingUsers users) {
  }

  private Harness harness(int ipMax, int accountMax, int globalMax) {
    AuthRateLimiter limiter = new AuthRateLimiter(
        new AuthRateLimiter.Limits(60, ipMax, accountMax, globalMax, 60, 100, 5000),
        new MutableClock());
    CountingUsers users = new CountingUsers(ACTIVE_USER);
    AuthService service = new AuthService(users, mock(AuditService.class),
        mock(FileStorageService.class), mock(NotificationStreamService.class), limiter,
        "conc-secret", "access_token", false);
    return new Harness(service, limiter, users);
  }

  /** 屏障同步：n 个线程同时放行，返回每个任务的“HTTP 状态码”。 */
  private static List<Integer> concurrently(int n, java.util.function.IntFunction<Integer> task)
      throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      final int idx = i;
      futures.add(pool.submit((Callable<Integer>) () -> {
        barrier.await(10, TimeUnit.SECONDS);
        return task.apply(idx);
      }));
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应全部结束");
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get());
    }
    return statuses;
  }

  /** 单次登录尝试 → 状态码（同时对 429 校验 Retry-After 语义）。 */
  private static int attempt(Harness h, String username, String password) {
    try {
      h.service().login(username, password, IP);
      return 200;
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 429) {
        assertNotNull(ex.getRetryAfterSeconds(), "429 必须带 Retry-After");
        assertTrue(ex.getRetryAfterSeconds() >= 1, "Retry-After 至少 1 秒");
      }
      return ex.getStatusCode();
    } catch (RuntimeException ex) {
      return 500;
    }
  }

  private static long count(List<Integer> statuses, int code) {
    return statuses.stream().filter(s -> s == code).count();
  }

  // ==================== 1. 账号+IP 维度：并发不得突破阈值 ====================

  @Test
  void concurrentWrongPasswordsNeverExceedAccountThreshold() throws Exception {
    for (int round = 0; round < 5; round++) { // 多轮重复，避免线程调度偶然通过
      Harness h = harness(50, 3, 500); // 账号+IP 阈值 = 3
      List<Integer> statuses = concurrently(24,
          idx -> attempt(h, "victim", WRONG)); // 同账号、同来源、错误密码

      long entered = h.users().lookups.get();
      assertTrue(entered <= 3,
          "进入用户查询/BCrypt 的次数不得超过阈值 3，实际 " + entered + "（第 " + round + " 轮）");
      assertEquals(entered, count(statuses, 400), "进入认证的请求应全部为正常失败 400");
      assertEquals(0, count(statuses, 200), "错误密码不允许成功");
      assertEquals(0, count(statuses, 500), "不允许出现服务端异常");
      long blocked = count(statuses, 429);
      assertEquals(24, entered + blocked, "其余请求必须全部 429");
      assertTrue(blocked >= 21, "至少 21 路应被 429，实际 " + blocked);
      assertEquals(3, h.limiter().accountFailureCount("victim", IP), "计数与进入次数一致");
    }
  }

  // ==================== 2. IP / 全局维度并发 ====================

  @Test
  void concurrentDifferentAccountsTriggerIpDimension() throws Exception {
    Harness h = harness(5, 100, 500); // IP 阈值 = 5，账号维度很宽
    List<Integer> statuses = concurrently(24,
        idx -> attempt(h, "user" + idx, WRONG)); // 不同账号、同来源

    long entered = h.users().lookups.get();
    assertTrue(entered <= 5, "IP 维度并发进入次数不得超过 5，实际 " + entered);
    assertEquals(entered, count(statuses, 400));
    assertEquals(24, entered + count(statuses, 429), "其余必须 429");
    assertEquals(0, count(statuses, 500));
  }

  @Test
  void concurrentAttemptsTriggerGlobalCeiling() throws Exception {
    Harness h = harness(100, 100, 4); // 全局阈值 = 4，其余维度很宽
    List<Integer> statuses = concurrently(24,
        idx -> attempt(h, "any" + idx, WRONG));

    long entered = h.users().lookups.get();
    assertTrue(entered <= 4, "全局维度并发进入次数不得超过 4，实际 " + entered);
    assertEquals(entered, count(statuses, 400));
    assertEquals(24, entered + count(statuses, 429), "其余必须 429");
  }

  // ==================== 3. 维度间不互相污染 ====================

  @Test
  void blockedAccountDoesNotCorruptIpOrGlobalCounts() throws Exception {
    Harness h = harness(100, 2, 500); // 账号阈值 2，IP/全局很宽
    List<Integer> statuses = concurrently(20, idx ->
        idx < 10 ? attempt(h, "hot", WRONG) : attempt(h, "cool" + idx, WRONG));

    long entered = h.users().lookups.get();
    assertEquals(12, entered, "hot 账号进入 2 次 + cool 账号进入 10 次");
    assertEquals(12, count(statuses, 400));
    assertEquals(8, count(statuses, 429), "hot 被限的 8 路必须 429");
    assertEquals(0, count(statuses, 500));
    // 被拒绝的请求不占用、也不回退其他维度：IP/全局计数 == 实际进入次数
    assertEquals(12, h.limiter().ipFailureCount(IP), "429 不得消耗或清空 IP 计数");
    assertEquals(2, h.limiter().accountFailureCount("hot", IP), "hot 计数精确等于阈值");
  }

  @Test
  void sameAccountOtherSourceAndOtherAccountStillAllowed() {
    Harness h = harness(50, 3, 500);
    for (int i = 0; i < 3; i++) {
      attempt(h, "victim", WRONG);
    }
    assertEquals(429, attempt(h, "victim", WRONG));
    // 同账号另一来源：不受限
    try {
      h.service().login("victim", CORRECT, "198.51.100.77");
    } catch (ApiException ex) {
      throw new AssertionError("同账号其他来源应可登录", ex);
    }
    // 同来源另一账号：不受限
    assertEquals(400, attempt(h, "bystander", WRONG));
  }

  // ==================== 4. 成功与失败混合并发 ====================

  @Test
  void mixedSuccessAndFailureConcurrentKeepCountsConsistent() throws Exception {
    Harness h = harness(100, 10, 500); // 账号阈值 10（pairA 失败 8 次不触顶）
    int failures = 8;
    int successes = 6;
    int total = failures + successes;
    List<Integer> statuses = concurrently(total, idx ->
        idx < failures ? attempt(h, "pairA", WRONG) : attempt(h, "pairB", CORRECT));

    assertEquals(failures, count(statuses, 400), "8 路失败");
    assertEquals(successes, count(statuses, 200), "6 路成功");
    assertEquals(0, count(statuses, 429), "阈值内不应有 429");
    assertEquals(0, count(statuses, 500), "不允许异常");

    // 计数语义（并发交错下仍确定）：
    assertEquals(failures, h.limiter().accountFailureCount("pairA", IP), "失败计数保留");
    assertEquals(0, h.limiter().accountFailureCount("pairB", IP), "成功清除自己的 (账号,IP)");
    // IP 计数 = 失败 8 + 成功占用 6 - 成功回退 6 = 8：成功不消耗来源额度，也不误减失败计数
    assertEquals(failures, h.limiter().ipFailureCount(IP),
        "成功只撤销自己的预占，IP 计数净额 = 失败数");
    assertTrue(h.limiter().ipFailureCount(IP) >= 0);
  }
}
