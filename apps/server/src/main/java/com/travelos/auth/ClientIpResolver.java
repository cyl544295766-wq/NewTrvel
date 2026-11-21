package com.travelos.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 限流用客户端来源 IP 解析（防伪造）。
 *
 * <p>规则（明确、可测试）：</p>
 * <ol>
 *   <li>直连（远端地址不在 {@code security.trusted-proxies} 白名单内）：
 *       <b>完全忽略</b> {@code X-Forwarded-For} / {@code X-Real-IP}，一律用远端地址——
 *       普通客户端伪造请求头无法更换限流身份；默认配置（白名单为空）即此行为。</li>
 *   <li>仅当远端是可信代理时才解析转发头：{@code X-Forwarded-For} 从<b>右往左</b>
 *       跳过仍是可信代理的段，第一个「合法且非受信」的地址即客户端地址；
 *       整条链全是受信代理时取最左合法段；链中空值/非法段直接跳过（不报错）。</li>
 *   <li>无 {@code X-Forwarded-For} 时回退 {@code X-Real-IP}（须为合法 IP），再回退远端地址。</li>
 *   <li>所有返回值统一规范化（IPv4 点分、IPv6 标准形式、去掉端口/引号），
 *       解析失败回退远端地址，绝不因此产生 500。</li>
 * </ol>
 *
 * <p>配置示例：{@code TRUSTED_PROXIES=10.0.0.0/8,127.0.0.1}（支持 IPv4/IPv6 CIDR 与精确 IP）。</p>
 */
@Component
public class ClientIpResolver {

  /** 受信代理条目：精确 IP 或 CIDR 网段。 */
  record TrustedRange(byte[] network, int prefixLength) {
    boolean matches(byte[] address) {
      if (address.length != network.length) {
        return false;
      }
      int fullBytes = prefixLength / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int remainderBits = prefixLength % 8;
      if (remainderBits == 0) {
        return true;
      }
      int mask = (0xFF << (8 - remainderBits)) & 0xFF;
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }

  private final List<TrustedRange> trustedProxies;

  @Autowired
  public ClientIpResolver(@Value("${security.trusted-proxies:}") String trustedProxiesCsv) {
    this.trustedProxies = parse(trustedProxiesCsv);
  }

  ClientIpResolver(List<TrustedRange> trustedProxies) {
    this.trustedProxies = List.copyOf(trustedProxies);
  }

  /** 解析当前请求的客户端 IP（永不因头信息抛异常）。 */
  public String resolve(HttpServletRequest request) {
    String remote = canonical(request.getRemoteAddr());
    if (!isTrustedProxy(remote)) {
      return remote; // 直连：忽略一切转发头
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String[] hops = forwarded.split(",", -1);
      // 右起第一个「合法且非受信」= 客户端（最靠近本服务的一跳）
      for (int i = hops.length - 1; i >= 0; i--) {
        String candidate = canonical(hops[i]);
        if (candidate != null && !isTrustedProxy(candidate)) {
          return candidate;
        }
      }
      // 整条链都是受信代理：取最左合法段
      for (String hop : hops) {
        String candidate = canonical(hop);
        if (candidate != null) {
          return candidate;
        }
      }
    }
    String realIp = canonical(request.getHeader("X-Real-IP"));
    if (realIp != null) {
      return realIp;
    }
    return remote;
  }

  /** 远端地址是否属于配置的可信代理。 */
  public boolean isTrustedProxy(String ip) {
    if (trustedProxies.isEmpty() || ip == null) {
      return false;
    }
    byte[] address = toAddressBytes(ip);
    if (address == null) {
      return false;
    }
    for (TrustedRange range : trustedProxies) {
      if (range.matches(address)) {
        return true;
      }
    }
    return false;
  }

  /** 可信代理条目数（测试/运维可观测）。 */
  public int trustedProxyCount() {
    return trustedProxies.size();
  }

  // ==================== 解析与规范化 ====================

  /** 解析配置串（逗号分隔的 IP 或 CIDR）；非法条目跳过，绝不因配置错误崩掉启动以外的路径。 */
  static List<TrustedRange> parse(String csv) {
    List<TrustedRange> result = new ArrayList<>();
    if (csv == null || csv.isBlank()) {
      return result;
    }
    for (String raw : csv.split(",")) {
      String entry = raw.trim();
      if (entry.isEmpty()) {
        continue;
      }
      String addrPart = entry;
      int prefix = -1;
      int slash = entry.indexOf('/');
      if (slash >= 0) {
        addrPart = entry.substring(0, slash).trim();
        try {
          prefix = Integer.parseInt(entry.substring(slash + 1).trim());
        } catch (NumberFormatException ex) {
          continue; // 非法前缀长度：跳过该条目
        }
      }
      byte[] bytes = toAddressBytes(addrPart);
      if (bytes == null) {
        continue; // 非法 IP：跳过
      }
      int maxPrefix = bytes.length * 8;
      if (prefix < 0) {
        prefix = maxPrefix;
      }
      if (prefix > maxPrefix) {
        continue; // 前缀超出地址族长度：跳过
      }
      result.add(new TrustedRange(bytes, prefix));
    }
    return result;
  }

  /**
   * 规范化单个地址：去引号/空白、去 {@code host:port} 与 {@code [v6]:port}、
   * 校验为纯数字 IP 并输出标准形式；非法返回 null。
   */
  static String canonical(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return null;
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1).trim();
    }
    if (value.isEmpty()) {
      return null;
    }
    String host = value;
    if (value.startsWith("[")) { // [2001:db8::1]:443
      int close = value.indexOf(']');
      if (close > 0) {
        host = value.substring(1, close);
      } else {
        return null;
      }
    } else {
      int colon = value.lastIndexOf(':');
      // 纯 IPv4 + 端口（单个冒号）才剥端口；IPv6 含多个冒号不剥
      if (colon > 0 && value.indexOf(':') == colon && value.chars().filter(ch -> ch == '.').count() == 3) {
        host = value.substring(0, colon);
      }
    }
    byte[] bytes = toAddressBytes(host);
    if (bytes == null) {
      return null;
    }
    return toCanonicalString(bytes);
  }

  /** 仅接受纯数字 IP（IPv4 点分 / IPv6 含冒号十六进制），杜绝 DNS 解析。 */
  private static byte[] toAddressBytes(String ip) {
    if (ip == null) {
      return null;
    }
    String value = ip.trim();
    boolean ipv4 = value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    boolean ipv6 = value.contains(":") && value.matches("[0-9A-Fa-f:.]+");
    if (!ipv4 && !ipv6) {
      return null;
    }
    try {
      byte[] bytes = InetAddress.getByName(value).getAddress(); // 纯数字，不触发 DNS
      if (bytes.length == 16 && isIpv4Mapped(bytes)) {
        byte[] ipv4Bytes = new byte[4];
        System.arraycopy(bytes, 12, ipv4Bytes, 0, 4);
        return ipv4Bytes;
      }
      return bytes;
    } catch (Exception ex) {
      return null; // 非法地址（如 300.1.1.1、::xyz）→ 视为无效
    }
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }

  private static String toCanonicalString(byte[] bytes) {
    try {
      InetAddress address = InetAddress.getByAddress(bytes);
      if (address instanceof Inet6Address) {
        return address.getHostAddress().toLowerCase(Locale.ROOT); // 统一小写（含 %scope 前的地址部分）
      }
      return address.getHostAddress();
    } catch (Exception ex) {
      return null;
    }
  }
}
