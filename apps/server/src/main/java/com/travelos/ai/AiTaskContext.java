package com.travelos.ai;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 AI 生成的任务上下文：请求标识、准入/配额归属、取消状态、总截止时间与关键进度标记。
 *
 * <p>生命周期（由 {@link AiTaskManager} 创建/结束）：</p>
 * <ul>
 *   <li>取消：客户端断开（SSE 回调/写失败）、主动停止、超时回调、服务关闭
 *       → {@link #cancel(String)}（幂等，重复调用无副作用）；</li>
 *   <li>进度检查：{@link #checkActive()} 在每个模型调用前后、每个工具调用前后、
 *       每次写库前后被调用——取消后不再开始下一轮，超时后不再继续；</li>
 *   <li>完成：正常返回（含降级/超时提示）由管理器标记 finished，
 *       与并发取消同时发生时以先到者为准（finished 后 cancel 不再中断执行体）。</li>
 * </ul>
 *
 * <p>状态标记：userMessageWritten（是否已写入用户消息）、finished（任务已产出结果）。
 * 计数/槽位的释放不依赖本对象，由管理器在 finally 中完成。</p>
 */
public class AiTaskContext {

  private final String taskId;
  private final String userId;
  private final String conversationId;
  private final String clientRequestId;
  private final long startedAt;
  private final long deadlineMillis;

  private volatile boolean cancelled;
  private volatile String cancelReason;
  private volatile boolean finished;

  private final AtomicBoolean userMessageWritten = new AtomicBoolean(false);

  public AiTaskContext(String taskId, String userId, String conversationId,
      String clientRequestId, long taskTimeoutMs) {
    this.taskId = taskId;
    this.userId = userId;
    this.conversationId = conversationId;
    this.clientRequestId = clientRequestId;
    this.startedAt = System.currentTimeMillis();
    this.deadlineMillis = startedAt + taskTimeoutMs;
  }

  public String taskId() { return taskId; }
  public String userId() { return userId; }
  public String conversationId() { return conversationId; }
  public String clientRequestId() { return clientRequestId; }
  public long startedAt() { return startedAt; }
  public long deadlineMillis() { return deadlineMillis; }

  public boolean isCancelled() { return cancelled; }

  public String cancelReason() { return cancelReason; }

  public boolean isFinished() { return finished; }

  /** 取消（幂等）：第一次生效并记录原因；返回是否为首次取消。 */
  public boolean cancel(String reason) {
    if (finished) {
      return false; // 已完成的正常结果不再被“取消”改写
    }
    if (cancelled) {
      return false;
    }
    cancelled = true;
    cancelReason = reason == null ? "cancelled" : reason;
    return true;
  }

  /** 由管理器在执行体产出结果后标记完成（与 cancel 先到者为准）。 */
  boolean markFinished() {
    if (cancelled) {
      return false;
    }
    finished = true;
    return true;
  }

  /** 进度闸：取消 → AiCancelledException；超过总截止 → AiTimeoutException。 */
  public void checkActive() {
    if (cancelled) {
      throw new AiCancelledException(cancelReason);
    }
    long now = System.currentTimeMillis();
    if (now > deadlineMillis) {
      throw new AiTimeoutException(deadlineMillis - startedAt);
    }
  }

  /** 是否剩余时间不足一次完整模型调用（用于提前收口，可选优化点）。 */
  public long remainingMillis() {
    return deadlineMillis - System.currentTimeMillis();
  }

  public boolean isUserMessageWritten() { return userMessageWritten.get(); }

  public void markUserMessageWritten() { userMessageWritten.set(true); }

  /** 取消原因文案（日志用，不含敏感正文）。 */
  @Override
  public String toString() {
    return "AiTask{" + taskId + ", user=" + userId + ", conv=" + conversationId
        + ", cancelled=" + cancelled + "(" + cancelReason + "), finished=" + finished + "}";
  }
}
