package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.JournalCollabService;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.TripDayService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/days")
public class TripDayController {

  private final TripDayService tripDayService;
  private final JournalCollabService collabService;

  public TripDayController(TripDayService tripDayService, JournalCollabService collabService) {
    this.tripDayService = tripDayService;
    this.collabService = collabService;
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping("/generate")
  public Map<String, Object> generate(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return tripDayService.generate(tripId, user.id());
  }

  @GetMapping
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripDayService.findAll(tripId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{dayId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String dayId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    // 天级编辑互斥：改天标题等内容必须持有该天的编辑锁
    collabService.requireDayLockHolder(dayId, user.id());
    return tripDayService.update(tripId, dayId, dto, user.id());
  }

  // ==================== 行程天编辑锁 ====================

  /** 锁状态：{ locked, holderName, heldByMe, ... }。 */
  @RequireTripRole({"owner", "admin", "member"})
  @GetMapping("/{dayId}/edit-lock")
  public Map<String, Object> lockStatus(@PathVariable String tripId, @PathVariable String dayId,
      @CurrentUser UserContext user) {
    return collabService.statusDay(dayId, user.id());
  }

  /** 获取/续期编辑锁（互斥：他人持锁未过期 → 409 并报持有人）。 */
  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping("/{dayId}/edit-lock")
  public Map<String, Object> acquireLock(@PathVariable String tripId, @PathVariable String dayId,
      @CurrentUser UserContext user) {
    return collabService.acquireDay(dayId, user);
  }

  /** 释放编辑锁（仅持锁人）。 */
  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/{dayId}/edit-lock")
  public Map<String, Object> releaseLock(@PathVariable String tripId, @PathVariable String dayId,
      @CurrentUser UserContext user) {
    return collabService.releaseDay(dayId, user);
  }
}
