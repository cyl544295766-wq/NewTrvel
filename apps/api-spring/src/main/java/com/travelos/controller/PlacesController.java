package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.PlacesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

  private final PlacesService placesService;

  public PlacesController(PlacesService placesService) {
    this.placesService = placesService;
  }

  @GetMapping("/suggestions")
  public Map<String, Object> suggestions(
      @RequestParam(value = "keyword", required = false) String keyword,
      @RequestParam(value = "city", required = false) String city,
      @RequestParam(value = "limit", required = false) Integer limit,
      @CurrentUser UserContext user) {
    return placesService.getSuggestions(keyword, city, limit);
  }
}
