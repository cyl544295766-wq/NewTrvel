package com.travelos.service;

import com.travelos.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlacesService {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
  private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

  /** 高德 POI typecode：餐饮服务 / 风景名胜|科教文化（含博物馆）/ 住宿服务（酒店、民宿、旅馆）。 */
  private static final String RESTAURANT_TYPES = "050000";
  private static final String ATTRACTION_TYPES = "110000|140000";
  private static final String HOTEL_TYPES = "100000";

  private final String webServiceKey;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  private final Map<String, CachedSuggestions> cache = new ConcurrentHashMap<>();
  private final Map<String, CachedPlaces> poiCache = new ConcurrentHashMap<>();

  public PlacesService(@Value("${amap.web-service-key:}") String webServiceKey) {
    this.webServiceKey = webServiceKey == null ? "" : webServiceKey.trim();
  }

  public Map<String, Object> getSuggestions(String keyword, String city, Integer limit) {
    String normalizedKeyword = keyword == null ? "" : keyword.trim();
    String normalizedCity = city == null || city.isBlank() ? null : city.trim();
    int normalizedLimit = limit == null || limit <= 0 ? 8 : limit;
    String cacheKey = normalizedKeyword.toLowerCase(java.util.Locale.ROOT) + "|"
        + (normalizedCity == null ? "" : normalizedCity) + "|" + normalizedLimit;
    CachedSuggestions cached = cache.get(cacheKey);
    if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
      return Map.of("suggestions", cached.suggestions());
    }
    if (webServiceKey.isEmpty()) {
      throw ApiException.serviceUnavailable("地点搜索服务尚未配置");
    }
    try {
      String url = "https://restapi.amap.com/v3/assistant/inputtips"
          + "?key=" + URLEncoder.encode(webServiceKey, StandardCharsets.UTF_8)
          + "&keywords=" + URLEncoder.encode(normalizedKeyword, StandardCharsets.UTF_8)
          + "&datatype=all";
      if (normalizedCity != null) {
        url += "&city=" + URLEncoder.encode(normalizedCity, StandardCharsets.UTF_8) + "&citylimit=true";
      }
      HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(REQUEST_TIMEOUT)
          .GET()
          .build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Upstream request failed");
      }
      Map<String, Object> data = com.travelos.common.JsonUtil.readObject(response.body());
      if (!"1".equals(String.valueOf(data.get("status")))) {
        throw new IllegalStateException("Upstream response rejected");
      }
      List<Map<String, Object>> suggestions = normalizeSuggestions(tips(data), normalizedCity);
      if (suggestions.size() > normalizedLimit) {
        suggestions = new ArrayList<>(suggestions.subList(0, normalizedLimit));
      }
      cache.put(cacheKey, new CachedSuggestions(System.currentTimeMillis() + CACHE_TTL_MS, suggestions));
      return Map.of("suggestions", suggestions);
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw ApiException.serviceUnavailable("地点搜索暂时不可用");
    }
  }

  /**
   * 高德 POI 详情搜索（place/text，extensions=all）：
   * 相比 inputtips 联想，额外返回评分(rating)、人均消费(avgCost)、营业时间(openTime)、电话(tel)，
   * 供 AI 做餐厅推荐、景点营业时间查询与时间规划。
   *
   * @param category "restaurant" / "attraction" / "hotel" / 其它（不限类型）
   */
  public Map<String, Object> searchPois(String keyword, String city, String category, Integer limit) {
    String normalizedKeyword = keyword == null ? "" : keyword.trim();
    if (normalizedKeyword.isEmpty()) {
      throw ApiException.badRequest("关键词不能为空");
    }
    String normalizedCity = city == null || city.isBlank() ? null : city.trim();
    String normalizedCategory = category == null ? "" : category.trim().toLowerCase(java.util.Locale.ROOT);
    int normalizedLimit = limit == null || limit <= 0 ? 6 : Math.min(limit, 15);
    String cacheKey = normalizedKeyword.toLowerCase(java.util.Locale.ROOT) + "|"
        + (normalizedCity == null ? "" : normalizedCity) + "|" + normalizedCategory + "|" + normalizedLimit;
    CachedPlaces cached = poiCache.get(cacheKey);
    if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
      return Map.of("places", cached.places());
    }
    if (webServiceKey.isEmpty()) {
      throw ApiException.serviceUnavailable("地点搜索服务尚未配置");
    }
    try {
      StringBuilder url = new StringBuilder("https://restapi.amap.com/v3/place/text")
          .append("?key=").append(URLEncoder.encode(webServiceKey, StandardCharsets.UTF_8))
          .append("&keywords=").append(URLEncoder.encode(normalizedKeyword, StandardCharsets.UTF_8))
          .append("&offset=").append(normalizedLimit)
          .append("&page=1&extensions=all&output=JSON");
      if (normalizedCity != null) {
        url.append("&city=").append(URLEncoder.encode(normalizedCity, StandardCharsets.UTF_8))
            .append("&citylimit=true");
      }
      if ("restaurant".equals(normalizedCategory)) {
        url.append("&types=").append(URLEncoder.encode(RESTAURANT_TYPES, StandardCharsets.UTF_8));
      } else if ("attraction".equals(normalizedCategory)) {
        url.append("&types=").append(URLEncoder.encode(ATTRACTION_TYPES, StandardCharsets.UTF_8));
      } else if ("hotel".equals(normalizedCategory)) {
        url.append("&types=").append(URLEncoder.encode(HOTEL_TYPES, StandardCharsets.UTF_8));
      }
      HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
          .uri(URI.create(url.toString()))
          .timeout(REQUEST_TIMEOUT)
          .GET()
          .build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("Upstream request failed");
      }
      Map<String, Object> data = com.travelos.common.JsonUtil.readObject(response.body());
      if (!"1".equals(String.valueOf(data.get("status")))) {
        throw new IllegalStateException("Upstream response rejected");
      }
      List<Map<String, Object>> places = normalizePois(pois(data), normalizedCity, normalizedCategory);
      poiCache.put(cacheKey, new CachedPlaces(System.currentTimeMillis() + CACHE_TTL_MS, places));
      return Map.of("places", places);
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw ApiException.serviceUnavailable("地点搜索暂时不可用");
    }
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> pois(Map<String, Object> data) {
    Object value = data.get("pois");
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private List<Map<String, Object>> normalizePois(List<Map<String, Object>> pois, String city,
      String category) {
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, Object> poi : pois) {
      String name = string(poi.get("name"));
      if (name == null || name.isBlank()) {
        continue;
      }
      double[] coordinates = parseLocation(string(poi.get("location")));
      String identity = string(poi.get("id")) + "|" + name + "|"
          + (coordinates == null ? "" : coordinates[0] + "," + coordinates[1]);
      if (!seen.add(identity)) {
        continue;
      }
      Map<String, Object> biz = poi.get("biz_ext") instanceof Map<?, ?> rawBiz
          ? castMap(rawBiz) : Map.of();
      String typecode = string(poi.get("typecode"));
      String cityName = city != null ? city : emptyToNull(listOrString(poi.get("cityname")));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", name.trim());
      row.put("address", listOrString(poi.get("address")));
      row.put("district", emptyToNull(listOrString(poi.get("adname"))));
      row.put("city", cityName);
      row.put("type", mapAmapPlaceType(typecode));
      row.put("category", emptyToNull(category));
      row.put("tel", emptyToNull(listOrString(poi.get("tel"))));
      row.put("rating", firstScalar(biz.get("rating")));
      row.put("avgCost", firstScalar(biz.get("cost")));
      row.put("openTime", firstNonBlank(firstScalar(biz.get("opentime2")),
          firstScalar(biz.get("open_time"))));
      row.put("latitude", coordinates == null ? null : BigDecimal7(coordinates[1]));
      row.put("longitude", coordinates == null ? null : BigDecimal7(coordinates[0]));
      row.put("sourceType", emptyToNull(typecode));
      result.add(row);
    }
    return result;
  }

  /** 取标量或数组中的第一个非空值（高德 biz_ext 字段有时为空数组）。 */
  private String firstScalar(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null && !item.toString().isBlank()) {
          return item.toString().trim();
        }
      }
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    return second == null || second.isBlank() ? null : second;
  }

  private String listOrString(Object value) {
    if (value instanceof List<?> list) {
      String joined = list.stream()
          .filter(item -> item != null && !item.toString().isBlank())
          .map(Object::toString)
          .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right)
          .trim();
      return joined.isEmpty() ? null : joined;
    }
    return value == null ? null : value.toString().trim();
  }

  private String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Map<?, ?> raw) {
    return (Map<String, Object>) raw;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> tips(Map<String, Object> data) {
    Object value = data.get("tips");
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private List<Map<String, Object>> normalizeSuggestions(List<Map<String, Object>> tips,
      String city) {
    List<Map<String, Object>> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, Object> tip : tips) {
      String name = string(tip.get("name"));
      if (name == null || name.isBlank()) {
        continue;
      }
      double[] coordinates = parseLocation(string(tip.get("location")));
      String identity = string(tip.get("id")) + "|" + name + "|"
          + (coordinates == null ? "" : String.valueOf(coordinates[0])) + "|"
          + (coordinates == null ? "" : String.valueOf(coordinates[1]));
      if (!seen.add(identity)) {
        continue;
      }
      Object rawAddress = tip.get("address");
      String address;
      if (rawAddress instanceof List<?> list) {
        address = list.stream().map(item -> item == null ? "" : item.toString())
            .reduce("", String::concat);
      } else {
        address = rawAddress == null ? null : rawAddress.toString().trim();
      }
      Map<String, Object> suggestion = new LinkedHashMap<>();
      suggestion.put("id", string(tip.get("id")) == null || string(tip.get("id")).isEmpty()
          ? identity
          : string(tip.get("id")));
      suggestion.put("name", name);
      suggestion.put("address", address == null || address.isEmpty() ? null : address);
      suggestion.put("district", string(tip.get("district")) == null
          || string(tip.get("district")).isEmpty() ? null : string(tip.get("district")).trim());
      suggestion.put("city", city);
      suggestion.put("province", null);
      suggestion.put("type", mapAmapPlaceType(string(tip.get("typecode"))));
      suggestion.put("sourceType", string(tip.get("typecode")));
      suggestion.put("latitude", coordinates == null ? null
          : BigDecimal7(coordinates[1]));
      suggestion.put("longitude", coordinates == null ? null
          : BigDecimal7(coordinates[0]));
      result.add(suggestion);
    }
    return result;
  }

  private String BigDecimal7(double value) {
    return String.format(java.util.Locale.ROOT, "%.7f", value);
  }

  private double[] parseLocation(String location) {
    if (location == null || !location.contains(",")) {
      return null;
    }
    try {
      String[] parts = location.split(",");
      double longitude = Double.parseDouble(parts[0]);
      double latitude = Double.parseDouble(parts[1]);
      return gcj02ToWgs84(longitude, latitude);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String mapAmapPlaceType(String typecode) {
    String group = typecode == null || typecode.length() < 2 ? null : typecode.substring(0, 2);
    if ("05".equals(group)) return "restaurant";
    if ("06".equals(group)) return "shopping";
    if ("10".equals(group)) return "hotel";
    if ("11".equals(group) || "14".equals(group)) return "attraction";
    if ("15".equals(group)) return "transport";
    return "custom";
  }

  /** GCJ-02 (AMap) to WGS-84 conversion, ported from coordinate-transform.ts. */
  static double[] gcj02ToWgs84(double longitude, double latitude) {
    if (outsideChina(longitude, latitude)) {
      return new double[] {longitude, latitude};
    }
    double[] delta = transform(longitude, latitude);
    return new double[] {longitude * 2 - delta[0], latitude * 2 - delta[1]};
  }

  private static double[] transform(double longitude, double latitude) {
    double latitudeDelta = transformLatitude(longitude - 105, latitude - 35);
    double longitudeDelta = transformLongitude(longitude - 105, latitude - 35);
    double radianLatitude = latitude / 180.0 * Math.PI;
    double magic = Math.sin(radianLatitude);
    magic = 1 - 0.006693421622965943 * magic * magic;
    double sqrtMagic = Math.sqrt(magic);
    latitudeDelta = (latitudeDelta * 180.0)
        / (((6378245.0 * (1 - 0.006693421622965943)) / (magic * sqrtMagic)) * Math.PI);
    longitudeDelta = (longitudeDelta * 180.0)
        / ((6378245.0 / sqrtMagic) * Math.cos(radianLatitude) * Math.PI);
    return new double[] {longitude + longitudeDelta, latitude + latitudeDelta};
  }

  private static boolean outsideChina(double longitude, double latitude) {
    return longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271;
  }

  private static double transformLatitude(double longitude, double latitude) {
    double result = -100.0 + 2.0 * longitude + 3.0 * latitude + 0.2 * latitude * latitude
        + 0.1 * longitude * latitude + 0.2 * Math.sqrt(Math.abs(longitude));
    result += (20.0 * Math.sin(6.0 * longitude * Math.PI)
        + 20.0 * Math.sin(2.0 * longitude * Math.PI)) * 2.0 / 3.0;
    result += (20.0 * Math.sin(latitude * Math.PI)
        + 40.0 * Math.sin(latitude / 3.0 * Math.PI)) * 2.0 / 3.0;
    result += (160.0 * Math.sin(latitude / 12.0 * Math.PI)
        + 320.0 * Math.sin(latitude * Math.PI / 30.0)) * 2.0 / 3.0;
    return result;
  }

  private static double transformLongitude(double longitude, double latitude) {
    double result = 300.0 + longitude + 2.0 * latitude + 0.1 * longitude * longitude
        + 0.1 * longitude * latitude + 0.1 * Math.sqrt(Math.abs(longitude));
    result += (20.0 * Math.sin(6.0 * longitude * Math.PI)
        + 20.0 * Math.sin(longitude * Math.PI)) * 2.0 / 3.0;
    result += (20.0 * Math.sin(longitude * Math.PI)
        + 40.0 * Math.sin(longitude / 3.0 * Math.PI)) * 2.0 / 3.0;
    result += (150.0 * Math.sin(longitude / 12.0 * Math.PI)
        + 300.0 * Math.sin(longitude / 30.0 * Math.PI)) * 2.0 / 3.0;
    return result;
  }

  private String string(Object value) {
    return value == null ? null : value.toString();
  }

  private record CachedSuggestions(long expiresAt, List<Map<String, Object>> suggestions) {
  }

  private record CachedPlaces(long expiresAt, List<Map<String, Object>> places) {
  }
}
