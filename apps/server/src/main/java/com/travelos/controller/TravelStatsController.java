package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TravelStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class TravelStatsController {

  private final TravelStatsService statsService;

  public TravelStatsController(TravelStatsService statsService) {
    this.statsService = statsService;
  }

  @GetMapping("/overview")
  public Map<String, Object> overview(@CurrentUser UserContext user) {
    return statsService.getOverview(user.id());
  }

  /** 足迹地图：按目的地聚合用户全部行程（城市/次数/天数/坐标）。 */
  @GetMapping("/footprints")
  public Map<String, Object> footprints(@CurrentUser UserContext user) {
    return statsService.footprints(user.id());
  }

  @GetMapping("/yearly")
  public Map<String, Object> yearly(@RequestParam(value = "year", required = false) String year,
      @CurrentUser UserContext user) {
    return statsService.getYearly(user.id(), year);
  }

  @GetMapping("/monthly")
  public Map<String, Object> monthly(@RequestParam(value = "year", required = false) String year,
      @CurrentUser UserContext user) {
    return statsService.getMonthly(user.id(), year);
  }

  @GetMapping("/expense-categories")
  public Map<String, Object> expenseCategories(@CurrentUser UserContext user) {
    return statsService.getExpenseCategories(user.id());
  }

  @GetMapping("/budgets")
  public Map<String, Object> budgets(@CurrentUser UserContext user) {
    return statsService.getBudgetOverview(user.id());
  }

  @GetMapping("/settlement-suggestions")
  public Map<String, Object> settlementSuggestions(@CurrentUser UserContext user) {
    return statsService.getSettlementSuggestions(user.id());
  }
}
