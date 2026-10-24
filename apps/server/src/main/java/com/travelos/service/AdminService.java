package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.entity.Trip;
import com.travelos.entity.TripJournal;
import com.travelos.entity.AiMessage;
import com.travelos.entity.TripExpense;
import com.travelos.entity.User;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超级管理员能力：平台概览、用户管理、CI/部署状态集成。
 * 所有入口必须先经过 requireSuperAdmin（普通用户一律 403）。
 */
@Service
public class AdminService {

  private final UserMapper userMapper;
  private final TripMapper tripMapper;
  private final TripJournalMapper journalMapper;
  private final TripExpenseMapper expenseMapper;
  private final AiMessageMapper aiMessageMapper;
  private final UserService userService;

  /** 服务器本地 CI 记录文件（与静态站 /ci/ 同源；本机为部署机时可直接读盘） */
  @Value("${travelos.ci.runs-path:}")
  private String ciRunsPath;

  /** 远端 CI 数据基址（本地文件不存在时回退 HTTP 拉取） */
  @Value("${travelos.ci.base-url:http://140.143.166.155/ci}")
  private String ciBaseUrl;

  public AdminService(UserMapper userMapper, TripMapper tripMapper,
      TripJournalMapper journalMapper, TripExpenseMapper expenseMapper,
      AiMessageMapper aiMessageMapper, UserService userService) {
    this.userMapper = userMapper;
    this.tripMapper = tripMapper;
    this.journalMapper = journalMapper;
    this.expenseMapper = expenseMapper;
    this.aiMessageMapper = aiMessageMapper;
    this.userService = userService;
  }

  /** 超级管理员守卫：非 super_admin 一律 403。 */
  public void requireSuperAdmin(UserContext user) {
    if (user == null || !user.isSuperAdmin()) {
      throw ApiException.forbidden("需要超级管理员权限");
    }
  }

  // ==================== 平台概览 ====================

  public Map<String, Object> overview() {
    // 平台级统计需绕过「行程成员」数据范围过滤（超管看全局数字）
    return bypassed(() -> overviewInner());
  }

  private Map<String, Object> overviewInner() {
    Long users = userMapper.selectCount(null);
    Long activeUsers = userMapper.selectCount(new LambdaQueryWrapper<User>()
        .eq(User::getStatus, "active"));
    Long trips = tripMapper.selectCount(new LambdaQueryWrapper<Trip>()
        .isNull(Trip::getDeletedAt));
    Long journals = journalMapper.selectCount(null);
    // 今日 AI 请求次数 = 今日新增的用户消息数（每次发送触发一次 AI 调用）
    java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    cal.set(java.util.Calendar.MILLISECOND, 0);
    java.util.Date todayStart = cal.getTime();
    Long aiRequestsToday = aiMessageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
        .eq(AiMessage::getRole, "user")
        .ge(AiMessage::getCreatedAt, todayStart));

    Map<String, Object> ci = ciRuns();
    List<Map<String, Object>> runs = (List<Map<String, Object>>) ci.get("runs");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("users", users);
    body.put("activeUsers", activeUsers);
    body.put("trips", trips);
    body.put("journals", journals);
    body.put("aiRequestsToday", aiRequestsToday);
    body.put("ciTotalRuns", ci.get("total"));
    body.put("ciLastRun", runs.isEmpty() ? null : runs.get(0));
    return body;
  }

  // ==================== 用户管理 ====================

  public Map<String, Object> listUsers(String keyword) {
    LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
        .orderByDesc(User::getCreatedAt);
    if (keyword != null && !keyword.isBlank()) {
      String kw = keyword.trim();
      wrapper.and(q -> q.like(User::getUsername, kw).or().like(User::getDisplayName, kw));
    }
    wrapper.last("LIMIT 200");
    List<Map<String, Object>> items = new ArrayList<>();
    for (User u : userMapper.selectList(wrapper)) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("id", u.getId());
      map.put("username", u.getUsername());
      map.put("displayName", u.getDisplayName());
      map.put("role", u.getRole() == null ? "user" : u.getRole());
      map.put("status", u.getStatus());
      map.put("lastLoginAt", u.getLastLoginAt());
      map.put("createdAt", u.getCreatedAt());
      items.add(map);
    }
    return Map.of("users", items);
  }

  /** 启用 / 禁用账户（禁用后 AuthInterceptor 立即拒绝其请求与登录）。 */
  public Map<String, Object> updateStatus(String operatorId, String targetId, String status) {
    if (!"active".equals(status) && !"disabled".equals(status)) {
      throw ApiException.badRequest("状态仅支持 active / disabled");
    }
    User target = requireTarget(operatorId, targetId);
    target.setStatus(status);
    if ("disabled".equals(status)) {
      // P2-1：禁用时刷新令牌吊销水位——即使后续被重新启用，禁用前签发的令牌也不会复活
      target.setTokenValidAfter(new Date());
    }
    target.setUpdatedAt(new Date());
    userMapper.updateById(target);
    return Map.of("success", true, "message",
        "disabled".equals(status) ? "已禁用账户「" + target.getUsername() + "」" : "已启用账户「" + target.getUsername() + "」");
  }

  // ==================== CI / 部署状态集成 ====================

  /** 目标用户校验：不能操作自己、用户须存在、不能修改其他超级管理员。 */
  private User requireTarget(String operatorId, String targetId) {
    if (operatorId.equals(targetId)) {
      throw ApiException.badRequest("不能修改自己的账户状态/角色");
    }
    User target = userMapper.selectById(targetId);
    if (target == null) {
      throw ApiException.notFound("用户不存在");
    }
    if ("super_admin".equals(target.getRole())) {
      throw ApiException.forbidden("不能修改其他超级管理员的账户");
    }
    return target;
  }

  /**
   * 读取部署服务器的 CI 运行记录（/ci/runs.json）：
   * 优先读服务器本地文件（部署机同盘），失败回退 HTTP 拉取 /ci/ 服务。
   * 返回 { runs, total, successCount, successRate, lastRun }。
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> ciRuns() {
    String raw = readRunsJson();
    if (raw == null || raw.isBlank()) {
      throw ApiException.serviceUnavailable("CI 数据暂不可用（服务器 /ci/runs.json 读取失败）");
    }
    List<Map<String, Object>> runs;
    try {
      com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
      runs = om.readValue(raw, List.class);
    } catch (Exception ex) {
      throw ApiException.serviceUnavailable("CI 数据解析失败");
    }
    long total = runs.size();
    long success = runs.stream().filter(r -> "success".equals(r.get("conclusion"))).count();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("runs", runs);
    body.put("total", total);
    body.put("successCount", success);
    body.put("successRate", total == 0 ? null : Math.round(success * 1000.0 / total) / 10.0);
    body.put("lastRun", runs.isEmpty() ? null : runs.get(0));
    return body;
  }

  /** 绕过行程数据范围过滤执行查询（平台级统计）。 */
  private <T> T bypassed(java.util.function.Supplier<T> action) {
    boolean prev = com.travelos.common.TripScopeContext.isBypassed();
    com.travelos.common.TripScopeContext.setBypassed(true);
    try {
      return action.get();
    } finally {
      com.travelos.common.TripScopeContext.setBypassed(prev);
    }
  }

  private String readRunsJson() {
    // 1) 服务器本地文件（生产同盘直读，最快最稳）
    if (ciRunsPath != null && !ciRunsPath.isBlank()) {
      try {
        Path p = Path.of(ciRunsPath);
        if (Files.exists(p)) {
          return Files.readString(p);
        }
      } catch (Exception ignored) {
        // 读不到则回退 HTTP
      }
    }
    // 2) HTTP 拉取 /ci/ 服务数据（本地开发等跨机场景）
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(ciBaseUrl + "/runs.json"))
          .timeout(Duration.ofSeconds(8))
          .GET().build();
      HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200) {
        return resp.body();
      }
    } catch (Exception ignored) {
      // 两种来源都失败时由上层返回友好 503
    }
    return null;
  }
}
