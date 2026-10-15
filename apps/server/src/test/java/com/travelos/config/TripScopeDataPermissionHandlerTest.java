package com.travelos.config;

import com.travelos.common.TripScopeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器兜底逻辑单元测试（纯逻辑，不依赖数据库）：
 * 覆盖「成员可见 / 非成员不可见 / 绕过生效 / 无上下文不拦截」四类场景的表达式生成。
 * SQL 层面的实际过滤效果由接入后的集成回归验证（见验收记录）。
 */
class TripScopeDataPermissionHandlerTest {

  private final TripScopeDataPermissionHandler handler = new TripScopeDataPermissionHandler();
  private static final String MEMBER = "userMember01";
  private static final Table PHOTOS = new Table("photos");
  private static final Table TRIPS = new Table("trips");
  private static final Table NOTIFICATIONS = new Table("notifications");

  @AfterEach
  void tearDown() {
    TripScopeContext.clear();
  }

  @Test
  void memberContextGeneratesTripScopeFilter() {
    TripScopeContext.setUserId(MEMBER);
    Expression expression = handler.getSqlSegment(PHOTOS, null, "test.find");
    assertEquals(
        "trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = 'userMember01')",
        expression.toString());
  }

  @Test
  void tripsTableFiltersByPrimaryKey() {
    TripScopeContext.setUserId(MEMBER);
    Expression expression = handler.getSqlSegment(TRIPS, null, "test.find");
    assertEquals(
        "id IN (SELECT trip_id FROM trip_members WHERE user_id = 'userMember01')",
        expression.toString());
  }

  @Test
  void notificationsTableFiltersByUserId() {
    TripScopeContext.setUserId(MEMBER);
    Expression expression = handler.getSqlSegment(NOTIFICATIONS, null, "test.find");
    assertEquals("user_id = 'userMember01'", expression.toString());
  }

  @Test
  void nonMemberWithOwnContextStillGetsOwnScopeOnly() {
    // 非成员拿到的是「自己的成员范围」过滤条件（因此查不到他人行程数据），
    // 条件本身与成员一致，但传入的 userId 不同
    TripScopeContext.setUserId("userOutsider99");
    Expression expression = handler.getSqlSegment(PHOTOS, null, "test.find");
    assertEquals(
        "trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = 'userOutsider99')",
        expression.toString());
  }

  @Test
  void bypassSwitchDisablesFilter() {
    TripScopeContext.setUserId(MEMBER);
    TripScopeContext.setBypassed(true);
    assertNull(handler.getSqlSegment(PHOTOS, null, "test.find"));
  }

  @Test
  void runBypassedRestoresPreviousState() {
    TripScopeContext.setUserId(MEMBER);
    TripScopeContext.runBypassed(() -> {
      assertNull(handler.getSqlSegment(PHOTOS, null, "test.find"));
    });
    // 嵌套调用结束后恢复原状
    assertTrue(handler.getSqlSegment(PHOTOS, null, "test.find") != null);
  }

  @Test
  void missingContextDoesNotFilter() {
    // 无请求上下文（种子/定时任务/未登录）：不追加条件
    assertNull(handler.getSqlSegment(PHOTOS, null, "test.find"));
  }

  @Test
  void ignoredTablesAreNotFiltered() {
    TripScopeContext.setUserId(MEMBER);
    assertNull(handler.getSqlSegment(new Table("users"), null, "test.find"));
    assertNull(handler.getSqlSegment(new Table("trip_members"), null, "test.find"));
    assertNull(handler.getSqlSegment(new Table("audit_logs"), null, "test.find"));
  }

  @Test
  void nestedTablesUseParentSubquery() {
    TripScopeContext.setUserId(MEMBER);
    Expression items = handler.getSqlSegment(new Table("packing_items"), null, "test.find");
    assertEquals(
        "packing_list_id IN (SELECT id FROM packing_lists WHERE "
            + "trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = 'userMember01'))",
        items.toString());
    Expression shares = handler.getSqlSegment(new Table("trip_expense_shares"), null, "test.find");
    assertEquals(
        "expense_id IN (SELECT id FROM trip_expenses WHERE "
            + "trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = 'userMember01'))",
        shares.toString());
  }

  @Test
  void bookingsTableIsTripScoped() {
    TripScopeContext.setUserId(MEMBER);
    Expression expression = handler.getSqlSegment(new Table("bookings"), null, "test.find");
    assertEquals(
        "trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = 'userMember01')",
        expression.toString());
  }

  @Test
  void malformedUserIdDoesNotFilter() {
    TripScopeContext.setUserId("bad'; DROP TABLE photos; --");
    assertNull(handler.getSqlSegment(PHOTOS, null, "test.find"));
  }
}
