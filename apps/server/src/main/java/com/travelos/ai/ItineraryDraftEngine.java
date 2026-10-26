package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.service.BookingService;
import com.travelos.service.PlacesService;
import com.travelos.service.TripExpenseService;
import com.travelos.service.TripPlaceService;
import com.travelos.service.TripService;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 草稿应用引擎：把用户确认的草稿（行程变更/记账/预订）按类型写入业务表。
 * 从 AiChatService 拆出，职责单一——「点应用之后发生什么」都在这里，
 * 含覆盖语义（replaceDay）、同名去重、时间/票价清洗与坐标兜底地理编码。
 * 所有写入仍走现有 Service 层（鉴权/数据隔离不绕过）。
 */
@Component
public class ItineraryDraftEngine {

  private final TripService tripService;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripPlaceService tripPlaceService;
  private final PlacesService placesService;
  private final TripExpenseService tripExpenseService;
  private final BookingService bookingService;

  public ItineraryDraftEngine(TripService tripService, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripPlaceService tripPlaceService,
      PlacesService placesService, TripExpenseService tripExpenseService,
      BookingService bookingService) {
    this.tripService = tripService;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripPlaceService = tripPlaceService;
    this.placesService = placesService;
    this.tripExpenseService = tripExpenseService;
    this.bookingService = bookingService;
  }

  /** 应用行程变更草稿（add/remove/update，可跨多天），返回实际应用的变更数。 */
  public int applyItineraryChanges(String tripId, Map<String, Object> draft, String userId) {
    if (!(draft.get("changes") instanceof List<?> changes) || changes.isEmpty()) {
      throw ApiException.badRequest("草稿中没有可应用的变更");
    }
    // 城市提示：取行程目的地，提升地理编码命中率（失败不阻断应用）
    String cityHint = "";
    try {
      Map<String, Object> tripRes = tripService.findOne(tripId, userId);
      @SuppressWarnings("unchecked")
      Map<String, Object> tripMap = (Map<String, Object>) tripRes.get("trip");
      if (tripMap != null && tripMap.get("destination") != null) {
        cityHint = tripMap.get("destination").toString();
      }
    } catch (Exception ignore) {
      // 读取行程失败仅影响地理编码质量
    }
    // 目标天 → 当天 add 变更数：用于识别「重新规划整天」（多个点 → 覆盖当天，而非叠加）
    Map<Integer, Integer> addCountByDay = new LinkedHashMap<>();
    for (Object item : changes) {
      if (item instanceof Map<?, ?> raw && raw.get("dayIndex") != null
          && "add".equals(String.valueOf(raw.get("action")))) {
        try {
          addCountByDay.merge(toInt(raw.get("dayIndex")), 1, Integer::sum);
        } catch (Exception ignore) {
          // dayIndex 非法时跳过统计，后续按普通追加处理
        }
      }
    }
    Object replaceFlag = draft.get("replaceDay");
    Set<String> replacedDayIds = new HashSet<>();
    int count = 0;
    for (Object item : changes) {
      if (!(item instanceof Map<?, ?> raw)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> change = (Map<String, Object>) raw;
      String action = String.valueOf(change.get("action"));
      switch (action) {
        case "add" -> {
          TripDay day = findDayByIndex(tripId, toInt(change.get("dayIndex")));
          // 覆盖语义：重新规划当天时清空原有安排，避免多次应用后「一天出现多段行程」
          if (shouldReplaceDay(replaceFlag, addCountByDay, change.get("dayIndex"))
              && replacedDayIds.add(day.getId())) {
            clearDayPlaces(tripId, day.getId(), null);
          }
          String placeType = change.get("type") == null ? null : change.get("type").toString();
          // 住宿是「一家」：同一天重新选酒店时替换旧酒店，而不是并列两家
          if (placeType != null && "hotel".equalsIgnoreCase(placeType)) {
            clearDayPlaces(tripId, day.getId(), "hotel");
          }
          String placeName = change.get("name") == null ? null
              : change.get("name").toString().trim();
          boolean duplicate = placeName != null && !placeName.isEmpty()
              && dayHasPlaceNamed(tripId, day.getId(), placeName);
          if (duplicate) {
            // 同名点已存在：视为同一安排，跳过重复插入
            count++;
          } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripDayId", day.getId());
            payload.put("name", change.get("name"));
            if (change.get("type") != null) {
              payload.put("type", change.get("type"));
            }
            if (change.get("address") != null) {
              payload.put("address", change.get("address"));
            }
            // 时间规划：把草稿的 HH:mm 落到该天的具体日期（startTime/endTime）
            putTime(payload, "startTime", day.getDate(), change.get("startTime"));
            putTime(payload, "endTime", day.getDate(), change.get("endTime"));
            // 票价：写入预估费用（estimatedCost）
            putCost(payload, change.get("estimatedCost"));
            // 营业时间并入备注（notes）
            putNotes(payload, change.get("notes"), stringValue(change.get("openTime")));
            // 坐标：草稿携带则直接用，缺则按名称/地址地理编码补齐 —— 否则地图无法定位（历史 bug：AI 应用的地点全部无坐标）
            String[] coord = resolveCoordinates(change, cityHint);
            if (coord[0] != null) {
              payload.put("latitude", coord[0]);
              payload.put("longitude", coord[1]);
            }
            tripPlaceService.create(tripId, payload, userId);
            count++;
          }
        }
        case "remove" -> {
          tripPlaceService.delete(tripId, String.valueOf(change.get("placeId")), userId);
          count++;
        }
        case "update" -> {
          Map<String, Object> payload = new LinkedHashMap<>();
          if (change.get("name") != null) {
            payload.put("name", change.get("name"));
          }
          if (change.get("type") != null) {
            payload.put("type", change.get("type"));
          }
          if (change.get("address") != null) {
            payload.put("address", change.get("address"));
          }
          // 时间规划：确定目标日期后落 startTime/endTime
          Date dayDate = resolveDayDate(change);
          putTime(payload, "startTime", dayDate, change.get("startTime"));
          putTime(payload, "endTime", dayDate, change.get("endTime"));
          putCost(payload, change.get("estimatedCost"));
          putNotes(payload, change.get("notes"), stringValue(change.get("openTime")));
          // 更新也允许显式携带坐标（containsKey 语义，仅在草稿提供时写入）
          if (change.get("latitude") != null && change.get("longitude") != null) {
            payload.put("latitude", change.get("latitude"));
            payload.put("longitude", change.get("longitude"));
          }
          tripPlaceService.update(tripId, String.valueOf(change.get("placeId")), payload, userId);
          count++;
        }
        default -> {
          // 未知动作跳过
        }
      }
    }
    return count;
  }

  /**
   * 是否用本次变更覆盖当天原有安排：
   * 草稿显式 replaceDay=true → 覆盖；显式 false → 追加；
   * 未提供时按启发式判断——同一草稿给某天 add 了 2 个及以上地点，视为「重新规划当天」→ 覆盖。
   * 历史 bug：多次应用同一天的规划时地点不断累加，导致一天出现多段行程。
   */
  private boolean shouldReplaceDay(Object replaceFlag, Map<Integer, Integer> addCountByDay,
      Object dayIndexValue) {
    if (Boolean.TRUE.equals(replaceFlag)) {
      return true;
    }
    if (Boolean.FALSE.equals(replaceFlag)) {
      return false;
    }
    if (dayIndexValue == null) {
      return false;
    }
    try {
      return addCountByDay.getOrDefault(toInt(dayIndexValue), 0) >= 2;
    } catch (Exception ignore) {
      return false;
    }
  }

  /** 清空某天原有地点（type 为 null 时清全部；指定 type 时只清该类型，如换酒店）。 */
  private void clearDayPlaces(String tripId, String dayId, String type) {
    LambdaQueryWrapper<TripPlace> wrapper = new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId)
        .eq(TripPlace::getTripDayId, dayId);
    if (type != null) {
      wrapper.eq(TripPlace::getType, type);
    }
    tripPlaceMapper.delete(wrapper);
  }

  /** 当天是否已存在同名地点（用于避免重复叠加同一安排）。 */
  private boolean dayHasPlaceNamed(String tripId, String dayId, String name) {
    Long existing = tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId)
        .eq(TripPlace::getTripDayId, dayId)
        .eq(TripPlace::getName, name));
    return existing != null && existing > 0;
  }

  /**
   * 变更目标日期：update 只信该地点当前所属行程天。
   * dayIndex 仅对 add 有意义——update 不携带 tripDayId，无法挪天，
   * 若这里改用 dayIndex 的日期拼 startTime，会出现「地点在第 N 天却带第 M 天时间戳」的错乱。
   */
  private Date resolveDayDate(Map<String, Object> change) {
    try {
      Object placeId = change.get("placeId");
      if (placeId != null) {
        TripPlace existing = tripPlaceMapper.selectById(String.valueOf(placeId));
        if (existing != null && existing.getTripDayId() != null) {
          TripDay day = tripDayMapper.selectById(existing.getTripDayId());
          if (day != null) {
            return day.getDate();
          }
        }
      }
    } catch (Exception ignore) {
      // 无法确定日期时，时间字段将被忽略
    }
    return null;
  }

  /** 把 HH:mm 形式的时刻落到指定日期，写入 payload（日期为空或格式非法则忽略）。 */
  private void putTime(Map<String, Object> payload, String key, Date dayDate, Object timeValue) {
    String time = stringValue(timeValue);
    if (dayDate == null || time == null || time.isBlank()) {
      return;
    }
    String hm = time.trim();
    if (hm.length() > 5) {
      hm = hm.substring(0, 5);
    }
    if (!hm.matches("\\d{2}:\\d{2}")) {
      return;
    }
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    payload.put(key, format.format(dayDate) + " " + hm + ":00");
  }

  /**
   * 票价写入 estimatedCost。LLM 常输出「免费 / 约100元 / ￥120.5」等非纯数字文本
   * （提示词要求纯数字但模型不总守约）：这里清洗出金额，「免费/免票」记 0，
   * 解析不出金额则丢弃该字段——而不是把脏字符串直传给下游抛错，避免整批变更应用失败。
   */
  private void putCost(Map<String, Object> payload, Object cost) {
    String text = stringValue(cost);
    if (text == null || text.isBlank()) {
      return;
    }
    text = text.trim();
    if (text.contains("免费") || text.contains("免票")) {
      payload.put("estimatedCost", "0");
      return;
    }
    String normalized = text.replaceAll("(?<=\\d)[,，](?=\\d)", "");
    Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(normalized);
    if (matcher.find()) {
      payload.put("estimatedCost", matcher.group());
    }
  }

  /** 营业时间并入备注；没有营业时间时保留原备注。 */
  private void putNotes(Map<String, Object> payload, Object notesObj, String openTime) {
    String notes = stringValue(notesObj);
    String notesText = notes == null ? "" : notes.trim();
    if (openTime != null && !openTime.isBlank()) {
      String merged = "营业时间：" + openTime.trim()
          + (notesText.isEmpty() ? "" : "；" + notesText);
      payload.put("notes", merged);
    } else if (!notesText.isEmpty()) {
      payload.put("notes", notesText);
    }
  }

  /** 空值安全的 toString；供本类与同包工具类复用。 */
  public static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * 解析地点坐标：优先用草稿自带的 latitude/longitude（来自 search_places 结果）；
   * 缺失时按名称（其次地址）调高德地点联想做地理编码，取首个带坐标的建议。
   * 任何失败都返回 {null, null}，不阻断草稿应用。
   */
  private String[] resolveCoordinates(Map<String, Object> change, String cityHint) {
    Object lat = change.get("latitude");
    Object lng = change.get("longitude");
    if (lat != null && lng != null && !String.valueOf(lat).isBlank() && !String.valueOf(lng).isBlank()) {
      return new String[]{String.valueOf(lat), String.valueOf(lng)};
    }
    String name = change.get("name") == null ? "" : change.get("name").toString().trim();
    String address = change.get("address") == null ? "" : change.get("address").toString().trim();
    /* 去括注名（如「日喀则（返程过夜点）」→「日喀则」）：高德联想对带括注的全名常搜不到，
     * 而括注多为「过夜点/途经落脚」等人工标注，去掉后才能命中真实地点 */
    String stripped = name.replaceAll("（[^）]*）", "").replaceAll("\\([^)]*\\)", "").trim();
    java.util.LinkedHashSet<String> keywords = new java.util.LinkedHashSet<>();
    if (!name.isBlank()) keywords.add(name);
    if (!stripped.isBlank() && !stripped.equals(name)) keywords.add(stripped);
    if (!address.isBlank()) keywords.add(address);
    for (String keyword : keywords) {
      if (keyword.isBlank()) {
        continue;
      }
      try {
        Map<String, Object> res = placesService.getSuggestions(keyword,
            cityHint == null ? "" : cityHint, 3);
        if (res.get("suggestions") instanceof List<?> list) {
          for (Object o : list) {
            if (!(o instanceof Map<?, ?> rawSug)) {
              continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sug = (Map<String, Object>) rawSug;
            Object sugLat = sug.get("latitude");
            Object sugLng = sug.get("longitude");
            if (sugLat != null && sugLng != null
                && !String.valueOf(sugLat).isBlank() && !String.valueOf(sugLng).isBlank()) {
              return new String[]{String.valueOf(sugLat), String.valueOf(sugLng)};
            }
          }
        }
      } catch (Exception ignore) {
        // 地理编码失败（key 未配置/超时等）不阻断应用
      }
    }
    return new String[]{null, null};
  }

  /** 应用记账草稿：走 TripExpenseService.create 全量校验；付款人默认当前用户、缺省均分。 */
  public void applyExpenseDraft(String tripId, Map<String, Object> draft, String userId) {
    @SuppressWarnings("unchecked")
    Map<String, Object> expense = (Map<String, Object>) draft.get("expense");
    if (expense == null) {
      throw ApiException.badRequest("草稿中没有记账信息");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("amount", expense.get("amount"));
    payload.put("category", expense.get("category"));
    if (expense.get("title") != null) {
      payload.put("title", expense.get("title"));
    }
    if (expense.get("notes") != null) {
      payload.put("notes", expense.get("notes"));
    }
    tripExpenseService.create(tripId, userId, payload);
  }

  /** 应用预订草稿：走 BookingService.create 全量校验（类型、标题、归属行程等），写入预定管理。 */
  public void applyBookingDraft(String tripId, Map<String, Object> draft, String userId) {
    @SuppressWarnings("unchecked")
    Map<String, Object> booking = (Map<String, Object>) draft.get("booking");
    if (booking == null) {
      throw ApiException.badRequest("草稿中没有预订信息");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", booking.get("type"));
    payload.put("title", booking.get("title"));
    if (booking.get("startTime") != null) {
      payload.put("startTime", normalizeBookingTime(booking.get("startTime")));
    }
    if (booking.get("endTime") != null) {
      payload.put("endTime", normalizeBookingTime(booking.get("endTime")));
    }
    if (booking.get("amount") != null) {
      payload.put("amount", booking.get("amount"));
    }
    if (booking.get("orderNo") != null) {
      payload.put("orderNo", booking.get("orderNo"));
    }
    if (booking.get("confirmCode") != null) {
      payload.put("confirmCode", booking.get("confirmCode"));
    }
    if (booking.get("dayIndex") != null) {
      try {
        TripDay day = findDayByIndex(tripId, toInt(booking.get("dayIndex")));
        payload.put("tripDayId", day.getId());
      } catch (Exception ignore) {
        // 天数不存在时忽略关联，不阻断预订写入
      }
    }
    if (booking.get("extra") instanceof Map<?, ?> extra && !extra.isEmpty()) {
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : extra.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object value = entry.getValue();
        if (value == null || String.valueOf(value).isBlank()) {
          continue;
        }
        normalized.put(key,
            "freeCancelDeadline".equals(key) ? normalizeBookingTime(value) : value);
      }
      if (!normalized.isEmpty()) {
        payload.put("extra", normalized);
      }
    }
    bookingService.create(tripId, userId, payload);
  }

  /** 预订时间归一化：与前端 datetime-local 约定一致（"YYYY-MM-DD HH:mm"，空格分隔；纯日期按当天零点）。 */
  private String normalizeBookingTime(Object value) {
    String text = String.valueOf(value).trim().replace('T', ' ');
    if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
      text += " 00:00:00";
    } else if (text.length() == 16) {
      text += ":00";
    }
    return text;
  }

  private TripDay findDayByIndex(String tripId, int dayIndex) {
    TripDay day = tripDayMapper.selectOne(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .eq(TripDay::getDayIndex, dayIndex)
        .last("LIMIT 1"));
    if (day == null) {
      throw ApiException.badRequest("第 " + dayIndex + " 天不存在，无法应用变更");
    }
    return day;
  }

  private int toInt(Object value) {
    try {
      return Integer.parseInt(String.valueOf(value).split("\\.")[0]);
    } catch (Exception ex) {
      throw ApiException.badRequest("dayIndex 格式不正确");
    }
  }
}
