package com.travelos.auth.huawei;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 华为账号（Account Kit）服务端接入配置。
 *
 * <p>client-id / client-secret 来自 AGC 项目设置 → 应用 → 开放能力 → 账号 kit；
 * 未配置时登录接口返回 503，而不是抛 500 让调用方误判为服务故障。</p>
 */
@Component
public class HuaweiAccountProperties {

  private final String clientId;
  private final String clientSecret;
  private final String issuer;
  private final String tokenEndpoint;
  private final String jwksEndpoint;
  private final String userInfoEndpoint;

  public HuaweiAccountProperties(
      @Value("${huawei.account.client-id:}") String clientId,
      @Value("${huawei.account.client-secret:}") String clientSecret,
      @Value("${huawei.account.issuer:https://accounts.huawei.com}") String issuer,
      @Value("${huawei.account.token-endpoint:https://oauth-login.cloud.huawei.com/oauth2/v3/token}")
      String tokenEndpoint,
      @Value("${huawei.account.jwks-endpoint:https://oauth-login.cloud.huawei.com/oauth2/v3/certs}")
      String jwksEndpoint,
      @Value("${huawei.account.user-info-endpoint:"
          + "https://account.cloud.huawei.com/rest.php?nsp_fmt=JSON&nsp_svc=GOpen.User.getInfo}")
      String userInfoEndpoint) {
    this.clientId = trim(clientId);
    this.clientSecret = trim(clientSecret);
    this.issuer = trim(issuer);
    this.tokenEndpoint = trim(tokenEndpoint);
    this.jwksEndpoint = trim(jwksEndpoint);
    this.userInfoEndpoint = trim(userInfoEndpoint);
  }

  public boolean isConfigured() {
    return !clientId.isEmpty() && !clientSecret.isEmpty();
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public String getIssuer() {
    return issuer;
  }

  public String getTokenEndpoint() {
    return tokenEndpoint;
  }

  public String getJwksEndpoint() {
    return jwksEndpoint;
  }

  public String getUserInfoEndpoint() {
    return userInfoEndpoint;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
