package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * AI 消息发送幂等键（普通/流式共享）：
 * id = userId|conversationId|clientRequestId（自然键派生，见 AiIdempotencyService）。
 *
 * status：running（执行中）/ succeeded（成功，result 存业务结果 JSON）/
 * failed（失败，可同键重试）/ cancelled（已取消，不自动恢复，前端须换新 ID）。
 */
@TableName("ai_request_keys")
public class AiRequestKey {

  @TableId(value = "id", type = IdType.INPUT)
  private String id;

  @TableField("user_id")
  private String userId;

  @TableField("conversation_id")
  private String conversationId;

  @TableField("client_request_id")
  private String clientRequestId;

  private String status;

  /** 成功时的业务结果 JSON（与非流式 sendMessage 返回同形状），用于重试回放。 */
  private String result;

  /**
   * 执行归属令牌：首次插入或成功接管（条件更新影响行数=1）时生成；
   * 完成/失败/取消/放弃只能携带本令牌做条件更新。可空（历史行 = 无人持有）。
   */
  @TableField("execution_token")
  private String executionToken;

  @TableField("created_at")
  private Date createdAt;

  @TableField("updated_at")
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getConversationId() { return conversationId; }
  public void setConversationId(String conversationId) { this.conversationId = conversationId; }
  public String getClientRequestId() { return clientRequestId; }
  public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getResult() { return result; }
  public void setResult(String result) { this.result = result; }
  public String getExecutionToken() { return executionToken; }
  public void setExecutionToken(String executionToken) { this.executionToken = executionToken; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
