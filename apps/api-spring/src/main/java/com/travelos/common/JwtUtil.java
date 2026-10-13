package com.travelos.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Minimal HS256 JWT implementation (sign + verify), compatible with @nestjs/jwt tokens. */
public final class JwtUtil {

  private JwtUtil() {
  }

  public static String sign(String secret, Map<String, Object> payload, long expiresInSeconds) {
    long nowSeconds = System.currentTimeMillis() / 1000;
    Map<String, Object> claims = new LinkedHashMap<>(payload);
    claims.put("iat", nowSeconds);
    claims.put("exp", nowSeconds + expiresInSeconds);
    String header = base64Url(toJson(Map.of("alg", "HS256", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
    String body = base64Url(toJson(claims).getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + body;
    return signingInput + "." + base64Url(hmacSha256(secret, signingInput));
  }

  /** @return payload map when valid and unexpired, otherwise null */
  public static Map<String, Object> verify(String secret, String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        return null;
      }
      byte[] expected = hmacSha256(secret, parts[0] + "." + parts[1]);
      byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
      if (!MessageDigest.isEqual(expected, actual)) {
        return null;
      }
      Map<String, Object> payload = JsonUtil.readObject(new String(Base64.getUrlDecoder().decode(parts[1]),
          StandardCharsets.UTF_8));
      Object exp = payload.get("exp");
      if (exp instanceof Number seconds && seconds.longValue() < System.currentTimeMillis() / 1000) {
        return null;
      }
      return payload;
    } catch (Exception ex) {
      return null;
    }
  }

  private static byte[] hmacSha256(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String toJson(Map<String, Object> map) {
    return JsonUtil.write(map);
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
