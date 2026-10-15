package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.Date;

@TableName("trip_expenses")
public class TripExpense {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("payer_user_id")
  private String payerUserId;

  private String title;

  private BigDecimal amount;

  private String currency;

  private String category;

  @TableField("spent_at")
  private Date spentAt;

  private String notes;

  /** 小票图片（data URL 或外链，≤5MB） */
  @TableField("receipt_url")
  private String receiptUrl;

  /** 分摊方式：equal（均分，默认）/ ratio（按比例）/ amount（按金额） */
  @TableField("split_type")
  private String splitType;

  /** 所属行程天（快速记账按 spent_at 自动匹配，用户可改） */
  @TableField("trip_day_id")
  private String tripDayId;

  @TableField("is_settled")
  private Boolean isSettled;

  @TableField("settled_at")
  private Date settledAt;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTripId() { return tripId; }
  public void setTripId(String tripId) { this.tripId = tripId; }
  public String getPayerUserId() { return payerUserId; }
  public void setPayerUserId(String payerUserId) { this.payerUserId = payerUserId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public Date getSpentAt() { return spentAt; }
  public void setSpentAt(Date spentAt) { this.spentAt = spentAt; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public String getReceiptUrl() { return receiptUrl; }
  public void setReceiptUrl(String receiptUrl) { this.receiptUrl = receiptUrl; }
  public String getSplitType() { return splitType; }
  public void setSplitType(String splitType) { this.splitType = splitType; }
  public String getTripDayId() { return tripDayId; }
  public void setTripDayId(String tripDayId) { this.tripDayId = tripDayId; }
  public Boolean getIsSettled() { return isSettled; }
  public void setIsSettled(Boolean isSettled) { this.isSettled = isSettled; }
  public Date getSettledAt() { return settledAt; }
  public void setSettledAt(Date settledAt) { this.settledAt = settledAt; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
