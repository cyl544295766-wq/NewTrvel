package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("travel_documents")
public class TravelDocument {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  @TableField("trip_place_id")
  private String tripPlaceId;

  private String type;

  private String title;

  private String url;

  private String notes;

  @TableField("expired_at")
  private Date expiredAt;

  @TableField("is_reminder")
  private Boolean isReminder;

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
  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public Date getExpiredAt() { return expiredAt; }
  public void setExpiredAt(Date expiredAt) { this.expiredAt = expiredAt; }
  public Boolean getIsReminder() { return isReminder; }
  public void setIsReminder(Boolean isReminder) { this.isReminder = isReminder; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
