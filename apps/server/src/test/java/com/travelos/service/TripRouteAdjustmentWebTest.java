package com.travelos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelos.ai.AiToolRegistry;
import com.travelos.ai.LlmGatewayService;
import com.travelos.common.JsonUtil;
import com.travelos.entity.AiConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripRouteAdjustmentWebTest {
  private static final String DATE = "2027-02-14";
  private static final String NEXT_DATE = "2027-02-15";
  private static final String CHECK = "check_day_route";
  private static final String PROPOSE = "propose_route_adjustment";
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired AiToolRegistry tools;
  @MockBean PlacesService maps;
  @MockBean WeatherService weather;
  @SpyBean LlmGatewayService model;

  @BeforeEach
  void providers() {
    when(maps.routeSeconds(anyString(), anyString(), eq("driving"), any())).thenReturn(OptionalLong.of(1_800));
    when(maps.routeSeconds(anyString(), anyString(), eq("walking"), any())).thenReturn(OptionalLong.of(2_700));
    when(maps.routeSeconds(anyString(), anyString(), eq("transit"), any())).thenReturn(OptionalLong.of(2_100));
    when(weather.getTripDayWeather(anyString(), anyString(), anyString())).thenReturn(
        Map.of("source", "api", "weather", Map.of("date", DATE, "condition", "雨", "rainChance", 80)));
    when(maps.searchPois(anyString(), nullable(String.class), nullable(String.class), anyInt(), any()))
        .thenAnswer(call -> {
          String name = call.getArgument(0);
          return switch (name) {
            case "室内博物馆" -> poi(name, "30.66", "104.04", "周一闭馆；其余时间 09:00–17:30");
            case "公园" -> poi(name, "30.67", "104.11", "全天开放");
            case "到达站" -> poi(name, "31.21", "105.18", "全天开放");
            default -> Map.of("places", List.of());
          };
        });
  }

  @Test
  void rainyDayDraftKeepsTrainAndOtherDayAndAppliesOnce() throws Exception {
    Fixture f = fixture();
    JsonNode original = call(f.user(), f.conversation(), CHECK, Map.of("date", DATE));
    assertEquals(80, original.at("/weather/rainChance").asInt(), original.toString());
    assertEquals("Asia/Shanghai", original.path("timezone").asText());
    assertFalse(original.toString().contains("order-no-private"));
    assertFalse(original.toString().contains("confirm-private"));
    String tripBefore = bookingTimes(f.train());
    doReturn(true).when(model).isEnabled();
    doReturn(modelCall(CHECK, Map.of("date", DATE)),
        modelCall("propose_itinerary_change", Map.of("replaceDay", true, "changes", List.of(
            Map.of("action", "add", "dayIndex", 1, "name", "不能用覆盖草稿改当天")))),
        modelCall(PROPOSE, propose(f.outdoor(), "16:00")), Map.of("content", "可以打开草稿核对改动。"))
        .when(model).chat(anyList(), anyList());
    String messagesPath = "/api/ai/conversations/" + f.conversation().getId() + "/messages";
    success(f.user(), "POST", messagesPath, Map.of(
        "content", "2月14日下午下雨，把公园换成室内博物馆，晚上的火车不动。"));
    JsonNode stored = success(f.user(), "GET", messagesPath + "?latest=true", Map.of()).path("messages");
    JsonNode routeMessage = java.util.stream.StreamSupport.stream(stored.spliterator(), false)
        .filter(message -> message.at("/draft/routeAdjustment/preview").isObject())
        .findFirst().orElseThrow(() -> new AssertionError(stored.toString()));
    assertFalse(stored.toString().contains("不能用覆盖草稿改当天"), stored.toString());
    JsonNode draft = JsonUtil.mapper().valueToTree(Map.of("messageId", routeMessage.path("id").asText(),
        "preview", routeMessage.at("/draft/routeAdjustment/preview")));
    assertEquals("公园", draft.at("/preview/differences/0/before/name").asText());
    assertEquals("室内博物馆", draft.at("/preview/differences/0/after/name").asText());
    assertEquals(45, draft.at("/preview/report/legs/0/arrivalBufferMinutes").asInt());
    assertEquals(1_800, draft.at("/preview/report/legs/0/durationSeconds").asLong());
    assertEquals(4_500, draft.at("/preview/report/legs/0/availableSeconds").asLong());
    assertEquals("公园", placeName(f.outdoor()));

    String path = actionPath(f, draft, "apply");
    var a = http.sendAsync(request(f.user(), "POST", path, Map.of()), HttpResponse.BodyHandlers.ofString());
    var b = http.sendAsync(request(f.user(), "POST", path, Map.of()), HttpResponse.BodyHandlers.ofString());
    CompletableFuture.allOf(a, b).join();
    assertTrue(List.of(a.join().statusCode(), b.join().statusCode()).contains(200));
    assertTrue(List.of(200, 409).contains(a.join().statusCode()));
    assertTrue(List.of(200, 409).contains(b.join().statusCode()));
    assertEquals(1, success(f.user(), "POST", path, Map.of()).path("appliedCount").asInt());
    assertEquals("室内博物馆", placeName(f.outdoor()));
    assertEquals(Instant.parse(DATE + "T06:00:00Z"), jdbc.queryForObject(
        "SELECT start_time FROM trip_places WHERE id = ?", (rs, row) -> rs.getTimestamp(1).toInstant(), f.outdoor()));
    assertEquals("第二天原计划", placeName(f.otherDayPlace()));
    assertEquals(tripBefore, bookingTimes(f.train()));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE trip_id = ? AND action = 'ai_itinerary_applied'",
        Integer.class, f.trip()));
  }

  @Test
  void routeConflictClosedVenueAndProtectedPlacesCannotProduceWritableDraft() throws Exception {
    Fixture f = fixture();
    JsonNode late = call(f.user(), f.conversation(), PROPOSE, propose(f.outdoor(), "17:00"));
    assertFalse(late.path("draftCreated").asBoolean(), late.toString());
    assertTrue(late.at("/preview/report/issues").toString().contains("travel_time"), late.toString());

    Map<String, Object> closed = Map.of("date", NEXT_DATE, "changes", List.of(Map.of(
        "action", "update", "placeId", f.otherDayPlace(), "name", "室内博物馆",
        "startTime", "14:00", "endTime", "16:00", "latitude", "30.66", "longitude", "104.04")));
    JsonNode closing = call(f.user(), f.conversation(), PROPOSE, closed);
    assertFalse(closing.path("draftCreated").asBoolean());
    assertTrue(closing.at("/preview/report/issues").toString().contains("opening_hours"), closing.toString());
    assertTrue(call(f.user(), f.conversation(), PROPOSE, Map.of("date", DATE, "changes",
        List.of(Map.of("action", "remove", "placeId", f.station())))).has("error"));
    assertTrue(call(f.user(), f.conversation(), PROPOSE, Map.of("date", DATE, "changes",
        List.of(Map.of("action", "remove", "placeId", f.otherDayPlace())))).has("error"));
    assertTrue(call(f.user(), f.conversation(), PROPOSE, Map.of("date", DATE, "lockedPlaceIds",
        List.of(f.outdoor()), "changes", List.of(Map.of("action", "remove", "placeId", f.outdoor())))).has("error"));
    assertTrue(call(f.user(), f.conversation(), CHECK, Map.of()).has("error"));
    assertTrue(call(f.user(), f.conversation(), CHECK, Map.of("date", DATE, "tripId", "other-trip")).has("error"));
    assertEquals("公园", placeName(f.outdoor()));
  }

  @Test
  void unavailableMapAndWeatherShowPartialCoverageAndDiscardKeepsTheItinerary() throws Exception {
    Fixture f = fixture();
    when(maps.routeSeconds(anyString(), anyString(), anyString(), any())).thenReturn(OptionalLong.empty());
    when(weather.getTripDayWeather(anyString(), anyString(), anyString())).thenReturn(
        Map.of("source", "unavailable"));
    JsonNode draft = call(f.user(), f.conversation(), PROPOSE, propose(f.outdoor(), "16:00"));
    assertTrue(draft.path("draftCreated").asBoolean(), draft.toString());
    assertEquals("partial", draft.at("/preview/report/status").asText());
    assertTrue(draft.at("/preview/report/legs/0/durationSeconds").isNull());
    assertTrue(draft.at("/preview/weather").isNull());
    success(f.user(), "POST", actionPath(f, draft, "discard"), Map.of());
    assertEquals("公园", placeName(f.outdoor()));
    assertEquals(400, send(f.user(), "POST", actionPath(f, draft, "apply"), Map.of()).statusCode());
  }

  @Test
  void confirmedDraftRejectsUpdatedBookingAndRevokedEditor() throws Exception {
    Fixture f = fixture();
    JsonNode draft = call(f.user(), f.conversation(), PROPOSE, propose(f.outdoor(), "16:00"));
    assertTrue(draft.path("draftCreated").asBoolean(), draft.toString());
    jdbc.update("UPDATE bookings SET start_time = start_time - interval '2 hours' WHERE id = ?", f.train());
    var stale = send(f.user(), "POST", actionPath(f, draft, "apply"), Map.of());
    assertEquals(409, stale.statusCode(), stale.body());
    assertTrue(stale.body().contains("重新校验"), stale.body());
    assertEquals("公园", placeName(f.outdoor()));

    User editor = register();
    assertTrue(call(editor, f.conversation(), CHECK, Map.of("date", DATE)).has("error"));
    jdbc.update("INSERT INTO trip_members (id, trip_id, user_id, role, created_at, updated_at) "
        + "VALUES (?, ?, ?, 'member', now(), now())", UUID.randomUUID().toString(), f.trip(), editor.id());
    AiConversation conversation = conversation(editor, f.trip());
    JsonNode editable = call(editor, conversation, PROPOSE, propose(f.outdoor(), "14:30"));
    assertTrue(editable.path("draftCreated").asBoolean(), editable.toString());
    String editorApplyPath = "/api/ai/conversations/" + conversation.getId()
        + "/apply/" + editable.path("messageId").asText();
    String lock = dayLockPath(f.trip(), day(f.trip(), 1));
    success(f.user(), "POST", lock, Map.of());
    var busy = send(editor, "POST", editorApplyPath, Map.of());
    assertEquals(409, busy.statusCode(), busy.body());
    assertTrue(busy.body().contains("正在") || busy.body().contains("正被"), busy.body());
    success(f.user(), "DELETE", lock, Map.of());
    jdbc.update("UPDATE trip_members SET role='viewer' WHERE trip_id=? AND user_id=?", f.trip(), editor.id());
    var forbidden = send(editor, "POST", editorApplyPath, Map.of());
    assertEquals(403, forbidden.statusCode(), forbidden.body());
    assertEquals("公园", placeName(f.outdoor()));
  }

  @Test
  void overnightBookingAppearsOnBothDatesAndUsesRequestedTransportData() throws Exception {
    Fixture f = fixture();
    JsonNode weekday = call(f.user(), f.conversation(), CHECK, Map.of("date", NEXT_DATE, "mode", "walking"));
    assertEquals("晚间火车", weekday.at("/bookings/0/title").asText(), weekday.toString());
    assertEquals("2027-02-14T18:00+08:00", weekday.at("/bookings/0/start").asText());
    assertEquals("2027-02-15T01:00+08:00", weekday.at("/bookings/0/end").asText());
    assertEquals("walking", weekday.at("/report/mode").asText());
    assertEquals(2_700, weekday.at("/report/legs/0/durationSeconds").asLong(), weekday.toString());
    JsonNode transit = call(f.user(), f.conversation(), CHECK, Map.of("date", DATE, "mode", "transit"));
    assertEquals(2_100, transit.at("/report/legs/0/durationSeconds").asLong(), transit.toString());
  }

  @Test
  void mapDurationAndHoursOnlyUseRecognizedProviderValues() {
    assertEquals(1800, PlacesService.parseRouteSeconds(
        "{\"status\":\"1\",\"route\":{\"paths\":[{\"cost\":{\"duration\":\"1800\"}}]}}", "paths").orElseThrow());
    assertEquals(2400, PlacesService.parseRouteSeconds(
        "{\"status\":\"1\",\"route\":{\"transits\":[{\"cost\":{\"duration\":\"2400\"}}]}}", "transits").orElseThrow());
    assertTrue(PlacesService.parseRouteSeconds("{\"status\":\"0\"}", "paths").isEmpty());
    LocalTime noon = LocalTime.NOON;
    assertEquals(Boolean.FALSE, TripRouteCheck.withinHours("周一闭馆；其余时间 09:00–17:30",
        LocalDate.parse(NEXT_DATE), noon, noon.plusHours(1)));
    assertNull(TripRouteCheck.withinHours("请以现场为准", LocalDate.parse(DATE), noon, null));
  }

  private Fixture fixture() throws Exception {
    User user = register();
    String trip = success(user, "POST", "/api/trips", Map.of("title", "成都周末", "destination", "成都",
        "startDate", DATE, "endDate", NEXT_DATE)).at("/trip/id").asText();
    success(user, "POST", "/api/trips/" + trip + "/days/generate", Map.of());
    String day = day(trip, 1);
    String next = day(trip, 2);
    String outdoor = place(user, trip, day, "公园", "attraction", "30.67", "104.11", DATE, "13:00", "15:00");
    String station = place(user, trip, day, "成都东站", "transport", "30.63", "104.14", DATE, "17:15", "18:00");
    String nextPlace = place(user, trip, next, "第二天原计划", "attraction", "31.25", "105.21", NEXT_DATE, "10:00", "11:00");
    String train = success(user, "POST", "/api/trips/" + trip + "/bookings", Map.ofEntries(
        Map.entry("type", "train"), Map.entry("title", "晚间火车"), Map.entry("tripPlaceId", station),
        Map.entry("tripDayId", day), Map.entry("startTime", DATE + "T18:00:00+08:00"),
        Map.entry("endTime", NEXT_DATE + "T01:00:00+08:00"), Map.entry("orderNo", "order-no-private"),
        Map.entry("confirmCode", "confirm-private"), Map.entry("extra", Map.of("stations", "成都东站 → 到达站"))))
        .at("/booking/id").asText();
    return new Fixture(user, trip, outdoor, station, nextPlace, train, conversation(user, trip));
  }

  private User register() throws Exception {
    String username = "route" + UUID.randomUUID().toString().substring(0, 8);
    String token = success(null, "POST", "/api/auth/register",
        Map.of("username", username, "password", "Route-test-only-2027")).path("token").asText();
    return new User(token, jdbc.queryForObject("SELECT id FROM users WHERE username = ?", String.class, username));
  }

  private AiConversation conversation(User user, String trip) throws Exception {
    AiConversation conversation = new AiConversation();
    conversation.setId(success(user, "POST", "/api/ai/conversations", Map.of("tripId", trip))
        .at("/conversation/id").asText());
    conversation.setTripId(trip);
    conversation.setUserId(user.id());
    return conversation;
  }

  private String place(User user, String trip, String day, String name, String type, String lat,
      String lon, String date, String start, String end) throws Exception {
    String lock = dayLockPath(trip, day);
    success(user, "POST", lock, Map.of());
    try {
      return success(user, "POST", "/api/trips/" + trip + "/places", Map.of(
          "tripDayId", day, "name", name, "type", type, "latitude", lat, "longitude", lon,
          "startTime", date + "T" + start + ":00+08:00", "endTime", date + "T" + end + ":00+08:00"))
          .at("/place/id").asText();
    } finally {
      success(user, "DELETE", lock, Map.of());
    }
  }

  private String day(String trip, int index) {
    return jdbc.queryForObject("SELECT id FROM trip_days WHERE trip_id = ? AND day_index = ?",
        String.class, trip, index);
  }

  private String dayLockPath(String trip, String day) {
    return "/api/trips/" + trip + "/days/" + day + "/edit-lock";
  }

  private Map<String, Object> poi(String name, String lat, String lon, String hours) {
    return Map.of("places", List.of(Map.of("name", name, "latitude", lat, "longitude", lon, "openTime", hours)));
  }

  private Map<String, Object> propose(String place, String end) {
    return Map.of("date", DATE, "reason", "下雨，改为室内参观，保留火车", "changes", List.of(
        Map.of("action", "update", "placeId", place, "name", "室内博物馆", "startTime", "14:00", "endTime", end,
            "latitude", "30.66", "longitude", "104.04")));
  }

  private JsonNode call(User user, AiConversation conversation, String tool, Map<String, Object> args) throws Exception {
    return JsonUtil.mapper().readTree(tools.executeTool(Map.of("name", tool, "arguments", JsonUtil.write(args)),
        conversation, user.id()));
  }

  private Map<String, Object> modelCall(String tool, Map<String, Object> arguments) {
    return Map.of("tool_calls", List.of(Map.of("id", tool, "type", "function",
        "function", Map.of("name", tool, "arguments", JsonUtil.write(arguments)))));
  }

  private String placeName(String id) {
    return jdbc.queryForObject("SELECT name FROM trip_places WHERE id = ?", String.class, id);
  }

  private String bookingTimes(String id) {
    return jdbc.queryForObject("SELECT start_time::text || '/' || end_time::text FROM bookings WHERE id = ?",
        String.class, id);
  }

  private String actionPath(Fixture fixture, JsonNode draft, String action) {
    return "/api/ai/conversations/" + fixture.conversation().getId() + "/" + action + "/"
        + draft.path("messageId").asText();
  }

  private JsonNode success(User user, String method, String path, Object body) throws Exception {
    var response = send(user, method, path, body);
    assertEquals(200, response.statusCode(), response.body());
    return JsonUtil.mapper().readTree(response.body());
  }

  private HttpResponse<String> send(User user, String method, String path, Object body) throws Exception {
    return http.send(request(user, method, path, body), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest request(User user, String method, String path, Object body) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(JsonUtil.write(body)));
    if (user != null) {
      request.header("Authorization", "Bearer " + user.token());
    }
    return request.build();
  }

  private record User(String token, String id) {}
  private record Fixture(User user, String trip, String outdoor, String station, String otherDayPlace,
                         String train, AiConversation conversation) {}
}
