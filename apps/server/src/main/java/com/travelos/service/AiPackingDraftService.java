package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.Fmt;
import com.travelos.common.Payloads;
import com.travelos.entity.Trip;
import com.travelos.mapper.TripMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiPackingDraftService {
  private static final int MAX_ITEMS = 32;
  private static final int MAX_QUANTITY = 999;
  private static final String NEW_LIST = "new";

  private final TripMapper trips;
  private final TripMemberService members;
  private final PackingListService packing;
  private final WeatherService weather;

  public AiPackingDraftService(TripMapper trips, TripMemberService members, PackingListService packing,
      WeatherService weather) {
    this.trips = trips;
    this.members = members;
    this.packing = packing;
    this.weather = weather;
  }

  public Map<String, Object> context(String tripId, String userId) {
    members.requireTripMember(tripId, userId);
    Trip trip = requireTrip(tripId);
    Map<String, Object> context = details(trip);
    List<Map<String, Object>> forecast = forecast(tripId, userId);
    context.put("weatherStatus", forecast.isEmpty() ? "unavailable" : "available");
    context.put("weather", forecast);
    context.put("lists", lists(tripId).stream().map(list -> Map.of(
        "id", list.id(), "name", list.name(), "items", list.items())).toList());
    return context;
  }

  /** 建议本身与各目标清单的重复项分开保存，用户可在确认前切换目标。 */
  public Map<String, Object> preview(String tripId, String userId, Map<String, Object> args) {
    members.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Trip trip = requireTrip(tripId);
    List<PackingListView> lists = lists(tripId);
    String defaultList = Payloads.trimmedOrNull(args.get("listId"));
    if (defaultList != null && !NEW_LIST.equals(defaultList)
        && lists.stream().noneMatch(list -> list.id().equals(defaultList))) {
      throw ApiException.badRequest("请在当前行程的清单中选择目标");
    }
    String newListName = args.get("newListName") == null ? "旅行打包清单"
        : label(args.get("newListName"), 100, "新清单名称");
    List<Map<?, ?>> suggestions = rows(args.get("items"), "请提供 1 至 32 项打包建议");
    List<Map<String, Object>> items = new ArrayList<>();
    List<String> duplicateSuggestions = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (Map<?, ?> item : suggestions) {
      String name = label(item.get("name"), 200, "物品名称");
      int count = item.get("quantity") == null ? 1 : quantity(item.get("quantity"));
      if (keys.add(PackingListService.nameKey(name))) {
        items.add(Map.of("id", Integer.toString(items.size() + 1), "name", name, "quantity", count));
      } else {
        duplicateSuggestions.add(name);
      }
    }
    List<Map<String, Object>> targets = lists.stream().map(list -> {
      Set<String> existing = list.items().stream().map(item -> PackingListService.nameKey(item.name()))
          .collect(Collectors.toSet());
      List<Object> duplicates = items.stream().filter(item ->
          existing.contains(PackingListService.nameKey((String) item.get("name")))).map(item -> item.get("id")).toList();
      return Map.<String, Object>of("id", list.id(), "name", list.name(), "itemCount", list.items().size(),
          "duplicateIds", duplicates);
    }).toList();
    Map<String, Object> preview = details(trip);
    preview.put("items", items);
    preview.put("targets", targets);
    preview.put("defaultTarget", defaultList != null ? defaultList : (lists.size() == 1 ? lists.get(0).id() : ""));
    preview.put("newListName", newListName);
    preview.put("duplicateSuggestions", duplicateSuggestions.stream().distinct().toList());
    preview.put("weatherNote", forecast(tripId, userId).isEmpty()
        ? "暂无天气预报，草稿以常规准备为主，天气相关物品请按出发前预报核对"
        : "天气建议仅参考已有预报，出发前可再核对");
    return preview;
  }

  @Transactional
  public Map<String, Object> apply(String tripId, String userId, Map<String, Object> draft,
      Map<String, Object> choices) {
    members.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    if (!(draft.get("packingPreview") instanceof Map<?, ?> preview) || !tripId.equals(preview.get("tripId"))) {
      throw ApiException.badRequest("打包草稿缺少可确认的预览");
    }
    String targetId = Payloads.trimmedOrNull(choices.get("targetId"));
    if (targetId == null) {
      throw ApiException.badRequest("请选择目标清单或确认创建新清单");
    }
    boolean create = NEW_LIST.equals(targetId);
    String listName = create ? label(choices.getOrDefault("newListName", preview.get("newListName")), 100, "新清单名称")
        : targetName(preview, targetId);
    List<PackingListService.PackingEntry> selected = selectedItems(preview, choices);

    // 新清单、成员撤权和手工编辑走同一个事务边界，先锁旅行再确认角色。
    if (trips.selectOne(new LambdaQueryWrapper<Trip>().eq(Trip::getId, tripId).last("FOR UPDATE")) == null) {
      throw ApiException.notFound("旅行不存在");
    }
    if (members.lockTripMembers(tripId).stream().noneMatch(member ->
        userId.equals(member.getUserId()) && TripPermission.EDITABLE_ROLES.contains(member.getRole()))) {
      throw ApiException.forbidden("当前用户已无法编辑该旅行的打包清单");
    }
    PackingListService.PackingInsert result;
    try {
      result = packing.appendDraftItems(tripId, userId, create ? null : targetId, listName, selected);
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        throw ApiException.conflict("目标清单已不存在，请重新生成打包草稿");
      }
      throw ex;
    }
    return Map.of("tripId", tripId, "listId", result.listId(), "listName", result.listName(),
        "items", result.added(), "skipped", result.skipped(), "appliedCount", result.added().size());
  }

  private List<PackingListService.PackingEntry> selectedItems(Map<?, ?> preview, Map<String, Object> choices) {
    Map<String, String> names = new LinkedHashMap<>();
    for (Map<?, ?> suggestion : rows(preview.get("items"), "打包草稿缺少物品建议")) {
      names.put(String.valueOf(suggestion.get("id")), label(suggestion.get("name"), 200, "物品名称"));
    }
    Map<String, Integer> quantities = new LinkedHashMap<>();
    for (Map<?, ?> choice : rows(choices.get("items"), "请至少选择一项物品")) {
      String id = Payloads.trimmedOrNull(choice.get("id"));
      if (!names.containsKey(id) || quantities.containsKey(id)) {
        throw ApiException.badRequest("只能确认草稿中的物品，每项只能确认一次");
      }
      quantities.put(id, quantity(choice.get("quantity")));
    }
    return names.entrySet().stream().filter(entry -> quantities.containsKey(entry.getKey()))
        .map(entry -> new PackingListService.PackingEntry(entry.getValue(), quantities.get(entry.getKey()))).toList();
  }

  private String targetName(Map<?, ?> preview, String targetId) {
    if (preview.get("targets") instanceof List<?> targets) {
      for (Object row : targets) {
        if (row instanceof Map<?, ?> target && targetId.equals(target.get("id"))) {
          return label(target.get("name"), 100, "目标清单名称");
        }
      }
    }
    throw ApiException.badRequest("只能选择草稿预览所列的目标清单");
  }

  private List<PackingListView> lists(String tripId) {
    return packing.findListsByTrip(tripId).stream().map(list -> new PackingListView(list.getId(), list.getName(),
        packing.itemsOf(list.getId()).stream()
            .map(item -> new PackingContextItem(item.getName(), item.getQuantity(), Boolean.TRUE.equals(item.getIsPacked())))
            .toList())).toList();
  }

  private Trip requireTrip(String tripId) {
    Trip trip = trips.selectById(tripId);
    if (trip == null) {
      throw ApiException.notFound("旅行不存在");
    }
    return trip;
  }

  private Map<String, Object> details(Trip trip) {
    String start = Fmt.dateKey(trip.getStartDate());
    String end = Fmt.dateKey(trip.getEndDate());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("tripId", trip.getId());
    details.put("tripTitle", trip.getTitle());
    details.put("destination", trip.getDestination());
    details.put("days", ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1);
    details.put("startDate", start);
    details.put("endDate", end);
    return details;
  }

  private List<Map<String, Object>> forecast(String tripId, String userId) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (weather.getTripWeather(tripId, userId).get("weather") instanceof List<?> forecasts) {
      for (Object entry : forecasts) {
        if (entry instanceof Map<?, ?> forecast) {
          Map<String, Object> row = new LinkedHashMap<>();
          if (forecast.get("date") instanceof Date date) {
            row.put("date", Fmt.dateKey(date));
          } else {
            row.put("date", forecast.get("date"));
          }
          for (String key : List.of("condition", "tempHigh", "tempLow", "rainChance")) {
            if (forecast.get(key) != null) {
              row.put(key, forecast.get(key));
            }
          }
          result.add(row);
        }
      }
    }
    return result;
  }

  private static List<Map<?, ?>> rows(Object value, String error) {
    if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_ITEMS) {
      throw ApiException.badRequest(error);
    }
    List<Map<?, ?>> result = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> row)) {
        throw ApiException.badRequest(error);
      }
      result.add(row);
    }
    return result;
  }

  private static int quantity(Object value) {
    try {
      int quantity = new BigDecimal(String.valueOf(value)).intValueExact();
      if (quantity >= 1 && quantity <= MAX_QUANTITY) {
        return quantity;
      }
    } catch (ArithmeticException | NumberFormatException ignored) {
      // 统一返回数量校验错误。
    }
    throw ApiException.badRequest("物品数量必须为 1 至 999 的整数");
  }

  private static String label(Object value, int maxLength, String field) {
    if (value instanceof String text) {
      String name = text.strip();
      if (!name.isEmpty() && name.length() <= maxLength && !PackingListService.nameKey(name).isEmpty()) {
        return name;
      }
    }
    throw ApiException.badRequest(field + "长度必须为 1-" + maxLength + " 个字符");
  }

  private record PackingContextItem(String name, int quantity, boolean packed) {}
  private record PackingListView(String id, String name, List<PackingContextItem> items) {}
}
