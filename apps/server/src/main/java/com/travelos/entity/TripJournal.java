package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("trip_journals")
public class TripJournal {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  @TableField("trip_day_id")
  private String tripDayId;

  @TableField("trip_place_id")
  private String tripPlaceId;

  private String title;

  private String content;

  @TableField("is_draft")
  private Boolean isDraft;

  /** 可见性：public 公开 / friends 好友可见 / private 私有 / specified 指定好友可见 */
  private String visibility;

  private String mood;

  /** 作者（数据隔离行级属主） */
  @TableField("author_id")
  private String authorId;

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
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public String getVisibility() { return visibility; }
  public void setVisibility(String visibility) { this.visibility = visibility; }

  public Boolean getIsDraft() { return isDraft; }
  public void setIsDraft(Boolean isDraft) { this.isDraft = isDraft; }
  public String getMood() { return mood; }
  public void setMood(String mood) { this.mood = mood; }
  public String getAuthorId() { return authorId; }
  public void setAuthorId(String authorId) { this.authorId = authorId; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
