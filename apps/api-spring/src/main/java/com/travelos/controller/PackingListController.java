package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.PackingListService;
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
@RequestMapping("/api/trips/{tripId}/packing-lists")
public class PackingListController {

  private final PackingListService packingListService;

  public PackingListController(PackingListService packingListService) {
    this.packingListService = packingListService;
  }

  @GetMapping
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return packingListService.findAll(tripId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return packingListService.create(tripId, user.id(), dto);
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{listId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String listId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return packingListService.update(tripId, listId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping("/{listId}/duplicate")
  public Map<String, Object> duplicate(@PathVariable String tripId, @PathVariable String listId,
      @CurrentUser UserContext user) {
    return packingListService.duplicate(tripId, listId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/{listId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String listId,
      @CurrentUser UserContext user) {
    return packingListService.delete(tripId, listId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping("/{listId}/items")
  public Map<String, Object> createItem(@PathVariable String tripId, @PathVariable String listId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return packingListService.createItem(tripId, listId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{listId}/items/{itemId}")
  public Map<String, Object> updateItem(@PathVariable String tripId, @PathVariable String listId,
      @PathVariable String itemId, @RequestBody Map<String, Object> dto,
      @CurrentUser UserContext user) {
    return packingListService.updateItem(tripId, listId, itemId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/{listId}/items/{itemId}")
  public Map<String, Object> deleteItem(@PathVariable String tripId, @PathVariable String listId,
      @PathVariable String itemId, @CurrentUser UserContext user) {
    return packingListService.deleteItem(tripId, listId, itemId, user.id());
  }
}
