package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.ItineraryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * App 端「我的行程」同步接口。
 *
 * <p>GET 返回整份快照（天/地点/路线/花销），PUT 用请求体整份覆盖。
 * 网页端继续使用 /api/trips 系列接口读写同一批表，两端因此共享同一份数据。</p>
 */
@RestController
@RequestMapping("/api/itinerary")
public class ItineraryController {

  private final ItineraryService itineraryService;

  public ItineraryController(ItineraryService itineraryService) {
    this.itineraryService = itineraryService;
  }

  @GetMapping
  public Map<String, Object> get(@CurrentUser UserContext user) {
    return itineraryService.snapshot(user.id());
  }

  @PutMapping
  public Map<String, Object> replace(@CurrentUser UserContext user,
      @RequestBody ItineraryService.ItineraryPayload payload) {
    return itineraryService.replace(user.id(), payload);
  }
}
