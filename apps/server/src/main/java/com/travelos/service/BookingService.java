package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travelos.common.Payloads;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Booking;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripPlace;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

  /** 凭证附件上限（前端提示为「图片 / PDF，≤ 5MB」） */
  private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

  private final BookingMapper bookingMapper;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final TripMemberService tripMemberService;
  private final AuditService auditService;
  private final TripExpenseService tripExpenseService;
  private final UserPreferenceService preferences;
  private final com.travelos.service.FileStorageService fileStorageService;

  public BookingService(BookingMapper bookingMapper, TripDayMapper tripDayMapper,
      TripPlaceMapper tripPlaceMapper, TripMemberService tripMemberService,
      AuditService auditService, TripExpenseService tripExpenseService,
      com.travelos.service.FileStorageService fileStorageService, UserPreferenceService preferences) {
    this.bookingMapper = bookingMapper;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.tripMemberService = tripMemberService;
    this.auditService = auditService;
    this.tripExpenseService = tripExpenseService;
    this.fileStorageService = fileStorageService;
    this.preferences = preferences;
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = fillFromDto(new Booking(), dto, userId);
    String type = booking.getType();
    if (type == null || !TYPES.contains(type)) {
      throw ApiException.badRequest("预订类型必须是 flight/hotel/train/ticket/other");
    }
    if (booking.getTitle() == null || booking.getTitle().isBlank()) {
      throw ApiException.badRequest("预订标题不能为空");
    }
    applyAssociations(booking, tripId, dto);
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
    tripExpenseService.lockLedger(tripId);
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = requireBooking(tripId, bookingId);
    // 数据隔离：仅创建者本人或行程 owner/admin 可修改（NULL 属主为存量兼容）
    tripMemberService.requireOwnerOrManager(tripId, booking.getCreatorId(), userId, "预订创建者");
    fillFromDto(booking, dto, userId);
    applyAssociations(booking, tripId, dto);
    applyDefaultReminder(booking, dto);
    booking.setUpdatedAt(new Date());
    bookingMapper.updateById(booking);
    if (dto.containsKey("amount") && booking.getAmount() == null) {
      bookingMapper.update(null, new LambdaUpdateWrapper<Booking>().eq(Booking::getId, bookingId)
          .set(Booking::getAmount, null));
    }
    if (dto.containsKey("startTime") || dto.containsKey("endTime")) {
      var dates = new LambdaUpdateWrapper<Booking>().eq(Booking::getId, bookingId);
      if (dto.containsKey("startTime")) {
        dates.set(Booking::getStartTime, booking.getStartTime()).set(Booking::getStartTimezone, booking.getStartTimezone());
      }
      if (dto.containsKey("endTime")) {
        dates.set(Booking::getEndTime, booking.getEndTime()).set(Booking::getEndTimezone, booking.getEndTimezone());
      }
      bookingMapper.update(null, dates);
    }
    return Map.of("booking", toResponse(booking, true));
  }

  private void applyAssociations(Booking booking, String tripId, Map<String, Object> dto) {
    if (dto.containsKey("tripDayId")) {
      String tripDayId = Payloads.text(dto.get("tripDayId"));
      if (tripDayId != null) {
        ensureDayBelongsToTrip(tripDayId, tripId);
      }
      booking.setTripDayId(tripDayId);
    }
    if (dto.containsKey("tripPlaceId")) {
      String tripPlaceId = Payloads.text(dto.get("tripPlaceId"));
      if (tripPlaceId != null) {
        ensurePlaceBelongsToTrip(tripPlaceId, tripId);
      }
      booking.setTripPlaceId(tripPlaceId);
    }
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String bookingId, String userId) {
    tripExpenseService.lockLedger(tripId);
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    Booking booking = requireBooking(tripId, bookingId);
    tripMemberService.requireOwnerOrManager(tripId, booking.getCreatorId(), userId, "预订创建者");
    if (tripExpenseService.hasBookingEntries(booking)) {
      throw ApiException.conflict("此预订有账目记录，请使用取消预订保留对账历史");
    }
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
  public Map<String, Object> convertToExpense(String tripId, String bookingId, String userId,
      Map<String, Object> input) {
    Booking booking = lockBookingLedger(tripId, bookingId, userId);
    String key = operationKey(input, false);
    TripExpense replay = tripExpenseService.bookingOperation(booking, key);
    if (replay != null) {
      if (replay.getAdjustmentKind() != null) {
        throw ApiException.conflict("操作编号已用于其他账目操作");
      }
      return bookingLedgerResponse(booking, replay);
    }
    TripExpense payment = tripExpenseService.bookingPayment(booking);
    if (payment != null) {
      return bookingLedgerResponse(booking, payment);
    }
    if (booking.getCancelledAt() != null) {
      throw ApiException.conflict("预订已取消，无法重新入账");
    }
    List<String> memberIds = tripMemberService.listMembers(tripId).stream()
        .map(com.travelos.entity.TripMember::getUserId)
        .toList();
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("title", booking.getTitle());
    dto.put("amount", input.getOrDefault("amount", booking.getAmount()));
    dto.put("currency", TripExpenseService.normalizeCurrency(
        Payloads.text(input.getOrDefault("currency", booking.getCurrency()))));
    dto.put("payerUserId", input.get("payerUserId"));
    dto.put("tripDayId", booking.getTripDayId());
    dto.put("category", TYPE_TO_EXPENSE_CATEGORY.getOrDefault(
        booking.getType() == null ? "other" : booking.getType(), "other"));
    if (booking.getStartTime() != null) {
      dto.put("spentAt", booking.getStartTime().toInstant().toString());
    }
    String memo = "由预订「" + booking.getTitle() + "」一键核销";
    if (booking.getOrderNo() != null && !booking.getOrderNo().isBlank()) {
      memo += "（订单号 " + booking.getOrderNo() + "）";
    }
    dto.put("notes", memo);
    dto.put("shareUserIds", input.getOrDefault("shareUserIds", memberIds));
    if (!(dto.get("shareUserIds") instanceof List<?> picked) || picked.isEmpty()) {
      throw ApiException.badRequest("请至少选择一位分摊成员");
    }
    payment = tripExpenseService.createBookingPayment(tripId, userId, dto, bookingId, key);
    booking.setExpenseId(payment.getId());
    booking.setUpdatedAt(new Date());
    bookingMapper.updateById(booking);
    auditService.record(userId, tripId, "booking_converted", "booking", bookingId,
        Map.of("title", booking.getTitle(),
            "amount", Fmt.money(payment.getAmount()),
            "currency", TripExpenseService.normalizeCurrency(payment.getCurrency()),
            "expenseId", booking.getExpenseId()));
    return bookingLedgerResponse(booking, payment);
  }

  @Transactional
  public Map<String, Object> cancel(String tripId, String bookingId, String userId) {
    Booking booking = lockBookingLedger(tripId, bookingId, userId);
    if (booking.getCancelledAt() == null) {
      booking.setCancelledAt(new Date());
      booking.setReminderEnabled(false);
      booking.setUpdatedAt(new Date());
      bookingMapper.updateById(booking);
      auditService.record(userId, tripId, "booking_cancelled", "booking", bookingId,
          Map.of("title", booking.getTitle()));
    }
    return Map.of("booking", toResponse(booking, true));
  }

  @Transactional
  public Map<String, Object> credit(String tripId, String bookingId, String userId,
      Map<String, Object> input, boolean reverse) {
    Booking booking = lockBookingLedger(tripId, bookingId, userId);
    String kind = reverse ? "reversal" : "refund";
    String key = operationKey(input, true);
    long amount = reverse ? 0 : TripExpenseService.toCents(Payloads.text(input.get("amount")), false);
    TripExpense replay = tripExpenseService.bookingOperation(booking, key);
    if (replay != null) {
      if (!kind.equals(replay.getAdjustmentKind())
          || (!reverse && -TripExpenseService.toCentsQuiet(replay.getAmount()) != amount)) {
        throw ApiException.conflict("操作编号已用于其他账目操作");
      }
      return bookingLedgerResponse(booking, replay);
    }
    TripExpense payment = tripExpenseService.bookingPayment(booking);
    if (payment == null) {
      throw ApiException.conflict("预订没有可调整的入账记录");
    }
    if (reverse && tripExpenseService.refundedCents(payment) > 0) {
      throw ApiException.conflict("此预订已有实际退款，请按实收继续记录退款");
    }
    Date date = input.get("spentAt") == null ? new Date() : DateUtil.parse(input.get("spentAt").toString());
    TripExpense credit = tripExpenseService.recordBookingCredit(booking, payment, kind, key, amount, date);
    if (reverse) {
      booking.setExpenseId(null);
      bookingMapper.update(null, new LambdaUpdateWrapper<Booking>().eq(Booking::getId, booking.getId())
          .set(Booking::getExpenseId, null).set(Booking::getUpdatedAt, new Date()));
    }
    auditService.record(userId, tripId, "booking_" + kind, "booking", bookingId,
        Map.of("title", booking.getTitle(), "expenseId", credit.getId(),
            "amount", Fmt.money(credit.getAmount()),
            "currency", TripExpenseService.normalizeCurrency(credit.getCurrency())));
    return bookingLedgerResponse(booking, credit);
  }

  private Booking lockBookingLedger(String tripId, String bookingId, String userId) {
    tripMemberService.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    tripExpenseService.lockLedger(tripId);
    Booking booking = requireBooking(tripId, bookingId);
    tripMemberService.requireOwnerOrManager(tripId, booking.getCreatorId(), userId, "预订创建者");
    return booking;
  }

  private String operationKey(Map<String, Object> input, boolean required) {
    String key = Payloads.trimmedOrNull(input.get("operationKey"));
    if ((key == null && required) || (key != null && !key.matches("[a-zA-Z0-9_-]{1,128}"))) {
      throw ApiException.badRequest("请提供有效的操作编号");
    }
    return key;
  }

  private Map<String, Object> bookingLedgerResponse(Booking booking, TripExpense entry) {
    return Map.of("booking", toResponse(booking, true), "expense", tripExpenseService.toExpenseResponse(entry));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> findAll(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> bookings = findBookingsByTrip(tripId).stream()
        .map(booking -> toResponse(booking, false))
        .toList();
    return Map.of("bookings", bookings, "timeZone", preferences.timeZone(userId).getId());
  }

  /** 凭证速查聚合（设计文档 3.3.2）：按时间正序返回确认码/订单号/附件摘要。 */
  public Map<String, Object> vouchers(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> vouchers = findBookingsByTrip(tripId).stream()
        .filter(booking -> booking.getCancelledAt() == null)
        .map(booking -> {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", booking.getId());
          map.put("type", booking.getType());
          map.put("title", booking.getTitle());
          map.put("startTime", booking.getStartTime());
          map.put("endTime", booking.getEndTime());
          map.put("startTimezone", booking.getStartTimezone());
          map.put("endTimezone", booking.getEndTimezone());
          map.put("orderNo", booking.getOrderNo());
          map.put("confirmCode", booking.getConfirmCode());
          map.put("attachmentUrl", booking.getAttachmentUrl());
          map.put("extra", parseExtra(booking.getExtra()));
          return map;
        })
        .toList();
    return Map.of("vouchers", vouchers, "timeZone", preferences.timeZone(userId).getId());
  }

  /** 提醒列表：各预订的提醒时间与开关状态。 */
  public Map<String, Object> reminders(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<Map<String, Object>> reminders = findBookingsByTrip(tripId).stream()
        .filter(booking -> booking.getCancelledAt() == null)
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
    return Map.of("reminders", reminders, "timeZone", preferences.timeZone(userId).getId());
  }

  /** 行程天角标摘要：关联到某天的预订（供时间轴显示订单角标）。 */
  public List<Map<String, Object>> daySummaries(String tripId, String tripDayId) {
    return findBookingsByTrip(tripId).stream()
        .filter(booking -> booking.getCancelledAt() == null && tripDayId.equals(booking.getTripDayId()))
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
  private Booking fillFromDto(Booking booking, Map<String, Object> dto, String userId) {
    if (dto.containsKey("type") && dto.get("type") != null) {
      booking.setType(dto.get("type").toString());
    }
    if (dto.containsKey("title")) {
      String title = Payloads.text(dto.get("title"));
      booking.setTitle(title == null ? null : title.trim());
    }
    if (dto.containsKey("startTime")) {
      BookingTime time = bookingTime(dto.get("startTime"), dto.get("startTimezone"));
      booking.setStartTime(time.instant());
      booking.setStartTimezone(time.zone());
    }
    if (dto.containsKey("endTime")) {
      BookingTime time = bookingTime(dto.get("endTime"), dto.get("endTimezone"));
      booking.setEndTime(time.instant());
      booking.setEndTimezone(time.zone());
    }
    if (booking.getStartTime() != null && booking.getEndTime() != null
        && booking.getEndTime().before(booking.getStartTime())) {
      throw ApiException.badRequest("结束时间不能早于开始时间");
    }
    if (dto.containsKey("orderNo")) {
      booking.setOrderNo(Payloads.trimmedOrNull(dto.get("orderNo")));
    }
    if (dto.containsKey("confirmCode")) {
      booking.setConfirmCode(Payloads.trimmedOrNull(dto.get("confirmCode")));
    }
    if (dto.containsKey("amount")) {
      booking.setAmount(toDecimal(dto.get("amount")));
    }
    if (dto.containsKey("currency") && Payloads.text(dto.get("currency")) != null) {
      booking.setCurrency(dto.get("currency").toString().trim());
    }
    if (dto.containsKey("attachmentUrl")) {
      booking.setAttachmentUrl(
          validateAttachment(dto.get("attachmentUrl"), booking.getAttachmentUrl(), userId));
    }
    if (dto.containsKey("extra")) {
      Object extra = dto.get("extra");
      booking.setExtra(extra == null ? null : com.travelos.common.JsonUtil.write(extra));
    }
    return booking;
  }

  /**
   * 凭证附件校验：data URL（图片/PDF ≤5MB，落盘转受管地址）或 http(s) 外链。
   * 写路径加固：受管地址只允许「原值保留」——禁止引用他人 /files/** 文件。
   *
   * @param current 该预订当前的 attachmentUrl（新建时为 null）
   * @param userId  文件创建者（file_metadata 归属）
   */
  private String validateAttachment(Object raw, String current, String userId) {
    String url = Payloads.trimmedOrNull(raw);
    if (url == null) {
      return null;
    }
    if (fileStorageService.isManagedFileUrl(url)) {
      if (url.equals(current)) {
        return url; // 编辑时保留原值
      }
      throw ApiException.badRequest("凭证附件仅支持上传文件或外链地址");
    }
    if (fileStorageService.isDataUrl(url)) {
      String[] parts = com.travelos.common.PhotoUtil.parseDocumentDataUrl(url);
      if (parts == null) {
        throw ApiException.badRequest("凭证附件仅支持图片或 PDF 文件");
      }
      if (com.travelos.common.PhotoUtil.base64Bytes(parts[1]) > MAX_ATTACHMENT_BYTES) {
        throw ApiException.badRequest("凭证附件不能超过 5MB");
      }
      return fileStorageService.storeDataUrl(url, userId);
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw ApiException.badRequest("凭证附件地址格式不正确");
    }
    return url;
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
    map.put("startTimezone", booking.getStartTimezone());
    map.put("endTimezone", booking.getEndTimezone());
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
    map.put("cancelledAt", booking.getCancelledAt());
    map.put("hasLedgerEntries", tripExpenseService.hasBookingEntries(booking));
    TripExpense payment = tripExpenseService.bookingPayment(booking);
    long paid = payment == null ? 0 : TripExpenseService.toCentsQuiet(payment.getAmount());
    long refunded = payment == null ? 0 : tripExpenseService.refundedCents(payment);
    String status = payment == null ? "pending" : refunded == 0 ? "paid"
        : refunded == paid ? "refunded" : "partial_refund";
    map.put("paymentStatus", status);
    map.put("paidAmount", TripExpenseService.centsToString(paid));
    map.put("paidCurrency", payment == null ? null : payment.getCurrency());
    map.put("refundedAmount", TripExpenseService.centsToString(refunded));
    map.put("refundableAmount", TripExpenseService.centsToString(paid - refunded));
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

  private BigDecimal toDecimal(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    return BigDecimal.valueOf(TripExpenseService.toCents(text, true), 2);
  }

  private record BookingTime(Date instant, String zone) {}

  private BookingTime bookingTime(Object value, Object zoneValue) {
    String text = Payloads.trimmedOrNull(value);
    if (text == null) return new BookingTime(null, null);
    String zoneText = Payloads.trimmedOrNull(zoneValue);
    try {
      ZoneId zone = zoneText == null ? null : ZoneId.of(zoneText);
      if (text.matches(".*(?:Z|[+-]\\d{2}:?\\d{2})$")) {
        String iso = text.replace(' ', 'T').replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
        OffsetDateTime timestamp = OffsetDateTime.parse(iso);
        return new BookingTime(Date.from(timestamp.toInstant()),
            zone == null ? timestamp.getOffset().getId() : zone.getId());
      }
      if (text.matches("\\d{4}-\\d{2}-\\d{2}") && zone == null) {
        return new BookingTime(DateUtil.parse(text), null);
      }
      zone = zone == null ? UserPreferenceService.DEFAULT_ZONE : zone;
      LocalDateTime date = LocalDateTime.parse(text.length() == 10 ? text + "T00:00" : text.replace(' ', 'T'));
      var offsets = zone.getRules().getValidOffsets(date);
      if (offsets.size() != 1) {
        throw ApiException.badRequest("该当地时刻受夏令时切换影响，请填写明确的 UTC 偏移或选择其它时刻");
      }
      return new BookingTime(Date.from(date.toInstant(offsets.get(0))), zone.getId());
    } catch (DateTimeException ex) {
      throw ApiException.badRequest("预订日期或时区格式不正确");
    }
  }
}
