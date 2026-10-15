package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.Date;

@TableName("trip_expense_shares")
public class TripExpenseShare {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("expense_id")
  private String expenseId;

  @TableField("user_id")
  private String userId;

  @TableField("share_amount")
  private BigDecimal shareAmount;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getExpenseId() { return expenseId; }
  public void setExpenseId(String expenseId) { this.expenseId = expenseId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public BigDecimal getShareAmount() { return shareAmount; }
  public void setShareAmount(BigDecimal shareAmount) { this.shareAmount = shareAmount; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
