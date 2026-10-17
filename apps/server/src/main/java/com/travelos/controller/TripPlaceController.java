package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.JournalCollabService;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.TripPlaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}")
public class TripPlaceController {

  private final TripPlaceService tripPlaceService;
  private final JournalCollabService collabService;

  public TripPlaceController(TripPlaceService tripPlaceService, JournalCollabService collabService) {
    this.tripPlaceService = tripPlaceService;
    this.collabService = collabService;
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping("/places")
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    // 天级编辑互斥：往某天添加地点必须持有该天的编辑锁
    Object tripDayId = dto.get("tripDayId");
    if (tripDayId != null && !tripDayId.toString().isBlank()) {
      collabService.requireDayLockHolder(tripDayId.toString(), user.id());
    }
    return tripPlaceService.create(tripId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/places/{placeId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String placeId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    // 天级编辑互斥：编辑地点必须持有其所属天的编辑锁
    collabService.requireLockHolderForPlace(placeId, user.id());
    return tripPlaceService.update(tripId, placeId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/places/{placeId}/move")
  public Map<String, Object> move(@PathVariable String tripId, @PathVariable String placeId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    // 天级编辑互斥：跨天移动按「地点当前所属天」持锁校验
    collabService.requireLockHolderForPlace(placeId, user.id());
    Object tripDayId = dto.get("tripDayId");
    if (tripDayId == null || tripDayId.toString().isBlank()) {
      throw new IllegalArgumentException("tripDayId should not be empty");
    }
    return tripPlaceService.move(tripId, placeId, tripDayId.toString(), user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/places/{placeId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String placeId,
      @CurrentUser UserContext user) {
    // 天级编辑互斥：删除地点必须持有其所属天的编辑锁
    collabService.requireLockHolderForPlace(placeId, user.id());
    return tripPlaceService.delete(tripId, placeId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/days/{dayId}/places/reorder")
  public Map<String, Object> reorder(@PathVariable String tripId, @PathVariable String dayId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    // 天级编辑互斥：重排某天地点必须持有该天的编辑锁
    collabService.requireDayLockHolder(dayId, user.id());
    @SuppressWarnings("unchecked")
    List<String> placeIds = (List<String>) dto.get("placeIds");
    if (placeIds == null) {
      placeIds = List.of();
    }
    return tripPlaceService.reorder(tripId, dayId, placeIds, user.id());
  }
}
