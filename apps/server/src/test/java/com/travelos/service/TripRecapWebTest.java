package com.travelos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelos.ai.LlmGatewayService;
import com.travelos.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=30"
})
class TripRecapWebTest {
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @MockBean LlmGatewayService model;

  @Test
  void realMaterialsAndLedgerBecomeAnAuthorOnlyDraft() throws Exception {
    Fixture fixture = fixture();
    String otherPhoto = photo(fixture.trip(), fixture.day(), null, "无关照片", false);
    String harborPhoto = photo(fixture.trip(), fixture.day(), fixture.completed(), "港口晨光", false);
    String cafePhoto = photo(fixture.trip(), fixture.day(), null, "窗边咖啡", true);
    String bakery = place(fixture.trip(), null, "面包店", "回来前买了一袋面包", false);
    expense(fixture, "船票", "120", "CNY", null);
    expense(fixture, "旅馆", "100", "USD", null);
    expense(fixture, "旅馆退款", "-20", "USD", "refund");
    JsonNode materials = ok(fixture.user(), "GET", fixture.path() + "/materials", null);
    assertEquals(3, materials.path("photos").size());
    assertEquals(4, materials.path("notes").size());
    assertTrue(materials.toString().contains("/files/"));
    assertFalse(materials.toString().contains("data:image"));

    AtomicReference<String> prompt = new AtomicReference<>();
    when(model.isEnabled()).thenReturn(true);
    when(model.chat(anyList(), anyList())).thenAnswer(call -> {
      prompt.set(JsonUtil.write(call.getArgument(0)));
      return Map.of("content", "标题：港口回顾\n这次在港口停留并记下了渡船经历。");
    });
    Map<String, Object> selection = Map.of("requestId", "example-recap",
        "photoIds", List.of(cafePhoto, harborPhoto),
        "noteIds", List.of("place:" + fixture.completed(), "day:" + fixture.day(), "place:" + bakery));
    JsonNode journal = ok(fixture.user(), "POST", fixture.path(), selection).path("journal");
    String id = journal.path("id").asText();
    String content = journal.path("content").asText();
    assertEquals("港口回顾", journal.path("title").asText());
    assertTrue(journal.path("isDraft").asBoolean());
    assertEquals("private", journal.path("visibility").asText());
    assertEquals(fixture.user().id(), journal.path("authorId").asText());
    assertEquals(cafePhoto, journal.at("/photos/0/id").asText());
    assertEquals(harborPhoto, journal.at("/photos/1/id").asText());
    assertTrue(content.contains("已标记完成\n2026-09-02 · 港口"));
    assertTrue(content.contains("原计划（完成状态未确认）\n2026-09-02 · 观景台"));
    assertTrue(content.contains("渡船改从东码头出发"));
    assertTrue(content.contains("午后看见退潮"));
    assertTrue(content.contains("面包店：回来前买了一袋面包"));
    assertTrue(content.contains("费用小结（原币）"));
    assertTrue(content.contains("CNY 120.00"));
    assertTrue(content.contains("USD 80.00"));
    assertTrue(content.contains("旅馆退款 · USD -20.00"));
    assertFalse(content.contains("未选择的私人片段"));
    assertFalse(content.contains("无关照片"));
    assertTrue(prompt.get().contains("港口晨光"));
    assertTrue(prompt.get().contains("渡船改从东码头出发"));
    for (String excluded : List.of("旅馆退款", "USD", "观景台", "未选择的私人片段", "无关照片", otherPhoto, "/files/", "data:image")) {
      assertFalse(prompt.get().contains(excluded), excluded);
    }
    assertEquals(id, ok(fixture.user(), "POST", fixture.path(), selection).at("/journal/id").asText());
    assertEquals(1, draftCount(fixture.trip()));

    User editor = register();
    addMember(fixture, editor, "member");
    assertEquals(403, send(editor, "GET", journals(fixture.trip()) + "/" + id, null).statusCode());
    assertEquals(0, ok(editor, "GET", journals(fixture.trip()), null).path("journals").size());
  }

  @Test
  void rejectsForeignMaterialsViewersAndTripsStillInProgress() throws Exception {
    Fixture fixture = fixture();
    Fixture unrelated = fixture();
    String foreignPhoto = photo(unrelated.trip(), unrelated.day(), null, "别人的照片", true);
    assertEquals(400, send(fixture.user(), "POST", fixture.path(),
        Map.of("photoIds", List.of(foreignPhoto))).statusCode());
    assertEquals(400, send(fixture.user(), "POST", fixture.path(),
        Map.of("noteIds", List.of("day:" + unrelated.day()))).statusCode());
    assertEquals(400, send(fixture.user(), "POST", fixture.path(),
        Map.of("photoIds", List.of(foreignPhoto, foreignPhoto))).statusCode());
    User viewer = register();
    addMember(fixture, viewer, "viewer");
    for (User denied : List.of(viewer, unrelated.user())) {
      assertEquals(403, send(denied, "GET", fixture.path() + "/materials", null).statusCode());
      assertEquals(403, send(denied, "POST", fixture.path(), Map.of()).statusCode());
    }
    String upcoming = createTrip(fixture.user(), "2099-03-04", "2099-03-05");
    assertEquals(400, send(fixture.user(), "GET", journals(upcoming) + "/recap/materials", null).statusCode());
    assertEquals(400, send(fixture.user(), "POST", journals(upcoming) + "/recap", null).statusCode());
    assertEquals(0, draftCount(fixture.trip()));
    verifyNoInteractions(model);
  }

  @Test
  void modelFailureRetryAndConcurrentDuplicateKeepExactlyOneDraft() throws Exception {
    Fixture fixture = fixture();
    var selection = Map.of("requestId", "retryable-recap", "includeExpenses", false,
        "noteIds", List.of("day:" + fixture.day()));
    when(model.isEnabled()).thenReturn(true);
    when(model.chat(anyList(), anyList())).thenThrow(new IllegalStateException("model failure"));
    assertEquals(503, send(fixture.user(), "POST", fixture.path(), selection).statusCode());
    assertEquals(0, draftCount(fixture.trip()));
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> {
      started.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
      return Map.of("content", "标题：回顾草稿\n记录了这次港口行程。");
    }).when(model).chat(anyList(), anyList());

    var first = http.sendAsync(request(fixture.user(), "POST", fixture.path(), selection), HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(started.await(8, TimeUnit.SECONDS));
      assertEquals(429, send(fixture.user(), "POST", fixture.path(), selection).statusCode());
    } finally {
      release.countDown();
    }
    var response = first.get(15, TimeUnit.SECONDS);
    assertEquals(200, response.statusCode(), response.body());
    JsonNode journal = JsonUtil.mapper().readTree(response.body()).path("journal");
    assertFalse(journal.path("content").asText().contains("费用小结"));
    assertTrue(journal.path("content").asText().contains("午后看见退潮"));
    assertEquals(journal.path("id"), ok(fixture.user(), "POST", fixture.path(), selection).at("/journal/id"));
    assertEquals(409, send(fixture.user(), "POST", fixture.path(),
        Map.of("requestId", "retryable-recap", "includeExpenses", true)).statusCode());
    assertEquals(1, draftCount(fixture.trip()));
  }

  @Test
  void bodylessOneClickWorksOnAnEmptyTripAndReopensTheSameDraft() throws Exception {
    User user = register();
    String trip = createTrip(user, "2026-09-02", "2026-09-02");
    String path = journals(trip) + "/recap";
    JsonNode draft = ok(user, "POST", path, null).path("journal");
    assertTrue(draft.path("content").asText().contains("无记账记录"));
    assertTrue(draft.path("isDraft").asBoolean());
    assertEquals(draft.path("id"), ok(user, "POST", path, Map.of()).at("/journal/id"));
    assertEquals(1, draftCount(trip));
    verifyNoInteractions(model);
  }

  private Fixture fixture() throws Exception {
    User user = register();
    String trip = createTrip(user, "2026-09-02", "2026-09-03");
    String day = ok(user, "POST", "/api/trips/" + trip + "/days/generate", Map.of()).at("/days/0/id").asText();
    jdbc.update("UPDATE trip_days SET summary = ? WHERE id = ?", "午后看见退潮", day);
    String completed = place(trip, day, "港口", "渡船改从东码头出发", true);
    place(trip, day, "观景台", "未选择的私人片段", false);
    return new Fixture(user, trip, day, completed);
  }

  private User register() throws Exception {
    String username = "recap" + UUID.randomUUID().toString().substring(0, 8);
    JsonNode result = ok(null, "POST", "/api/auth/register",
        Map.of("username", username, "password", "Recap-test-2027"));
    return new User(result.path("token").asText(), result.at("/user/id").asText());
  }

  private String createTrip(User user, String start, String end) throws Exception {
    return ok(user, "POST", "/api/trips", Map.of("title", "港口之旅", "destination", "大连",
        "startDate", start, "endDate", end)).at("/trip/id").asText();
  }

  private String place(String trip, String day, String name, String notes, boolean completed) {
    String id = UUID.randomUUID().toString();
    jdbc.update("""
        INSERT INTO trip_places (id, trip_id, trip_day_id, name, notes, is_completed, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, now())
        """, id, trip, day, name, notes, completed);
    return id;
  }

  private String photo(String trip, String day, String place, String caption, boolean legacy) {
    String id = UUID.randomUUID().toString();
    String url = legacy ? "data:image/png;base64,legacy" : "/files/" + id.replace("-", "") + ".jpg";
    jdbc.update("""
        INSERT INTO photos (id, trip_id, trip_day_id, trip_place_id, caption, url, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, now())
        """, id, trip, day, place, caption, url);
    return id;
  }

  private void expense(Fixture fixture, String title, String amount, String currency, String adjustmentKind) {
    jdbc.update("""
        INSERT INTO trip_expenses (id, trip_id, payer_user_id, title, amount, currency, spent_at, adjustment_kind, updated_at)
        VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, '2026-09-02', ?, now())
        """, UUID.randomUUID().toString(), fixture.trip(), fixture.user().id(), title, amount, currency, adjustmentKind);
  }

  private void addMember(Fixture fixture, User user, String role) {
    jdbc.update("""
        INSERT INTO trip_members (id, trip_id, user_id, role, created_at, updated_at)
        VALUES (?, ?, ?, CAST(? AS "TripMemberRole"), now(), now())
        """, UUID.randomUUID().toString(), fixture.trip(), user.id(), role);
  }

  private int draftCount(String trip) {
    return jdbc.queryForObject("SELECT count(*) FROM trip_journals WHERE trip_id = ?", Integer.class, trip);
  }

  private JsonNode ok(User user, String method, String path, Map<String, ?> payload) throws Exception {
    var response = send(user, method, path, payload);
    assertEquals(200, response.statusCode(), response.body());
    return JsonUtil.mapper().readTree(response.body());
  }

  private HttpResponse<String> send(User user, String method, String path, Map<String, ?> payload) throws Exception {
    return http.send(request(user, method, path, payload), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest request(User user, String method, String path, Map<String, ?> payload) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
    if (user != null) request.header("Authorization", "Bearer " + user.token());
    return request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JsonUtil.write(payload))).build();
  }

  private static String journals(String trip) {
    return "/api/trips/" + trip + "/journals";
  }

  private record User(String token, String id) {}
  private record Fixture(User user, String trip, String day, String completed) {
    private String path() {
      return journals(trip) + "/recap";
    }
  }
}
