package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.trip.RequireTripRole;
import com.travelos.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 行程审计日志查询：仅 owner/admin 可见，普通成员 403。 */
@RestController
@RequestMapping("/api/trips/{tripId}/audit-logs")
public class AuditController {

  private final AuditService auditService;

  public AuditController(AuditService auditService) {
    this.auditService = auditService;
  }

  @GetMapping
  @RequireTripRole({"owner", "admin"})
  public Map<String, Object> findByTrip(
      @PathVariable String tripId,
      @RequestParam(value = "page", required = false) String page,
      @RequestParam(value = "pageSize", required = false) String pageSize,
      @CurrentUser UserContext user) {
    return auditService.findByTrip(tripId, page, pageSize);
  }
}
