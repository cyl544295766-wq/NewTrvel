package com.travelos.auth.huawei;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.travelos.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 校验华为 ID Token：签名（JWKS 公钥）、iss、aud（必须等于本应用 client_id）、exp。
 *
 * <p>ID Token 是客户端拿到授权码的凭据来源，验签通过才说明该令牌由华为签发给本应用；
 * 身份标识（openID）仍以 userinfo 接口为准，这里的 openid 仅用于交叉校验。</p>
 */
@Service
public class HuaweiIdTokenVerifier {

  /** 允许的时钟偏移：华为侧与本机时间可能相差数十秒 */
  private static final long CLOCK_SKEW_SECONDS = 60L;
  private static final int MAX_TOKEN_LENGTH = 4096;

  private final HuaweiAccountProperties properties;
  private final HuaweiJwksSource jwksSource;

  public HuaweiIdTokenVerifier(HuaweiAccountProperties properties, HuaweiJwksSource jwksSource) {
    this.properties = properties;
    this.jwksSource = jwksSource;
  }

  public HuaweiIdTokenClaims verify(String idToken) {
    if (idToken == null || idToken.isBlank() || idToken.length() > MAX_TOKEN_LENGTH) {
      throw ApiException.unauthorized("华为身份凭证不合法");
    }
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(idToken);
    } catch (Exception ex) {
      throw ApiException.unauthorized("华为身份凭证不合法");
    }
    // 只接受 RSA-SHA256：华为 ID Token 固定使用 RS256，放开其它算法会失去算法混淆防护
    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
      throw ApiException.unauthorized("华为身份凭证签名算法不受支持");
    }
    JWK jwk = jwksSource.resolve(jwt.getHeader().getKeyID(), false);
    if (jwk == null || !KeyType.RSA.equals(jwk.getKeyType())) {
      throw ApiException.unauthorized("华为身份凭证签名无法验证");
    }
    RSAKey rsaKey = jwk.toRSAKey();
    try {
      if (!jwt.verify(new RSASSAVerifier(rsaKey))) {
        throw ApiException.unauthorized("华为身份凭证签名校验失败");
      }
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw ApiException.unauthorized("华为身份凭证签名校验失败");
    }

    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (Exception ex) {
      throw ApiException.unauthorized("华为身份凭证不合法");
    }
    validateIssuer(claims);
    validateAudience(claims);
    validateLifetime(claims);

    return new HuaweiIdTokenClaims(
        claim(claims, "openid"),
        claim(claims, "unionid"),
        // 昵称优先取 nickname：userinfo 接口的 displayName 在用户未设置昵称时返回掩码手机号，
        // 而 ID Token 的 nickname / display_name 是账号中心里真实展示的昵称
        firstNonEmpty(claim(claims, "nickname"), claim(claims, "display_name")),
        claim(claims, "picture"));
  }

  private static String firstNonEmpty(String first, String second) {
    return first == null || first.isBlank() ? (second == null ? "" : second) : first;
  }

  private void validateIssuer(JWTClaimsSet claims) {
    String issuer = claims.getIssuer();
    if (issuer == null || !issuer.equals(properties.getIssuer())) {
      throw ApiException.unauthorized("华为身份凭证签发方不匹配");
    }
  }

  private void validateAudience(JWTClaimsSet claims) {
    List<String> audience = claims.getAudience();
    String clientId = properties.getClientId();
    if (audience == null || clientId.isEmpty() || !audience.contains(clientId)) {
      throw ApiException.unauthorized("华为身份凭证不属于本应用");
    }
  }

  private void validateLifetime(JWTClaimsSet claims) {
    Date expiration = claims.getExpirationTime();
    if (expiration == null) {
      throw ApiException.unauthorized("华为身份凭证缺少有效期");
    }
    long now = System.currentTimeMillis();
    if (expiration.getTime() + CLOCK_SKEW_SECONDS * 1000L < now) {
      throw ApiException.unauthorized("华为身份凭证已过期，请重新登录");
    }
    Date issuedAt = claims.getIssueTime();
    if (issuedAt != null && issuedAt.getTime() - CLOCK_SKEW_SECONDS * 1000L > now) {
      throw ApiException.unauthorized("华为身份凭证签发时间异常");
    }
  }

  private static String claim(JWTClaimsSet claims, String name) {
    try {
      Object value = claims.getClaim(name);
      return value == null ? "" : String.valueOf(value).trim();
    } catch (Exception ex) {
      return "";
    }
  }

  /** 验签通过后从 ID Token 中读到的可选身份字段（空串表示该声明不存在）。 */
  public record HuaweiIdTokenClaims(String openId, String unionId, String displayName,
      String picture) {
  }
}
