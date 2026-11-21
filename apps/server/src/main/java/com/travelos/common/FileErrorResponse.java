package com.travelos.common;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * /files/** 非法路径的**统一错误响应**（唯一 JSON 文案来源，避免容器层与应用层漂移）。
 *
 * <p>与 {@link GlobalExceptionHandler} 对 ApiException(404) 的输出逐字段一致：
 * {@code {"message":"文件不存在","error":"Not Found","statusCode":404}}——
 * 因此「无权限」「不存在」「非法路径」三类响应体完全相同，无法据此枚举。</p>
 *
 * <p>两个使用点：</p>
 * <ul>
 *   <li>Tomcat 错误报告阀（容器解析层 400：编码斜杠/反斜杠/绝对路径等）；</li>
 *   <li>/error 分派拦截器（Spring 静态资源或无匹配路径产生的 404）。</li>
 * </ul>
 */
public final class FileErrorResponse {

  /** 受管文件请求的统一 404 响应体（字段与顺序固定）。 */
  public static final String NOT_FOUND_JSON =
      "{\"message\":\"文件不存在\",\"error\":\"Not Found\",\"statusCode\":404}";

  private FileErrorResponse() {
  }

  /** 原始请求是否指向 /files/**（只对文件路径做错误规范化）。 */
  public static boolean isFileRequest(String requestUri) {
    return requestUri != null && requestUri.startsWith("/files/");
  }

  /**
   * 若原始 URI 属于 /files/**，则以统一 404 JSON 覆写响应并返回 true；
   * 否则不做任何改动返回 false（交由既有错误处理链路）。
   *
   * <p>仅在响应未提交时生效；绝不读取文件内容，也不输出路径/用户/堆栈等信息。</p>
   */
  public static boolean tryWriteNotFound(HttpServletResponse response, String requestUri) {
    if (!isFileRequest(requestUri) || response == null || response.isCommitted()) {
      return false;
    }
    try {
      response.reset(); // 清除容器此前写入的 400/HTML 头与缓冲（仅未提交时可用）
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      byte[] bytes = NOT_FOUND_JSON.getBytes(StandardCharsets.UTF_8);
      response.setContentLength(bytes.length);
      // 私有错误响应同样不进入共享缓存
      response.setHeader("Cache-Control", "private, no-store, max-age=0");
      response.getWriter().write(NOT_FOUND_JSON);
      return true;
    } catch (IOException | IllegalStateException | UnsupportedOperationException ex) {
      // 覆写失败时不做二次加工：保持容器/应用既有输出，绝不因错误处理产生 500
      return false;
    }
  }
}
