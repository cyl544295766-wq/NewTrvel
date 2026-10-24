package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/weather")
public class WeatherController {

  private final WeatherService weatherService;

  public WeatherController(WeatherService weatherService) {
    this.weatherService = weatherService;
  }

  @GetMapping
  public Map<String, Object> tripWeather(@PathVariable String tripId,
      @CurrentUser UserContext user) {
    return weatherService.getTripWeather(tripId, user.id());
  }

  /** P1-4 冗余路由下线（2026-10-24）：
   *  原 GET /{tripDayId} 单日预报与整程预报返回同一份数据，前端从未接入
   *  （见 docs/architecture/未接入清单.md），故移除，仅保留整程预报。 */
}
