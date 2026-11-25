package com.travelos.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.travelos.common.JsonUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=30"
})
class AiReceiptWebTest {
  private static final AtomicInteger VISION_CALLS = new AtomicInteger();
  private static final String DATE = "2027-03-04";
  private static final Map<String, Object> RECEIPT = Map.of(
      "kind", "expense", "title", "Harbor Cafe", "date", DATE, "amount", "72.50", "currency", "USD",
      "subtotal", "65.00", "tax", "5.00", "orderNo", "private-123456789", "rawText", "untrusted image text");
  private static volatile VisionReply visionReply;
  private static HttpServer vision;
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void visionConfig(DynamicPropertyRegistry registry) throws IOException {
    vision = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    vision.createContext("/api/chat/completions", exchange -> {
      try (exchange) {
        var body = JsonUtil.mapper().readTree(exchange.getRequestBody());
        if (!"fixture-vision".equals(body.path("model").asText()) ||
            !body.at("/messages/1/content/1/image_url/url").asText().startsWith("data:image/jpeg;base64,")) {
          exchange.sendResponseHeaders(400, -1);
          return;
        }
        VISION_CALLS.incrementAndGet();
        VisionReply reply = visionReply;
        if (reply.delayMs() > 0) {
          try {
            Thread.sleep(reply.delayMs());
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
        byte[] response = JsonUtil.write(Map.of("choices", List.of(Map.of("message",
            Map.of("content", JsonUtil.write(reply.fields())))))).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), response.length);
        exchange.getResponseBody().write(response);
      }
    });
    vision.start();
    registry.add("llm.base-url", () -> "http://127.0.0.1:" + vision.getAddress().getPort() + "/api");
    registry.add("llm.api-key", () -> "fixture-key");
    registry.add("llm.model", () -> "fixture-text");
    registry.add("llm.vision-model", () -> "fixture-vision");
    registry.add("llm.vision-timeout-ms", () -> 1_100);
  }

  @BeforeEach
  void receiptModel() {
    visionReply = new VisionReply(200, 0, RECEIPT);
  }

  @AfterAll
  static void stopVision() {
    if (vision != null) vision.stop(0);
  }

  @Test
  void receiptNeedsReviewAndConfirmationAndPreservesEditedUsdAmount() throws Exception {
    Fixture f = fixture();
    String image = image("Harbor Cafe $72.50");
    int before = VISION_CALLS.get();
    JsonNode upload = upload(f, image);
    String id = upload.path("messageId").asText();
    assertEquals(upload, upload(f, image));
    assertEquals(before + 1, VISION_CALLS.get());
    JsonNode original = message(f, id);
    assertEquals("72.50", original.at("/draft/receiptReview/fields/amount").asText());
    assertEquals("65.00", original.at("/draft/receiptReview/fields/subtotal").asText());
    assertEquals("5.00", original.at("/draft/receiptReview/fields/tax").asText());
    assertTrue(original.at("/draft/receiptReview/uncertain").toString().contains("amount"));
    String json = original.toString();
    for (String privateValue : List.of("private-123456789", "untrusted image text", "receiptFingerprint", "base64")) {
      assertFalse(json.contains(privateValue));
    }
    assertEquals(400, send(f.user(), "POST", apply(f, id), Map.of()).statusCode());
    assertEquals(400, send(f.user(), "POST", confirm(f, id),
        Map.of("kind", "expense", "title", "Harbor Cafe", "date", DATE, "currency", "USD", "amount", "70.00")).statusCode());
    Map<String, Object> fields = Map.of("kind", "expense", "title", "Harbor Cafe", "date", DATE,
        "currency", "USD", "amount", "70.00", "useDefaultSplit", true);
    var first = async(f, confirm(f, id), fields);
    var second = async(f, confirm(f, id), fields);
    assertEquals(200, first.join().statusCode(), first.join().body());
    assertEquals(200, second.join().statusCode(), second.join().body());
    assertEquals("expense", message(f, id).at("/draft/type").asText());
    assertEquals(0, count(f, "trip_expenses"));
    var applied = ok(f.user(), "POST", apply(f, id), Map.of());
    assertEquals(applied, ok(f.user(), "POST", apply(f, id), Map.of()));
    assertEquals(1, count(f, "trip_expenses"));
    assertEquals("USD 70.00", jdbc.queryForObject("SELECT currency || ' ' || round(amount, 2)::text FROM trip_expenses WHERE trip_id=?",
        String.class, f.trip()));
    assertEquals(f.user().id(), jdbc.queryForObject("SELECT payer_user_id FROM trip_expenses WHERE trip_id=?", String.class, f.trip()));
    assertEquals(id, upload(f, image).path("messageId").asText());
    var differentCrop = upload(f, image("Harbor Cafe duplicate crop"));
    String duplicate = confirm(f, differentCrop.path("messageId").asText());
    var warning = send(f.user(), "POST", duplicate, fields);
    assertEquals(409, warning.statusCode(), warning.body());
    assertTrue(warning.body().contains("疑似重复"), warning.body());
    assertEquals(1, count(f, "trip_expenses"));
  }

  @Test
  void hotelAndFlightUseBookingDatesAndOriginalCurrency() throws Exception {
    Fixture f = fixture();
    booking(f, "hotel", "Osaka Hotel", "2027-03-04", "2027-03-05", "12500", "JPY");
    booking(f, "flight", "NH 806", "2027-03-06T12:45", "2027-03-06T16:30", "174.20", "USD");
    assertEquals(List.of("flight:USD", "hotel:JPY"), jdbc.queryForList(
        "SELECT type || ':' || currency FROM bookings WHERE trip_id=? ORDER BY type", String.class, f.trip()));
    assertEquals("Asia/Shanghai", jdbc.queryForObject(
        "SELECT start_timezone FROM bookings WHERE trip_id=? AND type='flight'", String.class, f.trip()));
    assertEquals("Asia/Tokyo", jdbc.queryForObject(
        "SELECT end_timezone FROM bookings WHERE trip_id=? AND type='flight'", String.class, f.trip()));
  }

  @Test
  void malformedOrUnavailableRecognitionPreservesManualAndDiscardFlows() throws Exception {
    Fixture f = fixture();
    visionReply = new VisionReply(415, 0, Map.of());
    String unreadable = upload(f, image("unsupported image provider")).path("messageId").asText();
    assertEquals("manual", message(f, unreadable).at("/draft/receiptReview/recognition").asText());
    ok(f.user(), "POST", path(f) + "/discard/" + unreadable, Map.of());
    assertEquals(0, count(f, "bookings"));
    visionReply = new VisionReply(200, 1_500, RECEIPT);
    String timeout = upload(f, image("timeout")).path("messageId").asText();
    assertEquals("manual", message(f, timeout).at("/draft/receiptReview/recognition").asText());
    ok(f.user(), "POST", confirm(f, timeout), Map.of("kind", "hotel", "title", "手填酒店", "startTime", DATE));
    assertEquals(0, count(f, "bookings"));
    ok(f.user(), "POST", apply(f, timeout), Map.of());
    assertEquals(1, count(f, "bookings"));
  }

  @Test
  void expenseDoesNotGuessCurrencyAndHonorsCustomAmountSplit() throws Exception {
    Fixture f = fixture();
    visionReply = new VisionReply(200, 0, Map.of("kind", "expense", "title", "收据",
        "date", "2027-02-30", "amount", "18.40", "currency", "$", "uncertain", List.of("amount")));
    String id = upload(f, image("blurry receipt")).path("messageId").asText();
    JsonNode review = message(f, id).at("/draft/receiptReview");
    assertTrue(review.at("/fields/currency").isMissingNode());
    assertTrue(review.at("/fields/date").isMissingNode());
    assertTrue(review.path("uncertain").toString().contains("currency"));
    var input = Map.of("kind", "expense", "title", "收据", "date", DATE, "amount", "18.40", "currency", "CAD",
        "payer", f.user().id(), "splitType", "amount", "participants",
        List.of(Map.of("member", f.user().id(), "shareAmount", "18.40")));
    ok(f.user(), "POST", confirm(f, id), input);
    assertEquals("amount", message(f, id).at("/draft/expensePreview/splitType").asText());
    ok(f.user(), "POST", apply(f, id), Map.of());
    assertEquals(1, count(f, "trip_expenses"));
  }

  @Test
  void attachmentMustBeAuthorizedForTheSameTripAndMatchTheFileType() throws Exception {
    Fixture f = fixture();
    String image = image("authorized image");
    var photo = ok(f.user(), "POST", "/api/trips/" + f.trip() + "/photos", Map.of("url", image));
    String url = photo.at("/photo/url").asText();
    Fixture another = fixture(f.user());
    int before = VISION_CALLS.get();
    assertEquals(404, send(another.user(), "POST", path(another) + "/receipts", Map.of("fileUrl", url)).statusCode());
    assertEquals(404, send(register(), "POST", path(f) + "/receipts", Map.of("fileUrl", url)).statusCode());
    assertEquals(400, send(f.user(), "POST", path(f) + "/receipts",
        Map.of("image", image.replace("image/png", "image/jpeg"))).statusCode());
    assertEquals(400, send(f.user(), "POST", path(f) + "/receipts",
        Map.of("image", image, "tripId", another.trip())).statusCode());
    assertEquals(404, send(f.user(), "POST", path(f) + "/receipts", Map.of("fileUrl", "https://example.com/private.png")).statusCode());
    assertEquals(before, VISION_CALLS.get());
    var result = ok(f.user(), "POST", path(f) + "/receipts", Map.of("fileUrl", url));
    assertEquals(upload(f, image), result);
  }

  private void booking(Fixture f, String kind, String title, String start, String end, String amount,
      String currency) throws Exception {
    Map<String, Object> fields = Map.of("kind", kind, "title", title, "startTime", start, "endTime", end,
        "amount", amount, "currency", currency, "endTimezone", "flight".equals(kind) ? "Asia/Tokyo" : "Asia/Shanghai");
    visionReply = new VisionReply(200, 0, fields);
    String id = upload(f, image(title + " " + start)).path("messageId").asText();
    assertEquals(start, message(f, id).at("/draft/receiptReview/fields/startTime").asText());
    int bookingsBefore = count(f, "bookings");
    ok(f.user(), "POST", confirm(f, id), fields);
    assertEquals(bookingMoney(amount, currency), message(f, id).at("/draft/booking/currency").asText() + " "
        + message(f, id).at("/draft/booking/amount").asText());
    assertEquals(bookingsBefore, count(f, "bookings"));
    ok(f.user(), "POST", apply(f, id), Map.of());
    assertEquals(bookingsBefore + 1, count(f, "bookings"));
  }

  private String bookingMoney(String amount, String currency) {
    return currency + " " + amount;
  }

  private static String image(String text) throws IOException {
    var image = new BufferedImage(460, 90, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 460, 90);
      graphics.setColor(Color.BLACK);
      graphics.drawString(text, 12, 32);
    } finally {
      graphics.dispose();
    }
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
  }

  private Fixture fixture() throws Exception {
    return fixture(register());
  }

  private Fixture fixture(User user) throws Exception {
    String trip = ok(user, "POST", "/api/trips", Map.of("title", "测试旅行", "destination", "大阪",
        "startDate", DATE, "endDate", "2027-03-07")).at("/trip/id").asText();
    String conversation = ok(user, "POST", "/api/ai/conversations", Map.of("tripId", trip)).at("/conversation/id").asText();
    return new Fixture(user, trip, conversation);
  }

  private User register() throws Exception {
    String username = "receipt" + UUID.randomUUID().toString().substring(0, 8);
    String token = ok(null, "POST", "/api/auth/register", Map.of("username", username, "password", "Receipt-test-2027"))
        .path("token").asText();
    return new User(token, jdbc.queryForObject("SELECT id FROM users WHERE username=?", String.class, username));
  }

  private int count(Fixture f, String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE trip_id=?", Integer.class, f.trip());
  }

  private JsonNode upload(Fixture f, String image) throws Exception {
    return ok(f.user(), "POST", path(f) + "/receipts", Map.of("image", image));
  }

  private JsonNode message(Fixture f, String messageId) throws Exception {
    for (JsonNode message : ok(f.user(), "GET", path(f) + "/messages?latest=true", Map.of()).path("messages")) {
      if (messageId.equals(message.path("id").asText())) return message;
    }
    throw new AssertionError("Receipt message missing: " + messageId);
  }

  private static String path(Fixture f) {
    return "/api/ai/conversations/" + f.conversation();
  }

  private static String confirm(Fixture f, String id) {
    return path(f) + "/receipts/" + id + "/confirm";
  }

  private static String apply(Fixture f, String id) {
    return path(f) + "/apply/" + id;
  }

  private JsonNode ok(User user, String method, String path, Object body) throws Exception {
    var response = send(user, method, path, body);
    assertEquals(200, response.statusCode(), response.body());
    return JsonUtil.mapper().readTree(response.body());
  }

  private HttpResponse<String> send(User user, String method, String path, Object body) throws Exception {
    return http.send(request(user, method, path, body), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> async(Fixture f, String path, Object body) {
    return http.sendAsync(request(f.user(), "POST", path, body), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest request(User user, String method, String path, Object body) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(18)).header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(JsonUtil.write(body)));
    if (user != null) request.header("Authorization", "Bearer " + user.token());
    return request.build();
  }

  private record VisionReply(int status, long delayMs, Map<String, Object> fields) {}
  private record User(String token, String id) {}
  private record Fixture(User user, String trip, String conversation) {}
}
