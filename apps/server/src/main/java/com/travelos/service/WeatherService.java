package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.Fmt;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripWeather;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripWeatherMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WeatherService {

  private static final long COORDINATE_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
  private static final long WEATHER_CACHE_TTL_MS = 6 * 60 * 60 * 1000L;
  private static final int MAX_FORECAST_DAYS = 15;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

  private final TripWeatherMapper weatherMapper;
  private final TripDayMapper tripDayMapper;
  private final TripMapper tripMapper;
  private final TripMemberService tripMemberService;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  private final Map<String, CachedCoordinates> coordinateCache = new ConcurrentHashMap<>();

  public WeatherService(TripWeatherMapper weatherMapper, TripDayMapper tripDayMapper,
      TripMapper tripMapper, TripMemberService tripMemberService) {
    this.weatherMapper = weatherMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripMapper = tripMapper;
    this.tripMemberService = tripMemberService;
  }

  public Map<String, Object> getTripWeather(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    Trip trip = tripMapper.selectById(tripId);
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    List<TripWeather> weather = loadWeather(trip);
    if (weather == null || weather.isEmpty()) {
      return unavailableList();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", "api");
    body.put("weather", weather.stream().map(this::toResponse).toList());
    return body;
  }

  public Map<String, Object> getTripDayWeather(String tripId, String tripDayId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    Trip trip = tripMapper.selectById(tripId);
    TripDay day = tripDayMapper.selectById(tripDayId);
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    if (day == null || !day.getTripId().equals(tripId)) {
      throw ApiException.notFound("行程日不存在");
    }
    List<TripWeather> weather = loadWeather(trip);
    TripWeather item = weather == null ? null : weather.stream()
        .filter(row -> Fmt.dateKey(row.getDate()).equals(Fmt.dateKey(day.getDate())))
        .findFirst()
        .orElse(null);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", "api");
    body.put("weather", item == null ? null : toResponse(item));
    if (item == null) {
      body.put("message", "暂无天气数据");
    }
    return body;
  }

  private List<TripWeather> loadWeather(Trip trip) {
    String destination = trip.getDestination() == null ? null : trip.getDestination().trim();
    if (destination == null || destination.isEmpty() || trip.getStartDate() == null) {
      return null;
    }
    List<TripWeather> cached = findFreshWeather(trip.getId(), destination,
        new Date(System.currentTimeMillis() - WEATHER_CACHE_TTL_MS));
    if (!cached.isEmpty()) {
      return cached;
    }
    try {
      String[] range = buildForecastDateRange(trip.getStartDate(),
          trip.getEndDate() == null ? trip.getStartDate() : trip.getEndDate());
      if (range == null) {
        return null;
      }
      CachedCoordinates coordinates = getCoordinates(destination);
      if (coordinates == null) {
        return null;
      }
      Map<String, Object> forecast = fetchForecast(coordinates, range[0], range[1]);
      List<TripDay> tripDays = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
          .eq(TripDay::getTripId, trip.getId()));
      Map<String, String> dayByDate = new LinkedHashMap<>();
      for (TripDay day : tripDays) {
        dayByDate.put(Fmt.dateKey(day.getDate()), day.getId());
      }
      Date fetchedAt = new Date();
      List<TripWeather> rows = toWeatherRows(trip.getId(), destination, forecast, dayByDate, fetchedAt);
      replaceForecast(trip.getId(), rows);
      return findFreshWeather(trip.getId(), destination,
          new Date(fetchedAt.getTime() - 1_000));
    } catch (Exception ex) {
      return null;
    }
  }

  private List<TripWeather> findFreshWeather(String tripId, String destination, Date fetchedAfter) {
    return weatherMapper.selectList(new LambdaQueryWrapper<TripWeather>()
        .eq(TripWeather::getTripId, tripId)
        .eq(TripWeather::getDestination, destination)
        .ge(TripWeather::getFetchedAt, fetchedAfter)
        .orderByAsc(TripWeather::getDate));
  }

  @Transactional
  protected void replaceForecast(String tripId, List<TripWeather> rows) {
    weatherMapper.delete(new LambdaQueryWrapper<TripWeather>()
        .eq(TripWeather::getTripId, tripId));
    for (TripWeather row : rows) {
      weatherMapper.insert(row);
    }
  }

  @SuppressWarnings("unchecked")
  private CachedCoordinates getCoordinates(String destination) {
    String key = destination.toLowerCase(java.util.Locale.ROOT);
    CachedCoordinates cached = coordinateCache.get(key);
    if (cached != null && System.currentTimeMillis() - cached.cachedAt() < COORDINATE_CACHE_TTL_MS) {
      return cached;
    }
    try {
      String url = "https://geocoding-api.open-meteo.com/v1/search?name="
          + java.net.URLEncoder.encode(destination, java.nio.charset.StandardCharsets.UTF_8)
          + "&count=1&language=zh&format=json";
      HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(REQUEST_TIMEOUT)
          .GET()
          .build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return null;
      }
      Map<String, Object> data = com.travelos.common.JsonUtil.readObject(response.body());
      Object results = data.get("results");
      if (!(results instanceof List<?> list) || list.isEmpty()) {
        return null;
      }
      Map<String, Object> first = (Map<String, Object>) list.get(0);
      CachedCoordinates coordinates = new CachedCoordinates(
          ((Number) first.get("latitude")).doubleValue(),
          ((Number) first.get("longitude")).doubleValue(),
          System.currentTimeMillis());
      coordinateCache.put(key, coordinates);
      return coordinates;
    } catch (Exception ex) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fetchForecast(CachedCoordinates coordinates, String start, String end) {
    try {
      String url = "https://api.open-meteo.com/v1/forecast"
          + "?latitude=" + coordinates.latitude()
          + "&longitude=" + coordinates.longitude()
          + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
          + "&timezone=auto&start_date=" + start + "&end_date=" + end;
      HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(REQUEST_TIMEOUT)
          .GET()
          .build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Open-Meteo request failed");
      }
      return com.travelos.common.JsonUtil.readObject(response.body());
    } catch (java.io.IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException(ex);
    }
  }

  private String[] buildForecastDateRange(Date startDate, Date endDate) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate latest = today.plusDays(MAX_FORECAST_DAYS);
    LocalDate start = toUtcDateOnly(startDate).isBefore(today) ? today : toUtcDateOnly(startDate);
    LocalDate end = toUtcDateOnly(endDate).isAfter(latest) ? latest : toUtcDateOnly(endDate);
    if (end.isBefore(start)) {
      return null;
    }
    return new String[] {start.toString(), end.toString()};
  }

  private List<TripWeather> toWeatherRows(String tripId, String destination,
      Map<String, Object> forecast, Map<String, String> dayByDate, Date fetchedAt) {
    List<TripWeather> rows = new ArrayList<>();
    Map<String, Object> daily = (Map<String, Object>) forecast.get("daily");
    if (daily == null) {
      return rows;
    }
    List<String> times = (List<String>) daily.get("time");
    List<Number> codes = (List<Number>) daily.get("weather_code");
    List<Number> maxTemps = (List<Number>) daily.get("temperature_2m_max");
    List<Number> minTemps = (List<Number>) daily.get("temperature_2m_min");
    List<Number> rain = (List<Number>) daily.get("precipitation_probability_max");
    if (times == null) {
      return rows;
    }
    for (int index = 0; index < times.size(); index++) {
      Number high = maxTemps == null ? null : maxTemps.get(index);
      Number low = minTemps == null ? null : minTemps.get(index);
      Number code = codes == null ? null : codes.get(index);
      if (high == null || low == null || code == null) {
        continue;
      }
      Map<String, String> mapped = mapWeatherCode(code.intValue());
      TripWeather row = new TripWeather();
      row.setTripId(tripId);
      row.setTripDayId(dayByDate.get(times.get(index)));
      row.setDate(Date.from(LocalDate.parse(times.get(index)).atStartOfDay(ZoneOffset.UTC).toInstant()));
      row.setDestination(destination);
      row.setCondition(mapped.get("condition"));
      row.setTempHigh(BigDecimal.valueOf(high.doubleValue()));
      row.setTempLow(BigDecimal.valueOf(low.doubleValue()));
      row.setRainChance(rain == null || rain.get(index) == null ? null : rain.get(index).intValue());
      row.setIconCode(mapped.get("iconCode"));
      row.setFetchedAt(fetchedAt);
      row.setCreatedAt(new Date());
      rows.add(row);
    }
    return rows;
  }

  private Map<String, String> mapWeatherCode(int code) {
    if (code == 0) {
      return Map.of("condition", "晴", "iconCode", "sun");
    }
    if (code == 1 || code == 2 || code == 3) {
      return Map.of("condition", "多云", "iconCode", "cloud");
    }
    if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
      return Map.of("condition", "雨", "iconCode", "cloud-rain");
    }
    if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) {
      return Map.of("condition", "雪", "iconCode", "snowflake");
    }
    if (code >= 95) {
      return Map.of("condition", "雷暴", "iconCode", "cloud-lightning");
    }
    return Map.of("condition", "其他", "iconCode", "cloud");
  }

  private Map<String, Object> toResponse(TripWeather weather) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", weather.getId());
    map.put("tripId", weather.getTripId());
    map.put("tripDayId", weather.getTripDayId());
    map.put("date", weather.getDate());
    map.put("destination", weather.getDestination());
    map.put("condition", weather.getCondition());
    map.put("tempHigh", weather.getTempHigh() == null ? null : weather.getTempHigh().doubleValue());
    map.put("tempLow", weather.getTempLow() == null ? null : weather.getTempLow().doubleValue());
    map.put("rainChance", weather.getRainChance());
    map.put("iconCode", weather.getIconCode());
    map.put("fetchedAt", weather.getFetchedAt());
    return map;
  }

  private Map<String, Object> unavailableList() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", "api");
    body.put("weather", List.of());
    body.put("message", "暂无天气数据");
    return body;
  }

  private LocalDate toUtcDateOnly(Date date) {
    return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
  }

  private record CachedCoordinates(double latitude, double longitude, long cachedAt) {
  }
}
