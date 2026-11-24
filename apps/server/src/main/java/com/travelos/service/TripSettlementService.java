package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.JsonUtil;
import com.travelos.common.Payloads;
import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripSettlement;
import com.travelos.entity.User;
import com.travelos.mapper.TripSettlementMapper;
import com.travelos.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;

import static com.travelos.service.TripExpenseService.centsToString;
import static com.travelos.service.TripExpenseService.normalizeCurrency;
import static com.travelos.service.TripExpenseService.toCentsQuiet;

@Service
public class TripSettlementService {
  private final TripExpenseService expenses;
  private final TripSettlementMapper mapper;
  private final TripMemberService members;
  private final UserMapper users;
  private final NotificationService notifications;
  private final AuditService audit;

  public TripSettlementService(TripExpenseService expenses, TripSettlementMapper mapper,
      TripMemberService members, UserMapper users, NotificationService notifications, AuditService audit) {
    this.expenses = expenses;
    this.mapper = mapper;
    this.members = members;
    this.users = users;
    this.notifications = notifications;
    this.audit = audit;
  }

  private record Balance(long paid, long owed) {
    Balance minus(Balance base) {
      return new Balance(paid - base.paid, owed - base.owed);
    }
  }

  private record Entry(String id, long spentAt, String payer, long amount, Map<String, Long> shares) {
  }

  private record History(TripSettlement row, String currency, Map<String, Object> snapshot) {
    boolean legacy() {
      return row.getPreviewToken() == null;
    }
  }

  private record Plan(String currency, History previous, Map<String, Object> preview,
      Map<String, Balance> current, List<Entry> entries) {
    String token() {
      return (String) preview.get("previewToken");
    }

    boolean canConfirm() {
      return Boolean.TRUE.equals(preview.get("canConfirm"));
    }
  }

  private static final Balance ZERO = new Balance(0, 0);

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> preview(String tripId, String userId) {
    members.requireTripMember(tripId, userId);
    List<History> history = history(tripId);
    Map<String, Plan> plans = plans(tripId, history);
    Map<String, Object> groups = new LinkedHashMap<>();
    plans.forEach((currency, plan) -> groups.put(currency, plan.preview));
    Map<String, Object> body = new LinkedHashMap<>();
    if (plans.isEmpty()) {
      body.put("currency", "CNY");
      body.put("totalAmount", "0.00");
      body.put("nets", List.of());
      body.put("transfers", List.of());
    } else {
      body.putAll(plans.values().iterator().next().preview);
    }
    body.put("byCurrency", groups);
    body.put("currencies", groups.keySet());
    body.put("multiCurrency", groups.size() > 1);
    // Callers without a currency may still confirm a single-currency trip.
    body.put("canConfirm", plans.size() == 1 && plans.values().iterator().next().canConfirm());
    if (plans.size() > 1) {
      body.put("confirmBlockedReason", "行程包含多种币种，请选择需要确认的币种。");
    }
    body.put("latestSettlement", history.isEmpty() ? null : expenses.settlementMap(history.get(0).row, null));
    body.put("history", history.stream().map(item -> expenses.settlementMap(item.row, null)).toList());
    return body;
  }

  @Transactional
  public Map<String, Object> confirm(String tripId, String userId, Map<String, Object> dto) {
    members.requireTripRole(tripId, userId, List.of("owner", "admin"));
    Trip trip = expenses.lockLedger(tripId);
    String currency = Payloads.trimmedOrNull(dto.get("currency"));
    String token = Payloads.trimmedOrNull(dto.get("previewToken"));
    List<History> history = history(tripId);
    if (currency != null) {
      currency = normalizeCurrency(currency);
      if (token == null) {
        throw ApiException.badRequest("请先预览所选币种的结算账单");
      }
      for (History item : history) {
        if (currency.equals(item.currency) && token.equals(item.row.getPreviewToken())) {
          return Map.of("settlement", expenses.settlementMap(item.row, trip.getTitle()));
        }
      }
    }
    Map<String, Plan> plans = plans(tripId, history);
    if (currency == null) {
      if (plans.size() != 1) {
        throw ApiException.badRequest(plans.isEmpty() ? "当前没有需要结算的账目" : "行程包含多种币种，请选择需要确认的币种");
      }
      currency = plans.keySet().iterator().next();
    }
    Plan plan = plans.get(currency);
    if (token != null && (plan == null || !plan.token().equals(token))) {
      throw ApiException.conflict("账目已变化，请刷新结算预览后重新确认");
    }
    if (plan == null) {
      throw ApiException.badRequest("当前没有需要结算的账目");
    }
    if (!plan.canConfirm()) {
      String message = (String) plan.preview.get("confirmBlockedReason");
      if (message != null) {
        throw ApiException.conflict(message);
      }
      if (token == null && plan.previous != null) {
        return Map.of("settlement", expenses.settlementMap(plan.previous.row, trip.getTitle()));
      }
      throw ApiException.badRequest("当前没有新增的账目差额");
    }
    if (plan.previous != null && plan.previous.legacy() && !Boolean.TRUE.equals(dto.get("acceptLegacyBaseline"))) {
      throw ApiException.conflict("请核对旧账单作为差额起点后重新确认");
    }

    Map<String, Object> snapshot = new LinkedHashMap<>(plan.preview);
    snapshot.remove("previewToken");
    snapshot.remove("canConfirm");
    snapshot.remove("confirmBlockedReason");
    snapshot.remove("legacyBaseline");
    snapshot.put("ledgerEntries", plan.entries);
    snapshot.put("ledgerBalances", plan.current.entrySet().stream().map(e -> Map.of(
        "userId", e.getKey(), "paid", centsToString(e.getValue().paid),
        "owed", centsToString(e.getValue().owed))).toList());
    snapshot.put("confirmedAt", new Date());
    snapshot.put("confirmedBy", userId);

    TripSettlement settlement = new TripSettlement();
    settlement.setTripId(tripId);
    settlement.setCurrency(currency);
    settlement.setPreviewToken(plan.token());
    settlement.setSnapshot(JsonUtil.write(snapshot));
    settlement.setStatus("confirmed");
    settlement.setConfirmedBy(userId);
    mapper.insert(settlement);

    for (var member : members.listMembers(tripId)) {
      notifications.createSettlementConfirmed(member.getUserId(), tripId, trip.getTitle(),
          "旅行「" + trip.getTitle() + "」的 " + currency + " 账单已确认，可查看本期差额及转账建议。");
    }
    audit.record(userId, tripId, "settlement_confirmed", "trip", tripId,
        Map.of("totalAmount", plan.preview.get("totalAmount"), "currency", currency,
            "transfers", ((List<?>) plan.preview.get("transfers")).size()));
    return Map.of("settlement", expenses.settlementMap(settlement, trip.getTitle()));
  }

  private List<History> history(String tripId) {
    return mapper.selectList(new LambdaQueryWrapper<TripSettlement>().eq(TripSettlement::getTripId, tripId)
            .orderByDesc(TripSettlement::getCreatedAt, TripSettlement::getId)).stream()
        .map(row -> {
          Map<String, Object> snapshot = JsonUtil.readObject(row.getSnapshot());
          String currency = row.getCurrency() != null ? row.getCurrency() : Payloads.trimmedOrNull(snapshot.get("currency"));
          return new History(row, currency == null ? null : normalizeCurrency(currency), snapshot);
        }).toList();
  }

  private Map<String, Plan> plans(String tripId, List<History> history) {
    Map<String, List<Entry>> entries = new LinkedHashMap<>();
    for (TripExpense expense : expenses.findExpenses(tripId)) {
      Map<String, Long> shares = new TreeMap<>();
      for (var share : expenses.sharesOf(expense.getId())) {
        shares.merge(share.getUserId(), toCentsQuiet(share.getShareAmount()), Long::sum);
      }
      Entry item = new Entry(expense.getId(), expense.getSpentAt().getTime(),
          expense.getPayerUserId(), toCentsQuiet(expense.getAmount()), shares);
      entries.computeIfAbsent(normalizeCurrency(expense.getCurrency()), key -> new ArrayList<>()).add(item);
    }
    Map<String, History> latest = new LinkedHashMap<>();
    for (History item : history) {
      if (item.currency != null) {
        latest.putIfAbsent(item.currency, item);
        entries.computeIfAbsent(item.currency, key -> new ArrayList<>());
      }
    }
    // An unlabeled legacy snapshot may include this currency. Preserve it and require reconciliation.
    boolean unlabeledLegacy = history.stream().anyMatch(item -> item.legacy() && item.currency == null);
    Map<String, String> names = new LinkedHashMap<>();
    for (Map<String, Object> member : expenses.getMembers(tripId)) {
      names.put(member.get("userId").toString(), displayName(member));
    }
    Map<String, Plan> plans = new LinkedHashMap<>();
    for (var group : entries.entrySet()) {
      String currency = group.getKey();
      History previous = latest.get(currency);
      List<Entry> items = group.getValue();
      items.sort(Comparator.comparing(Entry::id));
      plans.put(currency, buildPlan(tripId, currency, previous, items, names, unlabeledLegacy));
    }
    return plans;
  }

  private Plan buildPlan(String tripId, String currency, History previous, List<Entry> items,
      Map<String, String> names, boolean unlabeledLegacy) {
    Map<String, Balance> current = new TreeMap<>();
    for (Entry item : items) {
      current.merge(item.payer, new Balance(item.amount, 0), TripSettlementService::addBalance);
      item.shares.forEach((user, cents) ->
          current.merge(user, new Balance(0, cents), TripSettlementService::addBalance));
    }
    Map<String, Balance> baseline = previous == null ? Map.of()
        : balancesFrom(previous.snapshot.get(previous.legacy() ? "nets" : "ledgerBalances"));
    Set<String> ids = new TreeSet<>(current.keySet());
    ids.addAll(baseline.keySet());
    ids.addAll(names.keySet());
    Map<String, String> displayNames = new LinkedHashMap<>(names);
    if (previous != null && previous.snapshot.get("nets") instanceof List<?> historic) {
      for (Object item : historic) {
        if (item instanceof Map<?, ?> row && row.get("userId") instanceof String user) {
          displayNames.putIfAbsent(user, displayName(row));
        }
      }
    }
    List<String> unknown = ids.stream().filter(id -> !displayNames.containsKey(id)).toList();
    if (!unknown.isEmpty()) {
      for (User user : users.selectBatchIds(unknown)) {
        displayNames.put(user.getId(), user.getDisplayName() == null ? user.getUsername() : user.getDisplayName());
      }
    }

    var debtors = new ArrayList<TransferSide>();
    var creditors = new ArrayList<TransferSide>();
    List<Map<String, Object>> nets = new ArrayList<>();
    long totalPaid = 0, totalOwed = 0;
    boolean changed = false;
    for (String id : ids) {
      Balance balance = current.getOrDefault(id, ZERO).minus(baseline.getOrDefault(id, ZERO));
      totalPaid += balance.paid;
      totalOwed += balance.owed;
      long net = balance.paid - balance.owed;
      String name = displayNames.getOrDefault(id, "历史参与者");
      nets.add(Map.of("userId", id, "displayName", name, "paid", centsToString(balance.paid),
          "owed", centsToString(balance.owed), "net", centsToString(net), "formerMember", !names.containsKey(id)));
      if (net > 0) {
        creditors.add(new TransferSide(id, name, net));
      } else if (net < 0) {
        debtors.add(new TransferSide(id, name, -net));
      }
      changed |= !balance.equals(ZERO);
    }

    String reason = null;
    if (totalPaid != totalOwed || (previous != null && !validBaseline(previous, baseline))) {
      reason = "历史账目金额与分摊无法对平，请先核对旧账单";
    } else if (unlabeledLegacy && previous == null) {
      reason = "有未标记币种的旧账单，请先核对历史账单的币种和归属";
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("currency", currency);
    result.put("totalAmount", centsToString(totalPaid));
    result.put("nets", nets);
    result.put("transfers", reason == null ? transfers(currency, debtors, creditors) : List.of());
    result.put("canConfirm", changed && reason == null);
    result.put("confirmBlockedReason", reason);
    result.put("baseSettlementId", previous == null ? null : previous.row.getId());
    result.put("legacyBaseline", previous != null && previous.legacy());
    result.put("expenseIds", items.stream().map(Entry::id).toList());
    result.put("periodStart", items.stream().mapToLong(Entry::spentAt).min().stream().mapToObj(Date::new).findFirst().orElse(null));
    result.put("periodEnd", items.stream().mapToLong(Entry::spentAt).max().stream().mapToObj(Date::new).findFirst().orElse(null));
    result.put("previewToken", fingerprint(tripId, currency, previous, items));
    return new Plan(currency, previous, result, current, items);
  }

  private static Balance addBalance(Balance left, Balance right) {
    return new Balance(left.paid + right.paid, left.owed + right.owed);
  }

  private static Map<String, Balance> balancesFrom(Object value) {
    Map<String, Balance> balances = new TreeMap<>();
    if (value instanceof List<?> rows) {
      for (Object item : rows) {
        if (item instanceof Map<?, ?> row && row.get("userId") instanceof String id
            && row.get("paid") != null && row.get("owed") != null) {
          try {
            balances.put(id, new Balance(parseCents(row.get("paid")), parseCents(row.get("owed"))));
          } catch (IllegalArgumentException | ArithmeticException ex) {
            return Map.of();
          }
        }
      }
    }
    return balances;
  }

  private static boolean validBaseline(History history, Map<String, Balance> baseline) {
    if (baseline.isEmpty() && history.legacy()) {
      return false;
    }
    long paid = baseline.values().stream().mapToLong(Balance::paid).sum();
    long owed = baseline.values().stream().mapToLong(Balance::owed).sum();
    if (paid != owed) {
      return false;
    }
    try {
      return !history.legacy() || paid == parseCents(history.snapshot.get("totalAmount"));
    } catch (IllegalArgumentException | ArithmeticException ex) {
      return false;
    }
  }

  private static long parseCents(Object value) {
    return toCentsQuiet(new BigDecimal(Objects.toString(value, "")));
  }

  private static String displayName(Map<?, ?> user) {
    String name = Payloads.trimmedOrNull(user.get("displayName"));
    if (name == null) {
      name = Payloads.trimmedOrNull(user.get("username"));
    }
    return name == null ? "成员" : name;
  }

  private static String fingerprint(String tripId, String currency, History previous, List<Entry> entries) {
    List<Object> state = List.of(tripId, currency, previous == null ? "" : previous.row.getId(), entries);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(JsonUtil.write(state).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private record TransferSide(String id, String name, long cents) {
  }

  private static List<Map<String, Object>> transfers(String currency, List<TransferSide> debtors,
      List<TransferSide> creditors) {
    var byAmount = Comparator.comparingLong(TransferSide::cents).reversed().thenComparing(TransferSide::id);
    debtors.sort(byAmount);
    creditors.sort(byAmount);
    List<Map<String, Object>> transfers = new ArrayList<>();
    int from = 0, to = 0;
    while (from < debtors.size() && to < creditors.size()) {
      TransferSide debtor = debtors.get(from);
      TransferSide creditor = creditors.get(to);
      long amount = Math.min(debtor.cents, creditor.cents);
      transfers.add(Map.of("fromUserId", debtor.id, "fromDisplayName", debtor.name,
          "toUserId", creditor.id, "toDisplayName", creditor.name,
          "amount", centsToString(amount), "currency", currency));
      if (debtor.cents == amount) {
        from++;
      } else {
        debtors.set(from, new TransferSide(debtor.id, debtor.name, debtor.cents - amount));
      }
      if (creditor.cents == amount) {
        to++;
      } else {
        creditors.set(to, new TransferSide(creditor.id, creditor.name, creditor.cents - amount));
      }
    }
    return transfers;
  }
}
