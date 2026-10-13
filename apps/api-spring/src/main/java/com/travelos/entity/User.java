package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("users")
public class User {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  private String username;

  @TableField("display_name")
  private String displayName;

  @TableField("password_hash")
  private String passwordHash;

  @TableField("avatar_url")
  private String avatarUrl;

  private String status;

  @TableField("last_login_at")
  private Date lastLoginAt;

  /** 注销时间：status='deleted' 时记录，保留 30 天后由定时任务物理清理 */
  @TableField("deleted_at")
  private Date deletedAt;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public String getAvatarUrl() { return avatarUrl; }
  public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Date getLastLoginAt() { return lastLoginAt; }
  public void setLastLoginAt(Date lastLoginAt) { this.lastLoginAt = lastLoginAt; }
  public Date getDeletedAt() { return deletedAt; }
  public void setDeletedAt(Date deletedAt) { this.deletedAt = deletedAt; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
