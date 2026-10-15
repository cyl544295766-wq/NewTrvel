package com.travelos.common;

/**
 * 请求级行程数据范围上下文（ThreadLocal 桥接）。
 *
 * AuthInterceptor 认证成功后写入当前用户 ID，请求结束（afterCompletion）必须清理，
 * 防止线程池复用导致串号。MyBatis-Plus 数据权限拦截器据此为行程属下业务表追加
 * 「当前用户是该行程成员」的兜底条件。
 *
 * 绕过机制：bypass 开关或 {@link #runBypassed(Runnable)}，供种子初始化、
 * 定时清理、健康检查等无请求上下文/需跨用户访问的场景显式使用。
 */
public final class TripScopeContext {

  private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> BYPASSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

  private TripScopeContext() {
  }

  public static void setUserId(String userId) {
    CURRENT_USER.set(userId);
  }

  public static String getUserId() {
    return CURRENT_USER.get();
  }

  public static boolean isBypassed() {
    return Boolean.TRUE.equals(BYPASSED.get());
  }

  public static void setBypassed(boolean bypassed) {
    BYPASSED.set(bypassed);
  }

  /** 在绕过数据权限的上下文中执行一段逻辑，结束后恢复原状态。 */
  public static void runBypassed(Runnable action) {
    boolean previous = isBypassed();
    setBypassed(true);
    try {
      action.run();
    } finally {
      setBypassed(previous);
    }
  }

  /** 请求结束时调用：彻底清空 ThreadLocal，防线程复用串号。 */
  public static void clear() {
    CURRENT_USER.remove();
    BYPASSED.remove();
  }
}
