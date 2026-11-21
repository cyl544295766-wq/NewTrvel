package com.travelos.auth;

import com.travelos.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录/注册限流（本地内存、固定窗口计数）。
 *
 * <p>登录采用<b>组合维度</b>（任一超限即 429）：</p>
 * <ul>
 *   <li>来源 IP：限制单 IP 窗口内的登录失败总次数，防遍历大量账号；</li>
 *   <li>账号 + IP：限制对同一账号的持续口令尝试；共享出口（NAT）下不同账号互不影响；</li>
 *   <li>全局：异常流量兜底，额度远高于正常峰值——单个攻击者最多造成<b>一个窗口内</b>
 *       （默认 5 分钟）的登录退避，不会长期锁死全站。</li>
 * </ul>
 *
 * <p>注册仅按来源 IP 计数（每次尝试均计入，超限 429），阈值内流程与响应不变。</p>
 *
 * <p><b>计数与恢复策略（明确声明）</b>：</p>
 * <ul>
 *   <li><b>原子预占</b>：登录入口在密码校验前于同一把短锁内完成「三维度检查 + 额度占用」
 *       （{@link #beginLoginAttempt}），杜绝“先检查、失败后计数”的并发竞态——
 *       并发请求中只有不超过阈值的会进入用户查询/BCrypt，其余直接 429；</li>
 *   <li>对外仍以<b>失败登录</b>为主要限制对象：成功登录结算时只撤销<b>自己</b>的
 *       IP/全局预占（净效果 = 成功不消耗来源/全局额度），并按既有策略清除同窗口内
 *       该 (账号, IP) 的失败计数——攻击者无法用少量成功请求清空其他账号或整体记录；
 *       意外异常（非统一 400）会回退本次预占，避免系统性错误吃掉登录额度；</li>
 *   <li>固定窗口：窗口内计数只增不减，{@code Retry-After} = 距窗口结束的剩余秒数（≥1）；
 *       窗口过期后计数重置——无需人工解锁，也不保留无限历史；
 *       结算回退带「同窗口起点」校验，窗口切换后绝不把旧窗口的回退打到新窗口。</li>
 * </ul>
 *
 * <p><b>资源边界与线程安全</b>：每键 O(1) 的 {@code {窗口起点, 计数}}，由
 * {@link ConcurrentHashMap#compute} 原子更新（并发失败不丢计数）；过期键由周期清扫回收，
 * 键总数超过 {@code maxTrackedKeys} 时按窗口起点淘汰最旧键——内存有上界、无无限增长。</p>
 *
 * <p><b>多实例边界</b>：这是<b>单实例</b>内存限流，多副本部署时各实例独立计数
 * （总量最多放大为副本数），不宣称共享；需要跨实例一致限额须换集中式存储。</p>
 *
 * <p><b>重启</b>：计数无持久化，重启即清零——攻击者可借重启绕过<b>历史</b>计数，
 * 但重启后窗口内限额立即重新生效。</p>
 */
@Component
public class AuthRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);
  /** 429 统一文案：不暴露被限制的是真实账号还是不存在账号 */
  static final String TOO_MANY_REQUESTS_MESSAGE = "请求过于频繁，请稍后再试";

  /** 限流参数（生产由 application.yml / 环境变量注入；测试可直接构造）。 */
  public record Limits(int loginWindowSeconds, int loginIpMax, int loginAccountIpMax,
      int loginGlobalMax, int registerWindowSeconds, int registerIpMax, int maxTrackedKeys) {
  }

  /** 单个键的固定窗口状态（窗口起点 + 计数；均只在 compute/读取路径访问）。 */
  private static final class Counter {
    private final long startMillis;
    private int count;
    private boolean warned;

    private Counter(long startMillis, int count) {
      this.startMillis = startMillis;
      this.count = count;
    }
  }

  private final Limits limits;
  private final Clock clock;

  private final ConcurrentHashMap<String, Counter> loginIp = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> loginAccountIp = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> loginGlobal = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> registerIp = new ConcurrentHashMap<>();
  private volatile long lastSweepMillis;

  @Autowired
  public AuthRateLimiter(
      @Value("${security.rate-limit.login-window-seconds:300}") int loginWindowSeconds,
      @Value("${security.rate-limit.login-ip-max-attempts:30}") int loginIpMax,
      @Value("${security.rate-limit.login-account-ip-max-attempts:10}") int loginAccountIpMax,
      @Value("${security.rate-limit.login-global-max-attempts:600}") int loginGlobalMax,
      @Value("${security.rate-limit.register-window-seconds:600}") int registerWindowSeconds,
      @Value("${security.rate-limit.register-ip-max-attempts:10}") int registerIpMax,
      @Value("${security.rate-limit.max-tracked-keys:20000}") int maxTrackedKeys,
      Clock clock) {
    this(new Limits(loginWindowSeconds, loginIpMax, loginAccountIpMax, loginGlobalMax,
        registerWindowSeconds, registerIpMax, maxTrackedKeys), clock);
  }

  public AuthRateLimiter(Limits limits, Clock clock) {
    this.limits = limits;
    this.clock = clock;
  }

  // ==================== 登录（原子预占模型） ====================

  /**
   * 一次登录尝试的预占凭证：记录本次占用的三个键与各自的窗口起点，
   * 供成功/异常结算时**只回退自己**产生的计数（并发安全的最小凭据）。
   */
  public record LoginPermit(String ipKey, String accountKey, long ipWindowStart,
      long accountWindowStart, long globalWindowStart) {
  }

  private static final String GLOBAL_KEY = "global";
  /**
   * 登录三维度的检查与额度占用必须原子完成——只覆盖「读三个计数器 + 条件 +1」，
   * 绝不包住用户查询/BCrypt/数据库操作（那些在 permit 返回后执行）。
   */
  private final Object loginLock = new Object();

  /**
   * 原子「检查 + 预占」登录额度：三个维度（IP / 账号+IP / 全局）在同一把短锁内
   * 先全部检查、**全部通过后才一起占用**——任一维度超限直接 429（含 Retry-After），
   * 既不查询用户也不执行 BCrypt，且拒绝路径不会产生任何维度的部分预占。
   *
   * <p>无论账号是否存在都执行同一套键与阈值——429 本身不泄露账号是否真实。</p>
   *
   * @return 预占凭证；调用方在认证结束后必须调用 {@link #completeLoginSuccess(LoginPermit)}
   *         （成功）或 {@link #abandonLoginAttempt(LoginPermit)}（意外异常回退）；
   *         正常的认证失败（统一 400）无需结算——预占即失败计数。
   */
  public LoginPermit beginLoginAttempt(String clientIp, String username) {
    String ipKey = ipKey(clientIp);
    String accountKey = accountIpKey(username, clientIp);
    long now = clock.millis();
    long windowMillis = limits.loginWindowSeconds() * 1000L;
    LoginPermit permit;
    synchronized (loginLock) {
      // 依次检查（窗口过期先滚动），全部通过后才占用——拒绝时零占用、零回退
      Counter ip = roll(loginIp, ipKey, now, windowMillis);
      if (ip.count >= limits.loginIpMax()) {
        throw blocked(ip, now, windowMillis, "ip " + clientIp, limits.loginIpMax());
      }
      Counter account = roll(loginAccountIp, accountKey, now, windowMillis);
      if (account.count >= limits.loginAccountIpMax()) {
        throw blocked(account, now, windowMillis, "account+ip", limits.loginAccountIpMax());
      }
      Counter global = roll(loginGlobal, GLOBAL_KEY, now, windowMillis);
      if (global.count >= limits.loginGlobalMax()) {
        throw blocked(global, now, windowMillis, "global", limits.loginGlobalMax());
      }
      ip.count++;
      account.count++;
      global.count++;
      permit = new LoginPermit(ipKey, accountKey, ip.startMillis, account.startMillis,
          global.startMillis);
    }
    maybeSweep(now); // 清扫在锁外：不拖长临界区
    return permit;
  }

  /**
   * 登录成功结算：<b>只撤销本次预占</b>——IP 与全局各回退 1（成功不消耗来源/全局额度，
   * 与「仅失败计入」的既有语义一致），并按原有策略<b>清除同窗口内该 (账号, IP) 的失败计数</b>。
   * 其他账号、其他来源、全局历史记录一律不动；窗口已切换则不触碰新窗口的计数。
   */
  public void completeLoginSuccess(LoginPermit permit) {
    if (permit == null) {
      return;
    }
    synchronized (loginLock) {
      refundSameWindow(loginIp, permit.ipKey(), permit.ipWindowStart());
      refundSameWindow(loginGlobal, GLOBAL_KEY, permit.globalWindowStart());
      Counter account = loginAccountIp.get(permit.accountKey());
      if (account != null && account.startMillis == permit.accountWindowStart()) {
        // 同窗口：整体清除该 (账号,IP) 的失败计数（既有恢复策略）；窗口已切换则不动新窗口
        loginAccountIp.remove(permit.accountKey(), account);
      }
    }
    maybeSweep(clock.millis());
  }

  /**
   * 意外异常回退（DB 故障、参数异常等非统一 400 的错误）：把本次预占的三个维度各回退 1，
   * 只回退同一窗口的计数——不让系统性错误吃掉用户的登录额度，也不误删并发请求。
   * 正常认证失败（统一 400）<b>不</b>调用本方法：预占即失败计数。
   */
  public void abandonLoginAttempt(LoginPermit permit) {
    if (permit == null) {
      return;
    }
    synchronized (loginLock) {
      refundSameWindow(loginIp, permit.ipKey(), permit.ipWindowStart());
      refundSameWindow(loginAccountIp, permit.accountKey(), permit.accountWindowStart());
      refundSameWindow(loginGlobal, GLOBAL_KEY, permit.globalWindowStart());
    }
  }

  /** 锁内：键不存在或窗口已过期则滚动到新窗口（计数归零），返回当前计数器。 */
  private static Counter roll(ConcurrentHashMap<String, Counter> map, String key, long now,
      long windowMillis) {
    Counter current = map.get(key);
    if (current == null || now - current.startMillis >= windowMillis) {
      Counter fresh = new Counter(now, 0);
      map.put(key, fresh);
      return fresh;
    }
    return current;
  }

  /** 锁内：仅当仍是同一窗口起点时回退 1（count 归零则移除键），窗口切换/已回收则不动。 */
  private static void refundSameWindow(ConcurrentHashMap<String, Counter> map, String key,
      long expectedStart) {
    map.computeIfPresent(key, (k, c) -> {
      if (c.startMillis != expectedStart || c.count <= 0) {
        return c; // 窗口已切换或键已被回收：绝不把旧窗口的回退打到新窗口
      }
      if (c.count == 1) {
        return null;
      }
      c.count--;
      return c;
    });
  }

  /** 锁内：达到上限 → 记录（每键每窗一条 warn）并抛 429；不占用任何额度。 */
  private ApiException blocked(Counter counter, long now, long windowMillis, String scope,
      int max) {
    int retryAfter = retryAfterSeconds(counter, now, windowMillis);
    if (!counter.warned) {
      counter.warned = true;
      log.warn("auth rate limit hit ({}), max={} window={}s, retry in {}s",
          scope, max, limits.loginWindowSeconds(), retryAfter);
    } else {
      log.debug("auth rate limit still active ({})", scope);
    }
    return ApiException.tooManyRequests(TOO_MANY_REQUESTS_MESSAGE, retryAfter);
  }

  // ==================== 注册 ====================

  /**
   * 注册尝试：计数与阈值原子完成（第 1..max 次放行，第 max+1 次起窗口内 429）。
   * 无论后续业务校验是否通过都计数——限的是该来源的创建频率，不改变阈值内的业务响应。
   */
  public void attemptRegister(String clientIp) {
    long now = clock.millis();
    long windowMillis = limits.registerWindowSeconds() * 1000L;
    Counter counter = increment(registerIp, ipKey(clientIp), now, windowMillis);
    if (counter != null && now - counter.startMillis < windowMillis
        && counter.count > limits.registerIpMax()) {
      int retryAfter = retryAfterSeconds(counter, now, windowMillis);
      log.warn("register rate limited: max={} window={}s, retry in {}s",
          limits.registerIpMax(), limits.registerWindowSeconds(), retryAfter);
      throw ApiException.tooManyRequests(TOO_MANY_REQUESTS_MESSAGE, retryAfter);
    }
    maybeSweep(now);
  }

  // ==================== 测试/运维观测 ====================

  /** 当前追踪的键总数（断言内存有界、过期回收）。 */
  int trackedKeys() {
    return loginIp.size() + loginAccountIp.size() + loginGlobal.size() + registerIp.size();
  }

  /** 某 (账号, IP) **当前窗口内**的失败计数（过期/无记录 = 0）。 */
  int accountFailureCount(String username, String ip) {
    return currentCount(loginAccountIp.get(accountIpKey(username, ip)),
        limits.loginWindowSeconds() * 1000L);
  }

  /** 某 IP 当前窗口内的登录失败计数。 */
  int ipFailureCount(String ip) {
    return currentCount(loginIp.get(ipKey(ip)), limits.loginWindowSeconds() * 1000L);
  }

  /** 某 IP 当前窗口内的注册尝试计数。 */
  int registerAttemptCount(String ip) {
    return currentCount(registerIp.get(ipKey(ip)), limits.registerWindowSeconds() * 1000L);
  }

  private int currentCount(Counter counter, long windowMillis) {
    if (counter == null || clock.millis() - counter.startMillis >= windowMillis) {
      return 0;
    }
    return counter.count;
  }

  /** 清空全部计数（测试隔离/运维手动复位）。 */
  void reset() {
    loginIp.clear();
    loginAccountIp.clear();
    loginGlobal.clear();
    registerIp.clear();
    lastSweepMillis = 0L;
  }

  /** 限流参数（测试读取）。 */
  Limits limits() {
    return limits;
  }

  // ==================== 内部工具 ====================

  private static String ipKey(String ip) {
    return "ip:" + normalizePart(ip);
  }

  private static String accountIpKey(String username, String ip) {
    return "acct:" + normalizePart(username) + "|" + normalizePart(ip);
  }

  /** 用户名按小写合并（大小写变体共用预算）；IP 已由 ClientIpResolver 规范化，原样使用。 */
  private static String normalizePart(String value) {
    if (value == null || value.isBlank()) {
      return "?";
    }
    String trimmed = value.trim();
    return trimmed.matches("[A-Za-z0-9_]{1,64}") ? trimmed.toLowerCase(Locale.ROOT) : trimmed;
  }

  private static int retryAfterSeconds(Counter counter, long now, long windowMillis) {
    long remaining = counter.startMillis + windowMillis - now;
    long seconds = (remaining + 999) / 1000;
    return (int) Math.max(1, Math.min(seconds, 86_400));
  }

  /** 原子 +1（窗口过期则重开），返回当前计数器。 */
  private static Counter increment(ConcurrentHashMap<String, Counter> map, String key,
      long now, long windowMillis) {
    return map.compute(key, (k, current) -> {
      if (current == null || now - current.startMillis >= windowMillis) {
        return new Counter(now, 1);
      }
      current.count = current.count < Integer.MAX_VALUE - 1024 ? current.count + 1
          : Integer.MAX_VALUE;
      return current;
    });
  }

  /** 周期性回收：过期键删除；容量检查每次操作都做（size 为 O(1)），超限时按窗口起点淘汰。 */
  private void maybeSweep(long now) {
    // 容量兜底不走时间闸门：突发大量新键也必须立刻收敛，杜绝无界增长
    if (trackedKeys() > limits.maxTrackedKeys()) {
      evictOldest();
    }
    if (lastSweepMillis != 0L
        && now - lastSweepMillis < Math.min(Math.max(limits.loginWindowSeconds(), 1), 10) * 1000L) {
      return; // 过期清扫至多每 10s 一次，避免每次请求全表遍历
    }
    lastSweepMillis = now;
    long loginWindow = limits.loginWindowSeconds() * 1000L;
    long registerWindow = limits.registerWindowSeconds() * 1000L;
    loginIp.values().removeIf(c -> now - c.startMillis >= loginWindow);
    loginAccountIp.values().removeIf(c -> now - c.startMillis >= loginWindow);
    loginGlobal.values().removeIf(c -> now - c.startMillis >= loginWindow);
    registerIp.values().removeIf(c -> now - c.startMillis >= registerWindow);
    if (trackedKeys() > limits.maxTrackedKeys()) {
      evictOldest();
    }
  }

  /** 容量兜底：全部键按窗口起点升序，淘汰到 90% 容量（迟滞，避免每次操作都重排）。 */
  private void evictOldest() {
    List<KeyRef> all = new ArrayList<>(trackedKeys());
    collect(loginIp, all);
    collect(loginAccountIp, all);
    collect(loginGlobal, all);
    collect(registerIp, all);
    all.sort(Comparator.comparingLong(k -> k.startMillis));
    int target = Math.max(1, (int) (limits.maxTrackedKeys() * 0.9));
    int excess = all.size() - target;
    for (int i = 0; i < excess; i++) {
      KeyRef oldest = all.get(i);
      oldest.map().remove(oldest.key()); // 按所属 map 精确删除（ip: 前缀跨 map 同名不串）
    }
  }

  private record KeyRef(ConcurrentHashMap<String, Counter> map, String key, long startMillis) {
  }

  private static void collect(ConcurrentHashMap<String, Counter> map, List<KeyRef> into) {
    for (Map.Entry<String, Counter> entry : map.entrySet()) {
      into.add(new KeyRef(map, entry.getKey(), entry.getValue().startMillis));
    }
  }
}
