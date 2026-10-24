package com.travelos.config;

import com.travelos.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AuthInterceptor authInterceptor;

  @Value("${server.servlet.context-path:}")
  private String contextPath;

  /**
   * P2-2 跨域白名单：逗号分隔的来源列表（env: CORS_ALLOWED_ORIGINS）。
   * 未配置时完全不开放跨域——本应用前端与 API 同源部署，浏览器同源请求不受 CORS 影响；
   * 配置示例：https://travel.example.com,https://www.travel.example.com
   */
  @Value("${cors.allowed-origins:}")
  private String corsAllowedOrigins;

  public WebConfig(AuthInterceptor authInterceptor) {
    this.authInterceptor = authInterceptor;
  }

  @Override
  public void addCorsMappings(@NonNull CorsRegistry registry) {
    String raw = corsAllowedOrigins == null ? "" : corsAllowedOrigins.trim();
    if (raw.isEmpty()) {
      return; // 不注册 CORS 映射 = 仅同源可用（默认安全态）
    }
    registry.addMapping("/api/**")
        .allowedOriginPatterns(raw.split("\\s*,\\s*"))
        .allowedMethods("*")
        .allowedHeaders("*")
        .exposedHeaders("Set-Cookie")
        .allowCredentials(true)
        .maxAge(3600);
  }

  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor)
        // P3-1：/files/** 上传文件也在鉴权范围内（img 标签走同源 Cookie 通道）
        .addPathPatterns("/api/**", "/files/**")
        .excludePathPatterns(
            "/api/health",
            "/api/health/database",
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/logout");
  }

  @Override
  public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(authInterceptor);
  }
}
