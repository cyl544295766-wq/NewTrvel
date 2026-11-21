package testsupport;

import com.travelos.auth.MutableClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试专用可控时钟配置。
 *
 * <p>刻意放在 {@code com.travelos} 组件扫描范围<b>之外</b>（package testsupport）：
 * 否则本 @Configuration 会被所有 Spring 上下文（包括生产扫描的启动测试）一并扫描，
 * 造成多个 @Primary Clock 冲突。需要可控时钟的测试通过 {@code @Import} 显式引入，
 * 每个上下文只注册一个 MutableClock（@Primary 覆盖系统时钟）。</p>
 */
@Configuration
public class MutableClockConfig {

  @Bean
  @Primary
  public MutableClock mutableClock() {
    return new MutableClock();
  }
}
