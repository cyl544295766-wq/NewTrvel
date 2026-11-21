package com.travelos.config;

import com.travelos.common.FileErrorResponse;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

/**
 * 容器解析层的 /files/** 错误规范化（修复问题 1：Tomcat 提前返回 400 HTML）。
 *
 * <p>编码斜杠（%2F/%2f）、编码反斜杠（%5C）、编码绝对路径（%3A%5C）等原始 URL
 * 在 Tomcat 请求解析阶段即被拒绝（400），请求尚未进入 FileController/GlobalExceptionHandler，
 * 也不会走 /error 分派——只能在错误报告阀这一层接管。</p>
 *
 * <p>接管条件被严格限制为「容器层 400 且未发生错误分派」：把该 400 归一为统一 404 JSON。
 * 应用自身产生的 200/401/404（含 HEAD 无响应体场景）一律不触碰；其余路径保持
 * {@link ErrorReportValve} 原有行为——不放松路径规范化、不开启 ALLOW_ENCODED_SLASH。</p>
 *
 * <p>安装方式见 {@link FileErrorPageCustomizer}。</p>
 */
public class FileAwareErrorReportValve extends ErrorReportValve {

  @Override
  protected void report(Request request, Response response, Throwable throwable) {
    String uri = request.getRequestURI();
    if (FileErrorResponse.isFileRequest(uri)
        && response.getStatus() == javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST
        && request.getAttribute("javax.servlet.error.status_code") == null
        && !response.isCommitted()
        && FileErrorResponse.tryWriteNotFound(response, uri)) {
      return; // 容器层 400 → 统一 404 JSON
    }
    super.report(request, response, throwable);
  }
}
