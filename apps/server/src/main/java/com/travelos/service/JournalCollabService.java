package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.EditLock;
import com.travelos.entity.TripJournal;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.EditLockMapper;
import com.travelos.mapper.TripPlaceMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 编辑互斥锁（天级粒度）：
 *  - 同一天的行程（地点 / 天标题 / 挂在该天的攻略）同一时间有且只有一人可以编辑；
 *  - 攻略锁自动挂到所属行程天（无天归属的攻略用 "journal:{id}" 兜底）；
 *  - 作者与互为好友（且对该攻略可见）都可共同编辑攻略，行程天编辑要求行程成员身份；
 *  - 锁带 90 秒 TTL + 前端心跳续期，编辑器崩溃等异常由过期抢占兜底；
 *  - 保存与释放只允许持锁人操作；所有写操作在控制器层强制校验持锁。
 */
@Service
public class JournalCollabService {

  private static final long LOCK_TTL_MS = 90 * 1000L;

  private final EditLockMapper lockMapper;
  private final TripJournalService journalService;
  private final FriendService friendService;
  private final TripPlaceMapper tripPlaceMapper;

  public JournalCollabService(EditLockMapper lockMapper,
      TripJournalService journalService, FriendService friendService,
      TripPlaceMapper tripPlaceMapper) {
    this.lockMapper = lockMapper;
    this.journalService = journalService;
    this.friendService = friendService;
    this.tripPlaceMapper = tripPlaceMapper;
  }

  /** 攻略编辑锁的作用域：挂在所属行程天上（无天则挂攻略自身），实现「同一天只能一人编辑」。 */
  public String scopeOf(TripJournal journal) {
    return journal.getTripDayId() != null && !journal.getTripDayId().isBlank()
        ? journal.getTripDayId()
        : "journal:" + journal.getId();
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

  // ==================== 攻略入口（映射到天作用域） ====================

  /** 获取/续期攻略编辑锁（实锁在所属行程天上）。 */
  public Map<String, Object> acquire(String journalId, UserContext user) {
    TripJournal journal = journalService.requireJournalGlobal(journalId);
    requireEditor(journal, user.id());
    return acquireScope(scopeOf(journal), user);
  }

  /** 释放攻略编辑锁：仅持锁人。 */
  public Map<String, Object> release(String journalId, UserContext user) {
    TripJournal journal = journalService.requireJournalGlobal(journalId);
    return releaseScope(scopeOf(journal), user);
  }

  /** 攻略锁状态（实为所属天锁状态）。 */
  public Map<String, Object> status(String journalId, String viewerId) {
    TripJournal journal = journalService.requireJournalGlobal(journalId);
    return statusScope(scopeOf(journal), viewerId);
  }

  /** 保存前校验：必须持有该攻略所属天的未过期锁。 */
  public void requireLockHolder(String journalId, String userId) {
    TripJournal journal = journalService.requireJournalGlobal(journalId);
    requireScopeHolder(scopeOf(journal), userId);
  }

  // ==================== 行程天入口 ====================

  /** 获取/续期行程天编辑锁。 */
  public Map<String, Object> acquireDay(String dayId, UserContext user) {
    return acquireScope(dayId, user);
  }

  /** 释放行程天编辑锁：仅持锁人。 */
  public Map<String, Object> releaseDay(String dayId, UserContext user) {
    return releaseScope(dayId, user);
  }

  /** 行程天锁状态。 */
  public Map<String, Object> statusDay(String dayId, String viewerId) {
    return statusScope(dayId, viewerId);
  }

  /** 行程天写操作校验：必须持有该天的未过期锁。 */
  public void requireDayLockHolder(String dayId, String userId) {
    requireScopeHolder(dayId, userId);
  }

  /** 地点写操作校验：地点所属天必须处于持锁状态（无天归属的散点不锁）。 */
  public void requireLockHolderForPlace(String placeId, String userId) {
    TripPlace place = bypassed(() -> tripPlaceMapper.selectById(placeId));
    if (place == null) {
      throw ApiException.notFound("行程点不存在");
    }
    if (place.getTripDayId() == null || place.getTripDayId().isBlank()) {
      return;
    }
    requireScopeHolder(place.getTripDayId(), userId);
  }

  // ==================== 作用域锁核心实现 ====================

  /**
   * 获取/续期锁（原子抢占）：
   *   1) 条件 UPDATE 抢占空闲或已过期的锁（含自己续期）；
   *   2) 无行则 INSERT 首次获取；唯一键冲突说明他人持锁未过期 → 409（报持有人）。
   */
  private Map<String, Object> acquireScope(String scopeId, UserContext user) {
    Date now = new Date();
    Date expires = new Date(now.getTime() + LOCK_TTL_MS);
    final String holderId = user.id();
    final String holderName = displayName(user);

    // 1) 已有锁：自己续期 或 抢占已过期的锁（条件 UPDATE 原子执行）
    int taken = bypassed(() -> lockMapper.update(null,
        new LambdaUpdateWrapper<EditLock>()
            .eq(EditLock::getScopeId, scopeId)
            .and(q -> q.eq(EditLock::getHolderId, holderId)
                .or().lt(EditLock::getExpiresAt, now))
            .set(EditLock::getHolderId, holderId)
            .set(EditLock::getHolderName, holderName)
            .set(EditLock::getAcquiredAt, now)
            .set(EditLock::getExpiresAt, expires)));
    if (taken == 0) {
      // 2) 无锁记录：首次 INSERT（并发冲突时唯一键兜底）
      EditLock lock = new EditLock();
      lock.setScopeId(scopeId);
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
        EditLock holder = bypassed(() -> lockMapper.selectById(scopeId));
        throw ApiException.conflict("「" + (holder != null ? holder.getHolderName() : "其他成员")
            + "」正在编辑这一天的行程，请稍后再试");
      }
    }
    return response(bypassed(() -> lockMapper.selectById(scopeId)));
  }

  /** 释放锁：仅持锁人可释放。 */
  private Map<String, Object> releaseScope(String scopeId, UserContext user) {
    EditLock lock = bypassed(() -> lockMapper.selectById(scopeId));
    if (lock != null) {
      if (!user.id().equals(lock.getHolderId())) {
        throw ApiException.forbidden("当前不是你持有的编辑锁");
      }
      bypassed(() -> {
        lockMapper.deleteById(scopeId);
        return null;
      });
    }
    return Map.of("success", true, "message", "编辑锁已释放");
  }

  /** 锁状态（供 UI 展示「XX 正在编辑」）。{ locked, holderId, holderName, expiresAt, heldByMe } */
  private Map<String, Object> statusScope(String scopeId, String viewerId) {
    EditLock lock = bypassed(() -> lockMapper.selectById(scopeId));
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
  private void requireScopeHolder(String scopeId, String userId) {
    EditLock lock = bypassed(() -> lockMapper.selectById(scopeId));
    if (lock == null || lock.getExpiresAt() == null || !userId.equals(lock.getHolderId())
        || lock.getExpiresAt().before(new Date())) {
      throw ApiException.conflict("这一天正被他人编辑，请先获取编辑权限（重新点击编辑即可）");
    }
  }

  private Map<String, Object> response(EditLock lock) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("scopeId", lock.getScopeId());
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
