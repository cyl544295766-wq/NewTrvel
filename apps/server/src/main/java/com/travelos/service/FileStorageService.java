package com.travelos.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P3-1 文件出库：把照片/证件/小票的 data URL 落到本地磁盘，数据库只存
 * <code>/files/{32位随机hex}.{ext}</code> 访问路径。
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>文件名用 UUID 去横线（32 位 hex，不可猜测），URL 本身即能力凭证；</li>
 *   <li>/files/** 由 AuthInterceptor 守护（登录用户方可访问，见 WebConfig）；</li>
 *   <li>读取/删除仅接受受管文件名（正则白名单），杜绝路径穿越；</li>
 *   <li>存量数据 URL 不受影响——两者在前端 <code>&lt;img&gt;</code> 场景等价。</li>
 * </ul>
 *
 * <p>目录解析与 StaticResourceConfig 同策略：优先 ${travelos.files.dir}（env
 * FILES_DIR），否则基于工作目录的 ./data/files。后续换对象存储时仅需替换本类。</p>
 */
@Service
public class FileStorageService {

  /** 受管文件名白名单：32 位 hex + 2-5 位扩展名 */
  private static final Pattern MANAGED_NAME = Pattern.compile("^/files/([0-9a-f]{32})\\.([a-z0-9]{2,5})$");

  private static final Map<String, String> MIME_TO_EXT = Map.of(
      "image/jpeg", "jpg",
      "image/jpg", "jpg",
      "image/png", "png",
      "image/webp", "webp",
      "application/pdf", "pdf");

  private final Path baseDir;

  public FileStorageService(@Value("${travelos.files.dir:}") String configured) {
    this.baseDir = resolveDir(configured);
  }

  private static Path resolveDir(String configured) {
    if (configured != null && !configured.isBlank()) {
      Path p = Path.of(configured.trim());
      return p.isAbsolute() ? p : Path.of(System.getProperty("user.dir"), configured.trim());
    }
    return Path.of(System.getProperty("user.dir"), "data", "files");
  }

  /** 基础目录（StaticResourceConfig 注册 /files/** 映射用）。 */
  public Path baseDir() {
    return baseDir;
  }

  public boolean isDataUrl(String value) {
    return value != null && value.startsWith("data:");
  }

  /**
   * data URL 落盘，返回 <code>/files/{name}</code> 访问路径。
   * MIME 必须在白名单（与 PhotoUtil 支持的类型一致），否则抛 IllegalArgumentException。
   */
  public String storeDataUrl(String dataUrl) {
    int comma = dataUrl.indexOf(',');
    if (comma <= 0 || !dataUrl.regionMatches(0, "data:", 0, 5)) {
      throw new IllegalArgumentException("非法 data URL");
    }
    String mime = dataUrl.substring(5, comma).split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
    String ext = MIME_TO_EXT.get(mime);
    if (ext == null) {
      throw new IllegalArgumentException("不支持的文件类型：" + mime);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1).replaceAll("\\s", ""));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("base64 解码失败");
    }
    String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
    try {
      Files.createDirectories(baseDir);
      Path target = baseDir.resolve(name).normalize();
      if (!target.getParent().equals(baseDir)) {
        throw new IllegalStateException("文件路径越界");
      }
      Files.write(target, bytes);
    } catch (IOException ex) {
      throw new IllegalStateException("文件写入失败：" + ex.getMessage(), ex);
    }
    return "/files/" + name;
  }

  /** 是否为本服务管理的文件路径。 */
  public boolean isManagedFileUrl(String url) {
    return url != null && MANAGED_NAME.matcher(url).matches();
  }

  /** 读取受管文件（PDF 导出等后端消费场景）；非法路径返回 null。 */
  public byte[] readManagedFile(String url) {
    if (url == null) {
      return null;
    }
    var matcher = MANAGED_NAME.matcher(url);
    if (!matcher.matches()) {
      return null;
    }
    try {
      Path target = baseDir.resolve(matcher.group(1) + "." + matcher.group(2)).normalize();
      if (!target.getParent().equals(baseDir) || !Files.isRegularFile(target)) {
        return null;
      }
      return Files.readAllBytes(target);
    } catch (IOException ex) {
      return null;
    }
  }

  /** 尽力删除受管文件（记录删除时清理磁盘，孤儿无害）。 */
  public void deleteIfManaged(String url) {
    if (url == null) {
      return;
    }
    var matcher = MANAGED_NAME.matcher(url);
    if (!matcher.matches()) {
      return;
    }
    try {
      Files.deleteIfExists(baseDir.resolve(matcher.group(1) + "." + matcher.group(2)).normalize());
    } catch (IOException ignored) {
      // 清理失败不影响业务（孤儿文件可由维护脚本回收）
    }
  }

  /** 供运维搬迁：把旧目录内容整体迁入 baseDir（未使用，预留）。 */
  @SuppressWarnings("unused")
  private void importLegacy(Path sourceDir) throws IOException {
    Files.createDirectories(baseDir);
    try (var stream = Files.list(sourceDir)) {
      for (Path src : stream.toList()) {
        Files.copy(src, baseDir.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /** 防御：确保 UTF-8 路径处理一致（Windows 默认编码下文件名恒为 ASCII hex）。 */
  @SuppressWarnings("unused")
  private static String safeName(String name) {
    byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
