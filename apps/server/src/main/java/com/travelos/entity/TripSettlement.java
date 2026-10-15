package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("trip_settlements")
public class TripSettlement {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  private String tripId;

  /** 结算方案快照（jsonb，存原始 JSON 文本） */
  private String snapshot;

  /** 状态：confirmed */
  private String status;

  private String confirmedBy;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTripId() { return tripId; }
  public void setTripId(String tripId) { this.tripId = tripId; }
  public String getSnapshot() { return snapshot; }
  public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getConfirmedBy() { return confirmedBy; }
  public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
