package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/** 攻略（游记）指定好友可见名单：visibility=specified 时生效。 */
@TableName("trip_journal_audience")
public class TripJournalAudience {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("journal_id")
  private String journalId;

  @TableField("user_id")
  private String userId;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getJournalId() { return journalId; }
  public void setJournalId(String journalId) { this.journalId = journalId; }

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
