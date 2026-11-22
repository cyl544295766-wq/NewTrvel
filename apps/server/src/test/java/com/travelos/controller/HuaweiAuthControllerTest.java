package com.travelos.controller;

import com.travelos.auth.AuthService;
import com.travelos.auth.huawei.HuaweiAuthService;
import com.travelos.common.ApiException;
import com.travelos.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 华为登录接口契约：请求体校验、token 返回、Cookie 下发、上游未配置时的错误映射。
 * 不加载 Spring 上下文，因此不依赖数据库。
 */
class HuaweiAuthControllerTest {

  private HuaweiAuthService huaweiAuthService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    huaweiAuthService = mock(HuaweiAuthService.class);
    AuthService authService = mock(AuthService.class);
    when(authService.buildAuthCookie(anyString()))
        .thenReturn(ResponseCookie.from("access_token", "signed-jwt").build());
    mockMvc = MockMvcBuilders
        .standaloneSetup(new HuaweiAuthController(huaweiAuthService, authService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void returnsTokenAndUser() throws Exception {
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("id", "user-1");
    user.put("username", "hw_abc123");
    user.put("displayName", "测试用户");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("user", user);
    result.put("token", "jwt-1");
    when(huaweiAuthService.login("auth-code-1")).thenReturn(result);

    mockMvc.perform(post("/api/auth/huawei/login")
            .contentType("application/json")
            .content("{\"authCode\":\"auth-code-1\"}"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(jsonPath("$.token").value("jwt-1"))
        .andExpect(jsonPath("$.user.id").value("user-1"));
  }

  @Test
  void rejectsBlankAuthCode() throws Exception {
    mockMvc.perform(post("/api/auth/huawei/login")
            .contentType("application/json")
            .content("{\"authCode\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mapsServiceUnavailableWhenNotConfigured() throws Exception {
    when(huaweiAuthService.login(anyString()))
        .thenThrow(ApiException.serviceUnavailable("华为账号登录尚未配置"));

    mockMvc.perform(post("/api/auth/huawei/login")
            .contentType("application/json")
            .content("{\"authCode\":\"auth-code-1\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.statusCode").value(503))
        .andExpect(jsonPath("$.message").value("华为账号登录尚未配置"));
  }

  @Test
  void mapsUpstreamRejectionToUnauthorized() throws Exception {
    when(huaweiAuthService.login(anyString()))
        .thenThrow(new ApiException(401, "华为账号授权失败，请重新登录"));

    mockMvc.perform(post("/api/auth/huawei/login")
            .contentType("application/json")
            .content("{\"authCode\":\"stale-code\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.statusCode").value(401));
  }
}
