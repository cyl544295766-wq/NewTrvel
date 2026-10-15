package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.TripExpenseService;
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
@RequestMapping("/api/trips/{tripId}/expenses")
public class TripExpenseController {

  private final TripExpenseService tripExpenseService;

  public TripExpenseController(TripExpenseService tripExpenseService) {
    this.tripExpenseService = tripExpenseService;
  }

  @GetMapping
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripExpenseService.findAll(tripId, user.id());
  }

  @GetMapping("/summary")
  public Map<String, Object> getSummary(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return tripExpenseService.getSummary(tripId, user.id());
  }

  @GetMapping("/budget")
  public Map<String, Object> getBudget(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return tripExpenseService.getBudget(tripId, user.id());
  }

  @GetMapping("/settlement")
  public Map<String, Object> settlement(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return tripExpenseService.settlement(tripId, user.id());
  }

  /** 结算确认：仅 owner/admin。 */
  @RequireTripRole({"owner", "admin"})
  @PostMapping("/settlement/confirm")
  public Map<String, Object> confirmSettlement(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return tripExpenseService.confirmSettlement(tripId, user.id());
  }

  @GetMapping("/report")
  public Map<String, Object> report(@PathVariable String tripId, @CurrentUser UserContext user) {
    return tripExpenseService.report(tripId, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PostMapping
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripExpenseService.create(tripId, user.id(), dto);
  }

  @RequireTripRole({"owner", "admin", "member"})
  @PatchMapping("/{expenseId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String expenseId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripExpenseService.update(tripId, expenseId, dto, user.id());
  }

  @RequireTripRole({"owner", "admin", "member"})
  @DeleteMapping("/{expenseId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String expenseId,
      @CurrentUser UserContext user) {
    return tripExpenseService.delete(tripId, expenseId, user.id());
  }
}
