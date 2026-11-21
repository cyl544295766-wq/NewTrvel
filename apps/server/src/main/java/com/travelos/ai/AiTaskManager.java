package com.travelos.ai;

import com.travelos.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * AI 生成任务的组合准入与有界执行（普通与流式共用同一套资源语义）。
 *
 * <p><b>准入顺序（全部在提交任务/写入消息之前同步完成）</b>：</p>
 * <ol>
 *   <li>单用户频率窗口（每窗口最多 N 次尝试）→ 429 + Retry-After（窗口剩余秒）；</li>
 *   <li>单用户每日调用预算（累计保护）→ 429 + Retry-After（到次日 UTC 零点）；</li>
 *   <li>单用户并发槽（默认 1）→ 429 + Retry-After；</li>
 *   <li>提交到<b>有界线程池</b>（core = 全局并发上限，队列 = queue-max，AbortPolicy）：
 *       队列满 → 拒绝，释放用户槽并回退每日预算后 429。</li>
 * </ol>
 *
 * <p>额度释放：执行体成功/业务失败/超时/取消/拒绝执行/服务关闭的全部路径都在
 * {@code finally} 中释放用户并发槽；线程池槽位随任务结束自动回收；
 * 不使用全局长锁包住模型请求/DB（用户槽用 compute 原子增减，频率/预算用单键 compute）。</p>
 *
 * <p><b>有界与回收</b>：频率为固定窗口、每日预算为 UTC 自然日窗口，都是 O(1) 计数
 * （每键 2 个标量），过期键按周期清扫（每日键在跨日后按自然日清除）、
 * 总键数超上限时淘汰最旧键——内存有界。
 * <b>单实例边界</b>：全部为进程内状态，多副本各自独立计数；重启后频率/预算计数清零
 * （幂等键有数据库持久化，不受此影响）。</p>
 *
 * <p>可观测性：拒绝与周期性快照输出 active/queue/rejected 计数（不含任何提示词或密钥）。</p>
 */
@Component
public class AiTaskManager {

  private static final Logger log = LoggerFactory.getLogger(AiTaskManager.class);
  private static final DateTimeFormatter DAY_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final long DAY_MS = 86_400_000L;

  /** 单个任务的执行句柄：可取消（中断执行线程）、可等待结果。 */
  public static final class TaskHandle {
    private final AiTaskContext ctx;
    private final Future<Map<String, Object>> future;
    private final java.util.concurrent.atomic.AtomicBoolean started;
    private final Runnable cleanup;

    private TaskHandle(AiTaskContext ctx, Future<Map<String, Object>> future,
        java.util.concurrent.atomic.AtomicBoolean started, Runnable cleanup) {
      this.ctx = ctx;
      this.future = future;
      this.started = started;
      this.cleanup = cleanup;
    }

    public AiTaskContext context() { return ctx; }

    /** 取消：先标记 ctx（后续检查点立即生效），再中断执行线程（尽力而为）。 */
    public void cancel(String reason) {
      if (ctx.cancel(reason)) {
        boolean cancelled = future.cancel(true);
        if (cancelled && !started.get()) {
          // 任务尚未开始（排队中被取消）→ 执行体的 finally 不会运行，这里补释放
          cleanup.run();
        }
      }
    }

    /** 等待结果（受任务总时限 + 宽限约束）。 */
    public Map<String, Object> await(long waitMillis)
        throws InterruptedException, ExecutionException, TimeoutException {
      return future.get(waitMillis, TimeUnit.MILLISECONDS);
    }

    public boolean isDone() { return future.isDone(); }
  }

  private final int userMax;
  private final int globalMax;
  private final int queueMax;
  private final int rejectRetryAfterSeconds;
  private final int userRateWindowSeconds;
  private final int userRateMax;
  private final int dailyCallsPerUser;
  private final int maxTrackedKeys;
  private final long taskTimeoutMs;

  private final ThreadPoolExecutor executor;
  private final ConcurrentHashMap<String, AtomicInteger> inFlightUsers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, WindowCounter> rateWindows = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, WindowCounter> dailyCalls = new ConcurrentHashMap<>();

  private final ConcurrentHashMap<String, AiTaskContext> activeTasks = new ConcurrentHashMap<>();
  private final AtomicLong completedCount = new AtomicLong();
  private final AtomicLong rejectedCount = new AtomicLong();
  private volatile long lastRateSweepMillis;
  private volatile long lastDailySweepMillis;
  private volatile long lastRejectLogMillis;

  /**
   * 验收测试用的准入时钟偏移（毫秒，默认 0）。仅影响准入判定
   * （频率窗口、每日预算日期键、Retry-After、清扫），用于在验收中触发 UTC 跨日；
   * 任务总时限仍走真实时钟。生产环境保持 0。
   */
  @Value("${ai.budget.test-clock-offset-ms:0}")
  private volatile long admissionOffsetMs;

  /** 测试可整体替换准入时钟（null = 恢复默认：真实时钟 + 偏移）。 */
  private volatile java.util.function.LongSupplier admissionClockOverride;

  public AiTaskManager(
      @Value("${ai.concurrency.user-max:1}") int userMax,
      @Value("${ai.concurrency.global-max:4}") int globalMax,
      @Value("${ai.concurrency.queue-max:8}") int queueMax,
      @Value("${ai.concurrency.reject-retry-after-seconds:5}") int rejectRetryAfterSeconds,
      @Value("${ai.rate-limit.user-window-seconds:60}") int userRateWindowSeconds,
      @Value("${ai.rate-limit.user-max-per-window:10}") int userRateMax,
      @Value("${ai.budget.daily-calls-per-user:100}") int dailyCallsPerUser,
      @Value("${ai.budget.max-tracked-keys:20000}") int maxTrackedKeys,
      @Value("${ai.task.timeout-ms:120000}") long taskTimeoutMs) {
    this.userMax = Math.max(1, userMax);
    this.globalMax = Math.max(1, globalMax);
    this.queueMax = Math.max(0, queueMax);
    this.rejectRetryAfterSeconds = Math.max(1, rejectRetryAfterSeconds);
    this.userRateWindowSeconds = Math.max(1, userRateWindowSeconds);
    this.userRateMax = Math.max(1, userRateMax);
    this.dailyCallsPerUser = Math.max(1, dailyCallsPerUser);
    this.maxTrackedKeys = Math.max(100, maxTrackedKeys);
    this.taskTimeoutMs = Math.max(1000, taskTimeoutMs);

    AtomicInteger seq = new AtomicInteger();
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "ai-gen-" + seq.incrementAndGet());
      thread.setDaemon(true); // 进程退出不被工作线程阻塞
      return thread;
    };
    // 队列容量 0 = 不排队（SynchronousQueue：执行位被占即拒绝），>0 = 有界等待队列
    java.util.concurrent.BlockingQueue<Runnable> workQueue = this.queueMax <= 0
        ? new java.util.concurrent.SynchronousQueue<>()
        : new LinkedBlockingQueue<>(this.queueMax);
    this.executor = new ThreadPoolExecutor(this.globalMax, this.globalMax, 0L,
        TimeUnit.MILLISECONDS, workQueue, factory,
        new ThreadPoolExecutor.AbortPolicy());
    this.executor.allowCoreThreadTimeOut(false);
  }

  // ==================== 准入 + 提交 ====================

  /**
   * 组合准入并提交执行体；任一维度超限抛 429（带 Retry-After），不提交任何任务。
   * 执行体在有界线程池中运行；所有结束路径都会释放用户并发槽。
   *
   * @param body 执行体：接收任务上下文并返回业务结果
   */
  public TaskHandle submit(String userId, String conversationId, String clientRequestId,
      Function<AiTaskContext, Map<String, Object>> body) {
    long now = admissionNow();
    maybeSweep(now); // 有界：过期窗口按需回收

    // 1) 单用户频率窗口（计一次“尝试”，固定窗口自首次请求起算）
    String rateKey = "u:" + userId;
    WindowCounter rate = incrementBounded(rateWindows, rateKey, now,
        userRateWindowSeconds * 1000L);
    if (rate.count > userRateMax) {
      int retry = retrySeconds(rate, now, userRateWindowSeconds * 1000L);
      reject("ai rate", "user=" + userId);
      throw ApiException.tooManyRequests("请求过于频繁，请稍后再试", retry);
    }

    // 2) 单用户每日调用预算：<b>UTC 自然日窗口</b>——起点为当天 UTC 00:00，
    //    终点为下一个 UTC 00:00；键按 UTC 日期，计数窗口起点与日期键严格一致，
    //    Retry-After 也指向同一终点（到下一个 UTC 零点）。
    long dayStart = utcDayStart(now);
    long nextMidnight = dayStart + DAY_MS;
    String dailyKey = userId + "|" + DAY_FORMAT.format(Instant.ofEpochMilli(now));
    WindowCounter daily = incrementDaily(dailyCalls, dailyKey, dayStart);
    if (daily.count > dailyCallsPerUser) {
      refundDaily(dailyKey, daily);
      reject("ai daily budget", "user=" + userId);
      throw ApiException.tooManyRequests("今日 AI 调用次数已用完，请明天再试",
          secondsUntil(nextMidnight, now));
    }

    // 3) 单用户并发槽（默认 1）：增减全部在 compute 内完成，杜绝脱离映射的计数实例
    int[] held = {0};
    inFlightUsers.compute(userId, (k, existing) -> {
      AtomicInteger counter = existing == null ? new AtomicInteger() : existing;
      held[0] = counter.incrementAndGet();
      return counter;
    });
    if (held[0] > userMax) {
      inFlightUsers.computeIfPresent(userId, (k, counter) -> {
        counter.decrementAndGet();
        return counter.get() <= 0 ? null : counter;
      });
      refundDaily(dailyKey, daily);
      reject("ai user concurrency", "user=" + userId);
      throw ApiException.tooManyRequests("上一条回复还在生成中，请稍候再试",
          rejectRetryAfterSeconds);
    }

    AiTaskContext ctx = new AiTaskContext(
        "ai-" + Long.toHexString(System.nanoTime()) + "-" + seq(),
        userId, conversationId, clientRequestId, taskTimeoutMs);
    activeTasks.put(ctx.taskId(), ctx);

    // 资源清理必须幂等：执行体 finally、拒绝路径、取消未启动路径共用
    java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean cleaned = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable cleanup = () -> {
      if (cleaned.compareAndSet(false, true)) {
        releaseUserSlot(userId);
        activeTasks.remove(ctx.taskId());
      }
    };

    Future<Map<String, Object>> future;
    try {
      future = executor.submit(() -> {
        started.set(true);
        try {
          Map<String, Object> result = body.apply(ctx);
          ctx.markFinished();
          completedCount.incrementAndGet();
          return result;
        } catch (AiCancelledException ex) {
          log.info("AI task cancelled: {}", ctx); // 不含正文/密钥
          return null;
        } catch (Throwable ex) {
          log.warn("AI task failed: {} ({})", ctx, ex.getClass().getSimpleName());
          if (ex instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw new IllegalStateException(ex);
        } finally {
          cleanup.run();
        }
      });
    } catch (RejectedExecutionException ex) {
      // 队列满：不占用任何执行资源（含 activeTasks 占位）
      cleanup.run();
      refundDaily(dailyKey, daily);
      reject("ai queue full", "active=" + executor.getActiveCount()
          + " queue=" + executor.getQueue().size());
      throw ApiException.tooManyRequests("AI 服务繁忙，请稍后再试", rejectRetryAfterSeconds);
    }
    return new TaskHandle(ctx, future, started, cleanup);
  }

  private static final AtomicInteger SEQ = new AtomicInteger();

  private static int seq() {
    return SEQ.incrementAndGet();
  }

  /** 释放用户并发槽（原子 computeIfPresent：增减与移除在同一把键锁内，绝不出现游离计数）。 */
  private void releaseUserSlot(String userId) {
    inFlightUsers.computeIfPresent(userId, (k, counter) -> {
      int left = counter.decrementAndGet();
      if (left < 0) {
        log.warn("AI user slot underflow guarded for {}", userId);
        counter.set(0);
        left = 0;
      }
      return left <= 0 ? null : counter;
    });
  }

  // ==================== 观测与关停 ====================

  /** 资源快照（日志/测试断言用，不含敏感数据）。 */
  public Map<String, Object> stats() {
    return Map.of(
        "active", executor.getActiveCount(),
        "queue", executor.getQueue().size(),
        "tasks", activeTasks.size(),
        "userSlots", inFlightUsers.size(),
        "rejected", rejectedCount.get(),
        "completed", completedCount.get());
  }

  public int activeCount() { return executor.getActiveCount(); }

  public int queueSize() { return executor.getQueue().size(); }

  public long rejectedCount() { return rejectedCount.get(); }

  /** 单任务总时限（毫秒），供 SSE 超时与等待计算。 */
  public long taskTimeoutMs() { return taskTimeoutMs; }

  /** 供测试：等待所有执行体结束（有界轮询，不长睡）。 */
  public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()
          && activeTasks.isEmpty()) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private void reject(String scope, String detail) {
    rejectedCount.incrementAndGet();
    long now = System.currentTimeMillis();
    if (now - lastRejectLogMillis > 1000L) {
      lastRejectLogMillis = now;
      log.warn("AI admission rejected ({}): {}; stats={}", scope, detail, stats());
    }
  }

  /** 服务关闭：取消全部在途任务并中断执行线程（线程池 shut down）。 */
  @PreDestroy
  public void shutdown() {
    for (AiTaskContext ctx : new ArrayList<>(activeTasks.values())) {
      ctx.cancel("server-shutdown");
    }
    executor.shutdownNow();
    try {
      executor.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    inFlightUsers.clear();
    activeTasks.clear(); // 关停兜底：未运行完的占位一律清除（用户槽已整体清空）
  }

  // ==================== 计数工具（有界、线程安全） ====================

  /** 准入时钟：默认真实时间 + 验收偏移；测试可整体替换。 */
  private long admissionNow() {
    java.util.function.LongSupplier override = admissionClockOverride;
    return override != null ? override.getAsLong()
        : System.currentTimeMillis() + admissionOffsetMs;
  }

  /** UTC 自然日起点（当天 00:00 的 epoch 毫秒）。 */
  private static long utcDayStart(long now) {
    return now - Math.floorMod(now, DAY_MS);
  }

  /** 测试：替换准入时钟（传 null 恢复默认）。 */
  void setAdmissionClockForTest(java.util.function.LongSupplier clock) {
    this.admissionClockOverride = clock;
  }

  /** 固定窗口计数：{窗口起点, 计数}，单键 O(1)，compute 原子更新。 */
  public static final class WindowCounter {
    final long startMillis;
    int count;

    WindowCounter(long startMillis, int count) {
      this.startMillis = startMillis;
      this.count = count;
    }
  }

  private static WindowCounter incrementBounded(ConcurrentHashMap<String, WindowCounter> map,
      String key, long now, long windowMillis) {
    return map.compute(key, (k, cur) -> {
      if (cur == null || now - cur.startMillis >= windowMillis) {
        return new WindowCounter(now, 1);
      }
      cur.count = cur.count < Integer.MAX_VALUE - 1024 ? cur.count + 1 : Integer.MAX_VALUE;
      return cur;
    });
  }

  /**
   * 每日预算计数：<b>窗口起点固定为 UTC 当日 00:00</b>（不是首次请求时刻）。
   * 键已含 UTC 日期，同键必同日；`startMillis != dayStart` 仅作时钟异常防御。
   * 同一 UTC 日期内所有请求共享计数，跨日换键换窗口。
   */
  private static WindowCounter incrementDaily(ConcurrentHashMap<String, WindowCounter> map,
      String key, long dayStart) {
    return map.compute(key, (k, cur) -> {
      if (cur == null || cur.startMillis != dayStart) {
        return new WindowCounter(dayStart, 1);
      }
      cur.count = cur.count < Integer.MAX_VALUE - 1024 ? cur.count + 1 : Integer.MAX_VALUE;
      return cur;
    });
  }

  /**
   * 拒绝时回退本次每日预算占用（只回退同一 UTC 日窗口的同一计数；
   * computeIfPresent 单键原子执行，不会误减其他并发请求，也不会出现负数）。
   */
  void refundDaily(String key, WindowCounter counter) {
    dailyCalls.computeIfPresent(key, (k, c) -> {
      if (c.startMillis != counter.startMillis || c.count <= 0) {
        return c;
      }
      if (c.count == 1) {
        return null;
      }
      c.count--;
      return c;
    });
  }

  private static int retrySeconds(WindowCounter counter, long now, long windowMillis) {
    long remaining = counter.startMillis + windowMillis - now;
    return (int) Math.max(1, Math.min((remaining + 999) / 1000, 86_400));
  }

  /** 到目标时刻的秒数（向上取整，≥1）——每日预算用于「到下一个 UTC 零点」。 */
  private static int secondsUntil(long targetMillis, long now) {
    return (int) Math.max(1, Math.min((targetMillis - now + 999) / 1000, 86_400));
  }

  // ==================== 测试观测（包内） ====================

  int trackedRateKeys() { return rateWindows.size(); }

  int trackedDailyKeys() { return dailyCalls.size(); }

  /** 测试：某用户当前每日窗口的计数器实例（直接读，用于回退语义断言）。 */
  WindowCounter dailyCounterForTest(String userId, long now) {
    return dailyCalls.get(userId + "|" + DAY_FORMAT.format(Instant.ofEpochMilli(now)));
  }

  /** 测试：某用户在指定时刻的每日预算计数（0 = 无记录）。 */
  int dailyCountForTest(String userId, long now) {
    WindowCounter c = dailyCalls.get(userId + "|" + DAY_FORMAT.format(Instant.ofEpochMilli(now)));
    return c == null ? 0 : c.count;
  }

  /** 测试：某用户当前每日窗口起点（必须是 UTC 当日 00:00；0 = 无记录）。 */
  long dailyWindowStartForTest(String userId, long now) {
    WindowCounter c = dailyCalls.get(userId + "|" + DAY_FORMAT.format(Instant.ofEpochMilli(now)));
    return c == null ? 0L : c.startMillis;
  }

  /** 测试：清理过期窗口（与生产同一路径）。 */
  void sweepForTest(long now) {
    sweep(now);
  }

  private void sweep(long now) {
    long rateWindow = userRateWindowSeconds * 1000L;
    if (now - lastRateSweepMillis >= 10_000L) {
      lastRateSweepMillis = now;
      rateWindows.values().removeIf(c -> now - c.startMillis >= rateWindow);
    }
    if (now - lastDailySweepMillis >= 60_000L) {
      lastDailySweepMillis = now;
      // 每日预算按 UTC 自然日窗口回收：起点早于“当前 UTC 日 00:00”的键一律清除，
      // 不再沿用“首次请求起 24 小时”的语义
      long todayStart = utcDayStart(now);
      dailyCalls.values().removeIf(c -> c.startMillis < todayStart);
    }
    int tracked = rateWindows.size() + dailyCalls.size();
    if (tracked > maxTrackedKeys) {
      List<Map.Entry<String, WindowCounter>> all = new ArrayList<>();
      for (Map.Entry<String, WindowCounter> e : rateWindows.entrySet()) {
        all.add(Map.entry("r:" + e.getKey(), e.getValue()));
      }
      for (Map.Entry<String, WindowCounter> e : dailyCalls.entrySet()) {
        all.add(Map.entry("d:" + e.getKey(), e.getValue()));
      }
      all.sort((a, b) -> Long.compare(a.getValue().startMillis, b.getValue().startMillis));
      int excess = tracked - maxTrackedKeys;
      for (int i = 0; i < excess; i++) {
        Map.Entry<String, WindowCounter> oldest = all.get(i);
        if (oldest.getKey().startsWith("r:")) {
          rateWindows.remove(oldest.getKey().substring(2));
        } else {
          dailyCalls.remove(oldest.getKey().substring(2));
        }
      }
    }
  }

  /** 供周期触发：准入路径上按需清扫（调用开销 O(1) 判定）。 */
  void maybeSweep(long now) {
    if (now - Math.max(lastRateSweepMillis, lastDailySweepMillis) >= 10_000L) {
      sweep(now);
    }
  }
}
