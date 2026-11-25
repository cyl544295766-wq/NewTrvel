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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "security.rate-limit.register-ip-max-attempts=30"
})
class AiPackingDraftWebTest {
  private static final String CONTEXT = "get_packing_context";
  private static final String PROPOSE = "propose_packing_list";
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired AiToolRegistry tools;
  @MockBean WeatherService weather;
  @SpyBean LlmGatewayService model;

  @BeforeEach
  void forecast() {
    when(weather.getTripWeather(anyString(), anyString())).thenReturn(Map.of("weather",
        List.of(Map.of("date", "2027-03-04", "condition", "雨", "rainChance", 75))));
  }

  @Test
  void normalChatGeneratesEditableDraftWithoutTouchingPackedItems() throws Exception {
    Fixture f = fixture();
    String list = createList(f, "日常用品", "other");
    JsonNode umbrella = itemByName(items(f, list), "雨伞");
    ok(f.user(), "PATCH", packing(f) + "/" + list + "/items/" + umbrella.path("id").asText(),
        Map.of("quantity", 2, "isPacked", true));

    JsonNode context = tool(f.user(), f.conversation(), CONTEXT, Map.of());
    assertEquals("成都", context.path("destination").asText());
    assertEquals(5, context.path("days").asInt());
    assertEquals(75, context.at("/weather/0/rainChance").asInt());
    List<Map<String, Object>> suggestions = List.of(
        item("雨伞", 1), item("ＵＳＢ 充电线", 1), item("usb  充电线", 3), item("换洗袜", 2));
    doReturn(true).when(model).isEnabled();
    doReturn(modelCall(CONTEXT, Map.of()), modelCall(PROPOSE, Map.of("items", suggestions)),
        Map.of("content", "请选好清单并核对数量。")).when(model).chat(anyList(), anyList());
    ok(f.user(), "POST", conversation(f) + "/messages",
        Map.of("content", "给这次 5 天成都旅行生成打包清单，同行有小孩。"));
    JsonNode draft = readPackingMessage(f);
    JsonNode preview = draft.at("/draft/packingPreview");
    assertEquals(list, preview.path("defaultTarget").asText());
    assertEquals(3, preview.path("items").size());
    assertEquals("usb  充电线", preview.path("duplicateSuggestions").get(0).asText());
    assertEquals("1", preview.at("/targets/0/duplicateIds/0").asText());
    assertEquals(2, items(f, list).size());

    var applied = ok(f.user(), "POST", apply(f, draft.path("id").asText()),
        choices(list, List.of(selection("2", 4))));
    assertEquals(1, applied.path("appliedCount").asInt());
    assertEquals("ＵＳＢ 充电线", applied.at("/packing/items/0/name").asText());
    assertEquals(4, applied.at("/packing/items/0/quantity").asInt());
    JsonNode current = items(f, list);
    assertEquals(3, current.size());
    assertEquals(2, itemByName(current, "雨伞").path("quantity").asInt());
    assertTrue(itemByName(current, "雨伞").path("isPacked").asBoolean());
    assertFalse(itemByName(current, "ＵＳＢ 充电线").path("isPacked").asBoolean());
    assertEquals(applied.path("packing"), readPackingMessage(f).path("packingResult"));
  }

  @Test
  void multipleListsHaveNoDefaultAndInvalidChoicesNeverWrite() throws Exception {
    Fixture f = fixture();
    String first = createList(f, "日常用品", "other");
    String second = createList(f, "公司设备", "electronics");
    Fixture otherTrip = new Fixture(f.user(), newTrip(f.user()), f.conversation());
    String unrelated = createList(otherTrip, "其他行程", "other");
    var draft = propose(f, item("雨伞", 1), item("水杯", 1));
    String id = draft.path("messageId").asText();
    assertEquals("", draft.at("/preview/defaultTarget").asText());
    assertEquals(2, draft.at("/preview/targets").size());
    assertEquals(400, send(f.user(), "POST", apply(f, id), Map.of()).statusCode());
    assertEquals(400, send(f.user(), "POST", apply(f, id), choices(second, List.of())).statusCode());
    assertEquals(400, send(f.user(), "POST", apply(f, id), choices(unrelated, List.of(selection("1", 1)))).statusCode());
    assertEquals(400, send(f.user(), "POST", apply(f, id), choices(second, List.of(selection("typo", 1)))).statusCode());
    assertEquals(400, send(f.user(), "POST", apply(f, id), choices(second, List.of(selection("1", 0)))).statusCode());
    assertEquals(400, send(f.user(), "POST", apply(f, id), choices(second, List.of(selection("1", 1), selection("1", 2)))).statusCode());
    assertEquals(3, items(f, second).size());

    JsonNode result = ok(f.user(), "POST", apply(f, id), choices(second, List.of(selection("1", 1))));
    assertEquals(second, result.at("/packing/listId").asText());
    assertEquals(2, items(f, first).size());
    assertEquals(4, items(f, second).size());
    assertEquals(2, items(otherTrip, unrelated).size());
  }

  @Test
  void noWeatherStillProducesPreviewAndNewListRequiresConfirmation() throws Exception {
    Fixture f = fixture();
    when(weather.getTripWeather(anyString(), anyString())).thenReturn(Map.of("source", "unavailable"));
    assertEquals("unavailable", tool(f.user(), f.conversation(), CONTEXT, Map.of()).path("weatherStatus").asText());
    JsonNode discarded = propose(f, item("外套", 1));
    assertEquals("", discarded.at("/preview/defaultTarget").asText());
    assertTrue(discarded.at("/preview/weatherNote").asText().contains("暂无天气"));
    assertEquals(0, lists(f).size());
    String discardedId = discarded.path("messageId").asText();
    ok(f.user(), "POST", conversation(f) + "/discard/" + discardedId, Map.of());
    Map<String, Object> selected = Map.of("targetId", "new", "newListName", "成都出发清单",
        "items", List.of(selection("1", 2)));
    assertEquals(400, send(f.user(), "POST", apply(f, discardedId), selected).statusCode());
    assertEquals(0, lists(f).size());
    var draft = propose(f, item("外套", 1), item("拖鞋", 1));
    String path = apply(f, draft.path("messageId").asText());
    JsonNode result = ok(f.user(), "POST", path, selected);
    assertEquals(result, ok(f.user(), "POST", path, selected));
    assertEquals(1, lists(f).size());
    JsonNode current = lists(f).get(0);
    assertEquals("成都出发清单", current.path("name").asText());
    assertEquals(1, current.path("items").size());
    assertEquals(2, current.at("/items/0/quantity").asInt());
    assertEquals(f.user().id(), jdbc.queryForObject("SELECT creator_id FROM packing_lists WHERE id = ?",
        String.class, current.path("id").asText()));
  }

  @Test
  void concurrentManualAdditionAndDraftsKeepOneNormalizedItem() throws Exception {
    Fixture f = fixture();
    String list = createList(f, "野餐", "other");
    var first = propose(f, item("Ｆｉｌｍ", 2), item("水杯", 9));
    var second = propose(f, item("film", 3));
    var path = apply(f, first.path("messageId").asText());
    List<CompletableFuture<HttpResponse<String>>> parallel = List.of(
        async(f.user(), path, choices(list, List.of(selection("1", 2), selection("2", 9)))),
        async(f.user(), path, choices(list, List.of(selection("1", 2), selection("2", 9)))),
        async(f.user(), apply(f, second.path("messageId").asText()), choices(list, List.of(selection("1", 3)))),
        async(f.user(), packing(f) + "/" + list + "/items", item("FILM", 5)));
    CompletableFuture.allOf(parallel.toArray(CompletableFuture[]::new)).join();
    for (var response : parallel) {
      assertTrue(List.of(200, 409).contains(response.join().statusCode()), response.join().body());
    }
    JsonNode firstReplay = ok(f.user(), "POST", path, choices(list, List.of(selection("1", 2), selection("2", 9))));
    assertTrue(firstReplay.at("/packing/skipped").toString().contains("水杯"));
    assertEquals(3, items(f, list).size());
    assertEquals(1, itemByName(items(f, list), "水杯").path("quantity").asInt());
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE trip_id = ? AND action = 'ai_packing_applied'",
        Integer.class, f.trip()));
    assertEquals(409, send(f.user(), "POST", packing(f) + "/" + list + "/items", item("film", 7)).statusCode());
  }

  @Test
  void revokedRoleAndDeletedTargetCannotApplyStalePreview() throws Exception {
    Fixture f = fixture();
    String list = createList(f, "原清单", "other");
    User editor = register();
    assertTrue(tool(editor, f.conversation(), CONTEXT, Map.of()).has("error"));
    jdbc.update("INSERT INTO trip_members (id, trip_id, user_id, role, created_at, updated_at) "
        + "VALUES (?, ?, ?, 'member', now(), now())", UUID.randomUUID().toString(), f.trip(), editor.id());
    Fixture editing = new Fixture(editor, f.trip(), conversation(editor, f.trip()));
    var draft = propose(editing, item("转换插头", 1));
    jdbc.update("UPDATE trip_members SET role = 'viewer' WHERE trip_id = ? AND user_id = ?", f.trip(), editor.id());
    assertEquals(403, send(editor, "POST", apply(editing, draft.path("messageId").asText()),
        choices(list, List.of(selection("1", 1)))).statusCode());
    assertTrue(tool(editor, editing.conversation(), PROPOSE, Map.of("items", List.of(item("裹毯", 1)))).has("error"));
    assertEquals(2, items(f, list).size());

    var stale = propose(f, item("转换插头", 1));
    ok(f.user(), "DELETE", packing(f) + "/" + list, Map.of());
    assertEquals(409, send(f.user(), "POST", apply(f, stale.path("messageId").asText()),
        choices(list, List.of(selection("1", 1)))).statusCode());
    assertEquals(0, lists(f).size());
  }

  private Fixture fixture() throws Exception {
    User user = register();
    String trip = newTrip(user);
    return new Fixture(user, trip, conversation(user, trip));
  }

  private User register() throws Exception {
    String username = "pack" + UUID.randomUUID().toString().substring(0, 8);
    String token = ok(null, "POST", "/api/auth/register",
        Map.of("username", username, "password", "Packing-test-2027")).path("token").asText();
    return new User(token, jdbc.queryForObject("SELECT id FROM users WHERE username = ?", String.class, username));
  }

  private String newTrip(User user) throws Exception {
    return ok(user, "POST", "/api/trips", Map.of("title", "成都旅行", "destination", "成都",
        "startDate", "2027-03-04", "endDate", "2027-03-08")).at("/trip/id").asText();
  }

  private AiConversation conversation(User user, String trip) throws Exception {
    AiConversation conversation = new AiConversation();
    conversation.setId(ok(user, "POST", "/api/ai/conversations", Map.of("tripId", trip)).at("/conversation/id").asText());
    conversation.setTripId(trip);
    conversation.setUserId(user.id());
    return conversation;
  }

  private String createList(Fixture f, String name, String category) throws Exception {
    return ok(f.user(), "POST", packing(f), Map.of("name", name, "category", category)).at("/packingList/id").asText();
  }

  private JsonNode lists(Fixture f) throws Exception {
    return ok(f.user(), "GET", packing(f), Map.of()).path("packingLists");
  }

  private JsonNode items(Fixture f, String id) throws Exception {
    for (JsonNode list : lists(f)) {
      if (id.equals(list.path("id").asText())) return list.path("items");
    }
    throw new AssertionError("Expected list " + id);
  }

  private JsonNode itemByName(JsonNode items, String name) {
    for (JsonNode item : items) {
      if (name.equals(item.path("name").asText())) return item;
    }
    throw new AssertionError("Expected item " + name + " in " + items);
  }

  private JsonNode readPackingMessage(Fixture f) throws Exception {
    for (JsonNode message : ok(f.user(), "GET", conversation(f) + "/messages?latest=true", Map.of()).path("messages")) {
      if (message.at("/draft/packingPreview").isObject()) return message;
    }
    throw new AssertionError("Expected a packing draft");
  }

  @SafeVarargs
  private final JsonNode propose(Fixture f, Map<String, Object>... items) throws Exception {
    var draft = tool(f.user(), f.conversation(), PROPOSE, Map.of("items", List.of(items)));
    assertTrue(draft.path("draftCreated").asBoolean(), draft.toString());
    return draft;
  }

  private JsonNode tool(User user, AiConversation conversation, String tool, Map<String, Object> args) throws Exception {
    return JsonUtil.mapper().readTree(tools.executeTool(Map.of("name", tool, "arguments", JsonUtil.write(args)),
        conversation, user.id()));
  }

  private static Map<String, Object> modelCall(String tool, Map<String, Object> args) {
    return Map.of("tool_calls", List.of(Map.of("id", tool, "type", "function", "function",
        Map.of("name", tool, "arguments", JsonUtil.write(args)))));
  }

  private static Map<String, Object> item(String name, int quantity) {
    return Map.of("name", name, "quantity", quantity);
  }

  private static Map<String, Object> selection(String id, int quantity) {
    return Map.of("id", id, "quantity", quantity);
  }

  private static Map<String, Object> choices(String targetId, List<Map<String, Object>> items) {
    return Map.of("targetId", targetId, "items", items);
  }

  private static String packing(Fixture f) {
    return "/api/trips/" + f.trip() + "/packing-lists";
  }

  private static String conversation(Fixture f) {
    return "/api/ai/conversations/" + f.conversation().getId();
  }

  private static String apply(Fixture f, String id) {
    return conversation(f) + "/apply/" + id;
  }

  private JsonNode ok(User user, String method, String path, Object body) throws Exception {
    var response = send(user, method, path, body);
    assertEquals(200, response.statusCode(), response.body());
    return JsonUtil.mapper().readTree(response.body());
  }

  private HttpResponse<String> send(User user, String method, String path, Object body) throws Exception {
    return http.send(request(user, method, path, body), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> async(User user, String path, Object body) {
    return http.sendAsync(request(user, "POST", path, body), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest request(User user, String method, String path, Object body) {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(JsonUtil.write(body)));
    if (user != null) request.header("Authorization", "Bearer " + user.token());
    return request.build();
  }

  private record User(String token, String id) {}
  private record Fixture(User user, String trip, AiConversation conversation) {}
}
