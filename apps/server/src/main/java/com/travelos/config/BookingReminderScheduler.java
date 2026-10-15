package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.common.Fmt;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.Booking;
import com.travelos.entity.Notification;
import com.travelos.entity.PackingItem;
import com.travelos.entity.PackingList;
import com.travelos.entity.Trip;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripWeather;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.NotificationMapper;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripWeatherMapper;
import com.travelos.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 行前提醒引擎（设计文档 3.3.3）：
 * - 每 10 分钟扫描到点未发送的预订提醒，创建通知并标记 reminder_sent_at（幂等）；
 * - 每天 09:00 为「明天出发」的行程发行前检查清单汇总（同一天同行程幂等去重）。
 * 定时任务无请求上下文，全部通过 TripScopeContext.runBypassed 绕过数据权限拦截。
 */
@Component
@ConditionalOnProperty(value = "app.booking-reminder.enabled", havingValue = "true", matchIfMissing = true)
public class BookingReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(BookingReminderScheduler.class);
  private static final int SCAN_LIMIT = 200;

  private final BookingMapper bookingMapper;
  private final TripMapper tripMapper;
  private final TripMemberMapper tripMemberMapper;
  private final PackingListMapper packingListMapper;
  private final PackingItemMapper packingItemMapper;
  private final TripWeatherMapper tripWeatherMapper;
  private final NotificationMapper notificationMapper;
  private final NotificationService notificationService;

  public BookingReminderScheduler(BookingMapper bookingMapper, TripMapper tripMapper,
      TripMemberMapper tripMemberMapper, PackingListMapper packingListMapper,
      PackingItemMapper packingItemMapper, TripWeatherMapper tripWeatherMapper,
      NotificationMapper notificationMapper, NotificationService notificationService) {
    this.bookingMapper = bookingMapper;
    this.tripMapper = tripMapper;
    this.tripMemberMapper = tripMemberMapper;
    this.packingListMapper = packingListMapper;
    this.packingItemMapper = packingItemMapper;
    this.tripWeatherMapper = tripWeatherMapper;
    this.notificationMapper = notificationMapper;
    this.notificationService = notificationService;
  }

  /** 预订提醒扫描：每 10 分钟一轮（首轮启动即跑）。 */
  @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 15 * 1000L)
  public void scheduledScanReminders() {
    TripScopeContext.runBypassed(this::scanReminders);
  }

  /** 行前检查清单：每天 09:00。 */
  @Scheduled(cron = "0 0 9 * * ?")
  public void scheduledPrecheck() {
    TripScopeContext.runBypassed(this::sendPrecheckSummaries);
  }

  /** 扫描到点未发送的预订提醒。public 供验收触发。 */
  public void scanReminders() {
    List<Booking> dueBookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getReminderEnabled, true)
        .isNull(Booking::getReminderSentAt)
        .isNotNull(Booking::getStartTime)
        .apply("start_time <= now() + make_interval(mins => reminder_minutes)")
        // 太久远的漏发预订不再补发，避免历史数据轰炸
        .apply("start_time >= now() - interval '1 day'")
        .last("LIMIT " + SCAN_LIMIT));
    for (Booking booking : dueBookings) {
      Trip trip = tripMapper.selectById(booking.getTripId());
      if (trip == null) {
        continue;
      }
      String content = buildReminderContent(trip.getTitle(), booking);
      for (TripMember member : membersOf(booking.getTripId())) {
        notificationService.createBookingReminder(member.getUserId(), booking.getTripId(),
            trip.getTitle(), content);
      }
      // 幂等标记：条件更新（reminder_sent_at 仍为空才更新），重跑/重启/并发不重复发
      int updated = bookingMapper.update(null, new LambdaUpdateWrapper<Booking>()
          .eq(Booking::getId, booking.getId())
          .isNull(Booking::getReminderSentAt)
          .set(Booking::getReminderSentAt, new Date()));
      if (updated > 0) {
        log.info("Booking reminder sent: booking={}, trip={}", booking.getId(), booking.getTripId());
      }
    }
  }

  /** 行前检查清单：出发前一天上午给成员发汇总。public 供验收触发。 */
  public void sendPrecheckSummaries() {
    Date tomorrow = addDays(startOfDay(new Date()), 1);
    String dateKey = com.travelos.common.Fmt.dateKey(tomorrow);
    List<Trip> trips = tripMapper.selectList(new LambdaQueryWrapper<Trip>()
        .apply("start_date::date = (current_date + interval '1 day')::date")
        .isNull(Trip::getDeletedAt)
        .isNull(Trip::getArchivedAt)
        .last("LIMIT 200"));
    for (Trip trip : trips) {
      String precheckKey = "trip:" + trip.getId() + ":" + dateKey;
      for (TripMember member : membersOf(trip.getId())) {
        if (notificationService.precheckAlreadySent(member.getUserId(), precheckKey)) {
          continue; // 同一天同行程不重复发
        }
        notificationService.createTripPrecheck(member.getUserId(), trip.getId(), precheckKey,
            buildPrecheckContent(trip));
      }
      log.info("Trip precheck summary sent: trip={}", trip.getId());
    }
  }

  private String buildReminderContent(String tripTitle, Booking booking) {
    int minutes = booking.getReminderMinutes() == null ? 0 : booking.getReminderMinutes();
    String when = minutes % 60 == 0 && minutes >= 60 ? (minutes / 60) + " 小时" : minutes + " 分钟";
    String action = switch (booking.getType() == null ? "other" : booking.getType()) {
      case "flight" -> "后起飞，记得值机";
      case "train" -> "后发车，请提前取票进站";
      case "hotel" -> "后入住，记得带好确认单";
      case "ticket" -> "后开始使用，别错过入场时间";
      default -> "后开始，请留意行程安排";
    };
    return "「" + tripTitle + "」：" + booking.getTitle() + " " + when + action
        + (booking.getConfirmCode() == null ? "" : "（确认码 " + booking.getConfirmCode() + "）");
  }

  private String buildPrecheckContent(Trip trip) {
    StringBuilder builder = new StringBuilder("「" + trip.getTitle() + "」明天出发：");
    boolean first = true;
    long upcoming = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getTripId, trip.getId())
        .ge(Booking::getStartTime, new Date())
        .le(Booking::getStartTime, addHours(new Date(), 48)));
    builder.append("未来 48 小时内有 ").append(upcoming).append(" 项预订");
    long pendingItems = countPendingPackingItems(trip.getId());
    if (pendingItems > 0) {
      builder.append("；行李清单还有 ").append(pendingItems).append(" 件未打包");
      first = false;
    } else if (!first) {
      builder.append("；行李清单已全部打包");
    }
    String weatherTip = weatherTip(trip.getId());
    if (!weatherTip.isEmpty()) {
      builder.append("；").append(weatherTip);
    }
    builder.append("。祝旅途顺利！");
    if (first) {
      builder.insert(builder.length() - 6, "。");
    }
    return builder.toString();
  }

  private long countPendingPackingItems(String tripId) {
    long pending = 0;
    List<PackingList> lists = packingListMapper.selectList(new LambdaQueryWrapper<PackingList>()
        .eq(PackingList::getTripId, tripId));
    for (PackingList list : lists) {
      pending += packingItemMapper.selectCount(new LambdaQueryWrapper<PackingItem>()
          .eq(PackingItem::getPackingListId, list.getId())
          .eq(PackingItem::getIsPacked, false));
    }
    return pending;
  }

  /** 天气异常提示：复用 trip_weather 已有数据，没有则返回空串省略。 */
  private String weatherTip(String tripId) {
    List<TripWeather> weather = tripWeatherMapper.selectList(
        new LambdaQueryWrapper<TripWeather>()
            .eq(TripWeather::getTripId, tripId)
            .orderByDesc(TripWeather::getFetchedAt)
            .last("LIMIT 5"));
    for (TripWeather row : weather) {
      Integer rain = row.getRainChance();
      if (rain != null && rain >= 70) {
        return "天气提醒：" + Fmt.dateKey(row.getDate()) + "降水概率 " + rain + "%，记得带伞";
      }
      if (row.getTempHigh() != null && row.getTempHigh().doubleValue() >= 35) {
        return "天气提醒：" + Fmt.dateKey(row.getDate()) + "最高温 " + row.getTempHigh().intValue()
            + "°C，注意防暑";
      }
      if (row.getTempLow() != null && row.getTempLow().doubleValue() <= 0) {
        return "天气提醒：" + Fmt.dateKey(row.getDate()) + "最低温 " + row.getTempLow().intValue()
            + "°C，注意保暖";
      }
    }
    return "";
  }

  private List<TripMember> membersOf(String tripId) {
    return tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId));
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

  private Date addHours(Date date, int hours) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(date);
    calendar.add(Calendar.HOUR_OF_DAY, hours);
    return calendar.getTime();
  }
}
