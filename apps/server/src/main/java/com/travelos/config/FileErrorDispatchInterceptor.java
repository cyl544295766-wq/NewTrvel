package com.travelos.config;

import com.travelos.common.FileErrorResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 应用层错误分派的 /files/** 规范化（修复问题 2：嵌套路径等 404 返回 Spring 默认结构）。
 *
 * <p>静态资源或无匹配 Controller 的 404/400 会经 sendError 分派到 /error，
 * 由 BasicErrorController 输出 {@code {timestamp,status,error,path}} 默认结构。
 * 本拦截器仅拦截 /error 分派：若**原始请求 URI**（{@code javax.servlet.error.request_uri}）
 * 属于 /files/**，则直接写入与 GlobalExceptionHandler 完全一致的统一 404 JSON 并短路，
 * 其余请求原样放行——/api/** 与全站错误响应保持不变。</p>
 */
@Component
public class FileErrorDispatchInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response, @NonNull Object handler) {
    Object original = request.getAttribute("javax.servlet.error.request_uri");
    String uri = original instanceof String s ? s : request.getRequestURI();
    if (FileErrorResponse.tryWriteNotFound(response, uri)) {
      return false; // 已写统一 404 JSON，不再执行 BasicErrorController
    }
    return true;
  }
}
