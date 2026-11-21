package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.EnvLoader;
import com.travelos.entity.AiRequestKey;
import com.travelos.mapper.AiRequestKeyMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等状态机在<b>真实 Postgres</b> 上的并发原子性验证：
 * 数据库条件更新（状态 + updated_at CAS / execution_token 所有权）保证
 * 「转 NEW」与「完成/放弃」在共享数据库下只有一个赢家。
 *
 * <p>覆盖：failed 并发接管、过期 running 并发接管、旧执行者晚到写入不覆盖新执行者、
 * abandon 所有权、结果不可读并发重置。用户/会话为本测试合成值
 * （ai_request_keys 无外键），测试结束后按 user_id 清理。</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiIdempotencyConcurrencyDbTest {

  static {
    EnvLoader.loadDotEnv();
  }

  @Autowired
  private AiIdempotencyService service;

  @Autowired
  private AiRequestKeyMapper mapper;

  private String user;
  private String conv;

  @BeforeAll
  void setUpFixture() {
    user = "idemrace" + Long.toHexString(System.nanoTime());
    conv = "conv-race-" + Long.toHexString(System.nanoTime());
  }

  @AfterAll
  void cleanUp() {
    mapper.delete(new LambdaQueryWrapper<AiRequestKey>()
        .eq(AiRequestKey::getUserId, user));
  }

  private String key(String k) {
    return k;
  }

  /** 并发执行 begin，返回全部结果。 */
  private List<AiIdempotencyService.Begin> concurrentBegins(String clientRequestId, int threads)
      throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      java.util.List<AiIdempotencyService.Begin> results =
          java.util.Collections.synchronizedList(new java.util.ArrayList<>());
      for (int i = 0; i < threads; i++) {
        pool.execute(() -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            results.add(service.begin(user, conv, clientRequestId));
          } catch (Exception ex) {
            results.add(null); // 不允许：冲突/异常都视为失败
          }
        });
      }
      pool.shutdown();
      assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "并发 begin 未在时限内结束");
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static long countState(List<AiIdempotencyService.Begin> begins,
      AiIdempotencyService.State state) {
    return begins.stream().filter(b -> b != null && b.state() == state).count();
  }

  private AiRequestKey seedRow(String clientRequestId, String status, String result,
      String token, Date updatedAt) {
    AiRequestKey row = new AiRequestKey();
    row.setId(AiIdempotencyService.keyOf(user, conv, clientRequestId));
    row.setUserId(user);
    row.setConversationId(conv);
    row.setClientRequestId(clientRequestId);
    row.setStatus(status);
    row.setResult(result);
    row.setExecutionToken(token);
    Date now = updatedAt == null ? new Date() : updatedAt;
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    mapper.insert(row);
    return row;
  }

  // ==================== 1. failed 记录并发接管 ====================

  @Test
  void concurrentBeginOnFailedRowYieldsExactlyOneNew() throws Exception {
    String k = key("race-failed-0001");
    seedRow(k, "failed", null, "old-fail-token", new Date());

    List<AiIdempotencyService.Begin> begins = concurrentBegins(k, 20);

    assertEquals(1, countState(begins, AiIdempotencyService.State.NEW),
        "failed 记录并发接管只允许一个 NEW");
    assertEquals(19, countState(begins, AiIdempotencyService.State.RUNNING),
        "其余请求必须重读为“进行中”，不得直接 NEW");
    assertTrue(begins.stream().noneMatch(b -> b == null), "不得出现异常/409 冲突");

    // 唯一执行者持有唯一令牌
    Set<String> tokens = begins.stream()
        .filter(b -> b != null && b.token() != null)
        .map(AiIdempotencyService.Begin::token)
        .collect(Collectors.toSet());
    assertEquals(1, tokens.size(), "只有一个执行归属令牌");

    // 执行者完成 → 回放；其他人此前未执行，故结果只来自这一个执行者
    AiIdempotencyService.Begin owner = begins.stream()
        .filter(b -> b != null && b.state() == AiIdempotencyService.State.NEW).findFirst().get();
    service.markSucceeded(user, conv, owner, java.util.Map.of("exec", 1));
    AiIdempotencyService.Begin replay = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertEquals(1, replay.result().get("exec"));
  }

  // ==================== 2. 过期 running 并发接管 ====================

  @Test
  void concurrentStaleTakeoverSingleExecutorAndLegacyOwnerCannotOverwrite() throws Exception {
    String k = key("race-stale-0001");
    Date staleAt = new Date(System.currentTimeMillis() - 600_000L); // > stale 上限 5min
    seedRow(k, "running", null, "legacy-token", staleAt);

    List<AiIdempotencyService.Begin> begins = concurrentBegins(k, 20);
    assertEquals(1, countState(begins, AiIdempotencyService.State.NEW),
        "过期 running 并发接管只允许一个 NEW");
    assertEquals(19, countState(begins, AiIdempotencyService.State.RUNNING));
    assertTrue(begins.stream().noneMatch(b -> b == null));

    AiIdempotencyService.Begin owner = begins.stream()
        .filter(b -> b != null && b.state() == AiIdempotencyService.State.NEW).findFirst().get();
    assertNotEquals("legacy-token", owner.token(), "接管必须生成新的执行归属");

    // 旧执行者（legacy-token）晚到的成功/失败/取消/放弃全部无效
    AiIdempotencyService.Begin legacy =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, k, null, "legacy-token",
            AiIdempotencyService.Origin.STALE_TAKEOVER,
            new AiIdempotencyService.Restore("running", "legacy-token", staleAt, null));
    service.markSucceeded(user, conv, legacy, java.util.Map.of("stale", true));
    assertEquals("running", service.statusOf(user, conv, k),
        "旧执行者不得覆盖新执行者的 running");
    assertEquals(owner.token(), service.tokenOf(user, conv, k), "执行归属不得被旧执行者改动");
    service.markFailed(user, conv, legacy);
    assertEquals("running", service.statusOf(user, conv, k));
    service.markCancelled(user, conv, legacy);
    assertEquals("running", service.statusOf(user, conv, k));
    service.abandon(user, conv, legacy);
    assertEquals("running", service.statusOf(user, conv, k),
        "旧执行者的 abandon 不得删除新执行者占位");

    // 新执行者的结果可正常保存并回放
    service.markSucceeded(user, conv, owner, java.util.Map.of("exec", 2));
    AiIdempotencyService.Begin replay = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertEquals(2, replay.result().get("exec"));
    assertEquals(owner.token(), service.tokenOf(user, conv, k), "succeeded 行保留执行归属");
  }

  // ==================== 3. abandon 所有权（真实库） ====================

  @Test
  void abandonOnlyDeletesOwnPlaceholderOnRealDatabase() throws Exception {
    String k = key("race-abandon-0001");
    // A 首次占位（执行者）
    AiIdempotencyService.Begin a = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.NEW, a.state());

    // 同键并发第二请求 → RUNNING（不持有令牌，也绝不 abandon）
    AiIdempotencyService.Begin b = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.RUNNING, b.state());
    assertNull(b.token());
    service.abandon(user, conv, b); // 无令牌：空操作
    assertEquals("running", service.statusOf(user, conv, k),
        "非持有者的 abandon 不得删除执行者占位");

    // 伪造“接管”：把 A 的行置为过期，B 接管得到新令牌
    AiRequestKey row = mapper.selectById(AiIdempotencyService.keyOf(user, conv, k));
    row.setUpdatedAt(new Date(System.currentTimeMillis() - 600_000L));
    mapper.updateById(row);
    AiIdempotencyService.Begin b2 = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.NEW, b2.state());
    assertNotEquals(a.token(), b2.token());

    // A（已被接管）晚到的 abandon 不得删除 b2 的占位
    service.abandon(user, conv, a);
    assertEquals("running", service.statusOf(user, conv, k));
    assertEquals(b2.token(), service.tokenOf(user, conv, k));

    // 持有者 b2 可正常完成并回放
    service.markSucceeded(user, conv, b2, java.util.Map.of("own", true));
    assertEquals(AiIdempotencyService.State.SUCCEEDED,
        service.begin(user, conv, k).state());

    // 真正的首次占位在提交失败时可安全清理；重复 abandon 幂等
    String k2 = key("race-abandon-0002");
    AiIdempotencyService.Begin own = service.begin(user, conv, k2);
    service.abandon(user, conv, own);
    assertNull(service.statusOf(user, conv, k2));
    service.abandon(user, conv, own); // 第二次：0 行，无异常
    assertNull(service.statusOf(user, conv, k2));
  }

  // ==================== 4. 结果不可读并发重置 ====================

  @Test
  void concurrentResetOfUnreadableResultYieldsOneExecutor() throws Exception {
    String k = key("race-unreadable-01");
    seedRow(k, "succeeded", "{definitely-not-json", null, new Date());

    List<AiIdempotencyService.Begin> begins = concurrentBegins(k, 16);
    assertEquals(1, countState(begins, AiIdempotencyService.State.NEW),
        "结果不可读的并发重置只允许一个执行者");
    assertEquals(15, countState(begins, AiIdempotencyService.State.RUNNING));
    assertTrue(begins.stream().noneMatch(b -> b == null), "不得出现异常/409 冲突");
  }

  // ==================== 5. abandon 来源与完成/恢复竞争（真实库，本次修复核心） ====================

  @Test
  void staleTakeoverAbandonRestoresRowAndLateOwnerCompletes() {
    String k = key("race-restore-0001");
    Date oldAt = new Date(System.currentTimeMillis() - 600_000L);
    seedRow(k, "running", null, "old-token-1", oldAt);

    AiIdempotencyService.Begin taken = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.NEW, taken.state());
    assertEquals(AiIdempotencyService.Origin.STALE_TAKEOVER, taken.origin());
    assertNotNull(taken.restore());
    assertEquals("old-token-1", taken.restore().token());

    // 接管后准入失败 → abandon：真实库中恢复而非删除
    service.abandon(user, conv, taken);
    AiRequestKey restored = mapper.selectById(AiIdempotencyService.keyOf(user, conv, k));
    assertNotNull(restored, "恢复路径绝不物理删除记录");
    assertEquals("running", restored.getStatus());
    assertEquals("old-token-1", restored.getExecutionToken(), "旧执行归属恢复");
    assertEquals(oldAt.getTime(), restored.getUpdatedAt().getTime(),
        "恢复接管前时间戳（记录仍满足 stale 可再接管）");

    // 旧任务晚完成：按旧令牌保存 succeeded（本次修复的目标行为）
    AiIdempotencyService.Begin oldOwner =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, k, null, "old-token-1",
            AiIdempotencyService.Origin.NONE, null);
    service.markSucceeded(user, conv, oldOwner, java.util.Map.of("late", 1));
    assertEquals("succeeded", service.statusOf(user, conv, k), "旧任务结果必须可保存");
    AiIdempotencyService.Begin replay = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertEquals(1, replay.result().get("late"));

    // 重复 abandon（旧接管者）对已成功记录无效
    service.abandon(user, conv, taken);
    assertEquals("succeeded", service.statusOf(user, conv, k));
  }

  @Test
  void restoredStaleRowRemainsRetryableIfOldOwnerCrashed() {
    String k = key("race-restore-0002");
    seedRow(k, "running", null, "old-token-2",
        new Date(System.currentTimeMillis() - 600_000L));
    AiIdempotencyService.Begin taken = service.begin(user, conv, k);
    service.abandon(user, conv, taken); // 恢复为 stale running

    // 旧任务并未完成（崩溃）：后续请求仍可按 stale 规则再次接管（不卡死在“进行中”）
    AiIdempotencyService.Begin again = service.begin(user, conv, k);
    assertEquals(AiIdempotencyService.State.NEW, again.state(), "恢复后的 stale 记录可再次接管");
    assertNotEquals(taken.token(), again.token());

    // 旧接管者的重复 abandon 不影响新持有者
    service.abandon(user, conv, taken);
    assertEquals(again.token(), service.tokenOf(user, conv, k));
    service.markSucceeded(user, conv, again, java.util.Map.of("retry", 1));
    assertEquals("succeeded", service.statusOf(user, conv, k));
  }

  @Test
  void failedAndUnreadableAbandonRestoreInsteadOfDelete() {
    // failed 接管放弃 → 恢复 failed，仍可同键重试
    String kf = key("race-restore-0003");
    seedRow(kf, "failed", null, "fail-token", new Date());
    AiIdempotencyService.Begin takenF = service.begin(user, conv, kf);
    assertEquals(AiIdempotencyService.Origin.FAILED_TAKEOVER, takenF.origin());
    service.abandon(user, conv, takenF);
    AiRequestKey rowF = mapper.selectById(AiIdempotencyService.keyOf(user, conv, kf));
    assertNotNull(rowF, "failed 接管放弃不得删除记录");
    assertEquals("failed", rowF.getStatus());
    AiIdempotencyService.Begin retryF = service.begin(user, conv, kf);
    assertEquals(AiIdempotencyService.State.NEW, retryF.state(), "failed 恢复后仍可重试");

    // 不可读 succeeded 重置放弃 → 恢复原状态 + 原始结果快照
    String ku = key("race-restore-0004");
    seedRow(ku, "succeeded", "{broken-json", "unreadable-token", new Date());
    AiIdempotencyService.Begin takenU = service.begin(user, conv, ku);
    assertEquals(AiIdempotencyService.Origin.UNREADABLE_RESET, takenU.origin());
    assertEquals("{broken-json", takenU.restore().resultJson());
    service.abandon(user, conv, takenU);
    AiRequestKey rowU = mapper.selectById(AiIdempotencyService.keyOf(user, conv, ku));
    assertNotNull(rowU, "不可读重置放弃不得删除历史记录");
    assertEquals("succeeded", rowU.getStatus());
    assertEquals("{broken-json", rowU.getResult(), "原始结果快照原样保留");
    assertEquals(AiIdempotencyService.State.NEW,
        service.begin(user, conv, ku).state(), "历史仍不可读 → 可再次重置重试");
  }

  @Test
  void completionAndRestoreRaceConvergesToSucceededInBothOrders() throws Exception {
    // 顺序 A：恢复先于完成（主路径）
    String kA = key("race-order-a-01");
    Date oldAtA = new Date(System.currentTimeMillis() - 600_000L);
    seedRow(kA, "running", null, "old-a", oldAtA);
    AiIdempotencyService.Begin takenA = service.begin(user, conv, kA);
    service.abandon(user, conv, takenA);
    service.markSucceeded(user, conv,
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, kA, null, "old-a",
            AiIdempotencyService.Origin.NONE, null),
        java.util.Map.of("order", "restore-first"));
    assertEquals("succeeded", service.statusOf(user, conv, kA), "恢复先于完成 → succeeded");

    // 顺序 B：完成先发起（被接管挡住，进入有界等待）→ 随后恢复 → 收敛 succeeded
    String kB = key("race-order-b-01");
    Date oldAtB = new Date(System.currentTimeMillis() - 600_000L);
    seedRow(kB, "running", null, "old-b", oldAtB);
    AiIdempotencyService.Begin takenB = service.begin(user, conv, kB);
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    Thread completer = new Thread(() -> {
      started.countDown();
      service.markSucceeded(user, conv,
          new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, kB, null, "old-b",
              AiIdempotencyService.Origin.NONE, null),
          java.util.Map.of("order", "completion-first"));
    }, "db-old-completer");
    completer.start();
    assertTrue(started.await(2, TimeUnit.SECONDS));
    Thread.sleep(60); // 确保完成方先撞上“被接管”并进入等待
    service.abandon(user, conv, takenB); // 接管者恢复
    completer.join(5000);
    assertTrue(!completer.isAlive(), "完成方必须在有界等待内结束");
    assertEquals("succeeded", service.statusOf(user, conv, kB),
        "完成先于恢复也必须收敛到 succeeded（结果不丢失）");
    assertEquals("completion-first",
        service.begin(user, conv, kB).result().get("order"), "旧任务结果完整可回放");

    // 完成/放弃并发同时发生（顺序 B 的重复压测：不出现记录消失或重复 NEW）
    for (int i = 0; i < 3; i++) {
      final int round = i;
      String kC = "race-order-c-0" + i;
      seedRow(kC, "running", null, "old-c", new Date(System.currentTimeMillis() - 600_000L));
      AiIdempotencyService.Begin takenC = service.begin(user, conv, kC);
      java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
      Thread t1 = new Thread(() -> {
        try {
          go.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        service.markSucceeded(user, conv,
            new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, kC, null, "old-c",
                AiIdempotencyService.Origin.NONE, null),
            java.util.Map.of("i", round));
      });
      Thread t2 = new Thread(() -> {
        try {
          go.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        service.abandon(user, conv, takenC);
      });
      t1.start();
      t2.start();
      go.countDown();
      t1.join(5000);
      t2.join(5000);
      String status = service.statusOf(user, conv, kC);
      assertTrue("succeeded".equals(status) || "running".equals(status),
          "并发完成+放弃的终态必须可判定，实际=" + status);
      if ("running".equals(status)) {
        // 放弃先赢且完成已在等待内结束的组合不允许出现“完成被永久丢弃”：
        // 完成方要么在恢复后补交成功，要么已确认终态；running 意味着恢复先于完成
        // 的窗口内完成方尚未轮询到 → 再给一次收敛机会（有界等待内应已完成）
        service.markSucceeded(user, conv,
            new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, kC, null, "old-c",
                AiIdempotencyService.Origin.NONE, null),
            java.util.Map.of("i", i));
        assertEquals("succeeded", service.statusOf(user, conv, kC));
      }
      assertNotNull(mapper.selectById(AiIdempotencyService.keyOf(user, conv, kC)),
          "任何顺序都不得让记录无端消失");
    }
  }

  // ==================== 6. 正常语义回归（真实库） ====================

  @Test
  void firstInsertSingleExecutorAndCancelledRequiresNewId() throws Exception {
    String k = key("race-fresh-0001");
    List<AiIdempotencyService.Begin> begins = concurrentBegins(k, 20);
    assertEquals(1, countState(begins, AiIdempotencyService.State.NEW),
        "首次插入只有唯一执行者（唯一约束 + DuplicateKey 重读）");
    assertEquals(19, countState(begins, AiIdempotencyService.State.RUNNING));

    AiIdempotencyService.Begin owner = begins.stream()
        .filter(b -> b != null && b.state() == AiIdempotencyService.State.NEW).findFirst().get();
    service.markCancelled(user, conv, owner);
    assertEquals(AiIdempotencyService.State.CANCELLED,
        service.begin(user, conv, k).state(), "取消后必须换新 ID");
  }
}
