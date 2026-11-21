package com.travelos.service;

import com.travelos.common.ManagedFileNames;
import com.travelos.entity.FileMetadata;
import com.travelos.mapper.FileMetadataMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * P3-1 文件出库：把照片/证件/小票的 data URL 落到本地磁盘，数据库只存
 * <code>/files/{32位随机hex}.{ext}</code> 访问路径。
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>文件名用 UUID 去横线（32 位 hex，不可猜测），但 URL 本身不再是能力凭证——
 *       /files/** 的读取由 {@link FileAccessService} 做对象级授权；</li>
 *   <li>落盘时同步写 file_metadata（owner_user_id），供授权侧做防御性属主校验；</li>
 *   <li>读取/删除仅接受受管文件名（{@link ManagedFileNames} 白名单），并做
 *       规范化 + 符号链接 + 真实路径三重校验，杜绝路径穿越；</li>
 *   <li>存量数据 URL 不受影响——两者在前端 <code>&lt;img&gt;</code> 场景等价。</li>
 * </ul>
 *
 * <p>目录解析与 StaticResourceConfig 同策略：优先 ${travelos.files.dir}（env
 * FILES_DIR），否则基于工作目录的 ./data/files。后续换对象存储时仅需替换本类。</p>
 */
@Service
public class FileStorageService {

  private static final Map<String, String> MIME_TO_EXT = Map.of(
      "image/jpeg", "jpg",
      "image/jpg", "jpg",
      "image/png", "png",
      "image/webp", "webp",
      "application/pdf", "pdf");

  private final Path baseDir;
  private final FileMetadataMapper fileMetadataMapper;

  public FileStorageService(@Value("${travelos.files.dir:}") String configured,
      FileMetadataMapper fileMetadataMapper) {
    this.baseDir = resolveDir(configured);
    this.fileMetadataMapper = fileMetadataMapper;
  }

  private static Path resolveDir(String configured) {
    if (configured != null && !configured.isBlank()) {
      Path p = Path.of(configured.trim());
      return p.isAbsolute() ? p : Path.of(System.getProperty("user.dir"), configured.trim());
    }
    return Path.of(System.getProperty("user.dir"), "data", "files");
  }

  /** 受管文件根目录（已存在的目录）。 */
  public Path baseDir() {
    return baseDir;
  }

  public boolean isDataUrl(String value) {
    return value != null && value.startsWith("data:");
  }

  /**
   * data URL 落盘，返回 <code>/files/{name}</code> 访问路径，同时写入归属元数据。
   * MIME 必须在白名单（与 PhotoUtil 支持的类型一致），否则抛 IllegalArgumentException。
   *
   * @param ownerUserId 文件创建时的登录用户（file_metadata.owner_user_id）
   */
  public String storeDataUrl(String dataUrl, String ownerUserId) {
    if (ownerUserId == null || ownerUserId.isBlank()) {
      throw new IllegalArgumentException("文件缺少归属用户");
    }
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
    if (!ManagedFileNames.isValidName(name)) {
      throw new IllegalStateException("文件名生成异常");
    }
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
    // 元数据与磁盘文件一并写入；失败则回滚磁盘文件，避免产生无归属文件
    FileMetadata metadata = new FileMetadata();
    metadata.setName(name);
    metadata.setOwnerUserId(ownerUserId);
    try {
      fileMetadataMapper.insert(metadata);
    } catch (RuntimeException ex) {
      try {
        Files.deleteIfExists(baseDir.resolve(name));
      } catch (IOException ignored) {
        // 回滚失败不影响主流程（孤儿文件不可达）
      }
      throw ex;
    }
    return "/files/" + name;
  }

  /** 是否为本服务管理的文件路径（仅格式判定，不代表有权访问）。 */
  public boolean isManagedFileUrl(String url) {
    return ManagedFileNames.isManagedUrl(url);
  }

  /**
   * 解析受管文件名到磁盘真实路径；非法名 / 越界 / 符号链接 / 非普通文件一律返回 null。
   * 调用方必须先完成对象级授权。
   *
   * @param filename 32 位 hex + 受管扩展名，如 {@code a1b2....jpg}
   */
  public Path resolveByName(String filename) {
    if (!ManagedFileNames.isValidName(filename)) {
      return null;
    }
    Path base = baseDir.toAbsolutePath().normalize();
    Path target = base.resolve(filename).normalize();
    if (!target.getParent().equals(base)) {
      return null;
    }
    try {
      // 禁止符号链接（文件本身或父目录任一层为链接即拒绝）
      if (Files.isSymbolicLink(target)) {
        return null;
      }
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)
          || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      Path realBase = base.toRealPath();
      Path realTarget = target.toRealPath();
      if (!realTarget.startsWith(realBase) || Files.isSymbolicLink(realTarget)) {
        return null;
      }
      return realTarget;
    } catch (IOException | SecurityException ex) {
      return null;
    }
  }

  /** 按完整访问路径解析受管文件（PDF 导出等后端消费场景）；非法或不存在返回 null。 */
  public Path resolveManagedFile(String url) {
    return resolveByName(ManagedFileNames.nameFromUrl(url));
  }

  /** 读取受管文件（PDF 导出等后端消费场景）；非法路径返回 null。 */
  public byte[] readManagedFile(String url) {
    Path path = resolveManagedFile(url);
    if (path == null) {
      return null;
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      return null;
    }
  }

  /** 尽力删除受管文件与归属元数据（记录删除时清理，孤儿无害）。 */
  public void deleteIfManaged(String url) {
    String name = ManagedFileNames.nameFromUrl(url);
    if (name == null) {
      return;
    }
    try {
      Files.deleteIfExists(baseDir.toAbsolutePath().normalize().resolve(name));
    } catch (IOException | SecurityException ignored) {
      // 清理失败不影响业务（孤儿文件可由维护脚本回收）
    }
    try {
      fileMetadataMapper.deleteById(name);
    } catch (RuntimeException ignored) {
      // 元数据清理失败：文件已不可达或即将被重新生成，不影响主流程
    }
  }
}
