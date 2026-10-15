package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("packing_items")
public class PackingItem {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("packing_list_id")
  private String packingListId;

  private String name;

  private Integer quantity;

  @TableField("is_packed")
  private Boolean isPacked;

  private String notes;

  @TableField("order_index")
  private Integer orderIndex;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getPackingListId() { return packingListId; }
  public void setPackingListId(String packingListId) { this.packingListId = packingListId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public Integer getQuantity() { return quantity; }
  public void setQuantity(Integer quantity) { this.quantity = quantity; }
  public Boolean getIsPacked() { return isPacked; }
  public void setIsPacked(Boolean isPacked) { this.isPacked = isPacked; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public Integer getOrderIndex() { return orderIndex; }
  public void setOrderIndex(Integer orderIndex) { this.orderIndex = orderIndex; }
}
