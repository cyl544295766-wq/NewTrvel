package com.travelos.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.common.Payloads;
import com.travelos.entity.AiConversation;
import com.travelos.entity.AiMessage;
import com.travelos.entity.Booking;
import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.mapper.AiConversationMapper;
import com.travelos.mapper.AiMessageMapper;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.service.AiExpenseDraftService;
import com.travelos.service.FileAccessService;
import com.travelos.service.TripMemberService;
import com.travelos.service.UserPreferenceService;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Service
public class AiReceiptService {
  static final String REVIEW = "receipt_review";
  static final String FINGERPRINT = "receiptFingerprint";
  private static final String DUPLICATE = "疑似重复记录。请先核对已有预订或账目，再确认是否继续";
  private static final Set<String> EXPENSE_CATEGORIES = Set.of("transport", "hotel", "food", "ticket", "shopping", "other");

  private final AiConversationMapper conversations;
  private final AiMessageMapper messages;
  private final TripMapper trips;
  private final TripMemberService members;
  private final UserPreferenceService preferences;
  private final FileAccessService files;
  private final AiExpenseDraftService expenseDrafts;
  private final TripExpenseMapper expenses;
  private final BookingMapper bookings;
  private final ReceiptVisionClient vision;
  private final AiTaskManager tasks;
  private final TransactionTemplate transaction;

  public AiReceiptService(AiConversationMapper conversations, AiMessageMapper messages, TripMapper trips,
      TripMemberService members, UserPreferenceService preferences, FileAccessService files,
      AiExpenseDraftService expenseDrafts, TripExpenseMapper expenses, BookingMapper bookings,
      ReceiptVisionClient vision, AiTaskManager tasks, PlatformTransactionManager transactionManager) {
    this.conversations = conversations;
    this.messages = messages;
    this.trips = trips;
    this.members = members;
    this.preferences = preferences;
    this.files = files;
    this.expenseDrafts = expenseDrafts;
    this.expenses = expenses;
    this.bookings = bookings;
    this.vision = vision;
    this.tasks = tasks;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public Map<String, Object> recognize(String conversationId, UserContext user, Map<String, Object> input) {
    String tripId = requireContext(conversationId, user.id(), input, false).getTripId();
    String data = Payloads.trimmedOrNull(input.get("image"));
    String url = Payloads.trimmedOrNull(input.get("fileUrl"));
    if ((data == null) == (url == null)) {
      throw ApiException.badRequest("请选择一张票据图片");
    }
    ReceiptImage image;
    if (data != null) {
      image = ReceiptImage.fromUpload(data);
    } else {
      var file = files.authorizeReceiptImage(url, tripId, user);
      image = ReceiptImage.fromManaged(file.path(), file.filename());
    }
    String fingerprint = image.fingerprint();
    AiMessage previous = findReview(conversationId, user.id(), fingerprint);
    if (previous != null) {
      return reference(previous);
    }
    var handle = tasks.submit(user.id(), conversationId, "receipt-" + fingerprint, ctx -> {
      ctx.checkActive();
      requireContext(conversationId, user.id(), input, false);
      Map<String, Object> review = new LinkedHashMap<>(ReceiptFields.review(
          vision.extract(image.forModel()).orElse(null)));
      ctx.checkActive();
      return transaction.execute(status -> {
        AiConversation conversation = requireContext(conversationId, user.id(), input, true);
        ctx.checkActive();
        AiMessage existing = findReview(conversationId, user.id(), fingerprint);
        if (existing != null) {
          return reference(existing);
        }
        Trip trip = trips.selectById(tripId);
        if (trip == null || trip.getDeletedAt() != null) {
          throw ApiException.notFound("行程不存在");
        }
        review.put("tripTitle", trip.getTitle());
        review.put("timezone", preferences.timeZone(user.id()).getId());
        review.put("members", expenseDrafts.members(tripId, user.id()));
        Map<String, Object> draft = Map.of("type", REVIEW, "tripId", tripId, "receiptReview", review,
            FINGERPRINT, fingerprint, "summary", "核对票据内容");
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(user.id());
        message.setRole("assistant");
        message.setContent("核对票据内容");
        message.setDraftStatus("pending");
        message.setToolCalls(JsonUtil.write(draft));
        messages.insert(message);
        conversation.setUpdatedAt(new Date());
        conversations.updateById(conversation);
        return reference(message);
      });
    });
    try {
      var result = handle.await(Math.max(5_000, handle.context().deadlineMillis() - System.currentTimeMillis() + 2_000));
      if (result != null) {
        return result;
      }
      throw ApiException.serviceUnavailable("识别请求已停止，请重试");
    } catch (InterruptedException ex) {
      handle.cancel("receipt-interrupted");
      Thread.currentThread().interrupt();
      throw ApiException.serviceUnavailable("识别请求已中断，请重试");
    } catch (TimeoutException | CancellationException ex) {
      handle.cancel("receipt-timeout");
      throw ApiException.serviceUnavailable("识别请求超时，请重试");
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof ApiException api) {
        throw api;
      }
      throw ApiException.serviceUnavailable("识别暂不可用，请稍后重试");
    }
  }

  @Transactional
  public Map<String, Object> confirm(String conversationId, String messageId, String userId, Map<String, Object> input) {
    String tripId = requireContext(conversationId, userId, input, false).getTripId();
    AiMessage message = messages.selectById(messageId);
    if (message == null || !conversationId.equals(message.getConversationId()) || !userId.equals(message.getUserId())
        || message.getToolCalls() == null) {
      throw ApiException.notFound("票据草稿不存在");
    }
    Map<String, Object> old = JsonUtil.readObject(message.getToolCalls());
    if (!tripId.equals(old.get("tripId")) || !old.containsKey(FINGERPRINT)) {
      throw ApiException.notFound("票据草稿不存在");
    }
    if ("discarded".equals(message.getDraftStatus())) {
      throw ApiException.conflict("票据草稿已放弃");
    }
    if (!REVIEW.equals(old.get("type"))) {
      return reference(message);
    }
    String kind = ReceiptFields.required(input, "kind", "票据类别");
    String title = ReceiptFields.required(input, "title", "标题");
    ZoneId zone = preferences.timeZone(userId);
    Map<String, Object> draft = "expense".equals(kind) ? expenseDraft(tripId, userId, input, title, zone)
        : bookingDraft(tripId, input, title, kind, zone);
    draft.put("tripId", tripId);
    draft.put(FINGERPRINT, old.get(FINGERPRINT));
    String summary = String.valueOf(draft.get("summary"));
    if (messages.confirmReceipt(messageId, conversationId, userId, JsonUtil.write(draft), summary) == 0) {
      AiMessage current = messages.selectById(messageId);
      if (current == null || "discarded".equals(current.getDraftStatus())) {
        throw ApiException.conflict("票据草稿已放弃或不存在");
      }
      return reference(current);
    }
    return reference(message);
  }

  private Map<String, Object> expenseDraft(String tripId, String userId, Map<String, Object> input,
      String title, ZoneId zone) {
    String date = ReceiptFields.required(input, "date", "消费日期");
    String amount = ReceiptFields.required(input, "amount", "实付金额");
    String currency = ReceiptFields.required(input, "currency", "原币");
    LocalDate day = LocalDate.parse(date);
    if (!Boolean.TRUE.equals(input.get("acknowledgeDuplicate")) && expenses.exists(new LambdaQueryWrapper<TripExpense>()
        .eq(TripExpense::getTripId, tripId).eq(TripExpense::getTitle, title)
        .eq(TripExpense::getAmount, new BigDecimal(amount)).eq(TripExpense::getCurrency, currency)
        .ge(TripExpense::getSpentAt, Date.from(day.atStartOfDay(zone).toInstant()))
        .lt(TripExpense::getSpentAt, Date.from(day.plusDays(1).atStartOfDay(zone).toInstant())))) {
      throw ApiException.conflict(DUPLICATE);
    }
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("title", title);
    args.put("amount", amount);
    args.put("currency", currency);
    if (!Boolean.TRUE.equals(input.get("useDefaultSplit"))) {
      if (Payloads.trimmedOrNull(input.get("payer")) == null || Payloads.trimmedOrNull(input.get("splitType")) == null
          || !(input.get("participants") instanceof List<?> people) || people.isEmpty()) {
        throw ApiException.badRequest("请指定付款人和分摊成员，或明确选择本人付款、全员均分");
      }
      for (String field : List.of("payer", "splitType", "participants")) {
        args.put(field, input.get(field));
      }
    }
    String category = Payloads.trimmedOrNull(input.get("category"));
    Map<String, Object> draft = new LinkedHashMap<>(expenseDrafts.preview(tripId, userId, args,
        category != null && EXPENSE_CATEGORIES.contains(category) ? category : "other"));
    @SuppressWarnings("unchecked")
    Map<String, Object> expense = (Map<String, Object>) draft.get("expense");
    expense.put("spentAt", ReceiptFields.timestamp(date, zone));
    draft.put("type", "expense");
    draft.put("summary", title + " · " + currency + " " + amount);
    return draft;
  }

  private Map<String, Object> bookingDraft(String tripId, Map<String, Object> input, String title,
      String kind, ZoneId zone) {
    String startZoneInput = ReceiptFields.optional(input, "startTimezone", "开始时区");
    String endZoneInput = ReceiptFields.optional(input, "endTimezone", "结束时区");
    ZoneId startZone = startZoneInput == null ? zone : ZoneId.of(startZoneInput);
    ZoneId endZone = endZoneInput == null ? startZone : ZoneId.of(endZoneInput);
    String start = ReceiptFields.timestamp(ReceiptFields.required(input, "startTime", "预订开始日期或时间"), startZone);
    String endInput = ReceiptFields.optional(input, "endTime", "预订结束日期或时间");
    String end = endInput == null ? null : ReceiptFields.timestamp(endInput, endZone);
    Date startDate = Date.from(OffsetDateTime.parse(start).toInstant());
    if (end != null && OffsetDateTime.parse(end).toInstant().isBefore(startDate.toInstant())) {
      throw ApiException.badRequest("结束时间不能早于开始时间");
    }
    String amount = ReceiptFields.optional(input, "amount", "预订金额");
    String currency = ReceiptFields.optional(input, "currency", "原币");
    if ((amount == null) != (currency == null)) {
      throw ApiException.badRequest("请同时确认预订金额和原币");
    }
    if (!Boolean.TRUE.equals(input.get("acknowledgeDuplicate")) && bookings.exists(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getTripId, tripId).eq(Booking::getType, kind).eq(Booking::getTitle, title)
        .eq(Booking::getStartTime, startDate).isNull(Booking::getCancelledAt))) {
      throw ApiException.conflict(DUPLICATE);
    }
    Map<String, Object> booking = new LinkedHashMap<>();
    booking.put("type", kind);
    booking.put("title", title);
    booking.put("startTime", start);
    booking.put("endTime", end);
    booking.put("startTimezone", startZone.getId());
    booking.put("endTimezone", endZone.getId());
    booking.put("amount", amount);
    booking.put("currency", currency);
    return new LinkedHashMap<>(Map.of("type", "booking", "summary", title + " · 预订草稿", "booking", booking));
  }

  private AiConversation requireContext(String id, String userId, Map<String, Object> input, boolean lock) {
    var query = new LambdaQueryWrapper<AiConversation>().eq(AiConversation::getId, id)
        .eq(AiConversation::getUserId, userId);
    AiConversation conversation = conversations.selectOne(lock ? query.last("FOR UPDATE") : query);
    if (conversation == null) {
      throw ApiException.notFound("对话不存在");
    }
    String tripId = conversation.getTripId();
    String requested = Payloads.trimmedOrNull(input.get("tripId"));
    if (tripId == null || requested != null && !requested.equals(tripId)) {
      throw ApiException.badRequest("请在目标行程的会话中识别票据");
    }
    members.requireTripRole(tripId, userId, TripPermission.EDITABLE_ROLES);
    return conversation;
  }

  private AiMessage findReview(String conversationId, String userId, String fingerprint) {
    return messages.selectOne(new LambdaQueryWrapper<AiMessage>().eq(AiMessage::getConversationId, conversationId)
        .eq(AiMessage::getUserId, userId).apply("tool_calls ->> 'receiptFingerprint' = {0}", fingerprint)
        .in(AiMessage::getDraftStatus, List.of("pending", "applying", "applied"))
        .last("LIMIT 1"));
  }

  private static Map<String, Object> reference(AiMessage message) {
    return Map.of("messageId", message.getId(), "status", message.getDraftStatus());
  }
}
