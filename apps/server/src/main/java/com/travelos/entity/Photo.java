package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("photos")
public class Photo {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  @TableField("trip_place_id")
  private String tripPlaceId;

  private String url;

  private String caption;

  @TableField("shot_at")
  private Date shotAt;

  @TableField("is_cover")
  private Boolean isCover;

  /** 上传者（数据隔离行级属主） */
  @TableField("uploader_id")
  private String uploaderId;

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
  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }
  public String getCaption() { return caption; }
  public void setCaption(String caption) { this.caption = caption; }
  public Date getShotAt() { return shotAt; }
  public void setShotAt(Date shotAt) { this.shotAt = shotAt; }
  public Boolean getIsCover() { return isCover; }
  public void setIsCover(Boolean isCover) { this.isCover = isCover; }
  public String getUploaderId() { return uploaderId; }
  public void setUploaderId(String uploaderId) { this.uploaderId = uploaderId; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
