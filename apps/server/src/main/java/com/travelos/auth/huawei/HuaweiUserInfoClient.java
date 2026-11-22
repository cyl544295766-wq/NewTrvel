package com.travelos.auth.huawei;

import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
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
 * 用 access_token 换取华为用户信息（openID / unionID / 昵称）。
 *
 * <p>默认使用账号开放接口 GOpen.User.getInfo：它显式返回 openID 与 unionID，
 * 比 OIDC userinfo 的 sub 语义更明确，避免把 sub 误当成 openID 造成账号串号。
 * 接口地址在 application.yml 中可替换。access_token 放在请求体而非 URL 查询串，
 * 避免出现在访问日志里。</p>
 */
@Service
public class HuaweiUserInfoClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

  private final HuaweiAccountProperties properties;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public HuaweiUserInfoClient(HuaweiAccountProperties properties) {
    this.properties = properties;
  }

  public HuaweiUserInfo fetch(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      throw ApiException.serviceUnavailable("华为账号服务未返回访问令牌");
    }
    String form = "access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
    HttpResponse<String> response;
    try {
      response = httpClient.send(HttpRequest.newBuilder()
          .uri(URI.create(properties.getUserInfoEndpoint()))
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
      throw ApiException.serviceUnavailable("华为账号服务暂时不可用");
    }
    Map<String, Object> body;
    try {
      body = JsonUtil.readObject(response.body());
    } catch (Exception ex) {
      throw ApiException.serviceUnavailable("华为账号服务返回内容无法解析");
    }
    if (body.get("error") != null) {
      throw new ApiException(401, "华为账号授权已失效，请重新登录");
    }
    String openId = firstNonEmpty(body.get("openID"), body.get("openid"));
    if (openId.isEmpty()) {
      // 缺 openID 就无法可靠定位账号，宁可失败也不要退化成用昵称/unionID 猜测
      throw ApiException.serviceUnavailable("华为账号用户信息缺少 openID");
    }
    return new HuaweiUserInfo(openId, firstNonEmpty(body.get("unionID"), body.get("unionid")),
        firstNonEmpty(body.get("displayName"), body.get("display_name")),
        firstNonEmpty(firstNonEmpty(body.get("headPictureURL"), body.get("headPictureUrl")),
            firstNonEmpty(body.get("picture"), body.get("avatarUrl"))),
        firstNonEmpty(firstNonEmpty(body.get("mobileNumber"), body.get("phoneNumber")),
            body.get("phone")));
  }

  private static String firstNonEmpty(Object first, Object second) {
    String value = string(first);
    return value.isEmpty() ? string(second) : value;
  }

  private static String firstNonEmpty(String first, String second) {
    return first == null || first.isEmpty() ? (second == null ? "" : second) : first;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  /**
   * 华为用户信息。头像是华为侧的外部 URL，写入 users.avatar_url 时须注意：
   * 该字段平时存放受管文件，清理逻辑 deleteIfManaged 只处理受管文件，
   * 因此外部 URL 不会被误删；用户自行上传头像后会被受管地址覆盖。
   */
  public record HuaweiUserInfo(String openId, String unionId, String displayName,
      String avatarUrl, String phoneNumber) {
  }
}
