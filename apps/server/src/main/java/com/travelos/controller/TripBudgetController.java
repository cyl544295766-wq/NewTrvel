package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.TripExpenseService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/budget")
public class TripBudgetController {

  private final TripExpenseService tripExpenseService;

  public TripBudgetController(TripExpenseService tripExpenseService) {
    this.tripExpenseService = tripExpenseService;
  }

  /** P1-4 冗余路由下线（2026-10-24）：
   *  原 GET /api/trips/{tripId}/budget 与 GET /api/trips/{tripId}/expenses/budget
   *  是同一个 service 方法，属重复路由；前端在用后者，故移除本 GET，仅保留 PUT。 */
  /** 设置类别预算（EDITABLE_ROLES；amount 空或 0 表示移除该类别预算）。 */
  @RequireTripRole({"owner", "admin", "member"})
  @PutMapping
  public Map<String, Object> setBudgets(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return tripExpenseService.setBudgets(tripId, user.id(), dto);
  }
}
