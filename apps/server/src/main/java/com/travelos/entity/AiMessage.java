package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

@TableName("ai_messages")
public class AiMessage {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("conversation_id")
  private String conversationId;

  private String userId;

  /** user / assistant */
  private String role;

  private String content;

  /** 工具调用与草稿（jsonb，存原始 JSON 文本；assistant 消息可空） */
  private String toolCalls;

  /** 草稿状态：pending（待确认）/ applied（已应用）/ discarded（已放弃）；非草稿消息为空 */
  private String draftStatus;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getConversationId() { return conversationId; }
  public void setConversationId(String conversationId) { this.conversationId = conversationId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public String getToolCalls() { return toolCalls; }
  public void setToolCalls(String toolCalls) { this.toolCalls = toolCalls; }
  public String getDraftStatus() { return draftStatus; }
  public void setDraftStatus(String draftStatus) { this.draftStatus = draftStatus; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
