package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.AiRequestKey;
import com.travelos.mapper.AiRequestKeyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发送幂等状态机（内存 CAS 语义替身）：NEW/RUNNING/SUCCEEDED/CANCELLED、
 * 并发单执行者、失败可重试、过期 running 接管、执行归属（令牌）防误删与防旧执行者覆盖。
 *
 * <p>注意：这里用条件更新语义的内存替身验证<b>状态机与所有权规则</b>；
 * 「数据库条件更新在真实并发下只有一个赢家」由 {@link AiIdempotencyConcurrencyDbTest}
 * 在真实 Postgres 上验证。</p>
 */
class AiIdempotencyServiceTest {

  private static final String USER = "u1";
  private static final String CONV = "c1";
  private static final String KEY = "client-req-0001";

  private ConcurrentHashMap<String, AiRequestKey> store;
  private AiRequestKeyMapper mapper;
  private AiIdempotencyService service;

  /** 取参转字符串：避免 String.valueOf(inv.getArgument(n)) 被推断到 char[] 重载。 */
  private static String argStr(org.mockito.invocation.InvocationOnMock inv, int index) {
    Object value = inv.getArgument(index);
    return String.valueOf(value);
  }

  @BeforeEach
  void setUp() {
    store = new ConcurrentHashMap<>();
    mapper = mock(AiRequestKeyMapper.class);

    when(mapper.selectById(anyString())).thenAnswer(inv -> {
      Object id = inv.getArgument(0); // 显式 Object：避免 String.valueOf 重载解析到 char[]
      return store.get(String.valueOf(id));
    });
    when(mapper.insert(any(AiRequestKey.class))).thenAnswer(inv -> {
      AiRequestKey row = inv.getArgument(0);
      if (store.putIfAbsent(row.getId(), row) != null) {
        throw new DuplicateKeyException("ai_request_keys_unique_key");
      }
      return 1;
    });

    // ---- 条件更新替身：与 SQL 的 WHERE 子句一一对应（影响行数 = 1 才算成功） ----
    when(mapper.takeover(anyString(), anyString(), any(), anyString(), any())).thenAnswer(inv -> {
      AiRequestKey row = store.get(argStr(inv, 0));
      if (row == null) {
        return 0;
      }
      String expectedStatus = argStr(inv, 1);
      Date expectedUpdatedAt = inv.getArgument(2);
      Date current = row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt();
      if (!expectedStatus.equals(row.getStatus())) {
        return 0;
      }
      if (expectedUpdatedAt == null ? current != null : !expectedUpdatedAt.equals(current)) {
        return 0;
      }
      row.setStatus("running");
      row.setExecutionToken(argStr(inv, 3));
      row.setResult(null);
      row.setUpdatedAt(inv.getArgument(4));
      return 1;
    });
    when(mapper.succeedIfOwned(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
      AiRequestKey row = store.get(argStr(inv, 0));
      if (row == null || !"running".equals(row.getStatus())
          || !argStr(inv, 1).equals(row.getExecutionToken())) {
        return 0;
      }
      row.setStatus("succeeded");
      row.setResult(argStr(inv, 2));
      row.setUpdatedAt(inv.getArgument(3));
      return 1;
    });
    when(mapper.finishIfOwned(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
      AiRequestKey row = store.get(argStr(inv, 0));
      if (row == null || !"running".equals(row.getStatus())
          || !argStr(inv, 1).equals(row.getExecutionToken())) {
        return 0;
      }
      row.setStatus(argStr(inv, 2));
      row.setUpdatedAt(inv.getArgument(3));
      return 1;
    });
    when(mapper.deleteIfOwned(anyString(), anyString())).thenAnswer(inv -> {
      AiRequestKey row = store.get(argStr(inv, 0));
      if (row == null || !"running".equals(row.getStatus())
          || !argStr(inv, 1).equals(row.getExecutionToken())) {
        return 0;
      }
      store.remove(row.getId());
      return 1;
    });
    // 条件恢复：WHERE id + status='running' + execution_token=本次接管令牌（token/结果可为 null）
    when(mapper.restoreIfOwned(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenAnswer(inv -> {
          AiRequestKey row = store.get(argStr(inv, 0));
          if (row == null || !"running".equals(row.getStatus())
              || !argStr(inv, 1).equals(row.getExecutionToken())) {
            return 0;
          }
          row.setStatus(argStr(inv, 2));
          row.setExecutionToken(inv.getArgument(3)); // 可为 null（历史行）
          row.setUpdatedAt(inv.getArgument(4));
          row.setResult(inv.getArgument(5)); // 可为 null
          return 1;
        });
    when(mapper.delete(any(Wrapper.class))).thenReturn(0);
    when(mapper.updateById(any(AiRequestKey.class))).thenReturn(1);

    service = new AiIdempotencyService(mapper, 7, 300_000L);
  }

  /** 预置一条记录（模拟真实库中的既有状态）。 */
  private AiRequestKey seed(String key, String status, String result, String token,
      Date updatedAt) {
    String id = AiIdempotencyService.keyOf(USER, CONV, key);
    AiRequestKey row = new AiRequestKey();
    row.setId(id);
    row.setUserId(USER);
    row.setConversationId(CONV);
    row.setClientRequestId(key);
    row.setStatus(status);
    row.setResult(result);
    row.setExecutionToken(token);
    Date now = updatedAt == null ? new Date() : updatedAt;
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    store.put(id, row);
    return row;
  }

  // ==================== 首次创建与回放 ====================

  @Test
  void firstBeginOccupiesWithTokenAndDuplicateReturnsRunning() {
    AiIdempotencyService.Begin first = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.NEW, first.state());
    assertNotNull(first.token(), "NEW 必须携带执行归属令牌");
    assertEquals("running", service.statusOf(USER, CONV, KEY));
    assertEquals(first.token(), service.tokenOf(USER, CONV, KEY));

    AiIdempotencyService.Begin second = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.RUNNING, second.state());
    assertNull(second.token(), "非执行者不持有令牌");
    assertEquals(KEY, second.key());
  }

  @Test
  void succeededReplaysSameResultWithoutReExecuting() {
    AiIdempotencyService.Begin first = service.begin(USER, CONV, KEY);
    service.markSucceeded(USER, CONV, first,
        Map.of("assistantMessage", Map.of("id", "m1", "content", "回答")));
    AiIdempotencyService.Begin replay = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertNotNull(replay.result());
    assertEquals("m1", ((Map<?, ?>) replay.result().get("assistantMessage")).get("id"));
    // 幂等记录持久化：新建服务实例（模拟服务重启）后同键仍防重
    AiIdempotencyService restarted = new AiIdempotencyService(mapper, 7, 300_000L);
    AiIdempotencyService.Begin afterRestart = restarted.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, afterRestart.state());
    assertEquals("m1",
        ((Map<?, ?>) afterRestart.result().get("assistantMessage")).get("id"));
  }

  @Test
  void failedAllowsRetryButCancelledDoesNotResume() {
    AiIdempotencyService.Begin failedBegin = service.begin(USER, CONV, "failed-key-01");
    service.markFailed(USER, CONV, failedBegin);
    assertEquals("failed", service.statusOf(USER, CONV, "failed-key-01"));
    AiIdempotencyService.Begin retry = service.begin(USER, CONV, "failed-key-01");
    assertEquals(AiIdempotencyService.State.NEW, retry.state(), "失败可同键重试");
    assertNotEquals(failedBegin.token(), retry.token(), "重试必须获得新的执行归属");
    service.markSucceeded(USER, CONV, retry, Map.of("x", 1));

    AiIdempotencyService.Begin cancelled = service.begin(USER, CONV, "cancelled-key-02");
    service.markCancelled(USER, CONV, cancelled);
    assertEquals(AiIdempotencyService.State.CANCELLED,
        service.begin(USER, CONV, "cancelled-key-02").state(),
        "取消不自动恢复（须换新 ID）");
  }

  // ==================== 并发单执行者（插入与接管路径） ====================

  @Test
  void concurrentBeginsOnFreshKeyOnlyOneExecutor() throws Exception {
    int threads = 16;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    java.util.List<AiIdempotencyService.State> states =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(() -> {
        try {
          barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
          states.add(service.begin(USER, CONV, KEY).state());
        } catch (Exception ignored) {
          states.add(null);
        }
      });
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    long newCount = states.stream().filter(s -> s == AiIdempotencyService.State.NEW).count();
    assertEquals(1, newCount, "并发同键只有一个执行者（NEW 恰好一次）");
    assertEquals(threads - 1, states.stream()
        .filter(s -> s == AiIdempotencyService.State.RUNNING).count(),
        "其余全部得到“进行中”状态");
    assertTrue(states.stream().noneMatch(s -> s == null), "不得出现异常/冲突");
  }

  @Test
  void concurrentTakeoverOnFailedRowOnlyOneNew() {
    seed(KEY, "failed", null, null, new Date());
    int threads = 16;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    java.util.List<AiIdempotencyService.Begin> begins =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(() -> {
        try {
          barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
          begins.add(service.begin(USER, CONV, KEY));
        } catch (Exception ignored) {
          begins.add(null);
        }
      });
    }
    pool.shutdown();
    try {
      assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
    long newCount = begins.stream()
        .filter(b -> b != null && b.state() == AiIdempotencyService.State.NEW).count();
    assertEquals(1, newCount, "failed 记录并发接管只允许一个 NEW");
    assertTrue(begins.stream().allMatch(b ->
        b != null && (b.state() == AiIdempotencyService.State.NEW
            || b.state() == AiIdempotencyService.State.RUNNING)),
        "竞争失败者必须重读为明确的非执行状态");
  }

  @Test
  void staleRunningTakeoverSingleExecutorAndOldOwnerCannotOverwrite() {
    Date staleAt = new Date(System.currentTimeMillis() - 600_000L);
    AiRequestKey row = seed(KEY, "running", null, "old-token", staleAt);

    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.NEW, taken.state());
    assertNotEquals("old-token", taken.token(), "接管必须产生新的执行归属");
    assertEquals(taken.token(), row.getExecutionToken());

    // 旧执行者晚到的成功/失败/取消：全部因令牌不符而无效（有界等待后放弃，不覆盖）
    AiIdempotencyService.Begin oldOwner =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, KEY, null, "old-token",
            AiIdempotencyService.Origin.STALE_TAKEOVER,
            new AiIdempotencyService.Restore("running", "old-token", staleAt, null));
    service.markSucceeded(USER, CONV, oldOwner, Map.of("stale", true));
    assertEquals("running", service.statusOf(USER, CONV, KEY),
        "旧执行者不得把新执行者的 running 改为自己的结果");
    service.markFailed(USER, CONV, oldOwner);
    assertEquals("running", service.statusOf(USER, CONV, KEY));
    service.markCancelled(USER, CONV, oldOwner);
    assertEquals("running", service.statusOf(USER, CONV, KEY));
    service.abandon(USER, CONV, oldOwner);
    assertNotNull(store.get(AiIdempotencyService.keyOf(USER, CONV, KEY)),
        "旧执行者的 abandon 不得删除新执行者占位");

    // 新执行者正常完成并可回放
    service.markSucceeded(USER, CONV, taken, Map.of("ok", true));
    AiIdempotencyService.Begin replay = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertEquals(true, replay.result().get("ok"));
  }

  @Test
  void unreadableResultResetHasSingleExecutor() {
    seed(KEY, "succeeded", "{not-json", null, new Date());
    int threads = 8;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    java.util.List<AiIdempotencyService.State> states =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(() -> {
        try {
          barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
          states.add(service.begin(USER, CONV, KEY).state());
        } catch (Exception ignored) {
          states.add(null);
        }
      });
    }
    pool.shutdown();
    try {
      assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
    assertEquals(1, states.stream().filter(s -> s == AiIdempotencyService.State.NEW).count(),
        "结果不可读的并发重置只允许一个执行者");
  }

  // ==================== abandon 所有权 ====================

  @Test
  void abandonOnlyRemovesOwnRunningPlaceholder() {
    // 1) 真正的首次占位：提交失败后可安全清理（不残留）
    AiIdempotencyService.Begin own = service.begin(USER, CONV, "abandon-key-000001");
    service.abandon(USER, CONV, own);
    assertNull(service.statusOf(USER, CONV, "abandon-key-000001"), "占位删除后可重新开始");
    assertEquals(AiIdempotencyService.State.NEW,
        service.begin(USER, CONV, "abandon-key-000001").state());

    // 2) 非持有者（令牌不符）的 abandon 不得删除执行者占位（条件恢复同样0 行）
    AiIdempotencyService.Begin executor = service.begin(USER, CONV, "abandon-key-000002");
    AiIdempotencyService.Begin foreign =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, "abandon-key-000002",
            null, "someone-else-token", AiIdempotencyService.Origin.STALE_TAKEOVER,
            new AiIdempotencyService.Restore("running", "someone-else-token", new Date(), null));
    service.abandon(USER, CONV, foreign);
    assertEquals("running", service.statusOf(USER, CONV, "abandon-key-000002"),
        "不得删除其他执行者的 running");
    service.abandon(USER, CONV, executor); // 持有者清理成功
    assertNull(service.statusOf(USER, CONV, "abandon-key-000002"));

    // 3) 重复 abandon 幂等；已成功的键不会被删
    AiIdempotencyService.Begin done = service.begin(USER, CONV, "done-key-00000001");
    service.markSucceeded(USER, CONV, done, Map.of("x", 1));
    service.abandon(USER, CONV, done);
    service.abandon(USER, CONV, done); // 重复调用：0 行，无异常
    assertEquals("succeeded", service.statusOf(USER, CONV, "done-key-00000001"),
        "已成功的键不得被 abandon 删除");
    // 重复完成不会状态倒退
    service.markSucceeded(USER, CONV, done, Map.of("x", 2));
    service.markFailed(USER, CONV, done);
    assertEquals("succeeded", service.statusOf(USER, CONV, "done-key-00000001"),
        "重复/迟到的完成不得覆盖已成功状态");
  }

  @Test
  void staleRunningIsTakenOverAndInvalidKeysRejected() {
    service.begin(USER, CONV, "stale-key-000001");
    // 模拟崩溃遗留：updated_at 超过 stale 上限
    AiRequestKey row = store.get(AiIdempotencyService.keyOf(USER, CONV, "stale-key-000001"));
    row.setUpdatedAt(new Date(System.currentTimeMillis() - 600_000L));
    assertEquals(AiIdempotencyService.State.NEW,
        service.begin(USER, CONV, "stale-key-000001").state(), "过期 running 接管执行");

    // 格式校验：过长/非法字符 → 400；空键 → 兼容（非幂等执行）
    assertThrows(ApiException.class, () -> service.begin(USER, CONV, "bad key!"));
    assertThrows(ApiException.class,
        () -> service.begin(USER, CONV, "x".repeat(65)));
    assertEquals(AiIdempotencyService.State.NEW,
        service.begin(USER, CONV, "  ").state());
  }

  @Test
  void purgeRunsAtMostOncePerIntervalAndOldRowsDeleted() {
    service.begin(USER, CONV, "purge-key-000001");
    AiRequestKey old = new AiRequestKey();
    old.setId("u9|c9|old-key-00000001");
    old.setUserId("u9");
    old.setConversationId("c9");
    old.setClientRequestId("old-key-00000001");
    old.setStatus("succeeded");
    old.setCreatedAt(new Date(System.currentTimeMillis() - 30L * 86_400_000L));
    old.setUpdatedAt(old.getCreatedAt());
    store.put(old.getId(), old);
    // 间隔内的 begin 不再触发删除（至多每 10 分钟一次）
    service.begin(USER, CONV, "purge-key-00000002");
    service.begin(USER, CONV, "purge-key-00000003");
    verify(mapper, org.mockito.Mockito.atMost(2)).delete(any(Wrapper.class));
    verify(mapper, never()).deleteIfOwned(anyString(), anyString());
  }

  @Test
  void markSucceededWithoutNewBeginIsNoOp() {
    // 非 NEW（RUNNING/SUCCEEDED 等）不持有令牌 → 任何状态写入都无效
    service.begin(USER, CONV, KEY);
    AiIdempotencyService.Begin nonOwner =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.RUNNING, KEY, null, null,
            AiIdempotencyService.Origin.NONE, null);
    service.markSucceeded(USER, CONV, nonOwner, Map.of("x", 1));
    assertEquals("running", service.statusOf(USER, CONV, KEY));
    service.markFailed(USER, CONV, nonOwner);
    assertEquals("running", service.statusOf(USER, CONV, KEY));
    service.abandon(USER, CONV, nonOwner);
    assertEquals("running", service.statusOf(USER, CONV, KEY), "无令牌不得删除占位");
    AtomicInteger unused = new AtomicInteger();
    assertEquals(0, unused.get());
  }

  // ==================== abandon 来源策略（本次修复核心） ====================

  @Test
  void staleTakeoverAbandonRestoresOldOwnerAndLateCompletionSaves() {
    Date staleAt = new Date(System.currentTimeMillis() - 600_000L);
    AiRequestKey row = seed(KEY, "running", null, "old-token", staleAt);

    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.NEW, taken.state());
    assertEquals(AiIdempotencyService.Origin.STALE_TAKEOVER, taken.origin(),
        "过期接管必须记录来源");
    assertNotNull(taken.restore(), "接管必须携带接管前快照");
    assertEquals("running", taken.restore().status());
    assertEquals("old-token", taken.restore().token());

    // 接管后准入失败 → abandon：不删除整条记录，恢复接管前状态
    service.abandon(USER, CONV, taken);
    assertEquals("running", service.statusOf(USER, CONV, KEY), "恢复而非删除");
    assertEquals("old-token", service.tokenOf(USER, CONV, KEY), "旧执行归属恢复");
    assertEquals(staleAt, row.getUpdatedAt(), "恢复接管前时间戳（仍满足 stale 可再接管）");

    // 旧任务晚完成：按旧令牌保存 succeeded
    AiIdempotencyService.Begin oldOwner =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, KEY, null, "old-token",
            AiIdempotencyService.Origin.NONE, null);
    service.markSucceeded(USER, CONV, oldOwner, Map.of("late", true));
    assertEquals("succeeded", service.statusOf(USER, CONV, KEY), "旧任务结果必须可保存");
    AiIdempotencyService.Begin replay = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertEquals(true, replay.result().get("late"));

    // 已成功后旧接管者的重复 abandon 不得改写/删除
    service.abandon(USER, CONV, taken);
    assertEquals("succeeded", service.statusOf(USER, CONV, KEY));
  }

  @Test
  void staleTakeoverAbandonKeepsRetryableWhenOldOwnerCrashed() {
    Date staleAt = new Date(System.currentTimeMillis() - 600_000L);
    seed(KEY, "running", null, "old-token", staleAt);

    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    service.abandon(USER, CONV, taken); // 恢复为 stale running
    assertEquals("running", service.statusOf(USER, CONV, KEY));

    // 旧任务其实已崩溃：恢复后的记录仍可被再次接管（不会永久“进行中”）
    AiIdempotencyService.Begin again = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.NEW, again.state(), "崩溃任务的恢复记录可重试");
    assertNotEquals(taken.token(), again.token(), "再次接管产生新执行归属");

    // 已被再次接管后，旧接管者的重复 abandon 不得影响新持有者（条件恢复0 行）
    service.abandon(USER, CONV, taken);
    assertEquals("running", service.statusOf(USER, CONV, KEY));
    assertEquals(again.token(), service.tokenOf(USER, CONV, KEY), "新持有者占位不被旧 abandon破坏");

    // 新持有者可正常结算
    service.markSucceeded(USER, CONV, again, Map.of("retry", 1));
    assertEquals("succeeded", service.statusOf(USER, CONV, KEY));
  }

  @Test
  void failedTakeoverAbandonRestoresFailedForRetry() {
    seed(KEY, "failed", null, "fail-token", new Date());
    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.Origin.FAILED_TAKEOVER, taken.origin());

    service.abandon(USER, CONV, taken);
    assertEquals("failed", service.statusOf(USER, CONV, KEY), "failed 接管放弃 → 恢复 failed（不删除）");

    // 记录仍可安全同键重试
    AiIdempotencyService.Begin retry = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.NEW, retry.state());
    service.markSucceeded(USER, CONV, retry, Map.of("x", 1));
    assertEquals("succeeded", service.statusOf(USER, CONV, KEY));
  }

  @Test
  void unreadableResetAbandonRestoresOriginalSnapshot() {
    seed(KEY, "succeeded", "{bad-json", "s-token", new Date());
    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.Origin.UNREADABLE_RESET, taken.origin());
    assertEquals("{bad-json", taken.restore().resultJson(), "快照保留原始结果");

    service.abandon(USER, CONV, taken);
    assertEquals("succeeded", service.statusOf(USER, CONV, KEY), "恢复原 succeeded（不误删历史）");
    assertEquals("{bad-json",
        store.get(AiIdempotencyService.keyOf(USER, CONV, KEY)).getResult(),
        "原始结果快照必须原样保留");

    // 历史仍不可读 → 后续请求可再次重置重试（可重试，不卡死）
    assertEquals(AiIdempotencyService.State.NEW,
        service.begin(USER, CONV, KEY).state());
  }

  @Test
  void completionBeforeRestoreConvergesToSucceededViaBoundedWait() throws Exception {
    // 真实竞态的单元复现：旧任务完成（先发生，被接管挡住进入有界等待）
    // 与接管者 abandon（恢复旧令牌）并发 → 两种顺序都必须收敛到 succeeded
    Date staleAt = new Date(System.currentTimeMillis() - 600_000L);
    seed(KEY, "running", null, "old-token", staleAt);
    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.Origin.STALE_TAKEOVER, taken.origin());

    AiIdempotencyService.Begin oldOwner =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, KEY, null, "old-token",
            AiIdempotencyService.Origin.NONE, null);
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    Thread completer = new Thread(() -> {
      started.countDown();
      service.markSucceeded(USER, CONV, oldOwner, Map.of("late", true));
    }, "old-completer");
    completer.start();
    assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
    Thread.sleep(50); // 确保完成方先进入“等待恢复”循环（完成先于恢复）
    service.abandon(USER, CONV, taken); // 接管者恢复旧令牌
    completer.join(5000);
    assertFalse(completer.isAlive(), "完成方必须在有界等待内结束");

    assertEquals("succeeded", service.statusOf(USER, CONV, KEY),
        "完成先于恢复也必须收敛到 succeeded（不丢结果、不重复 NEW）");
    AiIdempotencyService.Begin replay = service.begin(USER, CONV, KEY);
    assertEquals(AiIdempotencyService.State.SUCCEEDED, replay.state());
    assertEquals(true, replay.result().get("late"), "旧任务结果完整保存");
  }

  @Test
  void restoreBeforeCompletionSucceedsImmediately() {
    // 恢复先于完成：恢复后旧任务直接按旧令牌保存（主路径）
    Date staleAt = new Date(System.currentTimeMillis() - 600_000L);
    seed(KEY, "running", null, "old-token", staleAt);
    AiIdempotencyService.Begin taken = service.begin(USER, CONV, KEY);
    service.abandon(USER, CONV, taken);
    assertEquals("old-token", service.tokenOf(USER, CONV, KEY));

    AiIdempotencyService.Begin oldOwner =
        new AiIdempotencyService.Begin(AiIdempotencyService.State.NEW, KEY, null, "old-token",
            AiIdempotencyService.Origin.NONE, null);
    service.markSucceeded(USER, CONV, oldOwner, Map.of("ok", 1));
    assertEquals("succeeded", service.statusOf(USER, CONV, KEY));
    assertEquals(1, service.begin(USER, CONV, KEY).result().get("ok"));
  }
}
