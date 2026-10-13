package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("audit_logs")
public class AuditLog {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  private String userId;

  private String tripId;

  private String action;

  private String targetType;

  private String targetId;

  /** jsonb 列，存原始 JSON 文本；不得包含密码、token 等敏感信息 */
  private String detail;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getTripId() { return tripId; }
  public void setTripId(String tripId) { this.tripId = tripId; }
  public String getAction() { return action; }
  public void setAction(String action) { this.action = action; }
  public String getTargetType() { return targetType; }
  public void setTargetType(String targetType) { this.targetType = targetType; }
  public String getTargetId() { return targetId; }
  public void setTargetId(String targetId) { this.targetId = targetId; }
  public String getDetail() { return detail; }
  public void setDetail(String detail) { this.detail = detail; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
