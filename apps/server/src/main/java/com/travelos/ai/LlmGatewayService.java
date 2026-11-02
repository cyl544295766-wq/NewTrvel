package com.travelos.ai;

import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型网关（设计文档 3.5.4）：统一封装 OpenAI 兼容协议的 /chat/completions 调用，
 * 支持 tools / function calling。baseUrl / apiKey / model 全部走配置（环境变量注入），
 * 未配置 key 时 {@link #isEnabled()} 为 false，上层返回「功能未启用」友好提示。
 */
@Service
public class LlmGatewayService {

  private static final int TIMEOUT_MS = 30_000;

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final RestTemplate restTemplate;

  public LlmGatewayService(@Value("${llm.base-url:}") String baseUrl,
      @Value("${llm.api-key:}") String apiKey,
      @Value("${llm.model:}") String model) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model == null ? "" : model.trim();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(TIMEOUT_MS);
    factory.setReadTimeout(TIMEOUT_MS);
    this.restTemplate = new RestTemplate(factory);
  }

  /** 未配置 key 时 AI 功能整体降级为「未启用」。 */
  public boolean isEnabled() {
    return !apiKey.isEmpty() && !baseUrl.isEmpty() && !model.isEmpty();
  }

  public String disabledMessage() {
    return "AI 助手功能未启用：请在服务端配置 llm.base-url / llm.api-key / llm.model 后重启。"
        + "行程、预订、记账等核心功能不受影响。";
  }

  /**
   * 发起一次对话补全。
   *
   * @param messages OpenAI 消息数组 [{role, content}]（content 也可为 null，由 tool_calls 承载）
   * @param tools    function tools 定义数组，可为 null
   * @return 响应中的 choices[0].message（含 content 与/或 tool_calls）
   */
  public Map<String, Object> chat(List<Map<String, Object>> messages,
      List<Map<String, Object>> tools) {
    if (!isEnabled()) {
      throw ApiException.serviceUnavailable(disabledMessage());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    if (tools != null && !tools.isEmpty()) {
      body.put("tools", tools);
      body.put("tool_choice", "auto");
    }
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + apiKey);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> response = restTemplate.postForObject(
          baseUrl.replaceAll("/$", "") + "/chat/completions",
          entity(body, headers),
          Map.class);
      if (response == null) {
        throw ApiException.serviceUnavailable("AI 服务无响应，请稍后再试");
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
      if (choices == null || choices.isEmpty()) {
        throw ApiException.serviceUnavailable("AI 服务返回为空，请稍后再试");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
      if (message == null) {
        throw ApiException.serviceUnavailable("AI 服务返回格式异常，请稍后再试");
      }
      return message;
    } catch (RestClientException ex) {
      // 超时 / 连接失败 / HTTP 错误统一降级，绝不影响核心接口
      throw ApiException.serviceUnavailable("AI 服务暂时不可用（超时或网络异常），请稍后再试");
    }
  }


  /**
   * 流式对话补全（SSE）。逐行解析上游 `data:` 增量：正文片段实时回调
   * onDelta，tool_calls 片段按 index 拼装。返回与 {@link #chat} 相同形状的
   * message（含完整 content / tool_calls），上层工具循环可无差别复用。
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> chatStream(List<Map<String, Object>> messages,
      List<Map<String, Object>> tools, java.util.function.Consumer<String> onDelta) {
    if (!isEnabled()) {
      throw ApiException.serviceUnavailable(disabledMessage());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    body.put("stream", true);
    if (tools != null && !tools.isEmpty()) {
      body.put("tools", tools);
      body.put("tool_choice", "auto");
    }
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + apiKey);
    try {
      return restTemplate.execute(
          baseUrl.replaceAll("/$", "") + "/chat/completions",
          org.springframework.http.HttpMethod.POST,
          request -> {
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            headers.forEach((k, v) -> request.getHeaders().set(k, String.valueOf(v)));
            request.getBody().write(
                JsonUtil.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          },
          (org.springframework.web.client.ResponseExtractor<Map<String, Object>>) response -> {
            StringBuilder content = new StringBuilder();
            Map<Integer, Map<String, Object>> toolAcc = new LinkedHashMap<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.getBody(),
                    java.nio.charset.StandardCharsets.UTF_8))) {
              String line;
              while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) break;
                Map<String, Object> chunk;
                try {
                  chunk = JsonUtil.readObject(payload);
                } catch (Exception ignore) {
                  continue;
                }
                Object choicesRaw = chunk.get("choices");
                if (!(choicesRaw instanceof List<?> choices) || choices.isEmpty()) continue;
                if (!(((Map<?, ?>) choices.get(0)).get("delta") instanceof Map<?, ?> delta)) continue;

                Object contentRaw = delta.get("content");
                if (contentRaw != null && !contentRaw.toString().isEmpty()) {
                  content.append(contentRaw);
                  if (onDelta != null) onDelta.accept(contentRaw.toString());
                }
                if (delta.get("tool_calls") instanceof List<?> tcList) {
                  for (Object tcObj : tcList) {
                    if (!(tcObj instanceof Map<?, ?> tc)) continue;
                    int index = tc.get("index") instanceof Number n ? n.intValue() : 0;
                    Map<String, Object> acc =
                        toolAcc.computeIfAbsent(index, k -> new LinkedHashMap<>());
                    if (tc.get("id") != null && acc.get("id") == null) {
                      acc.put("id", String.valueOf(tc.get("id")));
                    }
                    if (tc.get("function") instanceof Map<?, ?> fn) {
                      if (fn.get("name") != null && acc.get("name") == null) {
                        acc.put("name", String.valueOf(fn.get("name")));
                      }
                      if (fn.get("arguments") != null) {
                        acc.put("arguments", String.valueOf(acc.getOrDefault("arguments", ""))
                            + fn.get("arguments"));
                      }
                    }
                  }
                }
              }
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", content.length() == 0 ? null : content.toString());
            if (!toolAcc.isEmpty()) {
              List<Map<String, Object>> calls = new ArrayList<>();
              for (Map<String, Object> acc : toolAcc.values()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", acc.getOrDefault("name", ""));
                function.put("arguments", acc.getOrDefault("arguments", ""));
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", acc.getOrDefault("id", "call_" + calls.size()));
                call.put("type", "function");
                call.put("function", function);
                calls.add(call);
              }
              message.put("tool_calls", calls);
            }
            return message;
          });
    } catch (RestClientException ex) {
      throw ApiException.serviceUnavailable("AI 服务暂时不可用（超时或网络异常），请稍后再试");
    }
  }

  /** 把网关返回的 message 规整为内部消息格式（content / toolCalls）。 */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> extractToolCalls(Map<String, Object> message) {
    Object raw = message.get("tool_calls");
    List<Map<String, Object>> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> call) {
          Map<String, Object> normalized = new LinkedHashMap<>();
          normalized.put("id", String.valueOf(call.get("id")));
          normalized.put("type", "function");
          @SuppressWarnings("unchecked")
          Map<String, Object> function = (Map<String, Object>) call.get("function");
          if (function != null) {
            normalized.put("name", String.valueOf(function.get("name")));
            normalized.put("arguments", String.valueOf(function.get("arguments")));
          }
          result.add(normalized);
        }
      }
    }
    return result;
  }

  private org.springframework.http.HttpEntity<Map<String, Object>> entity(
      Map<String, Object> body, Map<String, Object> headers) {
    org.springframework.http.HttpHeaders httpHeaders = new org.springframework.http.HttpHeaders();
    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
    headers.forEach((key, value) -> httpHeaders.set(key, String.valueOf(value)));
    return new org.springframework.http.HttpEntity<>(body, httpHeaders);
  }

  /** 供测试与诊断使用。 */
  public String model() {
    return model;
  }

  /** 静态工具：解析模型返回的 arguments JSON。 */
  public static Map<String, Object> parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return Map.of();
    }
    try {
      return JsonUtil.readObject(arguments);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  /** 会话超时时长（秒），供限流与提示使用。 */
  public static Duration timeout() {
    return Duration.ofMillis(TIMEOUT_MS);
  }
}
