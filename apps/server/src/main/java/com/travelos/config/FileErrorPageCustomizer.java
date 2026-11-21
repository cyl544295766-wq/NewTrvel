package com.travelos.config;

import org.apache.catalina.Container;
import org.apache.catalina.core.StandardHost;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;

/**
 * 把 /files/** 的容器级错误报告替换为 {@link FileAwareErrorReportValve}。
 *
 * <p>在 Context 创建阶段（Host 尚未 start）把 Host 的 errorReportValveClass 指向自定义阀，
 * 由 StandardHost 启动时实例化——因此链路中只有这一个报告阀，输出不会与 Tomcat 默认
 * HTML 报告互相覆盖。仅改 /files/** 的错误输出，不启用 ALLOW_ENCODED_SLASH、
 * 不改动编码/路径规范化策略。</p>
 */
@Configuration
public class FileErrorPageCustomizer
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  @Override
  public void customize(@NonNull TomcatServletWebServerFactory factory) {
    factory.addContextCustomizers(context -> {
      Container parent = context.getParent();
      if (parent instanceof StandardHost host) {
        host.setErrorReportValveClass(FileAwareErrorReportValve.class.getName());
      }
    });
  }
}
