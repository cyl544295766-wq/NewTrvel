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

  @GetMapping("/{tripDayId}")
  public Map<String, Object> tripDayWeather(@PathVariable String tripId,
      @PathVariable String tripDayId, @CurrentUser UserContext user) {
    return weatherService.getTripDayWeather(tripId, tripDayId, user.id());
  }
}
