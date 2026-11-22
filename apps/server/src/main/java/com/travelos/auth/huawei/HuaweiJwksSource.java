package com.travelos.auth.huawei;

import com.nimbusds.jose.jwk.JWK;

/**
 * ID Token 公钥解析入口，便于在测试中替换为固定公钥（不依赖外网 JWKS）。
 */
public interface HuaweiJwksSource {

  /**
   * @param kid          ID Token 头部的密钥标识
   * @param forceRefresh 是否绕过缓存重新拉取
   * @return 匹配的公钥；找不到返回 null
   */
  JWK resolve(String kid, boolean forceRefresh);
}
