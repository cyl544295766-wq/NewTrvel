package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.Fmt;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.service.NotificationService;
import com.travelos.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 天气主动提醒引擎：
 * - 出发倒计时：D-3 提醒（含出发日预报，若有）；D-1 仅在有预报时发（通用行前清单由
 *   BookingReminderScheduler 每天 09:00 负责，避免重复轰炸）；
 * - 行程中明日遇雨/雪/雷暴（或降雨概率 ≥60%）：提示调整户外安排，并推荐预报内更合适的日期。
 * 幂等：NotificationService.weatherAlertAlreadySent 按 user + weatherKey 去重；
 * 天气数据经 WeatherService 刷新（自带 6 小时缓存）；定时任务无请求上下文，
 * 通过 TripScopeContext.runBypassed 绕过数据权限拦截。
 */
@Component
@ConditionalOnProperty(value = "app.weather-alert.enabled", havingValue = "true", matchIfMissing = true)
public class WeatherAlertScheduler {

  private static final Logger log = LoggerFactory.getLogger(WeatherAlertScheduler.class);
  private static final int SCAN_LIMIT = 200;
  /** 降雨概率达到该值即视为「可能影响出行」。 */
  private static final int RAIN_CHANCE_THRESHOLD = 60;

  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final WeatherService weatherService;
  private final NotificationService notificationService;

  public WeatherAlertScheduler(TripMapper tripMapper, TripMemberMapper tripMemberMapper,
      TripDayMapper tripDayMapper, TripPlaceMapper tripPlaceMapper,
      WeatherService weatherService, NotificationService notificationService) {
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.weatherService = weatherService;
    this.notificationService = notificationService;
  }

  /** 每小时第 20 分钟扫描一轮（错开 BookingReminderScheduler 的 09:00 清单）。 */
  @Scheduled(cron = "0 20 * * * ?")
  public void scheduledScan() {
    TripScopeContext.runBypassed(this::scanDepartureAlerts);
    TripScopeContext.runBypassed(this::scanRainAlerts);
  }

  /** 启动后首轮扫描（幂等去重兜底，重复执行不会重复发通知）。 */
  @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 20 * 1000L)
  public void scheduledInitialScan() {
    scheduledScan();
  }

  /** 供验收触发。 */
  public void scanDepartureAlerts() {
    List<Trip> trips = tripMapper.selectList(new LambdaQueryWrapper<Trip>()
        .apply("start_date::date IN ((current_date + interval '3 day')::date,"
            + " (current_date + interval '1 day')::date)")
        .isNull(Trip::getDeletedAt)
        .isNull(Trip::getArchivedAt)
        .last("LIMIT " + SCAN_LIMIT));
    for (Trip trip : trips) {
      try {
        int daysLeft = daysUntil(trip.getStartDate());
        if (daysLeft != 1 && daysLeft != 3) {
          continue;
        }
        String level = daysLeft == 1 ? "d1" : "d3";
        WeatherInfo startDayWeather = findWeatherOf(trip, Fmt.dateKey(trip.getStartDate()));
        // D-1 的通用行前清单已由 BookingReminderScheduler 负责，这里只在有预报时做天气侧重提醒
        if (daysLeft == 1 && startDayWeather == null) {
          continue;
        }
        String weatherText = startDayWeather == null ? ""
            : "，出发日预报：" + startDayWeather.describe();
        String content = "「" + trip.getTitle() + "」还有 " + daysLeft + " 天出发（"
            + trip.getDestination() + "）" + weatherText
            + (daysLeft == 1 ? "，明天就出发啦，记得最后检查一遍行程与行李！"
                : "，可以开始留意目的地的天气与行李准备了～");
        notifyMembers(trip, "weather_depart:" + trip.getId() + ":" + level, content);
      } catch (Exception ex) {
        log.warn("departure weather alert failed: trip={}", trip.getId(), ex);
      }
    }
  }

  /** 供验收触发。 */
  public void scanRainAlerts() {
    Date tomorrow = addDays(new Date(), 1);
    String tomorrowKey = Fmt.dateKey(tomorrow);
    List<Trip> trips = tripMapper.selectList(new LambdaQueryWrapper<Trip>()
        .apply("start_date::date <= (current_date + interval '1 day')::date")
        .apply("end_date::date >= (current_date + interval '1 day')::date")
        .isNull(Trip::getDeletedAt)
        .isNull(Trip::getArchivedAt)
        .last("LIMIT " + SCAN_LIMIT));
    for (Trip trip : trips) {
      try {
        TripDay tomorrowDay = findDayOf(trip, tomorrowKey);
        if (tomorrowDay == null) {
          continue; // 明天没有行程安排，无需提醒
        }
        WeatherInfo tomorrowWeather = findWeatherOf(trip, tomorrowKey);
        if (tomorrowWeather == null || !tomorrowWeather.isBad()) {
          continue;
        }
        long placeCount = tripPlaceMapper.selectCount(new LambdaQueryWrapper<TripPlace>()
            .eq(TripPlace::getTripDayId, tomorrowDay.getId()));
        StringBuilder content = new StringBuilder("「" + trip.getTitle() + "」明天（")
            .append(tomorrowKey).append("）预报 ").append(tomorrowWeather.describe());
        if (placeCount > 0) {
          content.append("，当天安排了 ").append(placeCount).append(" 个行程点");
        }
        String better = suggestBetterDay(trip, tomorrowKey);
        content.append(better.isEmpty()
            ? "。建议把户外景点调整为室内项目（博物馆/展馆等），或随时让我重新规划当天～"
            : "。建议把户外景点换到 " + better + "，或调整为室内项目（博物馆/展馆等）～");
        notifyMembers(trip, "weather_rain:" + trip.getId() + ":" + tomorrowKey,
            content.toString());
      } catch (Exception ex) {
        log.warn("rain weather alert failed: trip={}", trip.getId(), ex);
      }
    }
  }

  /** 单日天气快照（来自 getTripWeather 的响应字段）。 */
  private record WeatherInfo(String dateKey, String condition, Double tempLow,
      Double tempHigh, Integer rainChance) {

    /** 雨/雪/雷暴，或降雨概率达到阈值。 */
    boolean isBad() {
      String text = condition == null ? "" : condition;
      if (text.contains("雨") || text.contains("雪") || text.contains("雷")) {
        return true;
      }
      return rainChance != null && rainChance >= RAIN_CHANCE_THRESHOLD;
    }

    boolean isGood() {
      String text = condition == null ? "" : condition;
      int rain = rainChance == null ? 0 : rainChance;
      return ("晴".equals(text) || "多云".equals(text)) && rain < RAIN_CHANCE_THRESHOLD;
    }

    String describe() {
      StringBuilder builder = new StringBuilder(condition == null ? "未知" : condition);
      if (tempLow != null && tempHigh != null) {
        builder.append(" ").append(tempLow.intValue())
            .append("~").append(tempHigh.intValue()).append("°C");
      }
      if (rainChance != null) {
        builder.append("（降雨概率 ").append(rainChance).append("%）");
      }
      return builder.toString();
    }
  }

  /** 拉取（必要时刷新）行程天气，按日期建索引；任何失败返回空 Map，不影响提醒主流程。 */
  private Map<String, WeatherInfo> weatherOf(Trip trip) {
    Map<String, WeatherInfo> byDate = new LinkedHashMap<>();
    try {
      String userId = trip.getOwnerId();
      if (userId == null || userId.isBlank()) {
        return byDate;
      }
      Map<String, Object> body = weatherService.getTripWeather(trip.getId(), userId);
      if (body.get("weather") instanceof List<?> rows) {
        for (Object row : rows) {
          if (row instanceof Map<?, ?> map && map.get("date") != null) {
            WeatherInfo info = new WeatherInfo(
                Fmt.dateKey((Date) map.get("date")),
                (String) map.get("condition"),
                toDouble(map.get("tempLow")),
                toDouble(map.get("tempHigh")),
                toInteger(map.get("rainChance")));
            byDate.putIfAbsent(info.dateKey(), info);
          }
        }
      }
    } catch (Exception ignore) {
      // 天气刷新失败不影响本轮其他提醒
    }
    return byDate;
  }

  private WeatherInfo findWeatherOf(Trip trip, String dateKey) {
    return weatherOf(trip).get(dateKey);
  }

  /** 在目标日期之后、预报覆盖范围内找第一个好天气的日期，返回「10-27（多云）」；没有则空串。 */
  private String suggestBetterDay(Trip trip, String afterDateKey) {
    List<WeatherInfo> rows = new ArrayList<>(weatherOf(trip).values());
    rows.sort(Comparator.comparing(WeatherInfo::dateKey));
    for (WeatherInfo row : rows) {
      if (row.dateKey().compareTo(afterDateKey) <= 0) {
        continue;
      }
      if (row.isGood()) {
        return row.dateKey() + "（" + row.condition() + "）";
      }
    }
    return "";
  }

  private TripDay findDayOf(Trip trip, String dateKey) {
    for (TripDay day : tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, trip.getId()))) {
      if (dateKey.equals(Fmt.dateKey(day.getDate()))) {
        return day;
      }
    }
    return null;
  }

  private void notifyMembers(Trip trip, String weatherKey, String content) {
    for (TripMember member : tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, trip.getId()))) {
      if (notificationService.weatherAlertAlreadySent(member.getUserId(), weatherKey)) {
        continue;
      }
      notificationService.createWeatherAlert(member.getUserId(), trip.getId(),
          trip.getTitle(), content, weatherKey);
    }
    log.info("Weather alert processed: trip={}, key={}", trip.getId(), weatherKey);
  }

  private Double toDouble(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private Integer toInteger(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private int daysUntil(Date date) {
    long diff = startOfDay(date).getTime() - startOfDay(new Date()).getTime();
    return (int) Math.round(diff / 86400000.0);
  }

  private Date startOfDay(Date date) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(date);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    return calendar.getTime();
  }

  private Date addDays(Date date, int days) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(date);
    calendar.add(Calendar.DATE, days);
    return calendar.getTime();
  }
}
