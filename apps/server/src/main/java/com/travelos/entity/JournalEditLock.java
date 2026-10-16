package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/** 攻略编辑互斥锁：journal_id 主键，同一时间有且只有一人可编辑。 */
@TableName("journal_edit_locks")
public class JournalEditLock {

  @TableId("journal_id")
  private String journalId;

  @TableField("holder_id")
  private String holderId;

  @TableField("holder_name")
  private String holderName;

  @TableField("acquired_at")
  private Date acquiredAt;

  @TableField("expires_at")
  private Date expiresAt;

  public String getJournalId() { return journalId; }
  public void setJournalId(String journalId) { this.journalId = journalId; }

  public String getHolderId() { return holderId; }
  public void setHolderId(String holderId) { this.holderId = holderId; }

  public String getHolderName() { return holderName; }
  public void setHolderName(String holderName) { this.holderName = holderName; }

  public Date getAcquiredAt() { return acquiredAt; }
  public void setAcquiredAt(Date acquiredAt) { this.acquiredAt = acquiredAt; }

  public Date getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
