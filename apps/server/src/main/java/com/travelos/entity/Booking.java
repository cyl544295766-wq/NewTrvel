package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.Date;

/** 预订（机票/酒店/火车/门票/租车等），类型特有字段存 extra(jsonb)。 */
@TableName("bookings")
public class Booking {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  @TableField("trip_place_id")
  private String tripPlaceId;

  /** flight / hotel / train / ticket / other */
  private String type;

  private String title;

  @TableField("start_time")
  private Date startTime;

  @TableField("end_time")
  private Date endTime;

  @TableField("order_no")
  private String orderNo;

  @TableField("confirm_code")
  private String confirmCode;

  private BigDecimal amount;

  private String currency;

  @TableField("attachment_url")
  private String attachmentUrl;

  /** 提醒提前量（分钟），锚点为 start_time */
  @TableField("reminder_minutes")
  private Integer reminderMinutes;

  @TableField("reminder_enabled")
  private Boolean reminderEnabled;

  /** 提醒发送时间：非空表示已发送，保证提醒引擎幂等 */
  @TableField("reminder_sent_at")
  private Date reminderSentAt;

  /** jsonb：类型特有字段（航班号/舱位/酒店名/车次/场次等） */
  private String extra;

  /** 一键核销：转为费用后记录 trip_expenses.id；非空表示已入账（退出预估占用） */
  @TableField("expense_id")
  private String expenseId;

  @TableField("creator_id")
  private String creatorId;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTripId() { return tripId; }
  public void setTripId(String tripId) { this.tripId = tripId; }
  public String getTripDayId() { return tripDayId; }
  public void setTripDayId(String tripDayId) { this.tripDayId = tripDayId; }
  public String getTripPlaceId() { return tripPlaceId; }
  public void setTripPlaceId(String tripPlaceId) { this.tripPlaceId = tripPlaceId; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public Date getStartTime() { return startTime; }
  public void setStartTime(Date startTime) { this.startTime = startTime; }
  public Date getEndTime() { return endTime; }
  public void setEndTime(Date endTime) { this.endTime = endTime; }
  public String getOrderNo() { return orderNo; }
  public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
  public String getConfirmCode() { return confirmCode; }
  public void setConfirmCode(String confirmCode) { this.confirmCode = confirmCode; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public String getAttachmentUrl() { return attachmentUrl; }
  public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }
  public Integer getReminderMinutes() { return reminderMinutes; }
  public void setReminderMinutes(Integer reminderMinutes) { this.reminderMinutes = reminderMinutes; }
  public Boolean getReminderEnabled() { return reminderEnabled; }
  public void setReminderEnabled(Boolean reminderEnabled) { this.reminderEnabled = reminderEnabled; }
  public Date getReminderSentAt() { return reminderSentAt; }
  public void setReminderSentAt(Date reminderSentAt) { this.reminderSentAt = reminderSentAt; }
  public String getExtra() { return extra; }
  public void setExtra(String extra) { this.extra = extra; }
  public String getExpenseId() { return expenseId; }
  public void setExpenseId(String expenseId) { this.expenseId = expenseId; }
  public String getCreatorId() { return creatorId; }
  public void setCreatorId(String creatorId) { this.creatorId = creatorId; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
