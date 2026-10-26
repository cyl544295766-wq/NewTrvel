package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/** AI 旅行偏好记忆（口味/同行人群/预算档/节奏等，AI 助手推荐与规划时遵守）。 */
@TableName("ai_preferences")
public class AiPreference {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("user_id")
  private String userId;

  @TableField("content")
  private String content;

  @TableField("category")
  private String category;

  @TableField("status")
  private String status;

  @TableField("created_at")
  private Date createdAt;

  @TableField("updated_at")
  private Date updatedAt;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Date createdAt) {
    this.createdAt = createdAt;
  }

  public Date getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Date updatedAt) {
    this.updatedAt = updatedAt;
  }
}
