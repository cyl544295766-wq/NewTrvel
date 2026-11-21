package com.travelos.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来源 IP 解析：默认不信任任何代理头（防伪造）；仅配置可信代理后按约定解析。
 */
class ClientIpResolverTest {

  private static MockHttpServletRequest request(String remote, String xff, String realIp) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr(remote);
    if (xff != null) {
      req.addHeader("X-Forwarded-For", xff);
    }
    if (realIp != null) {
      req.addHeader("X-Real-IP", realIp);
    }
    return req;
  }

  // ==================== 未配置可信代理：伪造无效 ====================

  @Test
  void withoutTrustedProxiesForwardedHeadersAreIgnored() {
    ClientIpResolver resolver = new ClientIpResolver("");
    assertEquals("203.0.113.7",
        resolver.resolve(request("203.0.113.7", "1.2.3.4, 5.6.7.8", "9.9.9.9")));
  }

  @Test
  void withoutTrustedProxiesXRealIpIsIgnoredToo() {
    ClientIpResolver resolver = new ClientIpResolver("");
    assertEquals("198.51.100.9", resolver.resolve(request("198.51.100.9", null, "1.2.3.4")));
  }

  @Test
  void emptyConfigTrustsNoOne() {
    ClientIpResolver resolver = new ClientIpResolver(" , ,");
    assertFalse(resolver.isTrustedProxy("127.0.0.1"));
    assertEquals(0, resolver.trustedProxyCount());
  }

  // ==================== 配置可信代理后按约定解析 ====================

  @Test
  void trustedProxySingleHopForwardedForIsUsed() {
    ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,10.0.0.0/8");
    assertEquals("203.0.113.7",
        resolver.resolve(request("127.0.0.1", "203.0.113.7", null)));
  }

  @Test
  void rightmostUntrustedHopWins() {
    // 链上只有「配置过的可信代理段」被跳过：右起第一个非受信地址即客户端
    ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
    assertEquals("203.0.113.7",
        resolver.resolve(request("10.0.0.5", "203.0.113.7, 10.0.0.9", null)));
    // 中间混有未配置为可信的地址 → 它离本服务更近，按规则取它（只能信任已配置的跳）
    assertEquals("198.51.100.20",
        resolver.resolve(request("10.0.0.5", "203.0.113.7, 198.51.100.20, 10.0.0.9", null)));
    // 全部是受信代理：取最左合法段
    assertEquals("10.0.0.1",
        resolver.resolve(request("10.0.0.5", "10.0.0.1, 10.0.0.9", null)));
  }

  @Test
  void malformedHopsAreSkippedWithoutError() {
    ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
    // 空段/垃圾段跳过，右起第一个合法非受信 = 客户端
    assertEquals("203.0.113.7",
        resolver.resolve(request("10.0.0.5", " , not-an-ip, 203.0.113.7, 10.0.0.9", null)));
    // 整条链都非法：回退远端地址，绝不 500
    assertEquals("10.0.0.5",
        resolver.resolve(request("10.0.0.5", "garbage, , ,", null)));
  }

  @Test
  void xRealIpUsedOnlyWhenRemoteIsTrusted() {
    ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
    assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", null, "203.0.113.7")));
    // 远端不受信：X-Real-IP 同样忽略
    assertEquals("198.51.100.9", resolver.resolve(request("198.51.100.9", null, "203.0.113.7")));
  }

  @Test
  void portAndQuoteFormsAreNormalized() {
    ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
    assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", "\"203.0.113.7:44321\"", null)));
    assertEquals("203.0.113.7", resolver.resolve(request("127.0.0.1", "203.0.113.7:8080", null)));
  }

  @Test
  void invalidForwardedContentNeverThrows() {
    ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
    for (String bad : new String[] {"", "   ", ":::bad", "999.999.999.999", "[[[", "a.b.c.d:port"}) {
      String resolved = resolver.resolve(request("127.0.0.1", bad, null));
      assertNotNull(resolved, "非法头必须回退到远端地址: " + bad);
      assertEquals("127.0.0.1", resolved);
    }
    assertNull(ClientIpResolver.canonical(null));
    assertNull(ClientIpResolver.canonical("  "));
    assertNull(ClientIpResolver.canonical("hostname.example.com"));
  }

  @Test
  void cidrMatchingCoversBothFamilies() {
    ClientIpResolver resolver = new ClientIpResolver("10.1.2.0/24,192.168.0.1,2001:db8::/32");
    assertTrue(resolver.isTrustedProxy("10.1.2.77"));
    assertFalse(resolver.isTrustedProxy("10.1.3.1"));
    assertTrue(resolver.isTrustedProxy("192.168.0.1"));
    assertFalse(resolver.isTrustedProxy("192.168.0.2"));
    assertTrue(resolver.isTrustedProxy("2001:db8::1"));
    assertFalse(resolver.isTrustedProxy("2001:db9::1"));
    // 非法配置条目被跳过、不导致启动外的异常
    assertEquals(3, resolver.trustedProxyCount());
    new ClientIpResolver("not-an-ip,10.0.0.0/99,1.2.3.4/x");
  }

  @Test
  void ipv6RemoteIsCanonicalizedConsistently() {
    ClientIpResolver resolver = new ClientIpResolver("");
    assertEquals("0:0:0:0:0:0:0:1", resolver.resolve(request("::1", "1.2.3.4", null)));
    assertEquals("0:0:0:0:0:0:0:1", resolver.resolve(request("0:0:0:0:0:0:0:1", "1.2.3.4", null)));
  }
}
