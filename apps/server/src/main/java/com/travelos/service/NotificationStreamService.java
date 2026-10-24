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
