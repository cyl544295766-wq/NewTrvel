package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/** 好友私信：sender → receiver，read_at 为空表示未读。 */
@TableName("friend_messages")
public class FriendMessage {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("sender_id")
  private String senderId;

  @TableField("receiver_id")
  private String receiverId;

  private String content;

  @TableField("read_at")
  private Date readAt;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getSenderId() { return senderId; }
  public void setSenderId(String senderId) { this.senderId = senderId; }

  public String getReceiverId() { return receiverId; }
  public void setReceiverId(String receiverId) { this.receiverId = receiverId; }

  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }

  public Date getReadAt() { return readAt; }
  public void setReadAt(Date readAt) { this.readAt = readAt; }

  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
