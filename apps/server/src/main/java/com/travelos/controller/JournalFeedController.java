package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.JournalCollabService;
import com.travelos.service.TripJournalService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 攻略（游记）跨行程访问入口：
 *   GET /api/journals/feed          当前用户可见的攻略流（自己的全部 + 他人按可见性规则）
 *   GET /api/journals/{journalId}   单篇详情（按可见性规则校验，不要求行程成员身份）
 * 行程内管理仍走 /api/trips/{tripId}/journals。
 */
@RestController
@RequestMapping("/api/journals")
public class JournalFeedController {

  private final TripJournalService journalService;
  private final JournalCollabService collabService;

  public JournalFeedController(TripJournalService journalService, JournalCollabService collabService) {
    this.journalService = journalService;
    this.collabService = collabService;
  }

  @GetMapping("/feed")
  public Map<String, Object> feed(@CurrentUser UserContext user) {
    return journalService.feed(user.id());
  }

  @GetMapping("/{journalId}")
  public Map<String, Object> detail(@PathVariable String journalId, @CurrentUser UserContext user) {
    return journalService.findGlobally(journalId, user.id());
  }

  // ==================== 共同编辑（互斥锁） ====================

  /** 编辑锁状态：{ locked, holderName, heldByMe, ... }。 */
  @GetMapping("/{journalId}/edit-lock")
  public Map<String, Object> lockStatus(@PathVariable String journalId, @CurrentUser UserContext user) {
    return collabService.status(journalId, user.id());
  }

  /** 获取/续期编辑锁（互斥：他人持锁未过期 → 409）。 */
  @PostMapping("/{journalId}/edit-lock")
  public Map<String, Object> acquireLock(@PathVariable String journalId, @CurrentUser UserContext user) {
    return collabService.acquire(journalId, user);
  }

  /** 释放编辑锁（仅持锁人）。 */
  @DeleteMapping("/{journalId}/edit-lock")
  public Map<String, Object> releaseLock(@PathVariable String journalId, @CurrentUser UserContext user) {
    return collabService.release(journalId, user);
  }

  /** 共同编辑保存（全局）：必须持有编辑锁；可见性/名单仅作者可改。 */
  @PatchMapping("/{journalId}")
  public Map<String, Object> updateGlobally(@PathVariable String journalId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    collabService.requireLockHolder(journalId, user.id());
    return journalService.updateGlobally(journalId, dto, user.id());
  }
}
