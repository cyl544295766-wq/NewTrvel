package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripMemberService;
import com.travelos.service.TripService;
import com.travelos.service.TripActionService;
import com.travelos.trip.RequireTripRole;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trips")
public class TripController {

  private final TripService tripService;
  private final TripActionService tripActionService;
  private final TripMemberService tripMemberService;
  private final com.travelos.service.TripSquareService squareService;

  public TripController(TripService tripService, TripActionService tripActionService, TripMemberService tripMemberService,
      com.travelos.service.TripSquareService squareService) {
    this.tripService = tripService;
    this.tripActionService = tripActionService;
    this.tripMemberService = tripMemberService;
    this.squareService = squareService;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> dto,
      @CurrentUser UserContext user) {
    return tripService.create(dto, user.id());
  }

  @GetMapping
  public Map<String, Object> findAll(@CurrentUser UserContext user) {
    return tripService.findAll(user.id());
  }

  @GetMapping("/{tripId}")
  public Map<String, Object> findOne(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.findOne(tripId, user.id());
  }

  @GetMapping("/{tripId}/route")
  public Map<String, Object> getRoute(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.getRoute(tripId, user.id());
  }

  @GetMapping("/{tripId}/next-action")
  public Map<String, Object> nextAction(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripActionService.detail(tripId, user.id());
  }

  /** TR-03：日期变更预览——返回哪些天会移动/保留/移除，不实际修改。 */
  @GetMapping("/{tripId}/date-change-preview")
  public Map<String, Object> dateChangePreview(@PathVariable String tripId,
      @RequestParam Map<String, String> dates,
      @CurrentUser UserContext user) {
    return tripService.dateChangePreview(tripId, dates, user.id());
  }

  @PatchMapping("/{tripId}")
  @RequireTripRole({"owner", "admin", "member"})
  public Map<String, Object> update(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripService.update(tripId, dto, user.id());
  }

  @PatchMapping("/{tripId}/archive")
  @RequireTripRole({"owner", "admin", "member"})
  public Map<String, Object> archive(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.archive(tripId, user.id());
  }

  @PostMapping("/{tripId}/duplicate")
  public Map<String, Object> duplicate(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.duplicate(tripId, user.id());
  }

  @PatchMapping("/{tripId}/favorite")
  public Map<String, Object> favorite(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.favorite(tripId, user.id());
  }

  /** 攻略广场可见性：public / friends / private，仅行程创建者可改。 */
  @PatchMapping("/{tripId}/visibility")
  public Map<String, Object> setVisibility(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return squareService.setVisibility(tripId, user.id(),
        dto.get("visibility") == null ? null : dto.get("visibility").toString());
  }

  @DeleteMapping("/{tripId}")
  @RequireTripRole({"owner", "admin", "member"})
  public Map<String, Object> remove(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.remove(tripId, user.id());
  }

  /** owner 角色转移：仅当前 owner 可调用（提示词规定路径 /api/trips/{tripId}/transfer-owner）。 */
  @PostMapping("/{tripId}/transfer-owner")
  @RequireTripRole({"owner"})
  public Map<String, Object> transferOwner(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    Object targetUserId = dto.get("targetUserId");
    if (targetUserId == null || targetUserId.toString().isBlank()) {
      throw new IllegalArgumentException("targetUserId should not be empty");
    }
    return tripMemberService.transferOwner(tripId, user.id(), targetUserId.toString());
  }
}
