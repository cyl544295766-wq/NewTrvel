package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 行程中的一段驾车路线（对应 App 端 RouteSegment）。
 *
 * <p>points 存整条折线的 JSON，用于在 App 端还原地图上的路线形状；
 * 距离/时长单独成列，便于网页端直接展示而不必解析 JSON。</p>
 */
@TableName("trip_legs")
public class TripLeg {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  @TableField("segment_index")
  private Integer segmentIndex;

  @TableField("distance_meters")
  private Double distanceMeters;

  @TableField("duration_seconds")
  private Double durationSeconds;

  @TableField("points_json")
  private String pointsJson;

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
  public Integer getSegmentIndex() { return segmentIndex; }
  public void setSegmentIndex(Integer segmentIndex) { this.segmentIndex = segmentIndex; }
  public Double getDistanceMeters() { return distanceMeters; }
  public void setDistanceMeters(Double distanceMeters) { this.distanceMeters = distanceMeters; }
  public Double getDurationSeconds() { return durationSeconds; }
  public void setDurationSeconds(Double durationSeconds) { this.durationSeconds = durationSeconds; }
  public String getPointsJson() { return pointsJson; }
  public void setPointsJson(String pointsJson) { this.pointsJson = pointsJson; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
