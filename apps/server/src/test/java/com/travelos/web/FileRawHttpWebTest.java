package com.travelos.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /files/** 非法路径的**真实 HTTP 验收**（完整 Spring Boot 应用 + 随机端口 + 原始 Socket）。
 *
 * <p>与 MockMvc 的区别：请求行由本类按字节直接写入 Socket，未经任何客户端解码或
 * 规范化，能够复现 Tomcat 在请求解析层（URI 解码/反斜杠检查）与 Spring 无匹配
 * 路径两处入口的真实错误链路。断言同时覆盖状态码与统一 JSON 结构，
 * 因此在容器/应用错误处理未修复时会失败。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileRawHttpWebTest {

  static {
    // 与 TravelOsApplication.main 相同的数据源引导（仅调用应用自身加载器，不读取/输出内容）
    com.travelos.common.EnvLoader.loadDotEnv();
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  /** 与 FileController/FileAccessService 统一错误体保持一致 */
  private static final String EXPECTED_MESSAGE = "文件不存在";

  @LocalServerPort
  int port;

  private HttpClient http;
  private String userA;
  private String userB;
  private String pass;
  private String tokenA;
  private String tokenB;
  private String tripId;
  private String photoId;
  private String photoName;
  private byte[] payload;
  private String diskPathLeak;

  // ==================== 夹具 ====================

  @BeforeAll
  void setUp() throws Exception {
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    pass = "Rawpass123";
    String uniq = Long.toString(Math.abs(ThreadLocalRandom.current().nextLong(1_000_000_000_000L)));
    userA = ("aclrawa" + uniq).substring(0, Math.min(18, ("aclrawa" + uniq).length()));
    userB = ("aclrawb" + uniq).substring(0, Math.min(18, ("aclrawb" + uniq).length()));

    tokenA = register(userA);
    tokenB = register(userB);

    // A 创建行程并上传「可校验内容」的照片
    payload = "RAW-HTTP-ACL-PAYLOAD-0123456789".getBytes(StandardCharsets.UTF_8);
    String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(payload);

    tripId = body(call("POST", "/api/trips", tokenA,
        "{\"title\":\"raw HTTP ACL\",\"destination\":\"验城\","
            + "\"startDate\":\"2026-12-20\",\"endDate\":\"2026-12-22\"}"))
        .path("trip").path("id").asText();
    JsonNode photo = body(call("POST", "/api/trips/" + tripId + "/photos", tokenA,
        "{\"url\":\"" + dataUrl + "\",\"caption\":\"raw\"}"));
    photoId = photo.path("photo").path("id").asText();
    photoName = photo.path("photo").path("url").asText().replace("/files/", "");
    assertFalse(photoName.isBlank(), "夹具照片未创建");

    // 错误响应不得泄露的磁盘路径样例（当前进程可解析的受管根目录）
    diskPathLeak = java.nio.file.Path.of(System.getProperty("user.dir"), "data", "files")
        .toAbsolutePath().toString().toLowerCase();
  }

  @AfterAll
  void tearDown() {
    try {
      if (tripId != null) {
        if (photoId != null) {
          call("DELETE", "/api/trips/" + tripId + "/photos/" + photoId, tokenA, null);
        }
        call("DELETE", "/api/trips/" + tripId, tokenA, null);
      }
      if (tokenA != null) {
        call("POST", "/api/auth/deactivate", tokenA, "{\"password\":\"" + pass + "\"}");
      }
      if (tokenB != null) {
        call("POST", "/api/auth/deactivate", tokenB, "{\"password\":\"" + pass + "\"}");
      }
    } catch (Exception ignored) {
      // 清理失败不影响断言结果（另有 SQL 兜底）
    }
  }

  private String register(String username) throws Exception {
    HttpResponse<String> res = call("POST", "/api/auth/register", null,
        "{\"username\":\"" + username + "\",\"password\":\"" + pass + "\"}");
    assertEquals(200, res.statusCode(), "注册失败: " + res.body());
    return body(res).path("token").asText();
  }

  private HttpResponse<String> call(String method, String path, String token, String json)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (json != null) {
      b.header("Content-Type", "application/json").method(method,
          HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private JsonNode body(HttpResponse<String> res) throws Exception {
    return JSON.readTree(res.body());
  }

  // ==================== 原始 HTTP 客户端（不规范化请求行） ====================

  /** 一次原始 HTTP 往返的解析结果。 */
  static final class Raw {
    int status;
    final Map<String, String> headers = new LinkedHashMap<>();
    byte[] body = new byte[0];

    String contentType() {
      String v = headers.get("content-type");
      return v == null ? "" : v;
    }

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private Raw raw(String method, String rawPath, String authorizationHeader, String... extraHeaders)
      throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(15_000);
      StringBuilder req = new StringBuilder();
      req.append(method).append(' ').append(rawPath).append(" HTTP/1.1\r\n");
      req.append("Host: localhost:").append(port).append("\r\n");
      req.append("Connection: close\r\n");
      req.append("Accept: */*\r\n");
      if (authorizationHeader != null) {
        req.append(authorizationHeader).append("\r\n");
      }
      for (String h : extraHeaders) {
        req.append(h).append("\r\n");
      }
      req.append("\r\n");
      OutputStream out = socket.getOutputStream();
      out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      byte[] all = readAll(socket.getInputStream());
      return parse(all);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) != -1) {
      buf.write(chunk, 0, n);
    }
    return buf.toByteArray();
  }

  private static Raw parse(byte[] all) throws IOException {
    int split = indexOf(all, new byte[] {'\r', '\n', '\r', '\n'});
    if (split < 0) {
      throw new IOException("响应缺少头部终止符：" + new String(all, StandardCharsets.ISO_8859_1));
    }
    Raw r = new Raw();
    String head = new String(all, 0, split, StandardCharsets.ISO_8859_1);
    String[] lines = head.split("\r\n");
    String[] status = lines[0].split(" ");
    r.status = Integer.parseInt(status[1]);
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0) {
        r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(),
            lines[i].substring(colon + 1).trim());
      }
    }
    byte[] bodyBytes = new byte[all.length - split - 4];
    System.arraycopy(all, split + 4, bodyBytes, 0, bodyBytes.length);
    if ("chunked".equalsIgnoreCase(r.headers.getOrDefault("transfer-encoding", ""))) {
      bodyBytes = dechunk(bodyBytes);
    }
    r.body = bodyBytes;
    return r;
  }

  private static byte[] dechunk(byte[] in) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int pos = 0;
    while (pos < in.length) {
      int eol = indexOf(in, new byte[] {'\r', '\n'}, pos);
      if (eol < 0) {
        break;
      }
      String sizeLine = new String(in, pos, eol - pos, StandardCharsets.ISO_8859_1);
      int semi = sizeLine.indexOf(';');
      String sizeHex = (semi > 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
      int size;
      try {
        size = Integer.parseInt(sizeHex, 16);
      } catch (NumberFormatException ex) {
        break;
      }
      pos = eol + 2;
      if (size == 0) {
        break;
      }
      int end = Math.min(pos + size, in.length);
      out.write(in, pos, end - pos);
      pos = end + 2; // 跳过 CRLF
    }
    return out.toByteArray();
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = from; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    return indexOf(haystack, needle, 0);
  }

  private String bearer(String token) {
    return "Authorization: Bearer " + token;
  }

  // ==================== 统一断言 ====================

  /** 非法文件路径：404 + 统一 JSON + 无任何内部信息泄露。 */
  private void assertUnifiedFileNotFound(String label, Raw r) {
    assertEquals(404, r.status, label + " 应为 404，实际 " + r.status);
    assertTrue(r.contentType().toLowerCase().startsWith("application/json"),
        label + " Content-Type 应为 application/json，实际 " + r.contentType());
    JsonNode node;
    try {
      node = JSON.readTree(r.text());
    } catch (Exception ex) {
      throw new AssertionError(label + " 响应不是 JSON：" + r.text().substring(0, Math.min(200, r.text().length())));
    }
    assertEquals(EXPECTED_MESSAGE, node.path("message").asText(), label + " message");
    assertEquals("Not Found", node.path("error").asText(), label + " error");
    assertEquals(404, node.path("statusCode").asInt(), label + " statusCode");
    assertFalse(node.has("timestamp"), label + " 不得包含 timestamp 字段");
    assertFalse(node.has("path"), label + " 不得包含 path 字段");

    String lower = r.text().toLowerCase();
    assertFalse(lower.contains("<html") || lower.contains("<!doctype"), label + " 不得返回 HTML");
    assertFalse(lower.contains("tomcat"), label + " 不得泄露容器信息");
    assertFalse(lower.contains("exception") || lower.contains("stacktrace") || lower.contains("trace"),
        label + " 不得泄露堆栈");
    assertFalse(lower.contains("http status"), label + " 不得返回 Tomcat 错误页");
    assertFalse(lower.contains("root cause") || lower.contains("java.io") || lower.contains("org.apache"),
        label + " 不得泄露内部类名");
    assertFalse(r.text().toLowerCase().contains(diskPathLeak),
        label + " 不得泄露磁盘路径：" + diskPathLeak);
    assertFalse(r.text().contains(userA) || r.text().contains(userB),
        label + " 不得泄露用户 ID/用户名");
    // 任何非法路径都不得读出磁盘内容
    assertFalse(r.text().contains("RAW-HTTP-ACL-PAYLOAD"), label + " 不得读出文件内容");
  }

  // ==================== 1. 容器层编码/反斜杠/绝对路径 ====================

  @Test
  void encodedDoubleDotSlashTraversalIsUnified404() throws Exception {
    assertUnifiedFileNotFound("编码正斜杠穿越",
        raw("GET", "/files/..%2F..%2Fsecret.jpg", bearer(tokenA)));
  }

  @Test
  void encodedLowercaseTraversalIsUnified404() throws Exception {
    assertUnifiedFileNotFound("小写编码穿越",
        raw("GET", "/files/%2e%2e%2fsecret.jpg", bearer(tokenA)));
  }

  @Test
  void encodedBackslashTraversalIsUnified404() throws Exception {
    assertUnifiedFileNotFound("编码反斜杠穿越",
        raw("GET", "/files/..%5C..%5Csecret.jpg", bearer(tokenA)));
  }

  @Test
  void encodedAbsoluteWindowsPathIsUnified404() throws Exception {
    assertUnifiedFileNotFound("编码绝对路径",
        raw("GET", "/files/C%3A%5Csecret.jpg", bearer(tokenA)));
  }

  // ==================== 2. Spring 无匹配路径 ====================

  @Test
  void nestedPathIsUnified404() throws Exception {
    assertUnifiedFileNotFound("嵌套路径",
        raw("GET", "/files/sub/dir/" + photoName, bearer(tokenA)));
  }

  @Test
  void filesRootWithoutNameIsUnified404() throws Exception {
    assertUnifiedFileNotFound("/files/ 根路径", raw("GET", "/files/", bearer(tokenA)));
  }

  // ==================== 3. 文件名变形 ====================

  @Test
  void uppercaseExtensionIsUnified404() throws Exception {
    assertUnifiedFileNotFound("大写扩展名",
        raw("GET", "/files/" + photoName.toUpperCase(), bearer(tokenA)));
  }

  @Test
  void unknownExtensionIsUnified404() throws Exception {
    assertUnifiedFileNotFound("未知扩展名",
        raw("GET", "/files/" + photoName + ".txt", bearer(tokenA)));
  }

  @Test
  void doubleExtensionIsUnified404() throws Exception {
    assertUnifiedFileNotFound("双重扩展名",
        raw("GET", "/files/" + photoName + ".jpg", bearer(tokenA)));
  }

  // ==================== 4. 授权结果（无权限 vs 不存在 一致） ====================

  @Test
  void unauthorizedAndMissingResponsesAreIdentical() throws Exception {
    Raw denied = raw("GET", "/files/" + photoName, bearer(tokenB));   // 合法文件、非属主
    Raw missing = raw("GET", "/files/" + "ab".repeat(8) + "cd".repeat(8)
        + "ef".repeat(8) + "12345678.jpg", bearer(tokenA));            // 合法文件名、不存在
    assertUnifiedFileNotFound("无权限", denied);
    assertUnifiedFileNotFound("不存在", missing);
    assertEquals(missing.text(), denied.text(), "无权限与不存在的响应体必须完全一致");
  }

  // ==================== 5. 合法文件行为不变 ====================

  @Test
  void unauthenticatedGetAndHeadRemain401() throws Exception {
    Raw get = raw("GET", "/files/" + photoName, null);
    assertEquals(401, get.status, "未登录 GET");
    assertTrue(get.contentType().toLowerCase().startsWith("application/json"));
    assertEquals(401, JSON.readTree(get.text()).path("statusCode").asInt());

    Raw head = raw("HEAD", "/files/" + photoName, null);
    assertEquals(401, head.status, "未登录 HEAD");
    assertEquals(401, JSON.readTree(head.text().isEmpty() ? "{}" : head.text()).path("statusCode")
        .asInt(401));
  }

  @Test
  void ownerGetAndHeadRemain200() throws Exception {
    Raw get = raw("GET", "/files/" + photoName, bearer(tokenA));
    assertEquals(200, get.status,
        "GET status=" + get.status + " headers=" + get.headers + " body=" + get.text());
    assertTrue(get.contentType().startsWith("image/png"), "Content-Type: " + get.contentType());
    assertEquals(payload.length, Integer.parseInt(get.headers.getOrDefault("content-length", "-1")),
        "Content-Length");
    org.junit.jupiter.api.Assertions.assertArrayEquals(payload, get.body, "属主 GET 内容必须正确");

    Raw head = raw("HEAD", "/files/" + photoName, bearer(tokenA));
    assertEquals(200, head.status,
        "HEAD status=" + head.status + " headers=" + head.headers + " body=" + head.text());
    assertEquals(0, head.body.length, "HEAD 不得返回响应体");
    assertEquals(String.valueOf(payload.length),
        head.headers.getOrDefault("content-length", ""), "HEAD Content-Length");
    assertTrue(head.contentType().startsWith("image/png"), "HEAD Content-Type");
  }

  @Test
  void rangeRemains206WithPartialContent() throws Exception {
    Raw r = raw("GET", "/files/" + photoName, bearer(tokenA), "Range: bytes=0-9");
    assertEquals(206, r.status);
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        java.util.Arrays.copyOfRange(payload, 0, 10), r.body, "Range 分段内容");
    assertEquals("bytes 0-9/" + payload.length,
        r.headers.getOrDefault("content-range", ""), "Content-Range");
  }

  // ==================== 6. 非文件 API 的 400 保持原状 ====================

  @Test
  void nonFileApiBadRequestKeepsItsOwnJson() throws Exception {
    HttpResponse<String> res = call("POST", "/api/trips", tokenA, "{\"title\":\"\"}");
    assertEquals(400, res.statusCode(), "非文件 API 参数错误仍应为 400：" + res.body());
    JsonNode node = JSON.readTree(res.body());
    assertEquals(400, node.path("statusCode").asInt(), "保持 statusCode=400");
    assertTrue(node.has("message"), "保持原有 message 字段");
    assertEquals("Bad Request", node.path("error").asText(), "保持 error=Bad Request");
    assertNotEquals(404, node.path("statusCode").asInt(), "不得把 400 变成 404");
  }
}
