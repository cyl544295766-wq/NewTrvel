package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("notifications")
public class Notification {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("user_id")
  private String userId;

  private String type;

  private String title;

  private String content;

  private String link;

  /** jsonb column, stored as raw JSON text */
  private String metadata;

  @TableField("is_read")
  private Boolean isRead;

  private String priority;

  @TableField("read_at")
  private Date readAt;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField("expires_at")
  private Date expiresAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public String getLink() { return link; }
  public void setLink(String link) { this.link = link; }
  public String getMetadata() { return metadata; }
  public void setMetadata(String metadata) { this.metadata = metadata; }
  public Boolean getIsRead() { return isRead; }
  public void setIsRead(Boolean isRead) { this.isRead = isRead; }
  public String getPriority() { return priority; }
  public void setPriority(String priority) { this.priority = priority; }
  public Date getReadAt() { return readAt; }
  public void setReadAt(Date readAt) { this.readAt = readAt; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
