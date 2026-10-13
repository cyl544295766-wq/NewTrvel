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
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
