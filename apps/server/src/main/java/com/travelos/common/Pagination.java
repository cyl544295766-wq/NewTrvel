package com.travelos.common;

/**
 * 分页参数解析。
 *
 * 多个 Service 各自写了一份逐字相同的解析逻辑，这里收敛为唯一实现，
 * 口径保持不变：解析失败、非正数一律回退到默认值。
 */
public class Pagination {

  /**
   * 解析正整数参数：解析失败或不是正数时回退到 fallback。
   */
  public static int positive(String value, int fallback) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }
}
