package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripExpenseShare;
import com.travelos.entity.TripMember;
import com.travelos.entity.TripPlace;
import com.travelos.entity.User;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripExpenseShareMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

@Service
public class TravelStatsService {

  private static final String PRIMARY_CURRENCY = "CNY";
  /** 批量 IN 参数的单批上限：常规用户远小于此值（单批）；大批量时查询次数随批次数线性增长。 */
  private static final int BATCH_SIZE = 500;
  private static final List<String> MONTH_LABELS = List.of("一月", "二月", "三月", "四月", "五月", "六月",
      "七月", "八月", "九月", "十月", "十一月", "十二月");
  private static final Map<String, String> CATEGORY_LABELS = Map.of(
      "transport", "油费/交通", "hotel", "住宿", "food", "餐饮", "ticket", "门票",
      "parking", "停车", "shopping", "购物", "maintenance", "维修", "activity", "活动", "other", "其他");
  private static final List<String> CATEGORIES = List.of("transport", "hotel", "food", "ticket",
      "parking", "shopping", "maintenance", "activity", "other");

  private final TripMemberMapper tripMemberMapper;
  private final TripMapper tripMapper;
  private final TripDayMapper tripDayMapper;
  private final TripExpenseMapper expenseMapper;
  private final TripExpenseShareMapper shareMapper;
  private final UserMapper userMapper;
  private final TripPlaceMapper tripPlaceMapper;

  public TravelStatsService(TripMemberMapper tripMemberMapper, TripMapper tripMapper,
      TripDayMapper tripDayMapper, TripExpenseMapper expenseMapper,
      TripExpenseShareMapper shareMapper, UserMapper userMapper,
      TripPlaceMapper tripPlaceMapper) {
    this.tripMemberMapper = tripMemberMapper;
    this.tripMapper = tripMapper;
    this.tripDayMapper = tripDayMapper;
    this.expenseMapper = expenseMapper;
    this.shareMapper = shareMapper;
    this.userMapper = userMapper;
    this.tripPlaceMapper = tripPlaceMapper;
  }

  /** 足迹地图：按目的地聚合用户全部未删除行程（城市/次数/天数/地点数/平均坐标/首末到访）。 */
  public Map<String, Object> footprints(String userId) {
    List<StatsTrip> trips = findTripsForUser(userId);
    // 批量读取地点（仅目的地名非空的行程需要），按行程分组，消除逐行程查询
    List<String> destinationTripIds = new ArrayList<>();
    for (StatsTrip statsTrip : trips) {
      Trip trip = statsTrip.trip();
      if (trip.getDestination() != null && !trip.getDestination().isBlank()) {
        destinationTripIds.add(trip.getId());
      }
    }
    Map<String, List<TripPlace>> placesByTrip = placesByTrip(destinationTripIds);
    Map<String, Map<String, Object>> byCity = new LinkedHashMap<>();
    int totalDays = 0;
    int totalPlaces = 0;
    for (StatsTrip statsTrip : trips) {
      Trip trip = statsTrip.trip();
      if (trip.getDestination() == null || trip.getDestination().isBlank()) {
        continue;
      }
      List<TripPlace> places = placesByTrip.getOrDefault(trip.getId(), List.of());
      totalDays += statsTrip.days().size();
      totalPlaces += places.size();
      double latSum = 0;
      double lngSum = 0;
      int coordCount = 0;
      for (TripPlace place : places) {
        if (place.getLatitude() != null && place.getLongitude() != null) {
          latSum += place.getLatitude().doubleValue();
          lngSum += place.getLongitude().doubleValue();
          coordCount++;
        }
      }
      if (coordCount == 0) {
        continue; // 无坐标无法上图（纯计划行程）
      }
      String key = trip.getDestination().trim();
      Map<String, Object> city = byCity.computeIfAbsent(key, k -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destination", k);
        m.put("tripCount", 0);
        m.put("totalDays", 0);
        m.put("placeCount", 0);
        m.put("lat", 0.0);
        m.put("lng", 0.0);
        m.put("coordCount", 0);
        m.put("firstDate", null);
        m.put("lastDate", null);
        return m;
      });
      city.put("tripCount", (int) city.get("tripCount") + 1);
      city.put("totalDays", (int) city.get("totalDays") + statsTrip.days().size());
      city.put("placeCount", (int) city.get("placeCount") + places.size());
      city.put("lat", (double) city.get("lat") + latSum);
      city.put("lng", (double) city.get("lng") + lngSum);
      city.put("coordCount", (int) city.get("coordCount") + coordCount);
      Date tripStart = trip.getStartDate();
      Date tripEnd = trip.getEndDate() != null ? trip.getEndDate() : trip.getStartDate();
      if (city.get("firstDate") == null
          || (tripStart != null && tripStart.before((Date) city.get("firstDate")))) {
        city.put("firstDate", tripStart);
      }
      if (city.get("lastDate") == null
          || (tripEnd != null && tripEnd.after((Date) city.get("lastDate")))) {
        city.put("lastDate", tripEnd);
      }
    }
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    List<Map<String, Object>> cities = new ArrayList<>();
    for (Map<String, Object> city : byCity.values()) {
      int coordCount = (int) city.get("coordCount");
      city.put("lat", Math.round((double) city.get("lat") / coordCount * 10000.0) / 10000.0);
      city.put("lng", Math.round((double) city.get("lng") / coordCount * 10000.0) / 10000.0);
      city.remove("coordCount");
      Object first = city.get("firstDate");
      Object last = city.get("lastDate");
      city.put("firstDate", first == null ? null : format.format((Date) first));
      city.put("lastDate", last == null ? null : format.format((Date) last));
      cities.add(city);
    }
    // 按首次到访排序（足迹时间线）
    cities.sort((a, b) -> String.valueOf(a.get("firstDate"))
        .compareTo(String.valueOf(b.get("firstDate"))));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cities", cities);
    // Map.of 键序不定 → 响应键序跨运行漂移；LinkedHashMap 固定输出顺序（键集与值不变，契约兼容）
    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("cityCount", cities.size());
    totals.put("tripCount", trips.size());
    totals.put("totalDays", totalDays);
    totals.put("totalPlaces", totalPlaces);
    body.put("totals", totals);
    return body;
  }

  public Map<String, Object> getOverview(String userId) {
    List<StatsTrip> trips = findTripsForUser(userId);
    Map<String, Object> body = new LinkedHashMap<>(buildSummary(trips));
    body.put("availableYears", getAvailableYears(trips));
    body.put("yearlyComparison", buildYearlyComparison(trips));
    return body;
  }

  public Map<String, Object> getYearly(String userId, String yearValue) {
    int year = parseYear(yearValue);
    // 年份在读取阶段前置筛选（口径不变：行程开始日期的 UTC 年份），行程天/费用只加载该年份行程
    return buildFiltered(year, buildSummary(findTripsForUser(userId, year)));
  }

  public Map<String, Object> getMonthly(String userId, String yearValue) {
    int year = parseYear(yearValue);
    List<StatsTrip> trips = findTripsForUser(userId, year);
    List<Map<String, Object>> months = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      final int monthIndex = index;
      List<StatsTrip> monthTrips = trips.stream()
          .filter(trip -> trip.trip().getStartDate() != null
              && monthOf(trip.trip().getStartDate()) == monthIndex)
          .toList();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("month", index + 1);
      row.put("label", MONTH_LABELS.get(index));
      row.put("tripCount", monthTrips.size());
      row.put("totalDays", sumTripDays(monthTrips));
      row.put("totalExpenseAmount", centsToString(sumExpenses(monthTrips)));
      months.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("year", year);
    body.put("primaryCurrency", PRIMARY_CURRENCY);
    body.put("months", months);
    return body;
  }

  public Map<String, Object> getExpenseCategories(String userId) {
    List<StatsTrip> trips = findTripsForUser(userId);
    Map<String, Long> totals = new LinkedHashMap<>();
    for (String category : CATEGORIES) {
      totals.put(category, 0L);
    }
    long total = 0;
    for (StatsTrip statsTrip : trips) {
      for (TripExpense expense : statsTrip.expenses()) {
        if (!PRIMARY_CURRENCY.equals(expense.getCurrency())) {
          continue;
        }
        long cents = Math.round(expense.getAmount().doubleValue() * 100);
        totals.merge(expense.getCategory(), cents, Long::sum);
        total += cents;
      }
    }
    List<Map<String, Object>> categories = new ArrayList<>();
    for (String category : CATEGORIES) {
      long amount = totals.get(category);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("category", category);
      row.put("label", CATEGORY_LABELS.get(category));
      row.put("amount", centsToString(amount));
      row.put("percentage", total > 0
          ? BigDecimal.valueOf(amount * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue()
          : 0);
      categories.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("primaryCurrency", PRIMARY_CURRENCY);
    body.put("totalExpenseAmount", centsToString(total));
    // P2-3 币种口径：非 CNY 花费单列透出，避免"外币花费凭空消失"
    List<Map<String, Object>> foreign = foreignCurrencySummary(trips);
    body.put("foreignCurrency", foreign);
    body.put("hasForeignCurrency", !foreign.isEmpty());
    body.put("categories", categories);
    return body;
  }

  private Map<String, Object> buildSummary(List<StatsTrip> trips) {
    long totalDays = sumTripDays(trips);
    long totalExpenseCents = sumExpenses(trips);
    Map<String, Integer> destinationCounts = new LinkedHashMap<>();
    int[] monthCounts = new int[12];
    for (StatsTrip statsTrip : trips) {
      Trip trip = statsTrip.trip();
      String destination = trip.getDestination() == null ? null : trip.getDestination().trim();
      if (destination != null && !destination.isEmpty()) {
        destinationCounts.merge(destination.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
      }
      if (trip.getStartDate() != null) {
        monthCounts[monthOf(trip.getStartDate())] += 1;
      }
    }
    String mostVisitedLabel = null;
    int mostVisitedCount = 0;
    for (Map.Entry<String, Integer> entry : destinationCounts.entrySet()) {
      if (entry.getValue() > mostVisitedCount) {
        mostVisitedCount = entry.getValue();
        mostVisitedLabel = entry.getKey();
      }
    }
    // recover original casing label
    String mostVisitedOriginal = null;
    for (StatsTrip statsTrip : trips) {
      String destination = statsTrip.trip().getDestination() == null ? null
          : statsTrip.trip().getDestination().trim();
      if (destination != null && mostVisitedLabel != null
          && destination.toLowerCase(java.util.Locale.ROOT).equals(mostVisitedLabel)) {
        mostVisitedOriginal = destination;
        break;
      }
    }
    int busiestMonthCount = 0;
    int busiestMonthIndex = -1;
    for (int index = 0; index < 12; index++) {
      if (monthCounts[index] > busiestMonthCount) {
        busiestMonthCount = monthCounts[index];
        busiestMonthIndex = index;
      }
    }
    long tripCount = trips.size();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("primaryCurrency", PRIMARY_CURRENCY);
    body.put("tripCount", tripCount);
    body.put("totalDays", totalDays);
    body.put("totalExpenseAmount", centsToString(totalExpenseCents));
    // P2-3 币种口径：非 CNY 花费未折算、不并入主口径，显式透出让前端提示"另有外币未计入"
    List<Map<String, Object>> foreign = foreignCurrencySummary(trips);
    body.put("foreignCurrency", foreign);
    body.put("hasForeignCurrency", !foreign.isEmpty());
    body.put("destinationCount", destinationCounts.size());
    body.put("averageTripDays", tripCount > 0
        ? BigDecimal.valueOf(totalDays * 1.0 / tripCount).setScale(1, RoundingMode.HALF_UP).doubleValue()
        : 0);
    body.put("averageExpensePerTrip", tripCount > 0
        ? centsToString(totalExpenseCents / tripCount)
        : "0.00");
    Map<String, Object> mostVisited = new LinkedHashMap<>();
    mostVisited.put("label", mostVisitedOriginal);
    mostVisited.put("count", mostVisitedCount);
    body.put("mostVisitedDestination", mostVisited);
    Map<String, Object> busiestMonth = new LinkedHashMap<>();
    busiestMonth.put("month", busiestMonthIndex + 1);
    busiestMonth.put("label", busiestMonthIndex >= 0 ? MONTH_LABELS.get(busiestMonthIndex) : null);
    busiestMonth.put("count", busiestMonthCount);
    body.put("busiestMonth", busiestMonth);
    return body;
  }

  /** 预算 vs 已花：逐趟行程汇总总预算与 CNY 花费，>=80% 提示 warn，>=100% 提示 over。 */
  public Map<String, Object> getBudgetOverview(String userId) {
    List<StatsTrip> trips = findTripsForUser(userId);
    List<Map<String, Object>> rows = new ArrayList<>();
    long totalBudget = 0;
    long totalSpent = 0;
    long warnCount = 0;
    long overCount = 0;
    for (StatsTrip statsTrip : trips) {
      Trip trip = statsTrip.trip();
      long budgetCents = trip.getBudget() == null ? 0L : Math.round(trip.getBudget().doubleValue() * 100);
      long spentCents = 0;
      for (TripExpense expense : statsTrip.expenses()) {
        if (PRIMARY_CURRENCY.equals(expense.getCurrency())) {
          spentCents += Math.round(expense.getAmount().doubleValue() * 100);
        }
      }
      if (budgetCents <= 0 && spentCents <= 0) {
        continue;
      }
      double percentage = budgetCents > 0 ? spentCents * 100.0 / budgetCents : 0;
      String status = budgetCents <= 0 ? "no_budget"
          : percentage >= 100 ? "over"
          : percentage >= 80 ? "warn"
          : "ok";
      if ("over".equals(status)) {
        overCount++;
      } else if ("warn".equals(status)) {
        warnCount++;
      }
      if (budgetCents > 0) {
        totalBudget += budgetCents;
      }
      totalSpent += spentCents;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("tripId", trip.getId());
      row.put("title", trip.getTitle());
      row.put("destination", trip.getDestination());
      row.put("startDate", trip.getStartDate());
      row.put("tripStatus", trip.getStatus());
      row.put("budgetStatus", status);
      row.put("budget", budgetCents > 0 ? centsToString(budgetCents) : null);
      row.put("spent", centsToString(spentCents));
      row.put("remaining", budgetCents > 0 ? centsToString(budgetCents - spentCents) : null);
      row.put("percentage", BigDecimal.valueOf(percentage).setScale(1, RoundingMode.HALF_UP).doubleValue());
      rows.add(row);
    }
    rows.sort((left, right) -> {
      int a = statusRank(String.valueOf(left.get("budgetStatus")));
      int b = statusRank(String.valueOf(right.get("budgetStatus")));
      if (a != b) {
        return Integer.compare(a, b);
      }
      int cmp = Double.compare(
          ((Number) right.get("percentage")).doubleValue(),
          ((Number) left.get("percentage")).doubleValue());
      if (cmp != 0) return cmp;
      // 稳定 tie-breaker：tripId 升序（雪花 id 时间有序，创建早的在前）
      return String.valueOf(left.get("tripId")).compareTo(String.valueOf(right.get("tripId")));
    });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("primaryCurrency", PRIMARY_CURRENCY);
    body.put("totalBudget", totalBudget > 0 ? centsToString(totalBudget) : null);
    body.put("totalSpent", centsToString(totalSpent));
    body.put("totalPercentage", totalBudget > 0
        ? BigDecimal.valueOf(totalSpent * 100.0 / totalBudget).setScale(1, RoundingMode.HALF_UP).doubleValue()
        : 0);
    body.put("warnCount", warnCount);
    body.put("overCount", overCount);
    body.put("trips", rows);
    return body;
  }

  private int statusRank(String status) {
    return switch (status) {
      case "over" -> 0;
      case "warn" -> 1;
      case "no_budget" -> 3;
      default -> 2;
    };
  }

  /** 结算建议：把用户名下的每趟行程按费用分摊算净额，给出涉及该用户的转账建议。 */
  public Map<String, Object> getSettlementSuggestions(String userId) {
    List<StatsTrip> trips = findTripsForUser(userId);
    // 先收集参与结算的行程与 CNY 费用，随后一次性批量读取分摊与成员展示信息，
    // 消除「每笔费用查分摊」「每行程查成员」「每成员查用户」的三层 N+1。
    List<StatsTrip> settled = new ArrayList<>();
    List<String> expenseIds = new ArrayList<>();
    for (StatsTrip statsTrip : trips) {
      Trip trip = statsTrip.trip();
      if ("archived".equals(trip.getStatus())) {
        continue;
      }
      List<TripExpense> expenses = statsTrip.expenses().stream()
          .filter(expense -> PRIMARY_CURRENCY.equals(expense.getCurrency()))
          .toList();
      if (expenses.isEmpty()) {
        continue;
      }
      settled.add(statsTrip);
      for (TripExpense expense : expenses) {
        expenseIds.add(expense.getId());
      }
    }
    Map<String, List<TripExpenseShare>> sharesByExpense = sharesByExpense(expenseIds);
    List<String> nameTripIds = new ArrayList<>();
    for (StatsTrip statsTrip : settled) {
      nameTripIds.add(statsTrip.trip().getId());
    }
    Map<String, Map<String, String>> namesByTrip = memberNamesByTrip(nameTripIds);

    List<Map<String, Object>> items = new ArrayList<>();
    long inTotal = 0;
    long outTotal = 0;
    for (StatsTrip statsTrip : settled) {
      Trip trip = statsTrip.trip();
      List<TripExpense> expenses = statsTrip.expenses().stream()
          .filter(expense -> PRIMARY_CURRENCY.equals(expense.getCurrency()))
          .toList();
      Map<String, String> names = namesByTrip.getOrDefault(trip.getId(), Map.of());
      Map<String, Long> paid = new LinkedHashMap<>();
      Map<String, Long> owed = new LinkedHashMap<>();
      for (TripExpense expense : expenses) {
        paid.merge(expense.getPayerUserId(), Math.round(expense.getAmount().doubleValue() * 100), Long::sum);
        for (TripExpenseShare share : sharesByExpense.getOrDefault(expense.getId(), List.of())) {
          owed.merge(share.getUserId(), Math.round(share.getShareAmount().doubleValue() * 100), Long::sum);
        }
      }
      List<String> ids = new ArrayList<>(paid.keySet());
      for (String uid : owed.keySet()) {
        if (!ids.contains(uid)) {
          ids.add(uid);
        }
      }
      List<long[]> creditors = new ArrayList<>(); // 应收
      List<long[]> debtors = new ArrayList<>();   // 应付
      for (int index = 0; index < ids.size(); index++) {
        String uid = ids.get(index);
        long net = paid.getOrDefault(uid, 0L) - owed.getOrDefault(uid, 0L);
        if (net > 0) {
          creditors.add(new long[]{index, net});
        } else if (net < 0) {
          debtors.add(new long[]{index, -net});
        }
      }
      creditors.sort((left, right) -> Long.compare(right[1], left[1]));
      debtors.sort((left, right) -> Long.compare(right[1], left[1]));
      int ci = 0;
      int di = 0;
      while (ci < creditors.size() && di < debtors.size()) {
        long[] creditor = creditors.get(ci);
        long[] debtor = debtors.get(di);
        long amount = Math.min(creditor[1], debtor[1]);
        String fromId = ids.get((int) debtor[0]);
        String toId = ids.get((int) creditor[0]);
        if (amount > 0 && (userId.equals(fromId) || userId.equals(toId))) {
          boolean incoming = userId.equals(toId);
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("tripId", trip.getId());
          row.put("tripTitle", trip.getTitle());
          row.put("direction", incoming ? "in" : "out");
          row.put("counterpartyId", incoming ? fromId : toId);
          row.put("counterpartyName", names.getOrDefault(incoming ? fromId : toId, "成员"));
          row.put("amount", centsToString(amount));
          items.add(row);
          if (incoming) {
            inTotal += amount;
          } else {
            outTotal += amount;
          }
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
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", PRIMARY_CURRENCY);
    body.put("inTotal", centsToString(inTotal));
    body.put("outTotal", centsToString(outTotal));
    body.put("suggestions", items);
    return body;
  }

  /** 批量版成员展示名：tripId → (userId → 展示名)。成员与用户各一次批量查询（分批 IN）。 */
  private Map<String, Map<String, String>> memberNamesByTrip(List<String> tripIds) {
    Map<String, Map<String, String>> result = new LinkedHashMap<>();
    if (tripIds.isEmpty()) {
      return result;
    }
    Map<String, List<TripMember>> membersByTrip = new LinkedHashMap<>();
    Set<String> userIds = new LinkedHashSet<>();
    for (List<String> batch : partition(tripIds)) {
      for (TripMember member : tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
          .in(TripMember::getTripId, batch))) {
        membersByTrip.computeIfAbsent(member.getTripId(), k -> new ArrayList<>()).add(member);
        userIds.add(member.getUserId());
      }
    }
    Map<String, User> userById = new LinkedHashMap<>();
    if (!userIds.isEmpty()) {
      for (List<String> batch : partition(new ArrayList<>(userIds))) {
        for (User user : userMapper.selectBatchIds(batch)) {
          userById.put(user.getId(), user);
        }
      }
    }
    for (Map.Entry<String, List<TripMember>> entry : membersByTrip.entrySet()) {
      Map<String, String> names = new LinkedHashMap<>();
      for (TripMember member : entry.getValue()) {
        User user = userById.get(member.getUserId());
        if (user != null) {
          String name = user.getDisplayName() == null || user.getDisplayName().isBlank()
              ? user.getUsername()
              : user.getDisplayName();
          names.put(member.getUserId(), name);
        }
      }
      result.put(entry.getKey(), names);
    }
    return result;
  }

  private List<Map<String, Object>> buildYearlyComparison(List<StatsTrip> trips) {
    List<Integer> years = new ArrayList<>(getAvailableYears(trips));
    years.sort(Integer::compareTo);
    List<Map<String, Object>> comparison = new ArrayList<>();
    for (Integer year : years) {
      List<StatsTrip> yearTrips = filterByYear(trips, year);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("year", year);
      row.put("tripCount", yearTrips.size());
      row.put("totalDays", sumTripDays(yearTrips));
      row.put("totalExpenseAmount", centsToString(sumExpenses(yearTrips)));
      comparison.add(row);
    }
    return comparison;
  }

  private List<Integer> getAvailableYears(List<StatsTrip> trips) {
    Set<Integer> years = new LinkedHashSet<>();
    for (StatsTrip statsTrip : trips) {
      if (statsTrip.trip().getStartDate() != null) {
        years.add(yearOf(statsTrip.trip().getStartDate()));
      }
    }
    return new ArrayList<>(years.stream().sorted((left, right) -> right - left).toList());
  }

  private List<StatsTrip> filterByYear(List<StatsTrip> trips, int year) {
    return trips.stream()
        .filter(trip -> trip.trip().getStartDate() != null
            && yearOf(trip.trip().getStartDate()) == year)
        .toList();
  }

  private long sumTripDays(List<StatsTrip> trips) {
    long total = 0;
    for (StatsTrip statsTrip : trips) {
      total += getTripDays(statsTrip);
    }
    return total;
  }

  private long getTripDays(StatsTrip statsTrip) {
    if (!statsTrip.days().isEmpty()) {
      Set<String> uniqueDates = new LinkedHashSet<>();
      for (TripDay day : statsTrip.days()) {
        uniqueDates.add(Fmt.dateKey(day.getDate()));
      }
      return uniqueDates.size();
    }
    Trip trip = statsTrip.trip();
    if (trip.getStartDate() == null || trip.getEndDate() == null) {
      return 0;
    }
    Date start = DateUtil.utcDay(trip.getStartDate());
    Date end = DateUtil.utcDay(trip.getEndDate());
    return Math.max(Math.floorDiv(end.getTime() - start.getTime(), 86_400_000L) + 1, 0);
  }

  private long sumExpenses(List<StatsTrip> trips) {
    long total = 0;
    for (StatsTrip statsTrip : trips) {
      for (TripExpense expense : statsTrip.expenses()) {
        if (PRIMARY_CURRENCY.equals(expense.getCurrency())) {
          total += Math.round(expense.getAmount().doubleValue() * 100);
        }
      }
    }
    return total;
  }

  /** P2-3 币种口径：按币种汇总未折算的非 CNY 花费（原币金额，不做汇率换算）。 */
  private List<Map<String, Object>> foreignCurrencySummary(List<StatsTrip> trips) {
    Map<String, Long> byCurrency = new LinkedHashMap<>();
    for (StatsTrip statsTrip : trips) {
      for (TripExpense expense : statsTrip.expenses()) {
        if (expense.getCurrency() == null || PRIMARY_CURRENCY.equals(expense.getCurrency())) {
          continue;
        }
        long cents = Math.round(expense.getAmount().doubleValue() * 100);
        byCurrency.merge(expense.getCurrency(), cents, Long::sum);
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<String, Long> entry : byCurrency.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("currency", entry.getKey());
      row.put("amount", centsToString(entry.getValue()));
      rows.add(row);
    }
    return rows;
  }

  private List<StatsTrip> findTripsForUser(String userId) {
    return findTripsForUser(userId, null);
  }

  /**
   * 批量读取当前用户可访问（未删除）行程及其行程天/费用：
   * 成员资格 1 次 → 行程批量 selectBatchIds → 行程天/费用各按行程 ID 分批 IN → 按行程 ID 分组，
   * 消除「每行程 3 查询」的 N+1；year 非空时在加载行程天/费用**之前**按行程开始日期的 UTC 年份
   * 前置筛选（与原内存 filterByYear 口径一致），空集合短路、空 IN 不会出现（分批函数跳过空表）。
   */
  private List<StatsTrip> findTripsForUser(String userId, Integer year) {
    List<TripMember> memberships = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId));
    if (memberships.isEmpty()) {
      return List.of();
    }
    List<String> tripIds = new ArrayList<>();
    for (TripMember membership : memberships) {
      tripIds.add(membership.getTripId());
    }
    Map<String, Trip> tripById = new LinkedHashMap<>();
    for (List<String> batch : partition(new ArrayList<>(new LinkedHashSet<>(tripIds)))) {
      for (Trip trip : tripMapper.selectBatchIds(batch)) {
        tripById.put(trip.getId(), trip);
      }
    }
    // 顺序与原实现一致：按成员资格逐条展开（含历史重复资格语义），再按年份口径前置过滤
    List<Trip> selected = new ArrayList<>();
    for (TripMember membership : memberships) {
      Trip trip = tripById.get(membership.getTripId());
      if (trip == null || trip.getDeletedAt() != null) {
        continue;
      }
      if (year != null && (trip.getStartDate() == null || yearOf(trip.getStartDate()) != year)) {
        continue;
      }
      selected.add(trip);
    }
    if (selected.isEmpty()) {
      return List.of();
    }
    List<String> selectedIds = new ArrayList<>();
    for (Trip trip : selected) {
      selectedIds.add(trip.getId());
    }
    Map<String, List<TripDay>> daysByTrip = new LinkedHashMap<>();
    for (List<String> batch : partition(selectedIds)) {
      for (TripDay day : tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
          .in(TripDay::getTripId, batch))) {
        daysByTrip.computeIfAbsent(day.getTripId(), k -> new ArrayList<>()).add(day);
      }
    }
    Map<String, List<TripExpense>> expensesByTrip = new LinkedHashMap<>();
    for (List<String> batch : partition(selectedIds)) {
      for (TripExpense expense : expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
          .in(TripExpense::getTripId, batch))) {
        expensesByTrip.computeIfAbsent(expense.getTripId(), k -> new ArrayList<>()).add(expense);
      }
    }
    List<StatsTrip> trips = new ArrayList<>();
    for (Trip trip : selected) {
      trips.add(new StatsTrip(trip,
          daysByTrip.getOrDefault(trip.getId(), List.of()),
          expensesByTrip.getOrDefault(trip.getId(), List.of())));
    }
    return trips;
  }

  /** 批量读取行程地点，按行程 ID 分组（分批 IN，空输入返回空映射）。 */
  private Map<String, List<TripPlace>> placesByTrip(List<String> tripIds) {
    Map<String, List<TripPlace>> byTrip = new LinkedHashMap<>();
    if (tripIds.isEmpty()) {
      return byTrip;
    }
    for (List<String> batch : partition(tripIds)) {
      for (TripPlace place : tripPlaceMapper.selectList(new LambdaQueryWrapper<TripPlace>()
          .in(TripPlace::getTripId, batch))) {
        byTrip.computeIfAbsent(place.getTripId(), k -> new ArrayList<>()).add(place);
      }
    }
    return byTrip;
  }

  /** 批量读取费用分摊，按费用 ID 分组（分批 IN，空输入返回空映射）。 */
  private Map<String, List<TripExpenseShare>> sharesByExpense(List<String> expenseIds) {
    Map<String, List<TripExpenseShare>> byExpense = new LinkedHashMap<>();
    if (expenseIds.isEmpty()) {
      return byExpense;
    }
    for (List<String> batch : partition(expenseIds)) {
      for (TripExpenseShare share : shareMapper.selectList(new LambdaQueryWrapper<TripExpenseShare>()
          .in(TripExpenseShare::getExpenseId, batch))) {
        byExpense.computeIfAbsent(share.getExpenseId(), k -> new ArrayList<>()).add(share);
      }
    }
    return byExpense;
  }

  /** IN 参数分批：避免无限增长的 IN 列表（每批 {@link #BATCH_SIZE} 条）。 */
  private static List<List<String>> partition(List<String> items) {
    List<List<String>> batches = new ArrayList<>();
    for (int i = 0; i < items.size(); i += BATCH_SIZE) {
      batches.add(new ArrayList<>(items.subList(i, Math.min(items.size(), i + BATCH_SIZE))));
    }
    return batches;
  }

  private Map<String, Object> buildFiltered(int year, Map<String, Object> summary) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("year", year);
    body.putAll(summary);
    return body;
  }

  private int parseYear(String value) {
    int year;
    try {
      year = Integer.parseInt(value == null || value.isBlank()
          ? String.valueOf(Calendar.getInstance().get(Calendar.YEAR))
          : value);
    } catch (NumberFormatException ex) {
      throw ApiException.badRequest("年份格式不正确");
    }
    if (year < 1900 || year > 2100) {
      throw ApiException.badRequest("年份格式不正确");
    }
    return year;
  }

  private int yearOf(Date date) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(date);
    return calendar.get(Calendar.YEAR);
  }

  private int monthOf(Date date) {
    Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    calendar.setTime(date);
    return calendar.get(Calendar.MONTH);
  }

  private String centsToString(long cents) {
    return String.format(java.util.Locale.ROOT, "%.2f", cents / 100.0);
  }

  private record StatsTrip(Trip trip, List<TripDay> days, List<TripExpense> expenses) {
  }
}
