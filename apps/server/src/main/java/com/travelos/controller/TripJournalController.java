package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.JournalCollabService;
import com.travelos.service.TripJournalService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/journals")
public class TripJournalController {

  private final TripJournalService journalService;
  private final JournalCollabService collabService;

  public TripJournalController(TripJournalService journalService, JournalCollabService collabService) {
    this.journalService = journalService;
    this.collabService = collabService;
  }

  @GetMapping
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return journalService.findAll(tripId, user.id());
  }

  @GetMapping("/{journalId}")
  public Map<String, Object> findOne(@PathVariable String tripId, @PathVariable String journalId,
      @CurrentUser UserContext user) {
    return journalService.findOne(tripId, journalId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return journalService.create(tripId, user.id(), dto);
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{journalId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String journalId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    // 共同编辑互斥：行程内保存同样必须持有编辑锁（与全局共同编辑共用一把锁）
    collabService.requireLockHolder(journalId, user.id());
    return journalService.update(tripId, journalId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/{journalId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String journalId,
      @CurrentUser UserContext user) {
    return journalService.delete(tripId, journalId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{journalId}/photos/reorder")
  public Map<String, Object> reorderPhotos(@PathVariable String tripId,
      @PathVariable String journalId, @RequestBody Map<String, Object> dto,
      @CurrentUser UserContext user) {
    @SuppressWarnings("unchecked")
    List<String> photoIds = (List<String>) dto.get("photoIds");
    return journalService.reorderPhotos(tripId, journalId,
        photoIds == null ? List.of() : photoIds, user.id());
  }
}
