package com.travelos.service;

import com.travelos.entity.Trip;
import com.travelos.entity.TripExpense;
import com.travelos.entity.TripMember;
import com.travelos.entity.User;
import com.travelos.mapper.NotificationMapper;
import com.travelos.mapper.PackingItemMapper;
import com.travelos.mapper.PackingListMapper;
import com.travelos.mapper.PhotoMapper;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripExpenseMapper;
import com.travelos.mapper.TripJournalMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardService 服务层【防御场景】回归（纯 Mockito，不连数据库、不构造非法数据）。
 *
 * <p><b>与合法数据库场景的边界</b>（DashboardCorrectnessTest 以 SQL 断言约束不被放宽）：
 * <ul>
 *   <li>{@code trips.updated_at} 数据库为 NOT NULL → 合法数据不可能出现空更新时间；
 *       本类用 mock 实体覆盖「更新时间为空」的排序分支（含防御 NPE 与稳定平局规则）。</li>
 *   <li>{@code trip_expenses.currency} 数据库为 NOT NULL DEFAULT 'CNY' → 合法数据不可能为 NULL；
 *       合法的「空币种」（空字符串）不计入 CNY 已在 DashboardCorrectnessTest 覆盖；
 *       本类覆盖 currency=null / amount=null 的服务层防御（不折算、不计入、不抛异常）。</li>
 * </ul>
 *
 * <p>本类不修改任何数据库约束，也不依赖个人账号/固定行程 ID。
 */
class DashboardServiceBoundaryTest {

  private TripMapper tripMapper;
  private TripMemberMapper tripMemberMapper;
  private TripExpenseMapper expenseMapper;
  private DashboardService service;

  private static final Date TIMED = new Date(1_800_000_000_000L);

  @BeforeEach
  void setUp() {
    tripMapper = mock(TripMapper.class);
    tripMemberMapper = mock(TripMemberMapper.class);
    UserMapper userMapper = mock(UserMapper.class);
    expenseMapper = mock(TripExpenseMapper.class);
    PhotoMapper photoMapper = mock(PhotoMapper.class);
    TravelDocumentMapper documentMapper = mock(TravelDocumentMapper.class);
    TripJournalMapper journalMapper = mock(TripJournalMapper.class);
    PackingListMapper packingListMapper = mock(PackingListMapper.class);
    PackingItemMapper packingItemMapper = mock(PackingItemMapper.class);
    NotificationMapper notificationMapper = mock(NotificationMapper.class);

    when(notificationMapper.selectCount(any())).thenReturn(0L);
    when(userMapper.selectById(anyString())).thenReturn(new User());

    service = new DashboardService(tripMapper, tripMemberMapper, userMapper, expenseMapper,
        photoMapper, documentMapper, journalMapper, packingListMapper, packingItemMapper,
        notificationMapper);
  }

  // ==================== updatedAt 为空（防御场景） ====================

  @Test
  void nullUpdatedAtSortedAfterTimedTripsAndTieBreaksByTripIdDesc() {
    // 9001 有更新时间；9002/9003 为空。输入顺序刻意打乱，验证排序而非插入序。
    stubMembershipsAndTrips(
        List.of(member("9003"), member("9001"), member("9002")),
        List.of(trip("9003", null, false), trip("9001", TIMED, false),
            trip("9002", null, false)));

    List<String> first = recentTripIds(service.findOne("u1"));
    // 有更新时间的排在空时间之前；空时间之间按行程 id 降序稳定排序
    assertEquals(List.of("9001", "9003", "9002"), first,
        "空更新时间不得 NPE；有时间 > 空时间；空时间平局按 id 降序");

    // 稳定性：重复调用顺序完全一致（平局不随机）
    assertEquals(first, recentTripIds(service.findOne("u1")), "重复请求顺序必须稳定");
  }

  @Test
  void favoriteWithNullUpdatedAtStillRankedFirst() {
    // 收藏优先级高于更新时间：收藏但空更新时间 仍排在 有时间未收藏 之前
    stubMembershipsAndTrips(
        List.of(member("9101"), member("9102")),
        List.of(trip("9101", TIMED, false), trip("9102", null, true)));

    assertEquals(List.of("9102", "9101"), recentTripIds(service.findOne("u1")),
        "收藏优先在空更新时间场景下仍然生效");
  }

  // ==================== NULL 币种 / 金额（防御场景） ====================

  @Test
  void nullCurrencyAndNullAmountDefensivelyExcludedFromCnyTotal() {
    Trip trip = trip("9201", TIMED, false);
    stubMembershipsAndTrips(List.of(member("9201")), List.of(trip));

    // currency=null（合法 DB 不可能出现，见类注释）与 amount=null 均不得计入、不得抛异常
    TripExpense cny = expense("9201", "10.00", "CNY");
    TripExpense nullCurrency = expense("9201", "999.99", null);
    TripExpense usd = expense("9201", "50.00", "USD");
    TripExpense nullAmount = expense("9201", null, "CNY");
    when(expenseMapper.selectList(any()))
        .thenReturn(List.of(cny, nullCurrency, usd, nullAmount));

    Map<String, Object> body = service.findOne("u1");
    @SuppressWarnings("unchecked")
    Map<String, Object> stats = (Map<String, Object>) body.get("stats");
    assertEquals("10.00", stats.get("totalExpenseAmount"),
        "NULL 币种与 NULL 金额不计入人民币累计（仅精确 'CNY' + 非空金额）");

    // 最近费用明细不吞行：原币与真实金额照常透出（NULL 币种行也保留）
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> recent = (List<Map<String, Object>>) body.get("recentExpenses");
    assertEquals(4, recent.size(), "明细按原样透出（不因防御逻辑删行）");
    assertEquals(null, recent.get(1).get("currency"), "NULL 币种行保留原值");
    assertEquals("999.99", recent.get(1).get("amount"));
    assertEquals(null, recent.get(3).get("amount"), "NULL 金额行保留原值（Fmt.money(null)=null）");
  }

  // ==================== 工具 ====================

  private void stubMembershipsAndTrips(List<TripMember> members, List<Trip> trips) {
    when(tripMemberMapper.selectList(any())).thenReturn(members);
    when(tripMapper.selectBatchIds(any())).thenReturn(trips);
  }

  private TripMember member(String tripId) {
    TripMember member = new TripMember();
    member.setTripId(tripId);
    member.setUserId("u1");
    return member;
  }

  private Trip trip(String id, Date updatedAt, boolean favorite) {
    Trip trip = new Trip();
    trip.setId(id);
    trip.setTitle("t" + id);
    trip.setUpdatedAt(updatedAt);
    trip.setIsFavorite(favorite);
    return trip;
  }

  private TripExpense expense(String tripId, String amount, String currency) {
    TripExpense expense = new TripExpense();
    expense.setId("e" + tripId + "-" + amount + "-" + currency);
    expense.setTripId(tripId);
    expense.setAmount(amount == null ? null : new BigDecimal(amount));
    expense.setCurrency(currency);
    expense.setTitle("防御用例");
    expense.setCategory("other");
    return expense;
  }

  private List<String> recentTripIds(Map<String, Object> body) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("recentTrips");
    List<String> ids = rows.stream().map(row -> (String) row.get("id")).toList();
    assertTrue(!ids.isEmpty(), "夹具应产出非空 recentTrips");
    return ids;
  }
}
