package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.common.PhotoUtil;
import com.travelos.entity.Booking;
import com.travelos.entity.Trip;
import com.travelos.entity.TripBudget;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripExpenseShare;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.entity.TripSettlement;
import com.travelos.entity.User;
import com.travelos.mapper.BookingMapper;
import com.travelos.mapper.TripBudgetMapper;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripExpenseShareMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.mapper.TripSettlementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

@Service
public class TripExpenseService {

  private static final List<String> CATEGORIES = List.of("transport", "hotel", "food", "ticket",
      "parking", "shopping", "maintenance", "activity", "other");
  private static final Map<String, String> CATEGORY_LABELS = Map.of(
      "transport", "油费/交通", "hotel", "住宿", "food", "餐饮", "ticket", "门票",
      "parking", "停车", "shopping", "购物", "maintenance", "维修", "activity", "活动", "other", "其他");

  /** 类别预算支持的六个类别（与费用类别对齐，hotel 即住宿） */
  private static final List<String> BUDGET_CATEGORIES = List.of(
      "transport", "hotel", "food", "ticket", "shopping", "other");

  /** 预估占用：预订类型 → 预算类别 */
  private static final Map<String, String> BOOKING_TYPE_TO_CATEGORY = Map.of(
      "flight", "transport", "train", "transport", "hotel", "hotel",
      "ticket", "ticket", "other", "other");

  /** 预估占用：行程点类型 → 预算类别 */
  private static final Map<String, String> PLACE_TYPE_TO_CATEGORY = Map.of(
      "attraction", "ticket", "hotel", "hotel", "transport", "transport",
      "restaurant", "food", "shopping", "shopping", "custom", "other");

  private final TripExpenseMapper expenseMapper;
  private final TripExpenseShareMapper shareMapper;
  private final TripMemberService tripMemberService;
  private final TripMapper tripMapper;
  private final com.travelos.mapper.UserMapper userMapper;
  private final AuditService auditService;
  private final TripDayMapper tripDayMapper;
  private final TripPlaceMapper tripPlaceMapper;
  private final BookingMapper bookingMapper;
  private final TripBudgetMapper budgetMapper;
  private final TripSettlementMapper settlementMapper;
  private final NotificationService notificationService;

  public TripExpenseService(TripExpenseMapper expenseMapper, TripExpenseShareMapper shareMapper,
      TripMemberService tripMemberService, TripMapper tripMapper,
      com.travelos.mapper.UserMapper userMapper, AuditService auditService,
      TripDayMapper tripDayMapper, TripPlaceMapper tripPlaceMapper, BookingMapper bookingMapper,
      TripBudgetMapper budgetMapper, TripSettlementMapper settlementMapper,
      NotificationService notificationService) {
    this.expenseMapper = expenseMapper;
    this.shareMapper = shareMapper;
    this.tripMemberService = tripMemberService;
    this.tripMapper = tripMapper;
    this.userMapper = userMapper;
    this.auditService = auditService;
    this.tripDayMapper = tripDayMapper;
    this.tripPlaceMapper = tripPlaceMapper;
    this.bookingMapper = bookingMapper;
    this.budgetMapper = budgetMapper;
    this.settlementMapper = settlementMapper;
    this.notificationService = notificationService;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<TripExpense> expenses = findExpenses(tripId);
    List<Map<String, Object>> members = getMembers(tripId);
    List<Map<String, Object>> expenseMaps = expenses.stream().map(this::toExpenseResponse).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expenses", expenseMaps);
    body.put("summary", buildSummary(expenses, members));
    body.put("latestSettlement", latestSettlementMap(tripId));
    return body;
  }

  public Map<String, Object> getSummary(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<TripExpense> expenses = findExpenses(tripId);
    List<Map<String, Object>> members = getMembers(tripId);
    Trip trip = tripMapper.selectById(tripId);
    String budget = trip == null ? null : Fmt.money(trip.getBudget());
    return buildWalletSummary(expenses, members, budget);
  }

  @Transactional
  public Map<String, Object> create(String tripId, String userId, Map<String, Object> dto) {
    long amountCents = toCents(string(dto.get("amount")), false);
    String payerUserId = optionalString(dto.get("payerUserId")) == null
        ? userId
        : dto.get("payerUserId").toString();
    SharePlan plan = planShares(tripId, payerUserId, amountCents, dto, List.of());
    List<Map<String, String>> shares = plan.shares();

    String category = optionalString(dto.get("category")) == null ? "other"
        : dto.get("category").toString();
    String title = optionalString(dto.get("title"));
    if (title == null || title.isBlank()) {
      // 快速记账：标题缺省用类别名
      title = CATEGORY_LABELS.getOrDefault(category, "其他");
    }
    Date spentAt = dto.get("spentAt") == null
        ? new Date()
        : DateUtil.parse(dto.get("spentAt").toString());
    String tripDayId = optionalString(dto.get("tripDayId"));
    if (tripDayId == null) {
      tripDayId = matchTripDayId(tripId, spentAt);
    } else {
      ensureDayBelongsToTrip(tripDayId, tripId);
    }
    String receiptUrl = validateReceipt(dto.get("receiptUrl"));

    TripExpense expense = new TripExpense();
    expense.setTripId(tripId);
    expense.setPayerUserId(payerUserId);
    expense.setTitle(title.trim());
    expense.setAmount(centsToDecimal(amountCents));
    expense.setCurrency(optionalString(dto.get("currency")) == null ? "CNY"
        : dto.get("currency").toString().trim());
    expense.setCategory(category);
    expense.setSpentAt(spentAt);
    expense.setNotes(toNullable(dto.get("notes")));
    expense.setReceiptUrl(receiptUrl);
    expense.setSplitType(plan.splitType());
    expense.setTripDayId(tripDayId);
    expense.setIsSettled(false);
    expense.setCreatedAt(new Date());
    expense.setUpdatedAt(new Date());
    expenseMapper.insert(expense);
    for (Map<String, String> share : shares) {
      insertShare(expense.getId(), share.get("userId"), share.get("shareAmount"));
    }
    return Map.of("expense", toExpenseResponse(expenseMapper.selectById(expense.getId())));
  }

  @Transactional
  public Map<String, Object> update(String tripId, String expenseId, Map<String, Object> dto,
      String userId) {
    TripExpense expense = expenseMapper.selectById(expenseId);
    if (expense == null || !expense.getTripId().equals(tripId)) {
      throw ApiException.notFound("费用不存在");
    }

    String payerUserId = optionalString(dto.get("payerUserId")) == null
        ? expense.getPayerUserId()
        : dto.get("payerUserId").toString();
    long amountCents = dto.containsKey("amount")
        ? toCents(string(dto.get("amount")), false)
        : Math.round(expense.getAmount().doubleValue() * 100);
    List<Map<String, String>> existingShares = shareMapper
        .selectList(new LambdaQueryWrapper<TripExpenseShare>()
            .eq(TripExpenseShare::getExpenseId, expenseId))
        .stream()
        .map(share -> Map.of("userId", share.getUserId(),
            "shareAmount", Fmt.money(share.getShareAmount())))
        .toList();
    SharePlan plan = planShares(tripId, payerUserId, amountCents, dto, existingShares);
    List<Map<String, String>> shares = plan.shares();

    if (dto.containsKey("title")) {
      String title = string(dto.get("title"));
      expense.setTitle(title == null ? null : title.trim());
    }
    if (dto.containsKey("amount")) {
      expense.setAmount(centsToDecimal(amountCents));
    }
    if (dto.containsKey("currency") && optionalString(dto.get("currency")) != null) {
      expense.setCurrency(dto.get("currency").toString().trim());
    }
    if (dto.containsKey("category") && dto.get("category") != null) {
      expense.setCategory(dto.get("category").toString());
    }
    if (dto.containsKey("spentAt") && dto.get("spentAt") != null) {
      expense.setSpentAt(DateUtil.parse(dto.get("spentAt").toString()));
    }
    if (dto.containsKey("notes")) {
      expense.setNotes(toNullable(dto.get("notes")));
    }
    if (dto.containsKey("payerUserId")) {
      expense.setPayerUserId(payerUserId);
    }
    if (dto.containsKey("receiptUrl")) {
      expense.setReceiptUrl(validateReceipt(dto.get("receiptUrl")));
    }
    if (dto.containsKey("tripDayId")) {
      String tripDayId = optionalString(dto.get("tripDayId"));
      if (tripDayId != null) {
        ensureDayBelongsToTrip(tripDayId, tripId);
      }
      expense.setTripDayId(tripDayId);
    }
    expense.setSplitType(plan.splitType());
    expense.setUpdatedAt(new Date());
    expenseMapper.updateById(expense);

    shareMapper.delete(new LambdaQueryWrapper<TripExpenseShare>()
        .eq(TripExpenseShare::getExpenseId, expenseId));
    for (Map<String, String> share : shares) {
      insertShare(expenseId, share.get("userId"), share.get("shareAmount"));
    }
    return Map.of("expense", toExpenseResponse(expenseMapper.selectById(expenseId)));
  }

  @Transactional
  public Map<String, Object> delete(String tripId, String expenseId, String userId) {
    TripExpense expense = expenseMapper.selectById(expenseId);
    if (expense == null || !expense.getTripId().equals(tripId)) {
      throw ApiException.notFound("费用不存在");
    }
    shareMapper.delete(new LambdaQueryWrapper<TripExpenseShare>()
        .eq(TripExpenseShare::getExpenseId, expenseId));
    expenseMapper.deleteById(expenseId);
    auditService.record(userId, tripId, "expense_deleted", "trip_expense", expenseId,
        Map.of("title", expense.getTitle() == null ? "" : expense.getTitle(),
            "amount", Fmt.money(expense.getAmount())));
    return Map.of("success", true);
  }

  // ==================== 需求 1：类别预算与超支预警 ====================

  /** 预算总览：总预算 + 各类别 预算/已花/预估占用/剩余/超支。 */
  public Map<String, Object> getBudget(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    Trip trip = tripMapper.selectById(tripId);
    Long tripBudgetCents = trip != null && trip.getBudget() != null
        ? toCentsQuiet(trip.getBudget())
        : null;

    List<TripExpense> expenses = findExpenses(tripId);
    Map<String, Long> spentByCategory = new LinkedHashMap<>();
    long totalSpent = 0;
    for (TripExpense expense : expenses) {
      long cents = toCentsQuiet(expense.getAmount());
      totalSpent += cents;
      spentByCategory.merge(expense.getCategory(), cents, Long::sum);
    }

    // 预估占用 = 行程点预估费用 + bookings.amount（均不做汇率折算，产品决策）
    Map<String, Long> estimatedByCategory = new LinkedHashMap<>();
    long totalEstimated = 0;
    for (TripPlace place : tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
        .eq(TripPlace::getTripId, tripId))) {
      if (place.getEstimatedCost() == null) {
        continue;
      }
      long cents = toCentsQuiet(place.getEstimatedCost());
      String category = PLACE_TYPE_TO_CATEGORY.getOrDefault(place.getType(), "other");
      estimatedByCategory.merge(category, cents, Long::sum);
      totalEstimated += cents;
    }
    for (Booking booking : bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
        .eq(Booking::getTripId, tripId))) {
      if (booking.getAmount() == null) {
        continue;
      }
      long cents = toCentsQuiet(booking.getAmount());
      String category = BOOKING_TYPE_TO_CATEGORY.getOrDefault(booking.getType(), "other");
      estimatedByCategory.merge(category, cents, Long::sum);
      totalEstimated += cents;
    }

    Map<String, Long> budgetByCategory = new LinkedHashMap<>();
    for (TripBudget budget : budgetMapper.selectList(new LambdaQueryWrapper<TripBudget>()
        .eq(TripBudget::getTripId, tripId))) {
      budgetByCategory.put(budget.getCategory(), toCentsQuiet(budget.getAmount()));
    }

    List<Map<String, Object>> categories = new ArrayList<>();
    boolean anyWarning = false;
    for (String category : BUDGET_CATEGORIES) {
      long budgetCents = budgetByCategory.getOrDefault(category, 0L);
      long spent = spentByCategory.getOrDefault(category, 0L);
      long estimated = estimatedByCategory.getOrDefault(category, 0L);
      boolean hasBudget = budgetCents > 0;
      // 预警口径：已花费 + 预估占用 超过类别预算
      boolean warning = hasBudget && spent + estimated > budgetCents;
      boolean overspent = hasBudget && spent > budgetCents;
      anyWarning = anyWarning || warning;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", category);
      row.put("label", CATEGORY_LABELS.getOrDefault(category, category));
      row.put("budget", centsToString(budgetCents));
      row.put("hasBudget", hasBudget);
      row.put("spent", centsToString(spent));
      row.put("estimated", centsToString(estimated));
      row.put("remaining", centsToString(budgetCents - spent - estimated));
      row.put("warning", warning);
      row.put("overspent", overspent);
      categories.add(row);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tripBudget", tripBudgetCents == null ? null : centsToString(tripBudgetCents));
    body.put("totalSpent", centsToString(totalSpent));
    body.put("totalEstimated", centsToString(totalEstimated));
    body.put("totalRemaining", tripBudgetCents == null
        ? null
        : centsToString(tripBudgetCents - totalSpent - totalEstimated));
    body.put("totalWarning", tripBudgetCents != null && tripBudgetCents > 0
        && totalSpent + totalEstimated > tripBudgetCents);
    body.put("hasWarning", anyWarning);
    body.put("categories", categories);
    return body;
  }

  /** 设置类别预算：amount 为空或 0 表示移除该类别预算。 */
  @Transactional
  public Map<String, Object> setBudgets(String tripId, String userId, Map<String, Object> dto) {
    tripMemberService.requireTripMember(tripId, userId);
    if (!(dto.get("budgets") instanceof List<?> rawList) || rawList.isEmpty()) {
      throw ApiException.badRequest("budgets 不能为空");
    }
    for (Object item : rawList) {
      if (!(item instanceof Map<?, ?> entry)) {
        throw ApiException.badRequest("budgets 项格式不正确");
      }
      String category = entry.get("category") == null ? "" : entry.get("category").toString();
      if (!BUDGET_CATEGORIES.contains(category)) {
        throw ApiException.badRequest("不支持的预算类别：" + category);
      }
      TripBudget existing = budgetMapper.selectOne(new LambdaQueryWrapper<TripBudget>()
          .eq(TripBudget::getTripId, tripId)
          .eq(TripBudget::getCategory, category));
      String amountRaw = entry.get("amount") == null ? null : entry.get("amount").toString();
      if (amountRaw == null || amountRaw.isBlank() || toCents(amountRaw, true) == 0) {
        // 空金额 = 移除该类别预算
        if (existing != null) {
          budgetMapper.deleteById(existing.getId());
        }
        continue;
      }
      BigDecimal amount = centsToDecimal(toCents(amountRaw, true));
      if (existing == null) {
        TripBudget budget = new TripBudget();
        budget.setTripId(tripId);
        budget.setCategory(category);
        budget.setAmount(amount);
        budget.setCreatedAt(new Date());
        budget.setUpdatedAt(new Date());
        budgetMapper.insert(budget);
      } else {
        existing.setAmount(amount);
        existing.setUpdatedAt(new Date());
        budgetMapper.updateById(existing);
      }
    }
    return getBudget(tripId, userId);
  }

  // ==================== 需求 3：多人分摊与结算 ====================

  /** 实时净额 + 最少转账结算方案（净额正负贪心配对）。 */
  public Map<String, Object> settlement(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<TripExpense> expenses = findExpenses(tripId);
    List<Map<String, Object>> members = getMembers(tripId);

    Map<String, Long> paid = new LinkedHashMap<>();
    Map<String, Long> owed = new LinkedHashMap<>();
    long total = 0;
    for (TripExpense expense : expenses) {
      long cents = toCentsQuiet(expense.getAmount());
      total += cents;
      paid.merge(expense.getPayerUserId(), cents, Long::sum);
      for (TripExpenseShare share : sharesOf(expense.getId())) {
        owed.merge(share.getUserId(), toCentsQuiet(share.getShareAmount()), Long::sum);
      }
    }

    List<Map<String, Object>> nets = new ArrayList<>();
    List<long[]> creditors = new ArrayList<>(); // 应收（净额 > 0）
    List<long[]> debtors = new ArrayList<>();   // 应付（净额 < 0）
    for (int index = 0; index < members.size(); index++) {
      Map<String, Object> member = members.get(index);
      String memberId = member.get("userId").toString();
      long net = paid.getOrDefault(memberId, 0L) - owed.getOrDefault(memberId, 0L);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("userId", memberId);
      row.put("displayName", member.get("displayName"));
      row.put("paid", centsToString(paid.getOrDefault(memberId, 0L)));
      row.put("owed", centsToString(owed.getOrDefault(memberId, 0L)));
      row.put("net", centsToString(net));
      nets.add(row);
      if (net > 0) {
        creditors.add(new long[]{index, net});
      } else if (net < 0) {
        debtors.add(new long[]{index, -net});
      }
    }
    // 贪心：每次取最大应收与最大应付配对，每步至少清零一方 → 转账次数 ≤ 成员数 - 1
    creditors.sort((a, b) -> Long.compare(b[1], a[1]));
    debtors.sort((a, b) -> Long.compare(b[1], a[1]));
    List<Map<String, Object>> transfers = new ArrayList<>();
    int ci = 0;
    int di = 0;
    while (ci < creditors.size() && di < debtors.size()) {
      long[] creditor = creditors.get(ci);
      long[] debtor = debtors.get(di);
      long amount = Math.min(creditor[1], debtor[1]);
      if (amount > 0) {
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("fromUserId", members.get((int) debtor[0]).get("userId"));
        transfer.put("fromDisplayName", members.get((int) debtor[0]).get("displayName"));
        transfer.put("toUserId", members.get((int) creditor[0]).get("userId"));
        transfer.put("toDisplayName", members.get((int) creditor[0]).get("displayName"));
        transfer.put("amount", centsToString(amount));
        transfers.add(transfer);
      }
      creditor[1] -= amount;
      debtor[1] -= amount;
      if (creditor[1] == 0) {
        ci++;
      }
      if (debtor[1] == 0) {
        di++;
      }
    }

    String currency = expenses.isEmpty() ? "CNY" : expenses.get(0).getCurrency();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", currency);
    body.put("totalAmount", centsToString(total));
    body.put("nets", nets);
    body.put("transfers", transfers);
    body.put("canConfirm", !transfers.isEmpty());
    body.put("latestSettlement", latestSettlementMap(tripId));
    return body;
  }

  /** 确认结算（owner/admin）：把当前方案快照存档并通知全部成员。 */
  @Transactional
  public Map<String, Object> confirmSettlement(String tripId, String confirmedBy) {
    Map<String, Object> plan = settlement(tripId, confirmedBy);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> transfers = (List<Map<String, Object>>) plan.get("transfers");
    if (transfers.isEmpty()) {
      throw ApiException.badRequest("当前没有需要结算的账目");
    }
    Trip trip = tripMapper.selectById(tripId);
    String tripTitle = trip == null ? "" : trip.getTitle();

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("currency", plan.get("currency"));
    snapshot.put("totalAmount", plan.get("totalAmount"));
    snapshot.put("nets", plan.get("nets"));
    snapshot.put("transfers", transfers);
    snapshot.put("confirmedAt", new Date());
    snapshot.put("confirmedBy", confirmedBy);

    TripSettlement settlement = new TripSettlement();
    settlement.setTripId(tripId);
    settlement.setSnapshot(com.travelos.common.JsonUtil.write(snapshot));
    settlement.setStatus("confirmed");
    settlement.setConfirmedBy(confirmedBy);
    settlement.setCreatedAt(new Date());
    settlementMapper.insert(settlement);

    // 全员通知「xx 之旅账目已结清」
    for (TripMember member : tripMemberService.listMembers(tripId)) {
      notificationService.createSettlementConfirmed(member.getUserId(), tripId, tripTitle,
          "旅行「" + tripTitle + "」的账目已结清，共 " + transfers.size() + " 笔转账，点击查看结算方案。");
    }
    auditService.record(confirmedBy, tripId, "settlement_confirmed", "trip", tripId,
        Map.of("totalAmount", plan.get("totalAmount"), "transfers", transfers.size()));
    return Map.of("settlement", settlementMap(settlement, tripTitle));
  }

  // ==================== 需求 4：单次旅行统计报表 ====================

  /** 三个维度：按类别 / 按天 / 按成员。金额按主币种统计，其余币种单独汇总（不做汇率折算）。 */
  public Map<String, Object> report(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<TripExpense> expenses = findExpenses(tripId);
    List<Map<String, Object>> members = getMembers(tripId);

    // 主币种：出现次数最多（并列取先出现）
    Map<String, Integer> currencyCount = new LinkedHashMap<>();
    for (TripExpense expense : expenses) {
      currencyCount.merge(expense.getCurrency(), 1, Integer::sum);
    }
    String primary = expenses.isEmpty() ? "CNY" : expenses.get(0).getCurrency();
    int best = 0;
    for (Map.Entry<String, Integer> entry : currencyCount.entrySet()) {
      if (entry.getValue() > best) {
        best = entry.getValue();
        primary = entry.getKey();
      }
    }

    SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
    dayFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

    Map<String, Long> byCategory = new LinkedHashMap<>();
    Map<String, Long> byDay = new LinkedHashMap<>();
    Map<String, Long> paid = new LinkedHashMap<>();
    Map<String, Long> owed = new LinkedHashMap<>();
    Map<String, Long> otherCurrencyTotals = new LinkedHashMap<>();
    long primaryTotal = 0;
    for (TripExpense expense : expenses) {
      long cents = toCentsQuiet(expense.getAmount());
      if (!expense.getCurrency().equals(primary)) {
        otherCurrencyTotals.merge(expense.getCurrency(), cents, Long::sum);
        continue;
      }
      primaryTotal += cents;
      byCategory.merge(expense.getCategory(), cents, Long::sum);
      byDay.merge(dayFormat.format(expense.getSpentAt()), cents, Long::sum);
      paid.merge(expense.getPayerUserId(), cents, Long::sum);
      for (TripExpenseShare share : sharesOf(expense.getId())) {
        owed.merge(share.getUserId(), toCentsQuiet(share.getShareAmount()), Long::sum);
      }
    }

    List<Map<String, Object>> categories = new ArrayList<>();
    for (Map.Entry<String, Long> entry : byCategory.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", entry.getKey());
      row.put("label", CATEGORY_LABELS.getOrDefault(entry.getKey(), entry.getKey()));
      row.put("amount", centsToString(entry.getValue()));
      row.put("percent", primaryTotal == 0 ? 0
          : Math.round(entry.getValue() * 1000.0 / primaryTotal) / 10.0);
      categories.add(row);
    }

    List<Map<String, Object>> days = new ArrayList<>();
    for (Map.Entry<String, Long> entry : byDay.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", entry.getKey());
      row.put("amount", centsToString(entry.getValue()));
      days.add(row);
    }

    List<Map<String, Object>> memberRows = new ArrayList<>();
    for (Map<String, Object> member : members) {
      String memberId = member.get("userId").toString();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("userId", memberId);
      row.put("displayName", member.get("displayName"));
      row.put("paid", centsToString(paid.getOrDefault(memberId, 0L)));
      row.put("owed", centsToString(owed.getOrDefault(memberId, 0L)));
      memberRows.add(row);
    }

    Map<String, Object> others = new LinkedHashMap<>();
    for (Map.Entry<String, Long> entry : otherCurrencyTotals.entrySet()) {
      others.put(entry.getKey(), centsToString(entry.getValue()));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", primary);
    body.put("totalAmount", centsToString(primaryTotal));
    body.put("expenseCount", expenses.size());
    body.put("byCategory", categories);
    body.put("byDay", days);
    body.put("byMember", memberRows);
    body.put("otherCurrencies", others);
    return body;
  }

  // ==================== 分摊计划（三种方式） ====================

  private record SharePlan(List<Map<String, String>> shares, String splitType) {
  }

  /**
   * 三种分摊方式：equal（均分，默认）/ ratio（按比例）/ amount（按金额）。
   * amount 模式允许 ±0.01 尾差，尾差由付款人承担；最终合计必须等于总额。
   */
  private SharePlan planShares(String tripId, String payerUserId, long amountCents,
      Map<String, Object> dto, List<Map<String, String>> fallbackShares) {
    String splitType = optionalString(dto.get("splitType"));

    if (dto.get("shares") instanceof List<?> rawShares && !rawShares.isEmpty()) {
      boolean hasRatio = false;
      boolean hasAmount = false;
      List<Map<String, String>> entries = new ArrayList<>();
      for (Object item : rawShares) {
        if (!(item instanceof Map<?, ?> share)) {
          continue;
        }
        String entryUserId = share.get("userId") == null ? "" : share.get("userId").toString();
        if (share.get("ratio") != null) {
          hasRatio = true;
          entries.add(Map.of("userId", entryUserId, "ratio", share.get("ratio").toString()));
        } else if (share.get("shareAmount") != null) {
          hasAmount = true;
          entries.add(Map.of("userId", entryUserId,
              "shareAmount", share.get("shareAmount").toString()));
        }
      }
      if (hasRatio && hasAmount) {
        throw ApiException.badRequest("分摊方式不能混用 ratio 与 shareAmount");
      }
      if (hasRatio) {
        splitType = splitType == null ? "ratio" : splitType;
        return new SharePlan(ratioShares(tripId, payerUserId, amountCents, entries), splitType);
      }
      if (hasAmount) {
        splitType = splitType == null ? "amount" : splitType;
        return new SharePlan(amountShares(tripId, payerUserId, amountCents, entries), splitType);
      }
    }
    if (dto.get("shareUserIds") instanceof List<?> rawUserIds && !rawUserIds.isEmpty()) {
      splitType = splitType == null ? "equal" : splitType;
      List<String> userIds = rawUserIds.stream().map(Object::toString).toList();
      validateExpenseMembers(tripId, payerUserId, userIds);
      return new SharePlan(calculateShares(amountCents, userIds), splitType);
    }
    // 未传分摊信息：编辑场景沿用现有明细；新建场景默认均分给全部成员（快速记账 3 步直达）
    if (!fallbackShares.isEmpty()) {
      return new SharePlan(new ArrayList<>(fallbackShares),
          splitType == null ? "amount" : splitType);
    }
    List<String> allMemberIds = tripMemberService.listMembers(tripId).stream()
        .map(TripMember::getUserId)
        .distinct()
        .toList();
    if (allMemberIds.isEmpty()) {
      throw ApiException.badRequest("分摊金额不能为空");
    }
    validateExpenseMembers(tripId, payerUserId, allMemberIds);
    return new SharePlan(calculateShares(amountCents, allMemberIds),
        splitType == null ? "equal" : splitType);
  }

  /** 按比例：累计比例四舍五入差分，保证合计精确等于总额。 */
  private List<Map<String, String>> ratioShares(String tripId, String payerUserId, long amountCents,
      List<Map<String, String>> entries) {
    validateExpenseMembers(tripId, payerUserId, entries.stream()
        .map(entry -> entry.get("userId")).toList());
    double sum = 0;
    for (Map<String, String> entry : entries) {
      double ratio;
      try {
        ratio = Double.parseDouble(entry.get("ratio"));
      } catch (NumberFormatException ex) {
        throw ApiException.badRequest("分摊比例格式不正确");
      }
      if (Double.isNaN(ratio) || Double.isInfinite(ratio) || ratio < 0) {
        throw ApiException.badRequest("分摊比例不能为负数");
      }
      sum += ratio;
    }
    if (sum <= 0) {
      throw ApiException.badRequest("分摊比例之和必须大于 0");
    }
    List<Map<String, String>> shares = new ArrayList<>();
    long cumulative = 0;
    double ratioCumulative = 0;
    for (int index = 0; index < entries.size(); index++) {
      Map<String, String> entry = entries.get(index);
      ratioCumulative += Double.parseDouble(entry.get("ratio"));
      long target = index == entries.size() - 1
          ? amountCents
          : Math.round(amountCents * (ratioCumulative / sum));
      long cents = target - cumulative;
      cumulative = target;
      shares.add(Map.of("userId", entry.get("userId"), "shareAmount", centsToString(cents)));
    }
    return normalizeAmounts(amountCents, shares);
  }

  /** 按金额：合计与总额相差 ≤0.01 时放行，尾差由付款人承担。 */
  private List<Map<String, String>> amountShares(String tripId, String payerUserId,
      long amountCents, List<Map<String, String>> entries) {
    validateExpenseMembers(tripId, payerUserId, entries.stream()
        .map(entry -> entry.get("userId")).toList());
    Set<String> seen = new LinkedHashSet<>();
    List<Map<String, String>> shares = new ArrayList<>();
    long sum = 0;
    for (Map<String, String> entry : entries) {
      if (!seen.add(entry.get("userId"))) {
        throw ApiException.badRequest("分摊成员不能重复");
      }
      long cents = toCents(entry.get("shareAmount"), true);
      sum += cents;
      shares.add(Map.of("userId", entry.get("userId"), "shareAmount", centsToString(cents)));
    }
    long diff = amountCents - sum;
    if (Math.abs(diff) > 1) {
      throw ApiException.badRequest("分摊金额之和与总额相差超过 0.01 元，请检查分摊明细");
    }
    if (diff != 0) {
      // 尾差由付款人承担：调整付款人的份额使合计精确等于总额
      boolean payerAdjusted = false;
      List<Map<String, String>> adjusted = new ArrayList<>();
      for (Map<String, String> share : shares) {
        if (share.get("userId").equals(payerUserId)) {
          long cents = toCents(share.get("shareAmount"), true) + diff;
          if (cents < 0) {
            throw ApiException.badRequest("尾差调整后付款人份额不能为负数");
          }
          adjusted.add(Map.of("userId", payerUserId, "shareAmount", centsToString(cents)));
          payerAdjusted = true;
        } else {
          adjusted.add(share);
        }
      }
      if (!payerAdjusted) {
        adjusted.add(Map.of("userId", payerUserId, "shareAmount", centsToString(diff)));
      }
      shares = adjusted;
    }
    return normalizeAmounts(amountCents, shares);
  }

  /** 最终一致性校验：去重 + 合计必须精确等于总额。 */
  private List<Map<String, String>> normalizeAmounts(long amountCents,
      List<Map<String, String>> shares) {
    Set<String> seen = new LinkedHashSet<>();
    long total = 0;
    for (Map<String, String> share : shares) {
      if (!seen.add(share.get("userId"))) {
        throw ApiException.badRequest("分摊成员不能重复");
      }
      total += toCents(share.get("shareAmount"), true);
    }
    if (total != amountCents) {
      throw ApiException.badRequest("分摊金额之和必须等于消费总额");
    }
    return shares;
  }

  // ==================== 内部工具 ====================

  private void ensureDayBelongsToTrip(String tripDayId, String tripId) {
    TripDay day = tripDayMapper.selectById(tripDayId);
    if (day == null || !day.getTripId().equals(tripId)) {
      throw ApiException.badRequest("行程天不属于当前旅行");
    }
  }

  /** 快速记账：按 spent_at 的日期自动匹配行程天（UTC 日口径，与生成天数一致）。 */
  private String matchTripDayId(String tripId, Date spentAt) {
    TripDay day = tripDayMapper.selectOne(new LambdaQueryWrapper<TripDay>()
        .eq(TripDay::getTripId, tripId)
        .eq(TripDay::getDate, DateUtil.utcDay(spentAt))
        .last("LIMIT 1"));
    return day == null ? null : day.getId();
  }

  /** 小票图片校验：data URL（≤5MB）或 http(s) 外链。 */
  private String validateReceipt(Object raw) {
    String url = toNullable(raw);
    if (url == null) {
      return null;
    }
    if (url.startsWith("data:")) {
      String[] parts = PhotoUtil.parsePhotoDataUrl(url);
      if (parts == null) {
        throw ApiException.badRequest("小票仅支持 jpg、png、webp 图片");
      }
      if (PhotoUtil.base64Bytes(parts[1]) > PhotoUtil.MAX_PHOTO_BYTES) {
        throw ApiException.badRequest("小票图片不能超过 5MB");
      }
      return url;
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw ApiException.badRequest("小票地址格式不正确");
    }
    return url;
  }

  private Map<String, Object> latestSettlementMap(String tripId) {
    TripSettlement settlement = settlementMapper.selectOne(new LambdaQueryWrapper<TripSettlement>()
        .eq(TripSettlement::getTripId, tripId)
        .orderByDesc(TripSettlement::getCreatedAt)
        .last("LIMIT 1"));
    return settlement == null ? null : settlementMap(settlement, null);
  }

  private Map<String, Object> settlementMap(TripSettlement settlement, String tripTitle) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", settlement.getId());
    map.put("tripId", settlement.getTripId());
    map.put("status", settlement.getStatus());
    map.put("confirmedBy", settlement.getConfirmedBy());
    map.put("createdAt", settlement.getCreatedAt());
    map.put("snapshot", com.travelos.common.JsonUtil.readObject(settlement.getSnapshot()));
    if (tripTitle != null) {
      map.put("tripTitle", tripTitle);
    }
    return map;
  }

  private void validateExpenseMembers(String tripId, String payerUserId, List<String> shareUserIds) {
    if (shareUserIds.isEmpty()) {
      throw ApiException.badRequest("分摊成员不能为空");
    }
    Set<String> memberIds = new LinkedHashSet<>();
    for (TripMember member : tripMemberService.listMembers(tripId)) {
      memberIds.add(member.getUserId());
    }
    if (!memberIds.contains(payerUserId)) {
      throw ApiException.badRequest("付款人必须是旅行成员");
    }
    for (String userId : shareUserIds) {
      if (!memberIds.contains(userId)) {
        throw ApiException.badRequest("分摊成员必须全部属于当前旅行");
      }
    }
  }

  /** 均分：前面成员拿整除部分，最后一人兜底尾差。 */
  private List<Map<String, String>> calculateShares(long amountCents, List<String> userIds) {
    List<String> uniqueUserIds = new ArrayList<>(new LinkedHashSet<>(userIds));
    long base = amountCents / uniqueUserIds.size();
    long allocated = 0;
    List<Map<String, String>> shares = new ArrayList<>();
    for (int index = 0; index < uniqueUserIds.size(); index++) {
      long cents = index == uniqueUserIds.size() - 1 ? amountCents - allocated : base;
      allocated += cents;
      shares.add(Map.of("userId", uniqueUserIds.get(index),
          "shareAmount", centsToString(cents)));
    }
    return shares;
  }

  private Map<String, Object> buildSummary(List<TripExpense> expenses,
      List<Map<String, Object>> members) {
    Map<String, Long> paid = new LinkedHashMap<>();
    Map<String, Long> share = new LinkedHashMap<>();
    long total = 0;
    for (TripExpense expense : expenses) {
      long cents = toCentsQuiet(expense.getAmount());
      total += cents;
      paid.merge(expense.getPayerUserId(), cents, Long::sum);
      for (TripExpenseShare expenseShare : sharesOf(expense.getId())) {
        share.merge(expenseShare.getUserId(), toCentsQuiet(expenseShare.getShareAmount()), Long::sum);
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", expenses.isEmpty() ? "CNY" : expenses.get(0).getCurrency());
    body.put("totalAmount", centsToString(total));
    body.put("paidByUser", toMemberRows(paid, members));
    body.put("shareByUser", toMemberRows(share, members));
    List<Map<String, Object>> balances = new ArrayList<>();
    for (Map<String, Object> member : members) {
      String userId = member.get("userId").toString();
      long paidAmount = paid.getOrDefault(userId, 0L);
      long shareAmount = share.getOrDefault(userId, 0L);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("userId", userId);
      row.put("displayName", member.get("displayName"));
      row.put("amount", centsToString(paidAmount - shareAmount));
      balances.add(row);
    }
    body.put("balanceByUser", balances);
    return body;
  }

  private Map<String, Object> buildWalletSummary(List<TripExpense> expenses,
      List<Map<String, Object>> members, String budget) {
    Map<String, Long> categoryTotals = new LinkedHashMap<>();
    for (String category : CATEGORIES) {
      categoryTotals.put(category, 0L);
    }
    Map<String, Long> paid = new LinkedHashMap<>();
    Map<String, Long> owed = new LinkedHashMap<>();
    long total = 0;
    for (TripExpense expense : expenses) {
      long cents = toCentsQuiet(expense.getAmount());
      total += cents;
      categoryTotals.merge(expense.getCategory(), cents, Long::sum);
      paid.merge(expense.getPayerUserId(), cents, Long::sum);
      for (TripExpenseShare share : sharesOf(expense.getId())) {
        owed.merge(share.getUserId(), toCentsQuiet(share.getShareAmount()), Long::sum);
      }
    }
    Long budgetCents = budget == null ? null : toCents(budget, true);
    long remainingBudget = budgetCents == null ? 0 : budgetCents - total;
    Map<String, String> displayNameById = new LinkedHashMap<>();
    for (Map<String, Object> member : members) {
      displayNameById.put(member.get("userId").toString(), member.get("displayName").toString());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("budget", budget);
    body.put("totalExpenseAmount", centsToString(total));
    body.put("remainingBudget", centsToString(remainingBudget));
    List<Map<String, Object>> categories = new ArrayList<>();
    for (String category : CATEGORIES) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", category);
      row.put("label", CATEGORY_LABELS.get(category));
      row.put("amount", centsToString(categoryTotals.get(category)));
      categories.add(row);
    }
    body.put("categoryBreakdown", categories);
    List<Map<String, Object>> payers = new ArrayList<>();
    for (Map.Entry<String, Long> entry : paid.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("displayName", displayNameById.getOrDefault(entry.getKey(), "未知成员"));
      row.put("amount", centsToString(entry.getValue()));
      payers.add(row);
    }
    body.put("payerBreakdown", payers);
    List<Map<String, Object>> balances = new ArrayList<>();
    for (Map<String, Object> member : members) {
      String userId = member.get("userId").toString();
      long paidAmount = paid.getOrDefault(userId, 0L);
      long owedAmount = owed.getOrDefault(userId, 0L);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("displayName", member.get("displayName"));
      row.put("paid", centsToString(paidAmount));
      row.put("owed", centsToString(owedAmount));
      row.put("balance", centsToString(paidAmount - owedAmount));
      balances.add(row);
    }
    body.put("memberBalances", balances);
    return body;
  }

  private List<Map<String, Object>> getMembers(String tripId) {
    List<Map<String, Object>> members = new ArrayList<>();
    for (TripMember member : tripMemberService.listMembers(tripId)) {
      User user = userMapper.selectById(member.getUserId());
      if (user == null) {
        continue;
      }
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("userId", member.getUserId());
      map.put("displayName", user.getDisplayName());
      map.put("username", user.getUsername());
      members.add(map);
    }
    return members;
  }

  private List<Map<String, Object>> toMemberRows(Map<String, Long> source,
      List<Map<String, Object>> members) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> member : members) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("userId", member.get("userId"));
      row.put("displayName", member.get("displayName"));
      row.put("amount", centsToString(source.getOrDefault(member.get("userId").toString(), 0L)));
      rows.add(row);
    }
    return rows;
  }

  public List<TripExpense> findExpenses(String tripId) {
    return expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
        .eq(TripExpense::getTripId, tripId)
        .orderByDesc(TripExpense::getSpentAt));
  }

  private List<TripExpenseShare> sharesOf(String expenseId) {
    return shareMapper.selectList(new LambdaQueryWrapper<TripExpenseShare>()
        .eq(TripExpenseShare::getExpenseId, expenseId));
  }

  private Map<String, Object> toExpenseResponse(TripExpense expense) {
    User payer = userMapper.selectById(expense.getPayerUserId());
    Map<String, Object> payerMap = new LinkedHashMap<>();
    payerMap.put("id", expense.getPayerUserId());
    payerMap.put("displayName", payer == null ? null : payer.getDisplayName());

    List<Map<String, Object>> shares = new ArrayList<>();
    for (TripExpenseShare share : sharesOf(expense.getId())) {
      User user = userMapper.selectById(share.getUserId());
      Map<String, Object> shareMap = new LinkedHashMap<>();
      shareMap.put("userId", share.getUserId());
      shareMap.put("displayName", user == null ? null : user.getDisplayName());
      shareMap.put("shareAmount", Fmt.money(share.getShareAmount()));
      shares.add(shareMap);
    }
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", expense.getId());
    map.put("title", expense.getTitle());
    map.put("amount", Fmt.money(expense.getAmount()));
    map.put("currency", expense.getCurrency());
    map.put("category", expense.getCategory());
    map.put("spentAt", expense.getSpentAt());
    map.put("notes", expense.getNotes());
    map.put("receiptUrl", expense.getReceiptUrl());
    map.put("splitType", expense.getSplitType());
    map.put("tripDayId", expense.getTripDayId());
    map.put("payer", payerMap);
    map.put("shares", shares);
    return map;
  }

  private void insertShare(String expenseId, String userId, String shareAmount) {
    TripExpenseShare share = new TripExpenseShare();
    share.setExpenseId(expenseId);
    share.setUserId(userId);
    share.setShareAmount(new BigDecimal(shareAmount));
    share.setCreatedAt(new Date());
    share.setUpdatedAt(new Date());
    shareMapper.insert(share);
  }

  private long toCents(String amount, boolean allowZero) {
    double value;
    try {
      value = Double.parseDouble(amount);
    } catch (NumberFormatException ex) {
      throw ApiException.badRequest("金额不能小于 0");
    }
    if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
      throw ApiException.badRequest("金额不能小于 0");
    }
    if (!allowZero && value <= 0) {
      throw ApiException.badRequest("金额必须大于 0");
    }
    return Math.round(value * 100);
  }

  private long toCentsQuiet(BigDecimal amount) {
    return amount == null ? 0 : Math.round(amount.doubleValue() * 100);
  }

  private BigDecimal centsToDecimal(long cents) {
    return BigDecimal.valueOf(cents, 2);
  }

  private String centsToString(long cents) {
    return String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
  }

  private String string(Object value) {
    return value == null ? null : value.toString();
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
}
