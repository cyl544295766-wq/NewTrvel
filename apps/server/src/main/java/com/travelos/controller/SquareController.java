package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripSquareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 攻略广场入口：
 *   GET  /api/square?scope=public|friends&keyword=&destination=&sort=latest|hot|copies&page=&pageSize=
 *   GET  /api/square/facets            目的地筛选项
 *   GET  /api/square/{tripId}          攻略详情（行程天 + 地点概览，浏览量 +1）
 *   POST /api/square/{tripId}/copy     复制为我的行程（副本默认私有）
 */
@RestController
@RequestMapping("/api/square")
public class SquareController {

  private final TripSquareService squareService;

  public SquareController(TripSquareService squareService) {
    this.squareService = squareService;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam Map<String, Object> query,
      @CurrentUser UserContext user) {
    return squareService.square(user.id(), query);
  }

  @GetMapping("/facets")
  public Map<String, Object> facets() {
    return squareService.facets();
  }

  @GetMapping("/{tripId}")
  public Map<String, Object> detail(@PathVariable String tripId, @CurrentUser UserContext user) {
    return squareService.detail(tripId, user.id());
  }

  @PostMapping("/{tripId}/copy")
  public Map<String, Object> copy(@PathVariable String tripId, @CurrentUser UserContext user) {
    return squareService.copy(tripId, user.id());
  }
}
