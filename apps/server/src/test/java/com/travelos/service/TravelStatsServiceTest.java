package com.travelos.service;

import com.travelos.entity.Trip;
import com.travelos.entity.TripMember;
import com.travelos.mapper.TripDayMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripExpenseShareMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripPlaceMapper;
import com.travelos.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 无日期草稿行程的统计口径（单元级，Mapper 模拟，不依赖数据库）：
 * - overview 的 tripCount 统计所有可访问行程（含无开始日期的草稿）；无行程天记录时
 *   总天数回退按起止日期计算（起止为空 → 0 天，不贡献）；
 * - availableYears / yearly / monthly 只按「有开始日期」的行程（无日期草稿不进任何年份）。
 * 该口径在批量加载重构中保持不变（年份前置筛选必须保留 yearOf(null) 守卫，
 * 否则无日期草稿会在数据库前置筛选阶段被误删或抛错）。
 */
class TravelStatsServiceTest {

  private final TripMemberMapper tripMemberMapper = mock(TripMemberMapper.class);
  private final TripMapper tripMapper = mock(TripMapper.class);
  private final TripDayMapper tripDayMapper = mock(TripDayMapper.class);
  private final TripExpenseMapper expenseMapper = mock(TripExpenseMapper.class);
  private final TripExpenseShareMapper shareMapper = mock(TripExpenseShareMapper.class);
  private final UserMapper userMapper = mock(UserMapper.class);
  private final TripPlaceMapper tripPlaceMapper = mock(TripPlaceMapper.class);

  private TravelStatsService service() {
    return new TravelStatsService(tripMemberMapper, tripMapper, tripDayMapper,
        expenseMapper, shareMapper, userMapper, tripPlaceMapper);
  }

  private static Date utc(String iso) {
    return Date.from(java.time.Instant.parse(iso));
  }

  /** 一个用户、两个行程：有日期 + 无日期草稿（都未删除，均有成员资格）。 */
  private void stubMembershipAndTrips(Trip dated, Trip dateless) {
    TripMember m1 = new TripMember();
    m1.setUserId("u1");
    m1.setTripId("t-dated");
    TripMember m2 = new TripMember();
    m2.setUserId("u1");
    m2.setTripId("t-dateless");
    when(tripMemberMapper.selectList(any())).thenReturn(List.of(m1, m2));
    when(tripMapper.selectBatchIds(any())).thenReturn(List.of(dated, dateless));
    when(tripDayMapper.selectList(any())).thenReturn(List.of());
    when(expenseMapper.selectList(any())).thenReturn(List.of());
  }

  @Test
  void datelessDraftCountsInOverviewButNotInYearViews() {
    Trip dated = new Trip();
    dated.setId("t-dated");
    dated.setDestination("大连");
    dated.setStartDate(utc("2026-06-10T00:00:00Z"));
    dated.setEndDate(utc("2026-06-12T00:00:00Z"));
    Trip dateless = new Trip();
    dateless.setId("t-dateless");
    dateless.setDestination("草稿目的地");
    dateless.setStartDate(null);
    dateless.setEndDate(null);
    stubMembershipAndTrips(dated, dateless);

    TravelStatsService svc = service();

    // overview：tripCount=2（含无日期草稿）；总天数=日期回推 3 天（草稿不贡献天数）
    Map<String, Object> overview = svc.getOverview("u1");
    assertEquals(2, ((Number) overview.get("tripCount")).intValue());
    assertEquals(3, ((Number) overview.get("totalDays")).intValue());

    // 年份视图：无日期草稿不进任何年份（yearOf(null) 守卫，读取阶段前置筛选不误删有日期行程）
    Map<String, Object> yearly = svc.getYearly("u1", "2026");
    assertEquals(1, ((Number) yearly.get("tripCount")).intValue());

    Map<String, Object> monthly = svc.getMonthly("u1", "2026");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> months = (List<Map<String, Object>>) monthly.get("months");
    assertEquals(1, ((Number) months.get(5).get("tripCount")).intValue(), "6 月只有有日期行程");

    // 结算入口同样走批量读取：无费用 → 无建议
    Map<String, Object> settlement = svc.getSettlementSuggestions("u1");
    assertEquals(0, ((List<?>) settlement.get("suggestions")).size());
  }

  @Test
  void datelessDraftExcludedFromCityAggregationButCountedInTotals() {
    Trip dated = new Trip();
    dated.setId("t-dated");
    dated.setDestination("大连");
    dated.setStartDate(utc("2026-06-10T00:00:00Z"));
    dated.setEndDate(utc("2026-06-11T00:00:00Z"));
    Trip dateless = new Trip();
    dateless.setId("t-dateless");
    dateless.setStartDate(null);
    dateless.setEndDate(null);
    stubMembershipAndTrips(dated, dateless);

    Map<String, Object> body = service().footprints("u1");
    @SuppressWarnings("unchecked")
    Map<String, Object> totals = (Map<String, Object>) body.get("totals");
    // totals.tripCount 统计全部可访问行程；城市聚合只统计「目的地非空且有坐标」的行程
    assertEquals(2, ((Number) totals.get("tripCount")).intValue());
    assertEquals(0, ((Number) totals.get("cityCount")).intValue());
  }
}
