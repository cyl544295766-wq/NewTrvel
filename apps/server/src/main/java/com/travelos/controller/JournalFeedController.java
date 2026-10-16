package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripJournalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 攻略（游记）跨行程访问入口：
 *   GET /api/journals/feed          当前用户可见的攻略流（自己的全部 + 他人按可见性规则）
 *   GET /api/journals/{journalId}   单篇详情（按可见性规则校验，不要求行程成员身份）
 * 行程内管理仍走 /api/trips/{tripId}/journals。
 */
@RestController
@RequestMapping("/api/journals")
public class JournalFeedController {

  private final TripJournalService journalService;

  public JournalFeedController(TripJournalService journalService) {
    this.journalService = journalService;
  }

  @GetMapping("/feed")
  public Map<String, Object> feed(@CurrentUser UserContext user) {
    return journalService.feed(user.id());
  }

  @GetMapping("/{journalId}")
  public Map<String, Object> detail(@PathVariable String journalId, @CurrentUser UserContext user) {
    return journalService.findGlobally(journalId, user.id());
  }
}
