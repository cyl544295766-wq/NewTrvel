package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.entity.Trip;
import com.travelos.entity.TripDay;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripMember;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

  public TravelStatsService(TripMemberMapper tripMemberMapper, TripMapper tripMapper,
      TripDayMapper tripDayMapper, TripExpenseMapper expenseMapper) {
    this.tripMemberMapper = tripMemberMapper;
    this.tripMapper = tripMapper;
    this.tripDayMapper = tripDayMapper;
    this.expenseMapper = expenseMapper;
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
    return buildFiltered(year, buildSummary(filterByYear(findTripsForUser(userId), year)));
  }

  public Map<String, Object> getMonthly(String userId, String yearValue) {
    int year = parseYear(yearValue);
    List<StatsTrip> trips = filterByYear(findTripsForUser(userId), year);
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

  private List<StatsTrip> findTripsForUser(String userId) {
    List<TripMember> memberships = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userId));
    List<StatsTrip> trips = new ArrayList<>();
    for (TripMember membership : memberships) {
      Trip trip = tripMapper.selectById(membership.getTripId());
      if (trip == null || trip.getDeletedAt() != null) {
        continue;
      }
      List<TripDay> days = tripDayMapper.selectList(new LambdaQueryWrapper<TripDay>()
          .eq(TripDay::getTripId, trip.getId()));
      List<TripExpense> expenses = expenseMapper.selectList(new LambdaQueryWrapper<TripExpense>()
          .eq(TripExpense::getTripId, trip.getId()));
      trips.add(new StatsTrip(trip, days, expenses));
    }
    return trips;
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
