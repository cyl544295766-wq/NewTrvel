package com.travelos.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelos.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripSquareThumbnailWebTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void nonMemberSeesOrderedPublicRouteWithoutPrivateNotes() throws Exception {
    String owner = register();
    String viewer = register();
    String title = "thumbnail-" + UUID.randomUUID();
    String trip = request(owner, "POST", "/api/trips", Map.of("title", title,
        "startDate", "2027-03-01", "endDate", "2027-03-02")).at("/trip/id").asText();
    request(owner, "POST", "/api/trips/" + trip + "/days/generate", Map.of());
    for (int index = 2; index >= 1; index--) {
      String day = jdbc.queryForObject("SELECT id FROM trip_days WHERE trip_id = ? AND day_index = ?",
          String.class, trip, index);
      jdbc.update("""
          INSERT INTO trip_places (id, trip_id, trip_day_id, name, latitude, longitude, notes, updated_at)
          VALUES (?, ?, ?, ?, 30.1, 120.2, 'private-thumbnail-note', now())
          """, UUID.randomUUID().toString(), trip, day, "Day" + index);
    }
    String listPath = "/api/square?scope=public&keyword=" + title;
    assertEquals(0, request(viewer, "GET", listPath, null).path("items").size());
    jdbc.update("UPDATE trips SET visibility = 'public', published_at = now() WHERE id = ?", trip);
    JsonNode card = request(viewer, "GET", listPath, null).path("items").get(0);
    JsonNode preview = card.path("routePreview");
    assertEquals(2, preview.size());
    assertEquals("Day1", preview.get(0).path("name").asText());
    assertEquals("Day2", preview.get(1).path("name").asText());
    var keys = new java.util.HashSet<String>();
    preview.get(0).fieldNames().forEachRemaining(keys::add);
    assertEquals(Set.of("name", "latitude", "longitude"), keys);
    assertFalse(card.toString().contains("private-thumbnail-note"));
    assertEquals(preview, request(viewer, "GET", "/api/square/" + trip, null).at("/square/routePreview"));
  }

  private String register() throws Exception {
    return request(null, "POST", "/api/auth/register", Map.of(
        "username", "thumb" + UUID.randomUUID().toString().substring(0, 8),
        "password", "Thumbnail-test-2027")).path("token").asText();
  }

  private JsonNode request(String token, String method, String path, Map<String, ?> body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(JsonUtil.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JsonUtil.mapper().readTree(response.body());
  }
}
