package com.travelos.auth.huawei;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.travelos.common.ApiException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 华为 ID Token 签名公钥（JWKS）来源。
 *
 * <p>公钥按 kid 缓存 1 小时；遇到未知 kid 时允许强制刷新，但两次强制刷新至少间隔
 * {@link #FORCED_REFRESH_INTERVAL_MS}，避免伪造 kid 的请求把上游打成放大攻击。</p>
 */
@Service
public class HuaweiJwksProvider implements HuaweiJwksSource {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
  private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
  private static final long FORCED_REFRESH_INTERVAL_MS = 60 * 1000L;

  private final HuaweiAccountProperties properties;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private volatile JWKSet cachedKeys;
  private volatile long fetchedAt;
  private long lastForcedRefreshAt;

  public HuaweiJwksProvider(HuaweiAccountProperties properties) {
    this.properties = properties;
  }

  @Override
  public JWK resolve(String kid, boolean forceRefresh) {
    if (kid == null || kid.isBlank()) {
      return null;
    }
    JWKSet keys = currentKeys(forceRefresh);
    JWK key = keys == null ? null : keys.getKeyByKeyId(kid);
    if (key == null && !forceRefresh) {
      keys = currentKeys(true);
      key = keys == null ? null : keys.getKeyByKeyId(kid);
    }
    return key;
  }

  private synchronized JWKSet currentKeys(boolean forceRefresh) {
    long now = System.currentTimeMillis();
    if (cachedKeys != null && !forceRefresh && now - fetchedAt < CACHE_TTL_MS) {
      return cachedKeys;
    }
    if (forceRefresh && now - lastForcedRefreshAt < FORCED_REFRESH_INTERVAL_MS) {
      return cachedKeys;
    }
    try {
      JWKSet fetched = JWKSet.parse(fetchJwksJson());
      cachedKeys = fetched;
      fetchedAt = now;
      if (forceRefresh) {
        lastForcedRefreshAt = now;
      }
      return fetched;
    } catch (Exception ex) {
      if (cachedKeys != null) {
        return cachedKeys; // 上游抖动时继续用旧公钥，避免登录整体不可用
      }
      throw ApiException.serviceUnavailable("华为账号服务暂时不可用");
    }
  }

  private String fetchJwksJson() throws Exception {
    HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
        .uri(URI.create(properties.getJwksEndpoint()))
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .GET()
        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new IllegalStateException("JWKS endpoint returned " + response.statusCode());
    }
    return response.body();
  }
}
