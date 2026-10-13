package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.Date;

@TableName("trip_places")
public class TripPlace {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  private String name;

  private String type;

  private String address;

  private BigDecimal latitude;

  private BigDecimal longitude;

  @TableField("start_time")
  private Date startTime;

  @TableField("end_time")
  private Date endTime;

  private String notes;

  @TableField("sort_order")
  private Integer sortOrder;

  @TableField("is_completed")
  private Boolean isCompleted;

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
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getAddress() { return address; }
  public void setAddress(String address) { this.address = address; }
  public BigDecimal getLatitude() { return latitude; }
  public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
  public BigDecimal getLongitude() { return longitude; }
  public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
  public Date getStartTime() { return startTime; }
  public void setStartTime(Date startTime) { this.startTime = startTime; }
  public Date getEndTime() { return endTime; }
  public void setEndTime(Date endTime) { this.endTime = endTime; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Boolean getIsCompleted() { return isCompleted; }
  public void setIsCompleted(Boolean isCompleted) { this.isCompleted = isCompleted; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
