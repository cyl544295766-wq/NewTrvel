package com.travelos.service;

import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.JournalEditLock;
import com.travelos.entity.TripJournal;
import com.travelos.mapper.JournalEditLockMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 攻略共同编辑互斥锁：
 *  - 同一时间有且只有一人可以编辑某篇攻略（journal_id 主键天然互斥）；
 *  - 作者与互为好友（且对该攻略可见）都可编辑，编辑前必须先取锁；
 *  - 锁带 90 秒 TTL + 前端心跳续期，编辑器崩溃等异常由过期抢占兜底；
 *  - 保存与释放只允许持锁人操作。
 */
@Service
public class JournalCollabService {

  private static final long LOCK_TTL_MS = 90 * 1000L;

  private final JournalEditLockMapper lockMapper;
  private final TripJournalService journalService;
  private final FriendService friendService;

  public JournalCollabService(JournalEditLockMapper lockMapper,
      TripJournalService journalService, FriendService friendService) {
    this.lockMapper = lockMapper;
    this.journalService = journalService;
    this.friendService = friendService;
  }

  /**
   * 编辑资格：作者本人，或互为好友且对该攻略可见（private 仅作者）。
   */
  public void requireEditor(TripJournal journal, String userId) {
    if (journal.getAuthorId() != null && journal.getAuthorId().equals(userId)) {
      return;
    }
    boolean canView = journalService.canView(journal, userId);
    boolean mutual = friendService.isMutualFriend(journal.getAuthorId(), userId);
    if (!(canView && mutual)) {
      throw ApiException.forbidden("只有作者与互为好友（且对 TA 可见）的用户可以共同编辑这篇攻略");
    }
  }

  /**
   * 获取/续期编辑锁（原子抢占）：
   *   1) 条件 UPDATE 抢占空闲或已过期的锁（含自己续期）；
   *   2) 无行则 INSERT 首次获取；唯一键冲突说明他人持锁未过期 → 409。
   * 返回 { journalId, holderId, holderName, acquiredAt, expiresAt, expiresInSec }。
   */
  public Map<String, Object> acquire(String journalId, UserContext user) {
    TripJournal journal = journalService.requireJournalGlobal(journalId);
    requireEditor(journal, user.id());

    Date now = new Date();
    Date expires = new Date(now.getTime() + LOCK_TTL_MS);
    final String holderId = user.id();
    final String holderName = displayName(user);

    // 1) 已有锁：自己续期 或 抢占已过期的锁（条件 UPDATE 原子执行）
    int taken = bypassed(() -> lockMapper.update(null,
        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<JournalEditLock>()
            .eq(JournalEditLock::getJournalId, journalId)
            .and(q -> q.eq(JournalEditLock::getHolderId, holderId)
                .or().lt(JournalEditLock::getExpiresAt, now))
            .set(JournalEditLock::getHolderId, holderId)
            .set(JournalEditLock::getHolderName, holderName)
            .set(JournalEditLock::getAcquiredAt, now)
            .set(JournalEditLock::getExpiresAt, expires)));
    if (taken == 0) {
      // 2) 无锁记录：首次 INSERT（并发冲突时唯一键兜底）
      JournalEditLock lock = new JournalEditLock();
      lock.setJournalId(journalId);
      lock.setHolderId(holderId);
      lock.setHolderName(holderName);
      lock.setAcquiredAt(now);
      lock.setExpiresAt(expires);
      try {
        bypassed(() -> {
          lockMapper.insert(lock);
          return null;
        });
      } catch (Exception ex) {
        JournalEditLock holder = bypassed(() -> lockMapper.selectById(journalId));
        throw ApiException.conflict("「" + (holder != null ? holder.getHolderName() : "其他好友")
            + "」正在编辑这篇攻略，请稍后再试");
      }
    }
    JournalEditLock current = bypassed(() -> lockMapper.selectById(journalId));
    return response(current);
  }

  /** 释放编辑锁：仅持锁人可释放。 */
  public Map<String, Object> release(String journalId, UserContext user) {
    JournalEditLock lock = bypassed(() -> lockMapper.selectById(journalId));
    if (lock != null) {
      if (!user.id().equals(lock.getHolderId())) {
        throw ApiException.forbidden("当前不是你持有的编辑锁");
      }
      bypassed(() -> {
        lockMapper.deleteById(journalId);
        return null;
      });
    }
    return Map.of("success", true, "message", "编辑锁已释放");
  }

  /** 锁状态（供 UI 展示「XX 正在编辑」）。{ locked, holderId, holderName, expiresAt, heldByMe } */
  public Map<String, Object> status(String journalId, String viewerId) {
    JournalEditLock lock = bypassed(() -> lockMapper.selectById(journalId));
    boolean locked = lock != null && lock.getExpiresAt() != null && lock.getExpiresAt().after(new Date());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("locked", locked);
    body.put("holderId", locked ? lock.getHolderId() : null);
    body.put("holderName", locked ? lock.getHolderName() : null);
    body.put("expiresAt", locked ? lock.getExpiresAt() : null);
    body.put("heldByMe", locked && viewerId.equals(lock.getHolderId()));
    return body;
  }

  /** 保存前校验：必须持有未过期的锁（互斥约束的落点）。 */
  public void requireLockHolder(String journalId, String userId) {
    JournalEditLock lock = bypassed(() -> lockMapper.selectById(journalId));
    if (lock == null || lock.getExpiresAt() == null || !userId.equals(lock.getHolderId())
        || lock.getExpiresAt().before(new Date())) {
      throw ApiException.conflict("请先获取编辑权限后再保存（页面请重新点击编辑获取编辑锁）");
    }
  }

  private Map<String, Object> response(JournalEditLock lock) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("journalId", lock.getJournalId());
    body.put("holderId", lock.getHolderId());
    body.put("holderName", lock.getHolderName());
    body.put("acquiredAt", lock.getAcquiredAt());
    body.put("expiresAt", lock.getExpiresAt());
    body.put("expiresInSec", Math.max(0, (lock.getExpiresAt().getTime() - System.currentTimeMillis()) / 1000));
    return body;
  }

  private String displayName(UserContext user) {
    return user.username() == null ? "用户" : user.username();
  }

  private <T> T bypassed(Supplier<T> action) {
    boolean prev = TripScopeContext.isBypassed();
    TripScopeContext.setBypassed(true);
    try {
      return action.get();
    } finally {
      TripScopeContext.setBypassed(prev);
    }
  }
}
