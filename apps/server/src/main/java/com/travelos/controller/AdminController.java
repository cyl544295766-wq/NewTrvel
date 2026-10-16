package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 超级管理员接口（/api/admin/**）：平台概览、用户管理、CI/部署状态集成。
 * 每个入口都执行 requireSuperAdmin —— 普通用户（role=user）一律 403。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

  private final AdminService adminService;

  public AdminController(AdminService adminService) {
    this.adminService = adminService;
  }

  /** 平台概览：用户/行程/攻略/花费计数 + 最近一次部署。 */
  @GetMapping("/overview")
  public Map<String, Object> overview(@CurrentUser UserContext user) {
    adminService.requireSuperAdmin(user);
    return adminService.overview();
  }

  /** 用户列表（支持按用户名/昵称搜索）。 */
  @GetMapping("/users")
  public Map<String, Object> users(@RequestParam(value = "keyword", required = false) String keyword,
      @CurrentUser UserContext user) {
    adminService.requireSuperAdmin(user);
    return adminService.listUsers(keyword);
  }

  /** 启用 / 禁用账户：{ status: "active" | "disabled" }。 */
  @PatchMapping("/users/{userId}/status")
  public Map<String, Object> updateStatus(@PathVariable String userId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    adminService.requireSuperAdmin(user);
    return adminService.updateStatus(user.id(), userId,
        dto.get("status") == null ? "" : dto.get("status").toString());
  }

  /** 角色调整：{ role: "user" | "super_admin" }。 */
  @PatchMapping("/users/{userId}/role")
  public Map<String, Object> updateRole(@PathVariable String userId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    adminService.requireSuperAdmin(user);
    return adminService.updateRole(user.id(), userId,
        dto.get("role") == null ? "" : dto.get("role").toString());
  }

  /** CI / 部署状态（集成服务器 /ci/ 的 runs.json 数据）。 */
  @GetMapping("/ci/runs")
  public Map<String, Object> ciRuns(@CurrentUser UserContext user) {
    adminService.requireSuperAdmin(user);
    return adminService.ciRuns();
  }
}
