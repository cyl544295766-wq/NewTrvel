/* Travel OS · AI 发送策略（纯函数，无 DOM 依赖）
 * ai-assistant.js 使用；同时被 apps/web/tests/ai-send-policy.test.cjs 单测引用。
 *
 * 职责：
 *  - 每次用户发送生成稳定 clientRequestId（普通/流式共享的幂等键）；
 *  - 流式请求失败后的去向判定——只有能确认“未被服务端接受”时才允许自动改用普通接口；
 *  - 失败后以消息列表对齐服务端权威状态，避免重复插入/重复生成；
 *  - 停止后的新发送必须使用新 ID（由 newRequestId 每次调用保证）。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.TravelOSSendPolicy = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /** 每次调用都生成新 ID：停止后再次发送天然使用新键，不会被旧幂等记录阻挡。 */
  function newRequestId() {
    try {
      if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        return crypto.randomUUID();
      }
    } catch (e) { /* ignore */ }
    return 'r' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
  }

  /**
   * 流式请求失败后的去向判定。
   * @param state {streamSupported, hasToken, fetchFailed, status, bodyReadable}
   * @returns {{action: 'fallback'|'align'|'abort'|'read', reason: string}}
   *  - 'fallback'：可确认请求未被服务端接受（不支持流式 / 无 Token / 401 / 403）
   *                → 允许自动改用普通接口（携带同一 clientRequestId，服务端幂等兜底）
   *  - 'align'   ：请求可能已被接受（网络中断 / 5xx / 响应体不可读）
   *                → 只拉取权威消息列表对齐，不盲目再次发送
   *  - 'abort'   ：服务端明确拒绝（429 限流 / 其他 4xx）→ 展示结果，不重发
   *  - 'read'    ：正常读取 SSE 流
   */
  function decideStreamFallback(state) {
    if (!state || !state.streamSupported || !state.hasToken) {
      return { action: 'fallback', reason: 'not-supported' };
    }
    if (state.fetchFailed) {
      return { action: 'align', reason: 'network' };
    }
    if (typeof state.status !== 'number') {
      return { action: 'align', reason: 'no-status' };
    }
    if (state.status === 401 || state.status === 403) {
      return { action: 'fallback', reason: 'unauthenticated' };
    }
    if (state.status === 429) {
      return { action: 'abort', reason: 'rate-limited' };
    }
    if (state.status >= 500) {
      return { action: 'align', reason: 'server-error' };
    }
    if (!state.bodyReadable) {
      return { action: 'align', reason: 'no-body' };
    }
    if (state.status >= 400) {
      return { action: 'abort', reason: 'client-error' };
    }
    return { action: 'read', reason: 'ok' };
  }

  /** 权威消息列表中是否已存在该条用户消息（trim 后全等）。 */
  function hasUserMessageWithText(messages, text) {
    if (!Array.isArray(messages)) return false;
    var target = String(text == null ? '' : text).trim();
    if (!target) return false;
    for (var i = 0; i < messages.length; i++) {
      var m = messages[i];
      if (m && m.role === 'user' &&
          String(m.content == null ? '' : m.content).trim() === target) {
        return true;
      }
    }
    return false;
  }

  /**
   * 对齐后的二次决策：只有权威列表【不存在】这条用户消息（即服务端确实没收到/没落库）
   * 才允许自动回退到普通接口；重试必须复用同一 clientRequestId（服务端幂等防重复执行）。
   */
  function shouldFallbackAfterAlign(messages, text) {
    return !hasUserMessageWithText(messages, text);
  }

  return {
    newRequestId: newRequestId,
    decideStreamFallback: decideStreamFallback,
    hasUserMessageWithText: hasUserMessageWithText,
    shouldFallbackAfterAlign: shouldFallbackAfterAlign
  };
});
