package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.Ids;
import com.travelos.common.JsonUtil;
import com.travelos.entity.AuditLog;
import com.travelos.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 敏感操作审计日志：只记录业务上下文，禁止写入密码、token 等敏感信息。 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditLogMapper auditLogMapper;

  public AuditService(AuditLogMapper auditLogMapper) {
    this.auditLogMapper = auditLogMapper;
  }

  public void record(String userId, String tripId, String action, String targetType,
      String targetId, Map<String, Object> detail) {
    try {
      AuditLog auditLog = new AuditLog();
      auditLog.setId(Ids.next());
      auditLog.setUserId(userId);
      auditLog.setTripId(tripId);
      auditLog.setAction(action);
      auditLog.setTargetType(targetType);
      auditLog.setTargetId(targetId);
      auditLog.setDetail(detail == null ? null : JsonUtil.write(detail));
      auditLog.setCreatedAt(new Date());
      auditLogMapper.insert(auditLog);
    } catch (Exception ex) {
      // 审计失败不阻断业务，但要留痕排查
      log.error("Failed to write audit log: action={}, target={}", action, targetId, ex);
    }
  }

  /** 行程审计列表：owner/admin 专用（调用方需已做角色校验），按时间倒序分页。 */
  public Map<String, Object> findByTrip(String tripId, String pageValue, String pageSizeValue) {
    int page = toPositiveInteger(pageValue, 1);
    int pageSize = Math.min(toPositiveInteger(pageSizeValue, 20), 100);
    LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<AuditLog>()
        .eq(AuditLog::getTripId, tripId);
    Long total = auditLogMapper.selectCount(query);
    List<Map<String, Object>> logs = auditLogMapper.selectList(query
            .orderByDesc(AuditLog::getCreatedAt)
            .last("LIMIT " + pageSize + " OFFSET " + (long) (page - 1) * pageSize))
        .stream()
        .map(auditLog -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", auditLog.getId());
          map.put("userId", auditLog.getUserId());
          map.put("tripId", auditLog.getTripId());
          map.put("action", auditLog.getAction());
          map.put("targetType", auditLog.getTargetType());
          map.put("targetId", auditLog.getTargetId());
          map.put("detail", auditLog.getDetail() == null ? null
              : JsonUtil.readObject(auditLog.getDetail()));
          map.put("createdAt", auditLog.getCreatedAt());
          return map;
        })
        .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("logs", logs);
    body.put("total", total);
    body.put("page", page);
    body.put("pageSize", pageSize);
    body.put("totalPages", (int) Math.ceil((double) total / pageSize));
    return body;
  }

  private int toPositiveInteger(String value, int fallback) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
