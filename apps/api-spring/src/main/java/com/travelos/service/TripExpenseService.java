package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripExpenseShare;
import com.travelos.entity.TripMember;
import com.travelos.entity.User;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripExpenseShareMapper;
import com.travelos.mapper.TripMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TripExpenseService {

  private static final List<String> CATEGORIES = List.of("transport", "hotel", "food", "ticket",
      "parking", "shopping", "maintenance", "activity", "other");
  private static final Map<String, String> CATEGORY_LABELS = Map.of(
      "transport", "油费/交通", "hotel", "住宿", "food", "餐饮", "ticket", "门票",
      "parking", "停车", "shopping", "购物", "maintenance", "维修", "activity", "活动", "other", "其他");

  private final TripExpenseMapper expenseMapper;
  private final TripExpenseShareMapper shareMapper;
  private final TripMemberService tripMemberService;
  private final TripMapper tripMapper;
  private final com.travelos.mapper.UserMapper userMapper;

  public TripExpenseService(TripExpenseMapper expenseMapper, TripExpenseShareMapper shareMapper,
      TripMemberService tripMemberService, TripMapper tripMapper,
      com.travelos.mapper.UserMapper userMapper) {
    this.expenseMapper = expenseMapper;
    this.shareMapper = shareMapper;
    this.tripMemberService = tripMemberService;
    this.tripMapper = tripMapper;
    this.userMapper = userMapper;
  }

  public Map<String, Object> findAll(String tripId, String userId) {
    tripMemberService.requireTripMember(tripId, userId);
    List<TripExpense> expenses = findExpenses(tripId);
    List<Map<String, Object>> members = getMembers(tripId);
    List<Map<String, Object>> expenseMaps = expenses.stream().map(this::toExpenseResponse).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expenses", expenseMaps);
    body.put("summary", buildSummary(expenses, members));
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
    String payerUserId = requiredString(dto.get("payerUserId"), "payerUserId should not be empty");
    List<Map<String, String>> shares = normalizeShares(tripId, payerUserId, amountCents, dto, List.of());

    TripExpense expense = new TripExpense();
    expense.setTripId(tripId);
    expense.setPayerUserId(payerUserId);
    expense.setTitle(requiredString(dto.get("title"), "title should not be empty").trim());
    expense.setAmount(centsToDecimal(amountCents));
    expense.setCurrency(optionalString(dto.get("currency")) == null ? "CNY"
        : dto.get("currency").toString().trim());
    expense.setCategory(optionalString(dto.get("category")) == null ? "other"
        : dto.get("category").toString());
    expense.setSpentAt(DateUtil.parse(requiredString(dto.get("spentAt"), "spentAt should not be empty")));
    expense.setNotes(toNullable(dto.get("notes")));
    expense.setCreatedAt(new Date());
    expense.setUpdatedAt(new Date());
    expenseMapper.insert(expense);
    for (Map<String, String> share : shares) {
      insertShare(expense.getId(), share.get("userId"), share.get("shareAmount"));
    }
    return Map.of("expense", expenseMapper.selectById(expense.getId()));
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
    List<Map<String, String>> shares = normalizeShares(tripId, payerUserId, amountCents, dto,
        existingShares);

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
    expense.setUpdatedAt(new Date());
    expenseMapper.updateById(expense);

    shareMapper.delete(new LambdaQueryWrapper<TripExpenseShare>()
        .eq(TripExpenseShare::getExpenseId, expenseId));
    for (Map<String, String> share : shares) {
      insertShare(expenseId, share.get("userId"), share.get("shareAmount"));
    }
    return Map.of("expense", expenseMapper.selectById(expenseId));
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
    return Map.of("success", true);
  }

  private List<Map<String, String>> normalizeShares(String tripId, String payerUserId,
      long amountCents, Map<String, Object> dto, List<Map<String, String>> fallbackShares) {
    List<Map<String, String>> shares = null;

    if (dto.get("shares") instanceof List<?> rawShares) {
      shares = new ArrayList<>();
      for (Object item : rawShares) {
        if (item instanceof Map<?, ?> share) {
          shares.add(Map.of("userId", share.get("userId").toString(),
              "shareAmount", share.get("shareAmount").toString()));
        }
      }
    }
    if (shares == null && dto.get("shareUserIds") instanceof List<?> rawUserIds) {
      shares = calculateShares(amountCents,
          rawUserIds.stream().map(Object::toString).toList());
    }
    if (shares == null) {
      shares = new ArrayList<>(fallbackShares);
    }
    if (shares.isEmpty()) {
      throw ApiException.badRequest("分摊金额不能为空");
    }
    boolean hasPayer = shares.stream().anyMatch(share -> share.get("userId").equals(payerUserId));
    if (!hasPayer) {
      shares = new ArrayList<>(shares);
      shares.add(Map.of("userId", payerUserId, "shareAmount", "0.00"));
    }
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, String> share : shares) {
      if (!seen.add(share.get("userId"))) {
        throw ApiException.badRequest("分摊成员不能重复");
      }
    }
    validateExpenseMembers(tripId, payerUserId,
        shares.stream().map(share -> share.get("userId")).toList());

    List<Map<String, String>> normalized = new ArrayList<>();
    long shareTotalCents = 0;
    for (Map<String, String> share : shares) {
      long cents = toCents(share.get("shareAmount"), true);
      shareTotalCents += cents;
      normalized.add(Map.of("userId", share.get("userId"), "shareAmount",
          String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0)));
    }
    if (shareTotalCents != amountCents) {
      throw ApiException.badRequest("分摊金额之和必须等于消费总额");
    }
    return normalized;
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

  private List<Map<String, String>> calculateShares(long amountCents, List<String> userIds) {
    List<String> uniqueUserIds = new ArrayList<>(new LinkedHashSet<>(userIds));
    long base = amountCents / uniqueUserIds.size();
    long allocated = 0;
    List<Map<String, String>> shares = new ArrayList<>();
    for (int index = 0; index < uniqueUserIds.size(); index++) {
      long cents = index == uniqueUserIds.size() - 1 ? amountCents - allocated : base;
      allocated += cents;
      shares.add(Map.of("userId", uniqueUserIds.get(index),
          "shareAmount", String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0)));
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

  private String requiredString(Object value, String message) {
    if (value == null || value.toString().isBlank()) {
      throw ApiException.badRequest(message);
    }
    return value.toString();
  }

  private String toNullable(Object value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.toString().trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
