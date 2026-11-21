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

  /** 草稿状态：pending（待确认）/ applying（应用中，租约）/ applied（已应用）/ discarded（已放弃）；非草稿消息为空 */
  private String draftStatus;

  /** 草稿应用执行令牌（fencing：完成/释放/接管都按此令牌做条件更新） */
  @TableField("draft_apply_token")
  private String draftApplyToken;

  /** 草稿应用租约起点（超过 ai.draft.apply-stale-ms 才允许条件接管） */
  @TableField("draft_apply_started_at")
  private Date draftApplyStartedAt;

  /** 草稿应用成功结果 JSON（同稿重试回放一致结果，不再执行业务） */
  @TableField("draft_apply_result")
  private String draftApplyResult;

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
  public String getDraftApplyToken() { return draftApplyToken; }
  public void setDraftApplyToken(String draftApplyToken) { this.draftApplyToken = draftApplyToken; }
  public Date getDraftApplyStartedAt() { return draftApplyStartedAt; }
  public void setDraftApplyStartedAt(Date draftApplyStartedAt) {
    this.draftApplyStartedAt = draftApplyStartedAt;
  }
  public String getDraftApplyResult() { return draftApplyResult; }
  public void setDraftApplyResult(String draftApplyResult) {
    this.draftApplyResult = draftApplyResult;
  }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
