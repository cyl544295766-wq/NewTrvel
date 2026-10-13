package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.TripDayService;
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

  public TripDayController(TripDayService tripDayService) {
    this.tripDayService = tripDayService;
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
    return tripDayService.update(tripId, dayId, dto, user.id());
  }
}
