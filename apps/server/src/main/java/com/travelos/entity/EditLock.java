package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 编辑互斥锁（天级作用域）：
 *  - scope_id = 行程天 ID（攻略无天时为 "journal:{id}"）；
 *  - 主键天然互斥：同一作用域同一时间只有一条持锁记录；
 *  - TTL 过期可被他人抢占，编辑器异常由过期兜底。
 */
@TableName("edit_locks")
public class EditLock {

  @TableId("scope_id")
  private String scopeId;

  @TableField("holder_id")
  private String holderId;

  @TableField("holder_name")
  private String holderName;

  @TableField("acquired_at")
  private Date acquiredAt;

  @TableField("expires_at")
  private Date expiresAt;

  public String getScopeId() { return scopeId; }
  public void setScopeId(String scopeId) { this.scopeId = scopeId; }

  public String getHolderId() { return holderId; }
  public void setHolderId(String holderId) { this.holderId = holderId; }

  public String getHolderName() { return holderName; }
  public void setHolderName(String holderName) { this.holderName = holderName; }

  public Date getAcquiredAt() { return acquiredAt; }
  public void setAcquiredAt(Date acquiredAt) { this.acquiredAt = acquiredAt; }

  public Date getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
