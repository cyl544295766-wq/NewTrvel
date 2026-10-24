package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Booking;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 预订管理（设计文档 3.3.1 / 3.3.2）。 */
@Service
public class BookingService {

  private static final List<String> TYPES = List.of("flight", "hotel", "train", "ticket", "other");
  /** 各类型默认提醒提前量（分钟），锚点为 start_time；hotel 传了免费取消截止日时以截止日为准，此处为回退值；other 由用户自定义（默认关闭） */
  private static final Map<String, Integer> DEFAULT_REMINDER_MINUTES = Map.of(
      "flight", 24 * 60, "hotel", 24 * 60, "train", 2 * 60, "ticket", 24 * 60, "other", 60);

  /** P1-1 一键核销：预订类型 → 费用类别（与 TripExpenseService 的口径一致） */
  private static final Map<String, String> TYPE_TO_EXPENSE_CATEGORY = Map.of(
      "flight", "transport", "train", "transport", "hotel", "hotel",
      "ticket", "ticket", "other", "other");

  private final BookingMapper bookingMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;
  private final AuditService auditService;
  private final TripExpenseService tripExpenseService;

  public BookingService(BookingMapper bookingMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberService tripMemberService,
      AuditService auditService, TripExpenseService tripExpenseService) {
    this.bookingMapper = bookingMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
    this.auditService = auditService;
    this.tripExpenseService = tripExpenseService;
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = fillFromDto(new Booking(), dto);
    String type = booking.getType();
    if (type == null || !TYPES.contains(type)) {
      throw ApiException.badRequest("预订类型必须是 flight/hotel/train/ticket/other");
    }
    if (booking.getTitle() == null || booking.getTitle().isBlank()) {
      throw ApiException.badRequest("预订标题不能为空");
    }
    if (booking.getTripDayId() != null) {
      ensureDayBelongsToTrip(booking.getTripDayId(), tripId);
    }
    if (booking.getTripPlaceId() != null) {
      ensurePlaceBelongsToTrip(booking.getTripPlaceId(), tripId);
    }
    booking.setTripId(tripId);
    // 属主以当前登录用户为准，禁止信任前端传入的 creatorId 等字段
    booking.setCreatorId(userId);
    applyDefaultReminder(booking, dto);
    booking.setCreatedAt(new Date());
    booking.setUpdatedAt(new Date());
    bookingMapper.insert(booking);
    return Map.of("booking", toResponse(booking, true));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String bookingId, Map<String, Object> dto,
      String userId) {
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = requireBooking(tripId, bookingId);
    // 数据隔离：仅创建者本人或行程 owner/admin 可修改（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, booking.getCreatorId(), userId, "预订创建者");
    fillFromDto(booking, dto);
    if (dto.containsKey("tripDayId")) {
      String tripDayId = optionalString(dto.get("tripDayId"));
      if (tripDayId != null) {
        ensureDayBelongsToTrip(tripDayId, tripId);
      }
      booking.setTripDayId(tripDayId);
    }
    if (dto.containsKey("tripPlaceId")) {
      String tripPlaceId = optionalString(dto.get("tripPlaceId"));
      if (tripPlaceId != null) {
        ensurePlaceBelongsToTrip(tripPlaceId, tripId);
      }
      booking.setTripPlaceId(tripPlaceId);
    }
    applyDefaultReminder(booking, dto);
    booking.setUpdatedAt(new Date());
    bookingMapper.updateById(booking);
    return Map.of("booking", toResponse(booking, true));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String bookingId, String userId) {
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = requireBooking(tripId, bookingId);
    tripMemberService.requireOwnerOrManager(tripId, booking.getCreatorId(), userId, "预订创建者");
    bookingMapper.deleteById(bookingId);
    auditService.record(userId, tripId, "booking_deleted", "booking", bookingId,
        Map.of("title", booking.getTitle(), "type", booking.getType()));
    return Map.of("success", true);
  }

  /**
   * P1-1 一键核销：把预订金额转为一笔费用（消除预订/费用双录）。
   * 规则：金额 = bookings.amount；类别按类型映射（flight/train→transport、hotel→hotel、
   * ticket→ticket、other→other）；花费时间取预订开始时间（缺省当前）；分摊默认行程成员均分；
   * 成功后在 booking 上记 expense_id，已核销的预订退出预算"预估占用"。
   */
  @Transactional
  public Map<String, Object> convertToExpense(String tripId, String bookingId, String userId) {
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = requireBooking(tripId, bookingId);
    tripMemberService.requireOwnerOrManager(tripId, booking.getCreatorId(), userId, "预订创建者");
    if (booking.getExpenseId() != null && !booking.getExpenseId().isBlank()) {
      throw ApiException.conflict("该预订已转为费用，无需重复操作");
    }
    if (booking.getAmount() == null || booking.getAmount().signum() <= 0) {
      throw ApiException.badRequest("该预订未填写金额，请先补全金额再转为费用");
    }
    List<String> memberIds = tripMemberService.listMembers(tripId).stream()
        .map(com.travelos.entity.TripMember::getUserId)
        .toList();
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("title", booking.getTitle());
    dto.put("amount", booking.getAmount().toPlainString());
    dto.put("category", TYPE_TO_EXPENSE_CATEGORY.getOrDefault(
        booking.getType() == null ? "other" : booking.getType(), "other"));
    if (booking.getStartTime() != null) {
      dto.put("spentAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
          .format(booking.getStartTime()));
    }
    String memo = "由预订「" + booking.getTitle() + "」一键核销";
    if (booking.getOrderNo() != null && !booking.getOrderNo().isBlank()) {
      memo += "（订单号 " + booking.getOrderNo() + "）";
    }
    dto.put("notes", memo);
    dto.put("shareUserIds", memberIds);
    Map<String, Object> created = tripExpenseService.create(tripId, userId, dto);
    @SuppressWarnings("unchecked")
    Map<String, Object> expense = (Map<String, Object>) created.get("expense");
    booking.setExpenseId(String.valueOf(expense.get("id")));
    booking.setUpdatedAt(new Date());
    bookingMapper.updateById(booking);
    auditService.record(userId, tripId, "booking_converted", "booking", bookingId,
        Map.of("title", booking.getTitle(),
            "amount", Fmt.money(booking.getAmount()),
            "expenseId", booking.getExpenseId()));
    return Map.of("booking", toResponse(booking, true), "expense", expense);
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> bookings = findBookingsByTrip(tripId).stream()
        .map(booking -> toResponse(booking, false))
        .toList();
    return Map.of("bookings", bookings);
  }

  /** 凭证速查聚合（设计文档 3.3.2）：按时间正序返回确认码/订单号/附件摘要。 */
  public Map<String, Object> vouchers(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> vouchers = findBookingsByTrip(tripId).stream()
        .map(booking -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", booking.getId());
          map.put("type", booking.getType());
          map.put("title", booking.getTitle());
          map.put("startTime", booking.getStartTime());
          map.put("endTime", booking.getEndTime());
          map.put("orderNo", booking.getOrderNo());
          map.put("confirmCode", booking.getConfirmCode());
          map.put("attachmentUrl", booking.getAttachmentUrl());
          map.put("extra", parseExtra(booking.getExtra()));
          return map;
        })
        .toList();
    return Map.of("vouchers", vouchers);
  }

  /** 提醒列表：各预订的提醒时间与开关状态。 */
  public Map<String, Object> reminders(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> reminders = findBookingsByTrip(tripId).stream()
        .map(booking -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", booking.getId());
          map.put("title", booking.getTitle());
          map.put("type", booking.getType());
          map.put("startTime", booking.getStartTime());
          map.put("reminderMinutes", booking.getReminderMinutes());
          map.put("reminderEnabled", booking.getReminderEnabled());
          map.put("reminderSentAt", booking.getReminderSentAt());
          return map;
        })
        .toList();
    return Map.of("reminders", reminders);
  }

  /** 行程天角标摘要：关联到某天的预订（供时间轴显示订单角标）。 */
  public List<Map<String, Object>> daySummaries(String tripId, String tripDayId) {
    return findBookingsByTrip(tripId).stream()
        .filter(booking -> tripDayId.equals(booking.getTripDayId()))
        .map(booking -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", booking.getId());
          map.put("type", booking.getType());
          map.put("title", booking.getTitle());
          map.put("startTime", booking.getStartTime());
          map.put("confirmCode", booking.getConfirmCode());
          map.put("orderNo", booking.getOrderNo());
          return map;
        })
        .toList();
  }

  public List<Booking> findBookingsByTrip(String tripId) {
    return bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getTripId, tripId)
        .orderByAsc(Booking::getStartTime)
        .orderByAsc(Booking::getCreatedAt));
  }

  private void ensureDayBelongsToTrip(String dayId, String tripId) {
    TripDay day = tripDayMapper.selectById(dayId);
    if (day == null || !day.getTripId().equals(tripId)) {
      throw ApiException.badRequest("行程日期不属于当前旅行");
    }
  }

  private void ensurePlaceBelongsToTrip(String placeId, String tripId) {
    TripPlace place = tripPlaceMapper.selectById(placeId);
    if (place == null || !place.getTripId().equals(tripId)) {
      throw ApiException.badRequest("行程地点不属于当前旅行");
    }
  }

  private Booking requireBooking(String tripId, String bookingId) {
    Booking booking = bookingMapper.selectById(bookingId);
    if (booking == null || !booking.getTripId().equals(tripId)) {
      throw ApiException.notFound("预订不存在");
    }
    return booking;
  }

  /** 从 DTO 提取通用字段；extra 为类型特有字段包（前端按类型动态组装）。 */
  private Booking fillFromDto(Booking booking, Map<String, Object> dto) {
    if (dto.containsKey("type") && dto.get("type") != null) {
      booking.setType(dto.get("type").toString());
    }
    if (dto.containsKey("title")) {
      String title = optionalString(dto.get("title"));
      booking.setTitle(title == null ? null : title.trim());
    }
    if (dto.containsKey("startTime")) {
      booking.setStartTime(toDateOrNull(dto.get("startTime")));
    }
    if (dto.containsKey("endTime")) {
      booking.setEndTime(toDateOrNull(dto.get("endTime")));
    }
    if (dto.containsKey("orderNo")) {
      booking.setOrderNo(toNullable(dto.get("orderNo")));
    }
    if (dto.containsKey("confirmCode")) {
      booking.setConfirmCode(toNullable(dto.get("confirmCode")));
    }
    if (dto.containsKey("amount")) {
      booking.setAmount(toDecimal(dto.get("amount")));
    }
    if (dto.containsKey("currency") && optionalString(dto.get("currency")) != null) {
      booking.setCurrency(dto.get("currency").toString().trim());
    }
    if (dto.containsKey("attachmentUrl")) {
      booking.setAttachmentUrl(toNullable(dto.get("attachmentUrl")));
    }
    if (dto.containsKey("extra")) {
      Object extra = dto.get("extra");
      booking.setExtra(extra == null ? null : com.travelos.common.JsonUtil.write(extra));
    }
    return booking;
  }

  /**
   * 默认提醒规则：创建时按类型生成（flight 24h / train 2h / ticket 24h / other 关闭）；
   * hotel 若在 extra.freeCancelDeadline 传了免费取消截止日，则以截止日为提醒时刻
   * （换算为相对 start_time 的提前量），否则回退 24h；dto 显式传
   * reminderMinutes/reminderEnabled 时优先。
   */
  private void applyDefaultReminder(Booking booking, Map<String, Object> dto) {
    if (dto.containsKey("reminderMinutes") && dto.get("reminderMinutes") != null) {
      int minutes = Integer.parseInt(dto.get("reminderMinutes").toString());
      if (minutes < 0) {
        throw ApiException.badRequest("提醒提前量不能小于 0");
      }
      booking.setReminderMinutes(minutes);
    } else if (booking.getReminderMinutes() == null) {
      booking.setReminderMinutes(defaultReminderMinutes(booking));
    }
    if (dto.containsKey("reminderEnabled") && dto.get("reminderEnabled") != null) {
      booking.setReminderEnabled(Boolean.parseBoolean(dto.get("reminderEnabled").toString()));
    } else if (booking.getReminderEnabled() == null) {
      booking.setReminderEnabled(!"other".equals(booking.getType()));
    }
  }

  /**
   * 类型默认提前量（锚点 start_time）。hotel 提供了 extra.freeCancelDeadline 时，
   * 提醒时刻对齐免费取消截止日（提前量 = start_time - 截止日，下限 0）；截止日缺失
   * 或无法解析时回退到类型默认值。
   */
  private int defaultReminderMinutes(Booking booking) {
    String type = booking.getType() == null ? "other" : booking.getType();
    if ("hotel".equals(type) && booking.getStartTime() != null) {
      Date deadline = freeCancelDeadlineOf(booking);
      if (deadline != null) {
        long minutes = (booking.getStartTime().getTime() - deadline.getTime()) / 60000L;
        return (int) Math.max(0L, minutes);
      }
    }
    return DEFAULT_REMINDER_MINUTES.getOrDefault(type, 60);
  }

  /** 从 extra(json) 里取免费取消截止日；缺失或格式非法返回 null。 */
  private Date freeCancelDeadlineOf(Booking booking) {
    if (booking.getExtra() == null || booking.getExtra().isBlank()) {
      return null;
    }
    try {
      Object raw = com.travelos.common.JsonUtil.readObject(booking.getExtra())
          .get("freeCancelDeadline");
      return raw == null ? null : DateUtil.parse(raw.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private Map<String, Object> toResponse(Booking booking, boolean includeExtra) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", booking.getId());
    map.put("tripId", booking.getTripId());
    map.put("tripDayId", booking.getTripDayId());
    map.put("tripPlaceId", booking.getTripPlaceId());
    map.put("type", booking.getType());
    map.put("title", booking.getTitle());
    map.put("startTime", booking.getStartTime());
    map.put("endTime", booking.getEndTime());
    map.put("orderNo", booking.getOrderNo());
    map.put("confirmCode", booking.getConfirmCode());
    map.put("amount", Fmt.money(booking.getAmount()));
    map.put("currency", booking.getCurrency());
    map.put("attachmentUrl", booking.getAttachmentUrl());
    map.put("reminderMinutes", booking.getReminderMinutes());
    map.put("reminderEnabled", booking.getReminderEnabled());
    map.put("reminderSentAt", booking.getReminderSentAt());
    map.put("creatorId", booking.getCreatorId());
    map.put("expenseId", booking.getExpenseId());
    map.put("createdAt", booking.getCreatedAt());
    map.put("updatedAt", booking.getUpdatedAt());
    map.put("extra", parseExtra(booking.getExtra()));
    return map;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseExtra(String extra) {
    if (extra == null || extra.isBlank()) {
      return Map.of();
    }
    try {
      return com.travelos.common.JsonUtil.readObject(extra);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private String optionalString(Object value) {
    return value == null ? null : value.toString();
  }

  private String toNullable(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private BigDecimal toDecimal(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException ex) {
      throw ApiException.badRequest("金额格式不正确");
    }
  }

  private Date toDateOrNull(Object value) {
    return value == null ? null : DateUtil.parse(value.toString());
  }
}
