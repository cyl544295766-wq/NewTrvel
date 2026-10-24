package com.travelos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 把前端静态站点（apps/web）暴露在根路径上，使登录页/首页/行程页等
 * 纯 HTML + JS 页面能直接被 Spring 提供。
 *
 * <p>前端 fetch 默认指向 /api/**（由 WebConfig 的 CORS 处理），所以静态资源
 * 与 API 同源，不存在跨域问题。</p>
 *
 * <p>目录解析顺序：优先取 -Dtravelos.static.dir=... 或 application.yml 的
 * travelos.static.dir；未配置时按当前工作目录自动探测，覆盖
 * 「项目根 / apps/server / apps/server/target」三种常见启动位置。</p>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

  /** 未显式配置时的候选相对路径，依次对应「项目根 / apps/server / apps/server/target」 */
  private static final String[] CANDIDATES = { "apps/web", "../web", "../../web" };

  private final File staticDir;
  private final com.travelos.service.FileStorageService fileStorageService;

  public StaticResourceConfig(@Value("${travelos.static.dir:}") String configured,
      com.travelos.service.FileStorageService fileStorageService) {
    this.staticDir = resolveStaticDir(configured);
    this.fileStorageService = fileStorageService;
  }

  private static File resolveStaticDir(String configured) {
    if (configured != null && !configured.isBlank()) {
      String value = configured.trim();
      File f = new File(value);
      return f.isAbsolute() ? f : new File(System.getProperty("user.dir"), value).getAbsoluteFile();
    }
    File cwd = new File(System.getProperty("user.dir"));
    for (String relative : CANDIDATES) {
      File candidate = new File(cwd, relative).getAbsoluteFile();
      if (candidate.isDirectory()) {
        return candidate;
      }
    }
    return new File(cwd, CANDIDATES[0]).getAbsoluteFile();
  }

  @Override
  public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
    // P3-1 文件出库：上传的照片/证件/小票落盘在 FileStorageService.baseDir()，
    // 以 /files/** 暴露（须先于 /** 注册才会优先命中）；访问鉴权见 WebConfig 拦截器。
    var filesDir = fileStorageService.baseDir();
    if (java.nio.file.Files.isDirectory(filesDir)) {
      String filesLocation = filesDir.toUri().toString();
      if (!filesLocation.endsWith("/")) {
        filesLocation = filesLocation + "/";
      }
      registry.addResourceHandler("/files/**")
          .addResourceLocations(filesLocation)
          .setCachePeriod(3600);
    }
    if (!staticDir.exists() || !staticDir.isDirectory()) {
      System.err.println("[StaticResourceConfig] Directory not found: " + staticDir.getAbsolutePath());
      return;
    }
    String location = staticDir.toURI().toString();
    if (!location.endsWith("/")) {
      location = location + "/";
    }
    registry.addResourceHandler("/**")
        .addResourceLocations(location)
        .setCachePeriod(0);
  }

  /**
   * 目录路径自动映射到 index.html，让 /trips/ 这种 URL 能找到 trips/index.html。
   * Spring 静态资源本身不带目录索引，这里用 ViewController 兜底。
   */
  @Override
  public void addViewControllers(@NonNull ViewControllerRegistry registry) {
    if (!staticDir.exists() || !staticDir.isDirectory()) {
      return;
    }
    // 根路径 → 入口页：自定义资源处理器不提供欢迎页，需显式映射（否则 / 会 404）
    if (new File(staticDir, "index.html").exists()) {
      registry.addViewController("/").setViewName("forward:/index.html");
    }
    File[] subs = staticDir.listFiles();
    if (subs == null) return;
    for (File sub : subs) {
      if (sub.isDirectory() && new File(sub, "index.html").exists()) {
        String url = "/" + sub.getName() + "/";
        String viewName = "forward:/" + sub.getName() + "/index.html";
        registry.addViewController(url).setViewName(viewName);
      }
    }
  }
}