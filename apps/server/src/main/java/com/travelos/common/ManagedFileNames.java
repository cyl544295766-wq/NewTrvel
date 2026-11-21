package com.travelos.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受管文件名的唯一规则来源。
 *
 * <p>FileStorageService 只会生成 {@code /files/{32位小写hex}.{ext}}，其中 ext
 * 仅可能是 jpg / jpeg / png / webp / pdf。所有读取入口都必须先用本类校验，
 * 避免大小写、编码、路径穿越或未知扩展名绕过对象级授权。</p>
 */
public final class ManagedFileNames {

  /** 仅文件名部分（不含 /files/ 前缀）。 */
  public static final Pattern NAME_PATTERN =
      Pattern.compile("^([0-9a-f]{32})\\.(jpg|jpeg|png|webp|pdf)$");

  /** 数据库/业务层保存的完整访问路径。 */
  public static final Pattern URL_PATTERN =
      Pattern.compile("^/files/([0-9a-f]{32})\\.(jpg|jpeg|png|webp|pdf)$");

  private ManagedFileNames() {
  }

  public static boolean isValidName(String name) {
    return name != null && NAME_PATTERN.matcher(name).matches();
  }

  /** 从 {@code /files/xxx.ext} 提取文件名；非法 URL 返回 null。 */
  public static String nameFromUrl(String url) {
    if (url == null) {
      return null;
    }
    Matcher matcher = URL_PATTERN.matcher(url);
    if (!matcher.matches()) {
      return null;
    }
    return matcher.group(1) + "." + matcher.group(2);
  }

  public static boolean isManagedUrl(String url) {
    return nameFromUrl(url) != null;
  }

  /** 取扩展名（小写）；调用方需先通过 {@link #isValidName(String)}。 */
  public static String extensionOf(String name) {
    int dot = name == null ? -1 : name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1);
  }
}
