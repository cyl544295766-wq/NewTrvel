package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("packing_lists")
public class PackingList {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("trip_id")
  private String tripId;

  private String name;

  private String category;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTripId() { return tripId; }
  public void setTripId(String tripId) { this.tripId = tripId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
