package com.travelos.ai;

/**
 * 整次 AI 生成超过总时限（覆盖全部模型轮次、工具调用、纠正重试与数据库装配）。
 * 语义：停止后续轮次，<b>恰好写入一条</b>超时提示助手消息（不重复降级），随后正常收尾。
 */
public class AiTimeoutException extends RuntimeException {

  private final long timeoutMs;

  public AiTimeoutException(long timeoutMs) {
    super("AI 生成超过总时限 " + timeoutMs + "ms");
    this.timeoutMs = timeoutMs;
  }

  public long getTimeoutMs() {
    return timeoutMs;
  }
}
