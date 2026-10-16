package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 好友关系（单向记录）：user_id 添加了 friend_id。
 * 「互为好友」= 双向记录同时存在；账本（行程）邀请要求互为好友。
 */
@TableName("user_friends")
public class UserFriend {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("user_id")
  private String userId;

  @TableField("friend_id")
  private String friendId;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getFriendId() { return friendId; }
  public void setFriendId(String friendId) { this.friendId = friendId; }

  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
