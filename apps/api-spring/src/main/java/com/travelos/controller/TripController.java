package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripService;
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
@RequestMapping("/api/trips")
public class TripController {

  private final TripService tripService;

  public TripController(TripService tripService) {
    this.tripService = tripService;
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

  @PatchMapping("/{tripId}")
  public Map<String, Object> update(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripService.update(tripId, dto, user.id());
  }

  @PatchMapping("/{tripId}/archive")
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

  @DeleteMapping("/{tripId}")
  public Map<String, Object> remove(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripService.remove(tripId, user.id());
  }
}
