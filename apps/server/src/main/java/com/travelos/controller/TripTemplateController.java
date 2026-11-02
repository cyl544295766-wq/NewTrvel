package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 行程模板（P0-6）：内置模板清单 + 一键应用到行程（生成整套打包清单与天标题建议）。
 */
@RestController
public class TripTemplateController {

  private final TripTemplateService tripTemplateService;

  public TripTemplateController(TripTemplateService tripTemplateService) {
    this.tripTemplateService = tripTemplateService;
  }

  @GetMapping("/api/templates")
  public Map<String, Object> list() {
    return Map.of("templates", tripTemplateService.list());
  }

  @PostMapping("/api/trips/{tripId}/apply-template")
  public Map<String, Object> apply(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    String templateId = dto.get("templateId") == null ? "" : dto.get("templateId").toString();
    return tripTemplateService.apply(tripId, user.id(), templateId);
  }
}
