package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
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

  public TripPlaceController(TripPlaceService tripPlaceService) {
    this.tripPlaceService = tripPlaceService;
  }

  @PostMapping("/places")
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripPlaceService.create(tripId, dto, user.id());
  }

  @PatchMapping("/places/{placeId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String placeId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripPlaceService.update(tripId, placeId, dto, user.id());
  }

  @PatchMapping("/places/{placeId}/move")
  public Map<String, Object> move(@PathVariable String tripId, @PathVariable String placeId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    Object tripDayId = dto.get("tripDayId");
    if (tripDayId == null || tripDayId.toString().isBlank()) {
      throw new IllegalArgumentException("tripDayId should not be empty");
    }
    return tripPlaceService.move(tripId, placeId, tripDayId.toString(), user.id());
  }

  @DeleteMapping("/places/{placeId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String placeId,
      @CurrentUser UserContext user) {
    return tripPlaceService.delete(tripId, placeId, user.id());
  }

  @PatchMapping("/days/{dayId}/places/reorder")
  public Map<String, Object> reorder(@PathVariable String tripId, @PathVariable String dayId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    @SuppressWarnings("unchecked")
    List<String> placeIds = (List<String>) dto.get("placeIds");
    if (placeIds == null) {
      placeIds = List.of();
    }
    return tripPlaceService.reorder(tripId, dayId, placeIds, user.id());
  }
}
