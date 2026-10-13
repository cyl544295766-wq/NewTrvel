package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("trip_journal_photos")
public class TripJournalPhoto {

  @TableField("journal_id")
  private String journalId;

  @TableField("photo_id")
  private String photoId;

  @TableField("order_index")
  private Integer orderIndex;

  public String getJournalId() { return journalId; }
  public void setJournalId(String journalId) { this.journalId = journalId; }
  public String getPhotoId() { return photoId; }
  public void setPhotoId(String photoId) { this.photoId = photoId; }
  public Integer getOrderIndex() { return orderIndex; }
  public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }
}
