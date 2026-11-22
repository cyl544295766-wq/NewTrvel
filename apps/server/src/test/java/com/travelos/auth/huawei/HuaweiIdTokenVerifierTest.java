package com.travelos.auth.huawei;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.travelos.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ID Token 校验的离线用例：自签一对 RSA 密钥，覆盖验签、iss、aud、exp。
 */
class HuaweiIdTokenVerifierTest {

  private static final String CLIENT_ID = "client-123";
  private static final String ISSUER = "https://accounts.huawei.com";
  private static final String KEY_ID = "test-kid";

  private RSAKey signingKey;
  private HuaweiIdTokenVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    HuaweiAccountProperties properties = new HuaweiAccountProperties(
        CLIENT_ID, "secret", ISSUER, "https://token", "https://jwks", "https://userinfo");
    RSAKey publicKey = signingKey.toPublicJWK();
    HuaweiJwksSource jwksSource = (kid, forceRefresh) -> KEY_ID.equals(kid) ? publicKey : null;
    verifier = new HuaweiIdTokenVerifier(properties, jwksSource);
  }

  @Test
  void acceptsValidToken() throws Exception {
    String token = sign(claims(CLIENT_ID, ISSUER, 600_000L));

    HuaweiIdTokenVerifier.HuaweiIdTokenClaims claims = verifier.verify(token);

    assertEquals("open-abc", claims.openId());
    assertEquals("union-abc", claims.unionId());
  }

  @Test
  void rejectsWrongAudience() throws Exception {
    String token = sign(claims("other-client", ISSUER, 600_000L));

    ApiException ex = assertThrows(ApiException.class, () -> verifier.verify(token));
    assertEquals(401, ex.getStatusCode());
  }

  @Test
  void rejectsWrongIssuer() throws Exception {
    String token = sign(claims(CLIENT_ID, "https://evil.example.com", 600_000L));

    ApiException ex = assertThrows(ApiException.class, () -> verifier.verify(token));
    assertEquals(401, ex.getStatusCode());
  }

  @Test
  void rejectsExpiredToken() throws Exception {
    String token = sign(claims(CLIENT_ID, ISSUER, -600_000L));

    ApiException ex = assertThrows(ApiException.class, () -> verifier.verify(token));
    assertEquals(401, ex.getStatusCode());
  }

  @Test
  void rejectsTokenSignedByAnotherKey() throws Exception {
    RSAKey attackerKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    String token = sign(claims(CLIENT_ID, ISSUER, 600_000L), attackerKey);

    ApiException ex = assertThrows(ApiException.class, () -> verifier.verify(token));
    assertEquals(401, ex.getStatusCode());
  }

  @Test
  void rejectsUnknownKeyId() throws Exception {
    String token = sign(claims(CLIENT_ID, ISSUER, 600_000L),
        signingKey, "unknown-kid");

    ApiException ex = assertThrows(ApiException.class, () -> verifier.verify(token));
    assertEquals(401, ex.getStatusCode());
  }

  @Test
  void rejectsMalformedToken() {
    ApiException ex = assertThrows(ApiException.class, () -> verifier.verify("not-a-jwt"));
    assertEquals(401, ex.getStatusCode());
  }

  private JWTClaimsSet claims(String audience, String issuer, long expiresInMillis) {
    long now = System.currentTimeMillis();
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .issueTime(new Date(now))
        .expirationTime(new Date(now + expiresInMillis))
        .claim("openid", "open-abc")
        .claim("unionid", "union-abc")
        .claim("display_name", "测试用户")
        .build();
  }

  private String sign(JWTClaimsSet claims) throws Exception {
    return sign(claims, signingKey, KEY_ID);
  }

  private String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
    return sign(claims, key, KEY_ID);
  }

  private String sign(JWTClaimsSet claims, RSAKey key, String kid) throws Exception {
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
