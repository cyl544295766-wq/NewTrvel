package com.travelos.service;

import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.entity.Trip;
import com.travelos.entity.TripWeather;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripWeatherMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WeatherUpcomingTest {
  private final TripWeatherMapper weatherMapper = mock(TripWeatherMapper.class);
  private final TripDayMapper dayMapper = mock(TripDayMapper.class);
  private final TripMapper tripMapper = mock(TripMapper.class);
  private final TripMemberService members = mock(TripMemberService.class);
  private final HttpClient client = mock(HttpClient.class);
  private final WeatherService service = new WeatherService(weatherMapper, dayMapper, tripMapper, members);
  private final List<String> forecastQueries = new ArrayList<>();
  private Trip trip;
  private boolean unavailable;
  private boolean partial;

  @BeforeEach
  void setUp() throws Exception {
    trip = new Trip();
    trip.setId("trip-1");
    trip.setDestination("杭州");
    trip.setStartDate(Date.from(Instant.parse("2027-10-01T00:00:00Z")));
    trip.setEndDate(Date.from(Instant.parse("2027-10-08T00:00:00Z")));
    when(tripMapper.selectById("trip-1")).thenReturn(trip);
    ReflectionTestUtils.setField(service, "httpClient", client);
    clock("2026-11-27T17:00:00Z");
    when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenAnswer(call -> {
          HttpRequest request = call.getArgument(0);
          if (request.uri().getHost().startsWith("geocoding")) {
            return response(200, JsonUtil.write(Map.of("results", List.of(Map.of(
                "latitude", 30.25, "longitude", 120.12, "timezone", "Asia/Shanghai")))));
          }
          String query = request.uri().getQuery();
          forecastQueries.add(query);
          if (unavailable) return response(503, "{}");
          String start = java.util.Arrays.stream(query.split("&"))
              .filter(part -> part.startsWith("start_date=")).findFirst().orElseThrow().substring(11);
          LocalDate first = LocalDate.parse(start);
          List<String> dates = new ArrayList<>();
          for (int i = partial ? -1 : 0; i < (partial ? 3 : 7); i++) dates.add(first.plusDays(i).toString());
          int size = dates.size();
          return response(200, JsonUtil.write(Map.of("daily", Map.of("time", dates,
              "weather_code", java.util.Collections.nCopies(size, 0),
              "temperature_2m_max", java.util.Collections.nCopies(size, 20),
              "temperature_2m_min", java.util.Collections.nCopies(size, 10),
              "precipitation_probability_max", java.util.Collections.nCopies(size, 15)))));
        });
  }

  private void clock(String instant) {
    ReflectionTestUtils.setField(service, "forecastClock", Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rows(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("weather");
  }

  @Test
  void futureTripShowsExactlySevenDaysStartingTodayInDestinationTimezone() {
    Map<String, Object> result = service.getUpcomingWeather("trip-1", "user-1");
    assertEquals("2026-11-28", result.get("windowStart"));
    assertEquals("2026-12-04", result.get("windowEnd"));
    assertEquals("Asia/Shanghai", result.get("timezone"));
    assertEquals(7, rows(result).size());
    assertTrue(forecastQueries.get(0).contains("start_date=2026-11-28&end_date=2026-12-04"));
    verify(members).requireTripMember("trip-1", "user-1");
    verifyNoInteractions(weatherMapper, dayMapper);
  }

  @Test
  void tripsWithoutTravelDatesStillHaveDestinationForecast() {
    trip.setStartDate(null); trip.setEndDate(null);
    assertEquals(7, rows(service.getUpcomingWeather("trip-1", "user-1")).size());
  }

  @Test
  void cachedForecastKeepsFetchTimeAndRechecksMembership() {
    Map<String, Object> first = service.getUpcomingWeather("trip-1", "user-1");
    clock("2026-11-27T18:00:00Z");
    Map<String, Object> second = service.getUpcomingWeather("trip-1", "user-1");
    assertEquals(1, forecastQueries.size());
    assertEquals(rows(first).get(0).get("fetchedAt"), rows(second).get(0).get("fetchedAt"));
    doThrow(ApiException.forbidden("无权限")).when(members).requireTripMember("trip-1", "outsider");
    assertEquals(403, assertThrows(ApiException.class,
        () -> service.getUpcomingWeather("trip-1", "outsider")).getStatusCode());
    assertEquals(1, forecastQueries.size());
  }

  @Test
  void destinationMidnightStartsNewWindowEvenWithinCacheTtl() {
    clock("2026-11-27T15:30:00Z");
    assertEquals("2026-11-27", service.getUpcomingWeather("trip-1", "user-1").get("windowStart"));
    clock("2026-11-27T16:30:00Z");
    assertEquals("2026-11-28", service.getUpcomingWeather("trip-1", "user-1").get("windowStart"));
    assertEquals(2, forecastQueries.size());
  }

  @Test
  void sixHourOldForecastIsFetchedAgain() {
    service.getUpcomingWeather("trip-1", "user-1");
    clock("2026-11-27T23:00:00Z");
    service.getUpcomingWeather("trip-1", "user-1");
    assertEquals(2, forecastQueries.size());
  }

  @Test
  void partialForecastDoesNotIncludeYesterdayOrInventMissingDays() {
    partial = true;
    Map<String, Object> result = service.getUpcomingWeather("trip-1", "user-1");
    assertEquals(3, rows(result).size());
    assertEquals(Date.from(Instant.parse("2026-11-28T00:00:00Z")), rows(result).get(0).get("date"));
  }

  @Test
  void missingDestinationAndProviderFailureReturnClearEmptyStatesWithoutDatabaseWrites() {
    trip.setDestination(" ");
    assertTrue(rows(service.getUpcomingWeather("trip-1", "user-1")).isEmpty());
    verifyNoInteractions(client);
    trip.setDestination("杭州"); unavailable = true;
    Map<String, Object> result = service.getUpcomingWeather("trip-1", "user-1");
    assertTrue(rows(result).isEmpty());
    assertTrue(result.get("message").toString().contains("未来7天天气暂不可用"));
    verifyNoInteractions(weatherMapper, dayMapper);
  }

  @Test
  void existingTripWeatherStillUsesTravelDateData() {
    TripWeather existing = new TripWeather();
    existing.setDate(trip.getStartDate());
    when(weatherMapper.selectList(any())).thenReturn(List.of(existing));
    assertEquals(trip.getStartDate(), rows(service.getTripWeather("trip-1", "user-1")).get(0).get("date"));
    verifyNoInteractions(client);
  }
}
