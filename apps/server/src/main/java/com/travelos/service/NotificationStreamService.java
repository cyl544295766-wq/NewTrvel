package com.travelos.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P3-2 通知实时化：按用户维护 SSE 连接，通知产生/已读变化时推送 "unread" 事件。
 * EventSource 走同源 Cookie 通道鉴权（见 WebConfig 对 /api/** 的拦截）。
 * 连接断开/超时自动清理；推送失败静默摘除——前端保留轮询兜底，SSE 只做加速。
 */
@Service
public class NotificationStreamService {

  private final Map<String, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

  public SseEmitter subscribe(String userId) {
    SseEmitter emitter = new SseEmitter(0L); // 不超时，断连由 onError/onCompletion 清理
    List<SseEmitter> list = emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
    list.add(emitter);
    emitter.onCompletion(() -> remove(userId, emitter));
    emitter.onTimeout(() -> remove(userId, emitter));
    emitter.onError(e -> remove(userId, emitter));
    return emitter;
  }

  /** 向该用户全部在线连接推送事件；无连接/失败均为无操作。 */
  public void push(String userId, String eventName, Object data) {
    List<SseEmitter> list = emittersByUser.get(userId);
    if (list == null || list.isEmpty()) {
      return;
    }
    for (SseEmitter emitter : list) {
      try {
        emitter.send(SseEmitter.event().name(eventName).data(data));
      } catch (Exception ex) {
        remove(userId, emitter);
      }
    }
  }

  /** 通知未读数可能变化时调用（创建/已读/删除），载荷只带原因，客户端回查权威计数。 */
  public void notifyUnreadChanged(String userId, String reason) {
    push(userId, "unread", Map.of("reason", reason == null ? "changed" : reason));
  }

  /**
   * 会话失效（改密/注销/禁用账户）时主动结束该用户的全部通知 SSE，并整体移出注册表。
   *
   * <p>连接完成后浏览器 EventSource 触发 onerror，前端既有失效检测会探测普通 API：
   * 401 = 会话已撤销 → 清登录态跳登录页（不会无限重连）；网络问题则按有限退避重连。
   * 只摘除目标用户，不影响其他用户；同一用户的多标签页连接全部处理；
   * 先 remove 再 complete，emitter 的 onCompletion 回调对已移除的注册表是无操作，无泄漏。</p>
   *
   * @return 本次结束的连接数
   */
  public int closeAll(String userId) {
    List<SseEmitter> list = emittersByUser.remove(userId);
    if (list == null || list.isEmpty()) {
      return 0;
    }
    int closed = 0;
    for (SseEmitter emitter : list) {
      try {
        emitter.complete();
        closed++;
      } catch (Exception ignored) {
        /* 已完成/已断开的连接：注册表已整体移除，无需处理 */
      }
    }
    return closed;
  }

  /** 当前用户的在线连接数（诊断与测试用；不暴露连接本身）。 */
  public int connectionCount(String userId) {
    List<SseEmitter> list = emittersByUser.get(userId);
    return list == null ? 0 : list.size();
  }

  private void remove(String userId, SseEmitter emitter) {
    List<SseEmitter> list = emittersByUser.get(userId);
    if (list != null) {
      list.remove(emitter);
      if (list.isEmpty()) {
        emittersByUser.remove(userId, list);
      }
    }
  }
}
