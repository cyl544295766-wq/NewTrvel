package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TodayBriefService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 今日简报（只读）：读取数据 → 规则提醒 → 摘要 → 前端跳转处理，不做任何业务写入。
 * 两个入口共用 {@link TodayBriefService#brief} 同一套业务规则：
 * 自动选择目标行程（首页 / AI 助手用），或显式指定行程（?tripId 路径）。
 */
@RestController
@RequestMapping("/api")
public class TodayBriefController {

  private final TodayBriefService todayBriefService;

  public TodayBriefController(TodayBriefService todayBriefService) {
    this.todayBriefService = todayBriefService;
  }

  /** 生成/刷新今日简报。body：{tripId?, date?, refresh?}，tripId 缺省时按规则自动选择行程。 */
  @PostMapping("/ai/today-brief")
  public Map<String, Object> brief(@RequestBody(required = false) Map<String, Object> dto,
      @CurrentUser UserContext user) {
    return todayBriefService.brief(user.id(), stringOf(dto, "tripId"), stringOf(dto, "date"),
        Boolean.TRUE.equals(dto == null ? null : dto.get("refresh")), true);
  }

  /** 指定行程的今日简报（与自动选择共用同一实现；权限走行程成员校验）。 */
  @PostMapping("/trips/{tripId}/ai/today-brief")
  public Map<String, Object> briefOfTrip(@PathVariable String tripId,
      @RequestBody(required = false) Map<String, Object> dto, @CurrentUser UserContext user) {
    return todayBriefService.brief(user.id(), tripId, stringOf(dto, "date"),
        Boolean.TRUE.equals(dto == null ? null : dto.get("refresh")), true);
  }

  private String stringOf(Map<String, Object> dto, String key) {
    if (dto == null || dto.get(key) == null) {
      return null;
    }
    String value = dto.get(key).toString().trim();
    return value.isEmpty() ? null : value;
  }
}
