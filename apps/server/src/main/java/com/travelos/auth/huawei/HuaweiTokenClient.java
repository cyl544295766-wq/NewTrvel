package com.travelos.auth.huawei;

import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 授权码换取华为令牌（服务端到服务端）。
 *
 * <p>访问令牌只在内存中传递，不落库、不写日志：日志里只记录状态码与错误描述。</p>
 */
@Service
public class HuaweiTokenClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
  private static final Logger log = LoggerFactory.getLogger(HuaweiTokenClient.class);

  private final HuaweiAccountProperties properties;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public HuaweiTokenClient(HuaweiAccountProperties properties) {
    this.properties = properties;
  }

  public HuaweiTokenResponse exchangeAuthorizationCode(String authCode) {
    return exchangeAuthorizationCode(authCode, null);
  }

  /**
   * @param redirectUri 网页 OAuth 流程必须回传与授权请求完全一致的 redirect_uri：
   *                    授权码在服务端是绑定该地址的，缺失或不一致会被判为无效授权码。
   *                    App（原生 SDK）流程没有这一步，传 null 即可。
   */
  public HuaweiTokenResponse exchangeAuthorizationCode(String authCode, String redirectUri) {
    if (!properties.isConfigured()) {
      throw ApiException.serviceUnavailable("华为账号登录尚未配置");
    }
    String code = authCode == null ? "" : authCode.trim();
    if (code.isEmpty()) {
      throw ApiException.badRequest("authCode 不能为空");
    }
    // 授权码为一次性短期凭证（5 分钟），格式不合法时直接拒绝，避免无谓的上游调用
    if (code.length() > 1024) {
      throw ApiException.badRequest("authCode 长度不合法");
    }
    String form = "grant_type=authorization_code"
        + "&client_id=" + encode(properties.getClientId())
        + "&client_secret=" + encode(properties.getClientSecret())
        + "&code=" + encode(code);
    if (redirectUri != null && !redirectUri.isBlank()) {
      form += "&redirect_uri=" + encode(redirectUri);
    }

    HttpResponse<String> response;
    try {
      response = httpClient.send(HttpRequest.newBuilder()
          .uri(URI.create(properties.getTokenEndpoint()))
          .timeout(REQUEST_TIMEOUT)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
          .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw ApiException.serviceUnavailable("华为账号服务请求被中断");
    } catch (Exception ex) {
      throw ApiException.serviceUnavailable("华为账号服务暂时不可用");
    }

    if (response.statusCode() != 200) {
      // 授权码无效/已使用/已过期/参数不匹配都会落到这里；错误体不含令牌，可安全记录用于排查
      log.warn("华为 token 交换失败: status={}, body={}", response.statusCode(), response.body());
      throw new ApiException(401, upstreamMessage(response.body()));
    }

    Map<String, Object> body;
    try {
      body = JsonUtil.readObject(response.body());
    } catch (Exception ex) {
      throw ApiException.serviceUnavailable("华为账号服务返回内容无法解析");
    }
    String accessToken = string(body.get("access_token"));
    String idToken = string(body.get("id_token"));
    if (accessToken.isEmpty() || idToken.isEmpty()) {
      throw ApiException.serviceUnavailable("华为账号服务未返回访问令牌");
    }
    return new HuaweiTokenResponse(accessToken, idToken, string(body.get("refresh_token")),
        parseExpiresIn(body.get("expires_in")));
  }

  /** 华为错误体形如 {"error":400,"error_description":"..."}，只透出错误码，便于用户报障时不带敏感信息。 */
  private String upstreamMessage(String rawBody) {
    try {
      Map<String, Object> body = JsonUtil.readObject(rawBody);
      String code = string(body.get("error"));
      if (!code.isEmpty()) {
        return "华为账号授权失败（" + code + "），请重新登录";
      }
    } catch (Exception ignored) {
      // 错误体不是 JSON 时按通用文案返回
    }
    return "华为账号授权失败，请重新登录";
  }

  private static long parseExpiresIn(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(string(value));
    } catch (NumberFormatException ex) {
      return 0L;
    }
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** 华为令牌响应中本项目实际用到的字段。 */
  public record HuaweiTokenResponse(String accessToken, String idToken, String refreshToken,
      long expiresInSeconds) {
  }
}
