package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.entity.AiRequestKey;
import com.travelos.mapper.AiRequestKeyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AI 消息发送幂等（普通与流式共享同一 clientRequestId 键，持久化到 ai_request_keys）。
 *
 * <p>状态机：</p>
 * <ul>
 *   <li>无键（旧客户端/未传）→ NEW（非幂等路径，兼容既有行为）；</li>
 *   <li>不存在 → 插入 running → NEW（并发同键只有插入成功者执行）；</li>
 *   <li>running 且未过期 → RUNNING；过期 → <b>条件接管</b>（来源 STALE_TAKEOVER）→ NEW；</li>
 *   <li>succeeded → SUCCEEDED 回放；结果不可读 → <b>条件重置</b>（UNREADABLE_RESET）→ NEW；</li>
 *   <li>failed → <b>条件接管</b>（FAILED_TAKEOVER）→ NEW；cancelled → CANCELLED。</li>
 * </ul>
 *
 * <p><b>接管来源与放弃（abandon）策略</b>——接管后<b>尚未执行</b>就被准入拒绝（429）时，
 * 不再无差别物理删除，按来源处理：</p>
 * <ul>
 *   <li>INSERT（首次占位）→ 按令牌删除占位（该行只属于本请求）；</li>
 *   <li>STALE_TAKEOVER → <b>条件恢复</b>为接管前的 running + 旧令牌 + 旧时间戳：
 *       旧执行者晚完成仍可按旧令牌保存 succeeded；若旧执行者其实已崩溃，
 *       恢复后的记录按 stale 规则可再次被接管（可重试，不会永久卡住）；</li>
 *   <li>FAILED_TAKEOVER → 条件恢复为 failed（保持可同键重试）；</li>
 *   <li>UNREADABLE_RESET → 条件恢复为原 succeeded + 原始结果快照（不破坏历史记录）。</li>
 * </ul>
 * 恢复是数据库条件更新（status=running AND execution_token=本次接管令牌），
 * 影响行数 0 时<b>保留记录只记日志</b>，绝不删除。已进入执行体后不再走 abandon，
 * 由 succeeded / failed / cancelled 结算。
 *
 * <p><b>完成与恢复竞争</b>：旧执行者完成时若发现记录已被接管（令牌不符），
 * 进入<b>有界等待</b>（{@link #RESTORE_WAIT_MS}）——若接管者随后因 429 恢复了旧令牌，
 * 立即按旧令牌补交结果；若记录已进入终态或等待超时则放弃（不覆盖他人状态）。
 * 由此「完成先于恢复 / 恢复先于完成」两种顺序都收敛到同一个 succeeded 终态。</p>
 *
 * <p>原子性：所有状态转移均为数据库条件更新（无进程内锁、无 updateById 直写）；
 * execution_token 对成功/失败/取消/恢复/删除全部做归属校验。</p>
 */
@Service
public class AiIdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(AiIdempotencyService.class);
  /** clientRequestId 白名单：限制长度与字符集（防超长键与注入式键值）。 */
  private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
  private static final long PURGE_INTERVAL_MS = 10 * 60_000L;
  /** begin 竞争重读上限：正常 1~2 次内收敛，避免病态循环。 */
  private static final int MAX_BEGIN_ATTEMPTS = 5;
  /**
   * 完成时发现被接管后的有界等待上限：接管者因 429 放弃（abandon→恢复）发生在
   * 准入拒绝的同一调用栈内（微秒~毫秒级），300ms 远超该延迟；超时即放弃，
   * 不阻塞执行线程过久。
   */
  static final long RESTORE_WAIT_MS = 300;
  private static final long RESTORE_POLL_MS = 25;

  public enum State {
    /** 首次执行（含原子接管过期 running、失败后重试） */
    NEW,
    /** 同键执行中 */
    RUNNING,
    /** 已成功：回放 result */
    SUCCEEDED,
    /** 已取消：不恢复 */
    CANCELLED
  }

  /** NEW 的产生来源——决定 abandon（准入失败、尚未执行）采取哪种策略。 */
  public enum Origin {
    /** 非幂等路径（无键）：无记录可清理 */
    NONE,
    /** 首次插入占位：可按令牌删除 */
    INSERT,
    /** failed（含未知状态）条件接管：恢复为 failed */
    FAILED_TAKEOVER,
    /** 过期 running 条件接管：恢复为原 running（旧令牌/旧时间戳） */
    STALE_TAKEOVER,
    /** 结果不可读的 succeeded 重置：恢复为原 succeeded + 原始结果快照 */
    UNREADABLE_RESET
  }

  /**
   * 接管前的记录快照（恢复用）。
   *
   * @param status     接管前状态（running / failed / succeeded）
   * @param token      接管前执行令牌（可为 null——历史行）
   * @param updatedAt  接管前时间戳（stale running 恢复为旧值 → 立即重新满足接管条件）
   * @param resultJson 接管前结果原文（不可读 succeeded 用它保住历史快照）
   */
  public record Restore(String status, String token, Date updatedAt, String resultJson) {
  }

  /**
   * begin 结果。
   *
   * @param token   执行归属令牌：仅 NEW 状态非空
   * @param origin  NEW 的产生来源（非 NEW 为 NONE）
   * @param restore 接管前快照（仅接管来源非空），abandon 按此恢复
   */
  public record Begin(State state, String key, Map<String, Object> result, String token,
      Origin origin, Restore restore) {
  }

  private final AiRequestKeyMapper mapper;
  private final int retentionDays;
  private final long staleRunningMs;
  private volatile long lastPurgeMillis;

  public AiIdempotencyService(AiRequestKeyMapper mapper,
      @Value("${ai.idempotency.retention-days:7}") int retentionDays,
      @Value("${ai.idempotency.stale-running-ms:300000}") long staleRunningMs) {
    this.mapper = mapper;
    this.retentionDays = Math.max(1, retentionDays);
    this.staleRunningMs = Math.max(1000, staleRunningMs);
  }

  /** 复合键：user|conversation|clientRequestId 派生单主键（天然唯一约束兜底）。 */
  public static String keyOf(String userId, String conversationId, String clientRequestId) {
    return userId + "|" + conversationId + "|" + clientRequestId;
  }

  /**
   * 开始一次发送：不存在则占位 running；已存在按状态机返回。
   * 任何「转 NEW」都必须赢得数据库条件更新（影响行数 = 1），并记录接管来源与旧状态快照。
   *
   * @throws ApiException 400（clientRequestId 非法）/ 409（竞争后仍无法收敛）
   */
  public Begin begin(String userId, String conversationId, String clientRequestId) {
    if (clientRequestId == null || clientRequestId.isBlank()) {
      return new Begin(State.NEW, null, null, null, Origin.NONE, null);
    }
    String key = clientRequestId.trim();
    if (!REQUEST_ID_PATTERN.matcher(key).matches()) {
      throw ApiException.badRequest("clientRequestId 格式不正确");
    }
    maybePurge();
    String id = keyOf(userId, conversationId, key);

    for (int attempt = 0; attempt < MAX_BEGIN_ATTEMPTS; attempt++) {
      Date now = new Date();
      AiRequestKey row = mapper.selectById(id);
      if (row == null) {
        String token = newToken();
        try {
          mapper.insert(newRow(id, userId, conversationId, key, token, now));
          return new Begin(State.NEW, key, null, token, Origin.INSERT, null);
        } catch (DuplicateKeyException ex) {
          continue; // 并发插入输掉：重读按既有状态处理（只有一个执行者）
        }
      }
      String status = row.getStatus() == null ? "" : row.getStatus();
      // CAS 基准与 stale 判定同源（updated_at 缺失时退回 created_at）
      Date baseline = row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt();
      switch (status) {
        case "running": {
          long age = now.getTime() - baseline.getTime();
          if (age > staleRunningMs) {
            // 先捕获旧值再执行接管（接管是数据库 UPDATE；不得依赖读取对象后续仍保持旧值）
            String prevToken = row.getExecutionToken();
            String prevResult = row.getResult();
            String token = newToken();
            if (mapper.takeover(id, "running", baseline, token, now) == 1) {
              Restore restore = new Restore("running", prevToken, baseline, prevResult);
              return new Begin(State.NEW, key, null, token, Origin.STALE_TAKEOVER, restore);
            }
            continue; // 接管竞争失败 → 重读最新状态
          }
          return new Begin(State.RUNNING, key, null, null, Origin.NONE, null);
        }
        case "succeeded": {
          Map<String, Object> result;
          String rawResult = row.getResult();
          String prevToken = row.getExecutionToken();
          try {
            result = rawResult == null ? Map.of() : JsonUtil.readObject(rawResult);
          } catch (Exception ex) {
            // 结果不可读 → 条件重置为 running（同样只允许一个执行者），保留原始快照
            log.warn("ai idempotency result unreadable, treat as retryable: {}", id);
            String token = newToken();
            if (mapper.takeover(id, "succeeded", baseline, token, now) == 1) {
              Restore restore = new Restore("succeeded", prevToken, baseline, rawResult);
              return new Begin(State.NEW, key, null, token, Origin.UNREADABLE_RESET, restore);
            }
            continue; // 竞争失败 → 重读
          }
          return new Begin(State.SUCCEEDED, key, result, null, Origin.NONE, null);
        }
        case "cancelled":
          return new Begin(State.CANCELLED, key, null, null, Origin.NONE, null);
        case "failed":
        default: {
          // failed（含未知状态）：条件接管，允许同键重试
          String prevToken = row.getExecutionToken();
          String prevResult = row.getResult();
          String token = newToken();
          if (mapper.takeover(id, status, baseline, token, now) == 1) {
            Restore restore = new Restore(status, prevToken, baseline, prevResult);
            return new Begin(State.NEW, key, null, token, Origin.FAILED_TAKEOVER, restore);
          }
          continue; // 竞争失败 → 重读
        }
      }
    }
    // 多轮竞争仍未收敛：按最新状态给出保守结果——此路径绝不 NEW（避免双执行）。
    // 同键有存活原任务时“进行中”是真实的；其余极端竞争下客户端重试即可收敛。
    AiRequestKey last = mapper.selectById(id);
    if (last == null) {
      throw ApiException.conflict("请求正在处理中，请稍后重试"); // 记录消失的罕见兜底
    }
    log.warn("ai idempotency begin contention: attempts={}, key={}, lastStatus={}",
        MAX_BEGIN_ATTEMPTS, id, last.getStatus());
    String lastStatus = last.getStatus() == null ? "" : last.getStatus();
    if ("succeeded".equals(lastStatus)) {
      try {
        Map<String, Object> result = last.getResult() == null ? Map.of()
            : JsonUtil.readObject(last.getResult());
        return new Begin(State.SUCCEEDED, key, result, null, Origin.NONE, null);
      } catch (Exception ex) {
        return new Begin(State.RUNNING, key, null, null, Origin.NONE, null); // 结果不可读：重试后再重置
      }
    }
    if ("cancelled".equals(lastStatus)) {
      return new Begin(State.CANCELLED, key, null, null, Origin.NONE, null);
    }
    return new Begin(State.RUNNING, key, null, null, Origin.NONE, null);
  }

  // ==================== 状态结算（带“等待恢复”的归属保护） ====================

  /** 执行成功：仅当前执行归属可落 result；被接管时有界等待恢复后补交。 */
  public void markSucceeded(String userId, String conversationId, Begin begin,
      Map<String, Object> result) {
    if (!hasOwnership(begin)) {
      return;
    }
    String json;
    try {
      json = result == null ? "{}" : JsonUtil.write(result);
    } catch (Exception ex) {
      json = "{}";
    }
    settle(userId, conversationId, begin, "succeeded", json);
  }

  /** 执行失败：仅当前执行归属可标记 failed；被接管时同样等待恢复。 */
  public void markFailed(String userId, String conversationId, Begin begin) {
    settle(userId, conversationId, begin, "failed", null);
  }

  /** 取消：仅当前执行归属可标记 cancelled（前端须换新 ID）。 */
  public void markCancelled(String userId, String conversationId, Begin begin) {
    settle(userId, conversationId, begin, "cancelled", null);
  }

  /**
   * 条件结算 + 被接管时的有界等待：
   * <ol>
   *   <li>条件更新（running + 本令牌）；命中即返回；</li>
   *   <li>0 行 → 读取记录：已消失/已终态 → 放弃（他人已结算，不覆盖）；</li>
   *   <li>仍 running 但令牌是接管者的 → 轮询等待其 429 放弃（恢复旧令牌），
   *       一旦旧令牌回归立即补交；等待期间记录进入终态则放弃；</li>
   *   <li>超时（{@link #RESTORE_WAIT_MS}）→ 放弃并记日志（不阻塞执行线程过久）。</li>
   * </ol>
   * 由此「完成先于恢复 / 恢复先于完成」都收敛到唯一终态，且旧执行者永远不会
   * 覆盖接管者已写入的结果（条件更新仍要求 running + 本令牌）。
   */
  private void settle(String userId, String conversationId, Begin begin,
      String status, String resultJson) {
    String id = keyOf(userId, conversationId, begin.key());
    try {
      int rows = applySettle(id, begin.token(), status, resultJson);
      if (rows > 0) {
        return;
      }
      long deadline = System.currentTimeMillis() + RESTORE_WAIT_MS;
      while (System.currentTimeMillis() < deadline) {
        AiRequestKey row = mapper.selectById(id);
        if (row == null) {
          log.warn("ai idempotency {} skipped (record gone): {}", status, id);
          return;
        }
        if (begin.token().equals(row.getExecutionToken())
            && "running".equals(row.getStatus())) {
          if (applySettle(id, begin.token(), status, resultJson) > 0) {
            return; // 已被恢复（或瞬时竞争）→ 补交成功
          }
          continue;
        }
        if (!"running".equals(row.getStatus())) {
          log.warn("ai idempotency {} skipped (already {}): {}", status, row.getStatus(), id);
          return; // 他人已写入终态：不覆盖
        }
        // 仍由接管者持有且未终结 → 其放弃（恢复）应很快发生，继续等待
        Thread.sleep(RESTORE_POLL_MS);
      }
      log.warn("ai idempotency {} gave up waiting for takeover restore: {}", status, id);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("ai idempotency {} interrupted while waiting for restore: {}", status, id);
    } catch (RuntimeException ex) {
      log.warn("ai idempotency {} failed: {}", status, ex.getClass().getSimpleName());
    }
  }

  private int applySettle(String id, String token, String status, String resultJson) {
    Date now = new Date();
    if (resultJson != null) {
      return mapper.succeedIfOwned(id, token, resultJson, now);
    }
    return mapper.finishIfOwned(id, token, status, now);
  }

  // ==================== 放弃（按来源：删除或条件恢复） ====================

  /**
   * 准入被拒等「尚未执行」路径的清理——按 NEW 的来源区分：
   * <ul>
   *   <li>INSERT → 仅删除本请求自己的占位（重复调用幂等）；</li>
   *   <li>STALE_TAKEOVER → 条件恢复接管前的 running/旧令牌/旧时间戳
   *       （旧任务晚完成可保存；崩溃任务后续仍可按 stale 规则再接管）；</li>
   *   <li>FAILED_TAKEOVER → 条件恢复 failed（保持可重试）；</li>
   *   <li>UNREADABLE_RESET → 条件恢复原 succeeded + 原始结果快照（不破坏历史）。</li>
   * </ul>
   * 恢复/删除均为条件更新，影响行数 0 时保留记录只记日志（状态已变则以现状为准），
   * 绝不物理删除非 INSERT 来源的记录，也不影响其他执行者。
   */
  public void abandon(String userId, String conversationId, Begin begin) {
    if (begin == null || begin.key() == null || begin.token() == null) {
      return;
    }
    try {
      String id = keyOf(userId, conversationId, begin.key());
      int rows;
      switch (begin.origin()) {
        case INSERT:
          rows = mapper.deleteIfOwned(id, begin.token());
          break;
        case STALE_TAKEOVER:
        case FAILED_TAKEOVER:
        case UNREADABLE_RESET: {
          Restore r = begin.restore();
          if (r == null) { // 防御：接管来源必须带快照，缺快照时不得删除
            log.warn("ai idempotency abandon missing restore snapshot: {}", id);
            return;
          }
          rows = mapper.restoreIfOwned(id, begin.token(), r.status(), r.token(),
              r.updatedAt(), r.resultJson());
          break;
        }
        default:
          return; // NONE（非幂等路径）：无记录
      }
      if (rows == 0) {
        // 状态已被他人改变（旧任务已完成/他人已接管）：保留现状，仅记录
        log.info("ai idempotency abandon no-op (state changed, kept): {}", id);
      }
    } catch (RuntimeException ex) {
      // 清理失败不得影响其他同键请求（占位最终由保留期清理兜底）
      log.warn("ai idempotency abandon failed: {}", ex.getClass().getSimpleName());
    }
  }

  /** 当前键的持久状态（测试/诊断）。 */
  public String statusOf(String userId, String conversationId, String clientRequestId) {
    AiRequestKey row = rowOf(userId, conversationId, clientRequestId);
    return row == null ? null : row.getStatus();
  }

  /** 当前键的执行归属令牌（测试/诊断）。 */
  public String tokenOf(String userId, String conversationId, String clientRequestId) {
    AiRequestKey row = rowOf(userId, conversationId, clientRequestId);
    return row == null ? null : row.getExecutionToken();
  }

  private AiRequestKey rowOf(String userId, String conversationId, String clientRequestId) {
    if (clientRequestId == null || clientRequestId.isBlank()) {
      return null;
    }
    return mapper.selectById(keyOf(userId, conversationId, clientRequestId.trim()));
  }

  /** 只有 NEW（持有令牌）的 Begin 才允许做状态写入。 */
  private static boolean hasOwnership(Begin begin) {
    return begin != null && begin.key() != null && begin.token() != null;
  }

  private static String newToken() {
    return UUID.randomUUID().toString();
  }

  private AiRequestKey newRow(String id, String userId, String conversationId, String key,
      String token, Date now) {
    AiRequestKey row = new AiRequestKey();
    row.setId(id);
    row.setUserId(userId);
    row.setConversationId(conversationId);
    row.setClientRequestId(key);
    row.setStatus("running");
    row.setExecutionToken(token);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    return row;
  }

  /** 保留期清理：每 PURGE_INTERVAL_MS 最多触发一次批量删除（有界）。 */
  private void maybePurge() {
    long now = System.currentTimeMillis();
    if (now - lastPurgeMillis < PURGE_INTERVAL_MS) {
      return;
    }
    lastPurgeMillis = now;
    try {
      Date threshold = new Date(now - retentionDays * 86_400_000L);
      mapper.delete(new LambdaQueryWrapper<AiRequestKey>()
          .lt(AiRequestKey::getCreatedAt, threshold));
    } catch (RuntimeException ex) {
      log.warn("ai idempotency purge failed: {}", ex.getClass().getSimpleName());
    }
  }
}
