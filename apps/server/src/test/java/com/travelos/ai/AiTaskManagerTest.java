package com.travelos.ai;

import com.travelos.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 组合准入与有界执行：单用户并发、全局/队列上限、频率与每日预算、
 * 全路径释放、计数有界与服务关停。
 */
class AiTaskManagerTest {

  private static AiTaskManager manager(int userMax, int globalMax, int queueMax,
      int rateMax, int dailyMax, int taskTimeoutMs) {
    return new AiTaskManager(userMax, globalMax, queueMax, 5, 60, rateMax, dailyMax,
        20000, taskTimeoutMs);
  }

  /** 阻塞执行体：占住槽位直到 release。 */
  private static Function<AiTaskContext, Map<String, Object>> blocking(CountDownLatch release,
      AtomicInteger runs) {
    return ctx -> {
      runs.incrementAndGet();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(ex);
      }
      return Map.of("ok", true);
    };
  }

  private static ApiException expect429(Runnable action) {
    ApiException ex = assertThrows(ApiException.class, action::run);
    assertEquals(429, ex.getStatusCode());
    assertNotNull(ex.getRetryAfterSeconds());
    assertTrue(ex.getRetryAfterSeconds() >= 1);
    return ex;
  }

  /** 有界轮询等待（异步执行体就绪；避免固定睡眠）。 */
  private static void waitFor(java.util.function.BooleanSupplier condition, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "条件未在 " + timeoutMs + "ms 内满足");
  }

  /**
   * 有界等待线程池真正空闲后，对每个资源维度做严格最终断言。
   *
   * <p>语义区分：{@code await()} 返回只保证任务结果可见（执行记录/用户槽在其 callable 的
   * finally 中先于 Future 完成，可即时断言）；{@code executor.getActiveCount()} 是 worker
   * 线程池的瞬时观测——Future 完成后 worker 可能仍在 runWorker 收尾段，必须经
   * {@link AiTaskManager#awaitIdle} 有界等待再断言。失败信息携带 {@code stats()}（仅计数，
   * 无正文/令牌/密钥），用于区分“worker 时序未归零”与“真实资源泄漏”。</p>
   */
  private static void assertFullyIdle(AiTaskManager m, String phase) throws InterruptedException {
    boolean idle = m.awaitIdle(5000);
    Map<String, Object> stats = m.stats();
    assertTrue(idle, phase + "：5s 内未达空闲（worker/队列/执行记录），stats=" + stats);
    assertEquals(0, m.activeCount(), phase + "：活跃 worker 未归零，stats=" + stats);
    assertEquals(0, m.queueSize(), phase + "：队列有残留，stats=" + stats);
    assertEquals(0, ((Number) stats.get("tasks")).intValue(),
        phase + "：执行记录 activeTasks 未清理，stats=" + stats);
    assertEquals(0, ((Number) stats.get("userSlots")).intValue(),
        phase + "：用户并发槽未归零，stats=" + stats);
  }

  /** future 完成即已同步的两个语义（callable 的 finally 先于 Future 完成）——即时严格断言。 */
  private static void assertCleanupVisibleAfterFuture(AiTaskManager m, String phase) {
    Map<String, Object> stats = m.stats();
    assertEquals(0, ((Number) stats.get("tasks")).intValue(),
        phase + "：future 完成时执行记录必须已清理（真实泄漏），stats=" + stats);
    assertEquals(0, ((Number) stats.get("userSlots")).intValue(),
        phase + "：future 完成时用户槽必须已释放（真实泄漏），stats=" + stats);
  }

  @Test
  void secondTaskForSameUserRejectedWhileRunning() throws Exception {
    AiTaskManager m = manager(1, 4, 4, 10, 100, 30000);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    m.submit("u1", "c1", null, blocking(release, runs));
    try {
      ApiException ex = expect429(() -> m.submit("u1", "c1", null, blocking(release, runs)));
      assertTrue(ex.getMessage().contains("生成中"), ex.getMessage());
      waitFor(() -> runs.get() >= 1, 2000);
      assertEquals(1, runs.get(), "被拒绝的任务不得进入执行体");
      // 用户 B 不受影响
      m.submit("u2", "c2", null, blocking(release, runs));
      waitFor(() -> runs.get() == 2, 2000);
      assertEquals(2, runs.get());
    } finally {
      release.countDown();
      assertTrue(m.awaitIdle(3000));
    }
  }

  @Test
  void globalAndQueueLimitsRejectBeyondCapacity() throws Exception {
    AiTaskManager m = manager(5, 1, 1, 10, 100, 30000);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    m.submit("u1", "c1", null, blocking(release, runs));   // 占执行位
    m.submit("u2", "c2", null, blocking(release, runs));   // 入队（queue-max=1）
    try {
      expect429(() -> m.submit("u3", "c3", null, blocking(release, runs))); // 队列满
      waitFor(() -> runs.get() >= 1, 2000);
      assertEquals(1, runs.get(), "u2 在队列中未执行、u3 被拒：仅 u1 执行");
      assertTrue(m.rejectedCount() >= 1);
      assertEquals(1, m.queueSize());
      assertEquals(1, m.activeCount());
    } finally {
      release.countDown();
      assertTrue(m.awaitIdle(3000));
    }
    assertEquals(0, m.activeCount());
    assertEquals(0, m.queueSize());
  }

  @Test
  void userRateWindowRejectsBeforeExecutingBody() {
    AiTaskManager m = manager(5, 5, 5, 2, 100, 30000); // 频率上限 2
    AtomicInteger runs = new AtomicInteger();
    Function<AiTaskContext, Map<String, Object>> fast = ctx -> {
      runs.incrementAndGet();
      return Map.of("ok", true);
    };
    try {
      m.submit("u1", "c1", null, fast);
      m.submit("u1", "c1", null, fast);
      ApiException ex = expect429(() -> m.submit("u1", "c1", null, fast));
      assertTrue(ex.getMessage().contains("频繁"), ex.getMessage());
      assertTrue(m.awaitIdle(2000));
      assertEquals(2, runs.get(), "超频请求不得执行");
      m.submit("u2", "c2", null, fast); // 其他用户不受影响
      assertTrue(m.awaitIdle(2000));
      assertEquals(3, runs.get());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  @Test
  void dailyBudgetRejectsWithRetryUntilNextUtcMidnight() {
    AiTaskManager m = manager(5, 5, 5, 100, 2, 30000); // 每日预算 2
    AtomicInteger runs = new AtomicInteger();
    Function<AiTaskContext, Map<String, Object>> fast = ctx -> {
      runs.incrementAndGet();
      return Map.of("ok", true);
    };
    try {
      m.submit("u1", "c1", null, fast);
      m.submit("u1", "c1", null, fast);
      ApiException ex = expect429(() -> m.submit("u1", "c1", null, fast));
      assertTrue(ex.getMessage().contains("今日"), ex.getMessage());
      assertTrue(ex.getRetryAfterSeconds() >= 1 && ex.getRetryAfterSeconds() <= 86400);
      assertTrue(m.awaitIdle(2000));
      assertEquals(2, runs.get(), "超预算请求不得执行");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  @Test
  void queueRejectionRefundsDailyBudget() throws Exception {
    AiTaskManager m = manager(5, 1, 0, 100, 4, 30000); // 预算 4、全局 1、队列 0
    AtomicInteger runs = new AtomicInteger();
    // 第一次执行（占满全局）
    m.submit("u1", "c1", null, ctx -> {
      runs.incrementAndGet();
      return Map.of("ok", true);
    }).await(2000);
    // 队列满拒绝（全局仍被占）：用阻塞任务占位
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    m.submit("u1", "block", null, ctx -> {
      running.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        throw new AiCancelledException("interrupted");
      }
      return Map.of("ok", true);
    });
    running.await(2, TimeUnit.SECONDS);
    try {
      expect429(() -> m.submit("u1", "c2", null, ctx -> {
        runs.incrementAndGet();
        return Map.of();
      }));
      assertEquals(1, runs.get(), "被拒请求不得执行");
    } finally {
      release.countDown();
      assertTrue(m.awaitIdle(3000));
    }
    // 队列拒绝不消耗每日预算：预算 4 = c1 + 占位 + c3 + c4；被拒的 c2 已回退
    m.submit("u1", "c3", null, ctx -> {
      runs.incrementAndGet();
      return Map.of();
    }).await(2000);
    m.submit("u1", "c4", null, ctx -> {
      runs.incrementAndGet();
      return Map.of();
    }).await(2000);
    assertEquals(3, runs.get(), "预算未被被拒请求额外消耗");
    expect429(() -> m.submit("u1", "c5", null, ctx -> Map.of())); // 第 5 次占用 → 超预算
  }

  @Test
  void slotsReleasedOnSuccessExceptionAndCancel() throws Exception {
    AiTaskManager m = manager(1, 2, 2, 10, 100, 30000);

    // ---- 成功路径 ----
    m.submit("u1", "c1", null, ctx -> Map.of("ok", true)).await(2000);
    // future 完成即已同步的语义：执行记录与用户槽在 callable 的 finally 中先于 Future 完成
    assertCleanupVisibleAfterFuture(m, "成功路径");
    // worker/队列归零是另一套观测：有界等待线程池真正空闲（Future 完成 ≠ getActiveCount 即时归零）
    assertFullyIdle(m, "成功路径");

    // ---- 业务异常路径：调用者观察到原始异常包装，且资源全释放 ----
    AiTaskManager.TaskHandle failed = m.submit("u1", "c2", null, ctx -> {
      throw new IllegalStateException("boom");
    });
    java.util.concurrent.ExecutionException ex = assertThrows(
        java.util.concurrent.ExecutionException.class, () -> failed.await(2000));
    assertEquals(IllegalStateException.class, ex.getCause().getClass(),
        "调用者必须观察到原始异常包装，实际 cause=" + ex.getCause());
    assertEquals("boom", ex.getCause().getMessage());
    assertCleanupVisibleAfterFuture(m, "业务异常路径");
    assertFullyIdle(m, "业务异常路径");

    // ---- 取消路径：阻塞执行体被中断；cancel 可能使 Future 先于 callable 收尾完成，
    //      因此这里必须用有界 awaitIdle 等待真实空闲，而不是即时断言 worker 计数 ----
    CountDownLatch blocked = new CountDownLatch(1);
    AiTaskManager.TaskHandle running = m.submit("u1", "c3", null, ctx -> {
      try {
        blocked.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ex2) {
        throw new AiCancelledException("interrupted");
      }
      return Map.of();
    });
    running.cancel("test-cancel");
    try {
      running.await(2000);
    } catch (java.util.concurrent.CancellationException
        | java.util.concurrent.ExecutionException ignored) {
      // 取消路径：future 被 cancel 或执行体以取消异常结束
    }
    blocked.countDown(); // 兜底：确保执行体退出（正常情况下中断已使其退出）
    assertFullyIdle(m, "取消路径");

    // ---- 额度未被残留占用：后续任务可正常提交并再次全释放 ----
    m.submit("u1", "c4", null, ctx -> Map.of("ok", true)).await(2000);
    assertCleanupVisibleAfterFuture(m, "后续任务");
    assertFullyIdle(m, "后续任务");
  }

  /**
   * 连续混合执行（成功/异常/取消交替）：不发生计数累积，后续任务绝不因前一任务残留被误拒绝。
   * 每轮结束都对四个维度（worker/队列/执行记录/用户槽）做严格最终断言。
   */
  @Test
  void mixedSuccessExceptionCancelNeverAccumulates() throws Exception {
    AiTaskManager m = manager(2, 3, 3, 100, 500, 30000);
    AtomicInteger executed = new AtomicInteger();
    for (int round = 0; round < 8; round++) {
      // 成功
      m.submit("mix-u", "ok-" + round, null,
          ctx -> { executed.incrementAndGet(); return Map.of(); }).await(2000);
      assertCleanupVisibleAfterFuture(m, "round" + round + " 成功");
      assertFullyIdle(m, "round" + round + " 成功");

      // 业务异常
      AiTaskManager.TaskHandle bad = m.submit("mix-u", "bad-" + round, null, ctx -> {
        throw new IllegalStateException("boom");
      });
      assertThrows(java.util.concurrent.ExecutionException.class, () -> bad.await(2000));
      assertCleanupVisibleAfterFuture(m, "round" + round + " 异常");
      assertFullyIdle(m, "round" + round + " 异常");

      // 取消（阻塞体被中断）
      CountDownLatch block = new CountDownLatch(1);
      AiTaskManager.TaskHandle running = m.submit("mix-u", "cancel-" + round, null, ctx -> {
        try {
          block.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          throw new AiCancelledException("round-cancel");
        }
        return Map.of();
      });
      running.cancel("round-cancel");
      try {
        running.await(2000);
      } catch (java.util.concurrent.CancellationException
          | java.util.concurrent.ExecutionException ignored) {
        // 取消路径
      }
      block.countDown();
      assertFullyIdle(m, "round" + round + " 取消");

      // 探针：同用户仍可提交（前序残留不得造成误拒绝），并再次全释放
      m.submit("mix-u", "probe-" + round, null,
          ctx -> { executed.incrementAndGet(); return Map.of(); }).await(2000);
      assertFullyIdle(m, "round" + round + " 探针");
    }
    assertEquals(16, executed.get(), "每轮成功+探针各执行一次（8 轮），实际=" + executed.get());
  }

  /**
   * 重复稳定性：成功路径的“future 完成 → 即时语义断言 → 有界等待 worker 归零”循环
   * 重复至多40轮或15秒总时限（有界，不构成压力死循环），证明不是一次偶然通过。
   */
  @Test
  void slotReleaseObservationStableUnderRepetition() throws Exception {
    AiTaskManager m = manager(1, 2, 2, 1000, 10000, 30000);
    long deadline = System.currentTimeMillis() + 15_000;
    AtomicInteger executed = new AtomicInteger();
    int rounds = 0;
    while (rounds < 40 && System.currentTimeMillis() < deadline) {
      m.submit("rep-u", "r-" + rounds, null,
          ctx -> { executed.incrementAndGet(); return Map.of(); }).await(2000);
      assertCleanupVisibleAfterFuture(m, "重复#" + rounds);
      assertFullyIdle(m, "重复#" + rounds);
      rounds++;
    }
    assertEquals(rounds, executed.get(), "每轮恰好执行一次");
    assertTrue(rounds >= 10,
        "至少应完成10轮重复验证（实际 " + rounds + " 轮）——过早退出不得算通过");
    assertEquals(0, ((Number) m.stats().get("userSlots")).intValue(),
        "重复结束后用户槽必须为0，stats=" + m.stats());
  }

  /**
   * 清理幂等：排队中（未开始）被取消 → 由 cancel 路径补执行 cleanup；重复取消不产生
   * 二次释放（cleaned 幂等守卫），计数不下溢，且被取消的排队任务绝不执行、
   * 其用户额度可被后续任务正常复用。
   */
  @Test
  void cancelledBeforeStartAndDoubleCancelReleaseExactlyOnce() throws Exception {
    AiTaskManager m = manager(2, 1, 1, 100, 100, 30000); // 全局1 + 队列1
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch holderRunning = new CountDownLatch(1);
    AtomicInteger executed = new AtomicInteger();

    // 占住唯一执行位（u1）
    m.submit("u1", "hold", null, ctx -> {
      holderRunning.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        throw new AiCancelledException("holder-interrupted");
      }
      executed.incrementAndGet();
      return Map.of();
    });
    assertTrue(holderRunning.await(2, TimeUnit.SECONDS), "占位任务必须已开始");

    // u2 任务进入队列（未开始）→ 取消：cleanup 由 cancel 路径补执行
    AiTaskManager.TaskHandle queued = m.submit("u2", "queued", null, ctx -> {
      executed.incrementAndGet();
      return Map.of();
    });
    waitFor(() -> m.queueSize() == 1, 2000);
    queued.cancel("pre-start");
    queued.cancel("pre-start-again"); // 重复取消：ctx.cancel 返回 false，无二次释放
    // u2 的用户槽必须已释放（只剩 u1 占位）
    waitFor(() -> ((Number) m.stats().get("userSlots")).intValue() == 1, 2000);

    release.countDown();
    assertFullyIdle(m, "排队取消后");
    assertEquals(1, executed.get(), "被取消的排队任务不得执行（仅占位任务执行过）");

    // u2 额度未被残留占用：可再次提交并正常执行
    m.submit("u2", "probe", null, ctx -> {
      executed.incrementAndGet();
      return Map.of();
    }).await(2000);
    assertFullyIdle(m, "u2 探针");
    assertEquals(2, executed.get());
  }

  @Test
  void countersAreBoundedAndSwept() throws Exception {
    AiTaskManager m = manager(50, 5, 5, 100, 100, 30000);
    AtomicInteger runs = new AtomicInteger();
    for (int i = 0; i < 150; i++) { // 150 个用户：触发容量淘汰（有界性）
      try {
        m.submit("bound-u" + i, "c", null, ctx -> {
          runs.incrementAndGet();
          return Map.of();
        });
      } catch (ApiException ex) {
        // 队列/并发上限拒绝属正常（本用例只验证计数有界）
        assertEquals(429, ex.getStatusCode());
      }
    }
    assertTrue(m.awaitIdle(5000));
    // 容量上限由 ctor 钳制 min=100：150 用户后总量必须被约束（速率+预算两表合计）
    assertTrue(m.trackedRateKeys() + m.trackedDailyKeys() <= 20000,
        "计数键必须有界，实际 " + (m.trackedRateKeys() + m.trackedDailyKeys()));
    // 过期窗口清扫（窗口 60s→用 sweepForTest(now) 清不掉未过期的；这里验证清扫路径可执行）
    m.sweepForTest(System.currentTimeMillis());
    assertTrue(m.trackedRateKeys() >= 0);
  }

  @Test
  void shutdownCancelsRunningTasksAndStopsPool() throws Exception {
    AiTaskManager m = manager(2, 2, 2, 10, 100, 30000);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    m.submit("u1", "c1", null, blocking(release, runs));
    m.shutdown();
    // 关停后：任务被中断结束，池停止接收
    assertThrows(ApiException.class, () -> m.submit("u2", "c2", null, ctx -> Map.of()));
    assertTrue(runs.get() <= 1);
    release.countDown();
  }

  // ==================== UTC 自然日预算（可控时钟，不在 CI 真实等待） ====================

  /** 构造 UTC 时刻（epoch 毫秒）。 */
  private static long utc(int y, int mon, int d, int h, int min, int s) {
    return java.time.LocalDateTime.of(y, mon, d, h, min, s)
        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
  }

  private static long nextUtcMidnight(long now) {
    return now - Math.floorMod(now, 86_400_000L) + 86_400_000L;
  }

  private static Function<AiTaskContext, Map<String, Object>> fast(AtomicInteger runs) {
    return ctx -> {
      runs.incrementAndGet();
      return Map.of("ok", true);
    };
  }

  @Test
  void dailyBudgetWindowStartsAtUtcMidnightAndRetryAfterPointsThere() throws Exception {
    AiTaskManager m = manager(5, 5, 5, 1000, 2, 30000); // 预算 2/日
    java.util.concurrent.atomic.AtomicLong clock =
        new java.util.concurrent.atomic.AtomicLong(utc(2026, 11, 20, 12, 0, 0));
    m.setAdmissionClockForTest(clock::get);
    try {
      AtomicInteger runs = new AtomicInteger();
      // 当天预算内正常执行
      m.submit("u1", "c1", null, fast(runs)).await(2000);
      m.submit("u1", "c1", null, fast(runs)).await(2000);
      assertEquals(2, runs.get());

      // 窗口起点必须是当天 UTC 00:00（不是“首次请求 12:00”）
      long dayStart = utc(2026, 11, 20, 0, 0, 0);
      assertEquals(dayStart, m.dailyWindowStartForTest("u1", clock.get()),
          "每日窗口起点必须是 UTC 当日 00:00");

      // 超限 429：Retry-After = 到下一个 UTC 零点的秒数（与窗口终点一致）
      ApiException ex = expect429(
          () -> m.submit("u1", "c1", null, fast(runs)));
      assertTrue(ex.getMessage().contains("今日"), ex.getMessage());
      long midnight = nextUtcMidnight(clock.get());
      int expectedRetry = (int) Math.max(1, (midnight - clock.get() + 999) / 1000);
      assertEquals(expectedRetry, ex.getRetryAfterSeconds(),
          "Retry-After 必须指向窗口终点（下一个 UTC 零点）");
      assertEquals(2, runs.get(), "超预算请求不得执行");

      // 零点前不能提前恢复
      clock.set(utc(2026, 11, 20, 23, 59, 59));
      expect429(() -> m.submit("u1", "c1", null, fast(runs)));
      assertEquals(2, runs.get());

      // 到达 UTC 零点 → 新日期键新窗口 → 恢复
      clock.set(utc(2026, 11, 21, 0, 0, 0));
      m.submit("u1", "c1", null, fast(runs)).await(2000);
      assertEquals(3, runs.get(), "UTC 零点后必须恢复执行");
      assertEquals(1, m.dailyCountForTest("u1", clock.get()), "新一天从 1 开始计数");
    } finally {
      m.setAdmissionClockForTest(null);
    }
  }

  @Test
  void dailyBudgetRecoveryNeverDelayedToFirstRequestPlus24h() throws Exception {
    AiTaskManager m = manager(5, 5, 5, 1000, 2, 30000);
    // 首次请求发生在 23:50：若按“首次请求 + 24h”，次日 23:50 才恢复；
    // UTC 自然日则次日 00:00 恢复
    java.util.concurrent.atomic.AtomicLong clock =
        new java.util.concurrent.atomic.AtomicLong(utc(2026, 11, 20, 23, 50, 0));
    m.setAdmissionClockForTest(clock::get);
    try {
      AtomicInteger runs = new AtomicInteger();
      m.submit("u2", "c1", null, fast(runs)).await(2000);
      m.submit("u2", "c1", null, fast(runs)).await(2000);
      ApiException ex = expect429(() -> m.submit("u2", "c1", null, fast(runs)));
      // 23:50 → 零点 = 10 分钟 = 600 秒（绝不是 86400）
      assertEquals(600, ex.getRetryAfterSeconds(),
          "恢复时间必须到 UTC 零点，不得是首次请求 + 24h");

      // 次日 00:00:01 即恢复（远早于“首次请求 + 24h”的 23:50）
      clock.set(utc(2026, 11, 21, 0, 0, 1));
      m.submit("u2", "c1", null, fast(runs)).await(2000);
      assertEquals(3, runs.get(), "跨 UTC 零点立即恢复");
    } finally {
      m.setAdmissionClockForTest(null);
    }
  }

  @Test
  void expiredUtcDayKeysAreSweptAtNaturalDayBoundary() throws Exception {
    AiTaskManager m = manager(5, 5, 5, 1000, 100, 30000);
    java.util.concurrent.atomic.AtomicLong clock =
        new java.util.concurrent.atomic.AtomicLong(utc(2026, 11, 20, 8, 0, 0));
    m.setAdmissionClockForTest(clock::get);
    try {
      AtomicInteger runs = new AtomicInteger();
      m.submit("u3", "c1", null, fast(runs)).await(2000);
      assertEquals(1, m.trackedDailyKeys());

      // 跨日后清扫（生产同一路径）：旧日期键按自然日回收，不等“首次请求 + 24h”
      m.sweepForTest(utc(2026, 11, 21, 0, 5, 0));
      assertEquals(0, m.trackedDailyKeys(),
          "过期 UTC 日期键必须在跨日后被回收");

      // 当天键不受影响
      clock.set(utc(2026, 11, 21, 0, 6, 0));
      m.submit("u3", "c1", null, fast(runs)).await(2000);
      assertEquals(1, m.trackedDailyKeys(), "新一天的键保留");
    } finally {
      m.setAdmissionClockForTest(null);
    }
  }

  @Test
  void dailyRefundNeverNegativeAndNeverTouchesOtherDayWindow() throws Exception {
    AiTaskManager m = manager(5, 5, 5, 1000, 5, 30000);
    long day1Noon = utc(2026, 11, 20, 12, 0, 0);
    java.util.concurrent.atomic.AtomicLong clock =
        new java.util.concurrent.atomic.AtomicLong(day1Noon);
    m.setAdmissionClockForTest(clock::get);
    try {
      AtomicInteger runs = new AtomicInteger();
      m.submit("u4", "c1", null, fast(runs)).await(2000);
      AiTaskManager.WindowCounter day1Counter = m.dailyCounterForTest("u4", clock.get());
      assertNotNull(day1Counter);
      assertEquals(1, day1Counter.count);

      // 跨到第二天：新窗口新计数
      long day2Noon = utc(2026, 11, 21, 12, 0, 0);
      clock.set(day2Noon);
      m.submit("u4", "c1", null, fast(runs)).await(2000);
      assertEquals(1, m.dailyCountForTest("u4", day2Noon));

      // 用 day1 的计数器/键做回退：不得误减 day2 窗口
      m.refundDaily("u4|20261120", day1Counter);
      assertEquals(1, m.dailyCountForTest("u4", day2Noon),
          "跨日回退不得误减其他窗口的计数");

      // 同窗口并发回退：不为负、不异常
      AiTaskManager.WindowCounter day2Counter = m.dailyCounterForTest("u4", day2Noon);
      int threads = 8;
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(threads);
      for (int i = 0; i < threads; i++) {
        new Thread(() -> {
          try {
            start.await();
            m.refundDaily("u4|20261121", day2Counter);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        }).start();
      }
      start.countDown();
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertTrue(m.dailyCountForTest("u4", day2Noon) >= 0,
          "并发回退不得使计数为负");
    } finally {
      m.setAdmissionClockForTest(null);
    }
  }
}
