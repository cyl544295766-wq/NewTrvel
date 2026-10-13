package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.Date;

@TableName("trip_weather")
public class TripWeather {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  private Date date;

  private String destination;

  private String condition;

  @TableField("temp_high")
  private BigDecimal tempHigh;

  @TableField("temp_low")
  private BigDecimal tempLow;

  @TableField("rain_chance")
  private Integer rainChance;

  @TableField("icon_code")
  private String iconCode;

  @TableField("fetched_at")
  private Date fetchedAt;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTripId() { return tripId; }
  public void setTripId(String tripId) { this.tripId = tripId; }
  public String getTripDayId() { return tripDayId; }
  public void setTripDayId(String tripDayId) { this.tripDayId = tripDayId; }
  public Date getDate() { return date; }
  public void setDate(Date date) { this.date = date; }
  public String getDestination() { return destination; }
  public void setDestination(String destination) { this.destination = destination; }
  public String getCondition() { return condition; }
  public void setCondition(String condition) { this.condition = condition; }
  public BigDecimal getTempHigh() { return tempHigh; }
  public void setTempHigh(BigDecimal tempHigh) { this.tempHigh = tempHigh; }
  public BigDecimal getTempLow() { return tempLow; }
  public void setTempLow(BigDecimal tempLow) { this.tempLow = tempLow; }
  public Integer getRainChance() { return rainChance; }
  public void setRainChance(Integer rainChance) { this.rainChance = rainChance; }
  public String getIconCode() { return iconCode; }
  public void setIconCode(String iconCode) { this.iconCode = iconCode; }
  public Date getFetchedAt() { return fetchedAt; }
  public void setFetchedAt(Date fetchedAt) { this.fetchedAt = fetchedAt; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
