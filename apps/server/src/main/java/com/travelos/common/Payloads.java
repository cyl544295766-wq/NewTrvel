package com.travelos.common;

/**
 * 请求体（payload）取值辅助。
 *
 * 这些方法原先在十几个 Service 里各写一份、名字还各不相同（string / optionalString / toNullable），
 * 语义却完全一样。这里按三种口径收敛，调用点逐一等价替换，行为不变。
 */
public class Payloads {

  /**
   * 取字符串值：null 原样返回，其余转字符串。
   */
  public static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * 取字符串值并去掉首尾空白：null 原样返回。
   */
  public static String trimmed(Object value) {
    return value == null ? null : value.toString().trim();
  }

  /**
   * 取字符串值：null、空串、纯空白一律返回 null。
   */
  public static String trimmedOrNull(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
