package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.util.Date;

@TableName("trips")
public class Trip {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  private String title;

  private String description;

  private String destination;

  @TableField("start_date")
  private Date startDate;

  @TableField("end_date")
  private Date endDate;

  private String status;

  @TableField("cover_image_url")
  private String coverImageUrl;

  private BigDecimal budget;

  @TableField("owner_id")
  private String ownerId;

  @TableField("is_favorite")
  private Boolean isFavorite;

  @TableField("archived_at")
  private Date archivedAt;

  @TableField("deleted_at")
  private Date deletedAt;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getDestination() { return destination; }
  public void setDestination(String destination) { this.destination = destination; }
  public Date getStartDate() { return startDate; }
  public void setStartDate(Date startDate) { this.startDate = startDate; }
  public Date getEndDate() { return endDate; }
  public void setEndDate(Date endDate) { this.endDate = endDate; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getCoverImageUrl() { return coverImageUrl; }
  public void setCoverImageUrl(String coverImageUrl) { this.coverImageUrl = coverImageUrl; }
  public BigDecimal getBudget() { return budget; }
  public void setBudget(BigDecimal budget) { this.budget = budget; }
  public String getOwnerId() { return ownerId; }
  public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
  public Boolean getIsFavorite() { return isFavorite; }
  public void setIsFavorite(Boolean isFavorite) { this.isFavorite = isFavorite; }
  public Date getArchivedAt() { return archivedAt; }
  public void setArchivedAt(Date archivedAt) { this.archivedAt = archivedAt; }
  public Date getDeletedAt() { return deletedAt; }
  public void setDeletedAt(Date deletedAt) { this.deletedAt = deletedAt; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
