package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelos.common.EnvLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未配置模型（llm.* 全空）的本地降级路径：普通与流式各返回一条“未启用”回复，
 * 不调用模型、不报错、不重复消息（既有业务价值保持）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "llm.base-url=",
    "llm.api-key=",
    "llm.model="
})
class AiDisabledWebTest {

  static {
    EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "Webpass123";

  @LocalServerPort
  int port;

  private HttpClient http;
  private String token;
  private String conv;

  @BeforeAll
  void createFixture() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String suffix = String.format("%09d",
        Math.abs(ThreadLocalRandom.current().nextLong(1_000_000_000L)));
    HttpResponse<String> reg = post("/api/auth/register", null,
        "{\"username\":\"aidis" + suffix + "\",\"password\":\"" + PASSWORD + "\"}");
    assertEquals(200, reg.statusCode(), reg.body());
    token = JSON.readTree(reg.body()).path("token").asText();
    conv = JSON.readTree(post("/api/ai/conversations", token, "{}").body())
        .path("conversation").path("id").asText();
  }

  private HttpRequest.Builder req(String path) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token);
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> res = http.send(b.method("POST",
            HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return res;
  }

  @Test
  void normalSendReturnsSingleDisabledReply() throws Exception {
    HttpResponse<String> res = post("/api/ai/conversations/" + conv + "/messages", token,
        "{\"content\":\"你好\"}");
    assertEquals(200, res.statusCode(), res.body());
    JsonNode body = JSON.readTree(res.body());
    String reply = body.path("assistantMessage").path("content").asText();
    assertTrue(reply.contains("未启用"), reply);

    // 再发一条：各自只有一条助手降级（不重复）
    HttpResponse<String> res2 = post("/api/ai/conversations/" + conv + "/messages", token,
        "{\"content\":\"再来一条\"}");
    assertEquals(200, res2.statusCode());
    JsonNode msgs = JSON.readTree(listMessages());
    int assistants = 0;
    for (JsonNode m : msgs.path("messages")) {
      if ("assistant".equals(m.path("role").asText())) {
        assistants++;
      }
    }
    assertEquals(2, assistants, "两次发送各一条降级回复");
  }

  private String listMessages() throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port
            + "/api/ai/conversations/" + conv + "/messages?latest=true"))
        .header("Authorization", "Bearer " + token)
        .GET().build();
    return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
  }

  @Test
  void streamSendReturnsDoneWithDisabledReply() throws Exception {
    HttpRequest req = req("/api/ai/conversations/" + conv + "/messages/stream")
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"content\":\"流式一条\",\"clientRequestId\":\"aidis-stream-0001\"}",
            StandardCharsets.UTF_8))
        .build();
    HttpResponse<String> res = http.send(req,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, res.statusCode(), res.body());
    assertTrue(res.headers().firstValue("content-type").orElse("").contains("text/event-stream"),
        "应为 SSE：" + res.headers());
    String body = res.body();
    assertTrue(body.contains("event:done"), "应有 done 事件：" + body);
    assertTrue(body.contains("未启用"), "done 应携带未启用回复：" + body);
  }
}
