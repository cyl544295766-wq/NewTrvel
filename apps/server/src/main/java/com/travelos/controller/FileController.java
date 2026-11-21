package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.ManagedFileNames;
import com.travelos.service.FileAccessService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /files/** 的唯一读取通道（替代原 StaticResourceConfig 的静态目录映射）。
 *
 * <p>GET 与 HEAD 走同一对象级授权（{@link FileAccessService}）；未登录 401（拦截器），
 * 无权限/不存在统一 404（防枚举），错误响应不包含磁盘路径、属主或任何敏感元数据。</p>
 *
 * <p>响应约定：</p>
 * <ul>
 *   <li>Content-Type 按受管扩展名给定（jpg/png/webp/pdf），并带 X-Content-Type-Options: nosniff；</li>
 *   <li>私有内容一律 {@code Cache-Control: private, no-store, max-age=0} + {@code Pragma: no-cache}，
 *       共享缓存不得缓存，权限撤销后不残留；</li>
 *   <li>GET 支持 Range（大文件/音视频分段），未带 Range 时显式给出 Content-Length；</li>
 *   <li>HEAD 仅返回与 GET 相同的授权结果与响应头，不读文件内容。</li>
 * </ul>
 */
@RestController
public class FileController {

  private final FileAccessService fileAccessService;

  public FileController(FileAccessService fileAccessService) {
    this.fileAccessService = fileAccessService;
  }

  @RequestMapping(value = "/files/{filename}", method = RequestMethod.GET)
  public ResponseEntity<Resource> get(@PathVariable String filename,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
      @CurrentUser UserContext user) {
    FileAccessService.AuthorizedFile file = fileAccessService.authorize(filename, user);
    long size = sizeOf(file.path());
    HttpHeaders headers = privateHeaders(filename, size);
    if (range != null && !range.isBlank()) {
      headers.remove(HttpHeaders.CONTENT_LENGTH); // 交给 Spring 按 Range 生成 206/416
    }
    return ResponseEntity.ok()
        .headers(headers)
        .body(new FileSystemResource(file.path()));
  }

  @RequestMapping(value = "/files/{filename}", method = RequestMethod.HEAD)
  public ResponseEntity<Void> head(@PathVariable String filename,
      @CurrentUser UserContext user) {
    FileAccessService.AuthorizedFile file = fileAccessService.authorize(filename, user);
    long size = sizeOf(file.path());
    return ResponseEntity.ok().headers(privateHeaders(filename, size)).build();
  }

  /** 私有文件响应头：禁止共享缓存 + nosniff + 允许 Range。 */
  private HttpHeaders privateHeaders(String filename, long size) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(mediaTypeOf(filename));
    headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    headers.setPragma("no-cache");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.setContentLength(size);
    return headers;
  }

  private long sizeOf(Path path) {
    try {
      return Files.size(path);
    } catch (IOException ex) {
      // 读取前文件消失/不可读：与不存在保持一致 404，绝不 500
      throw ApiException.notFound("文件不存在");
    }
  }

  /** 受管扩展名 → 响应 Content-Type（白名单之外的文件名在授权阶段已被 404）。 */
  private static MediaType mediaTypeOf(String filename) {
    return switch (ManagedFileNames.extensionOf(filename)) {
      case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
      case "png" -> MediaType.IMAGE_PNG;
      case "webp" -> MediaType.parseMediaType("image/webp");
      case "pdf" -> MediaType.APPLICATION_PDF;
      default -> MediaType.APPLICATION_OCTET_STREAM;
    };
  }
}
