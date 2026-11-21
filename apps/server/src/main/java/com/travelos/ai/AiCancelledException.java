package com.travelos.ai;

/**
 * 生成被取消（客户端断开 / 主动停止 / 服务关闭 / 写 SSE 失败）。
 * 语义：立即停止后续模型与工具调用，<b>不写降级回复</b>；已写入的数据保留
 * （user 消息已写则保留，已生成的草稿卡片保留），未写的不再写。
 *
 * <p>不继承 ApiException——避免被既有“模型异常→友好降级”分支吞掉。</p>
 */
public class AiCancelledException extends RuntimeException {

  public AiCancelledException(String reason) {
    super(reason == null ? "cancelled" : reason);
  }
}
