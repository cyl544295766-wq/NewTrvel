package com.travelos.config;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.travelos.common.TripScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;

import java.util.Set;

/**
 * 行程维度数据权限兜底拦截器（与手写校验是双保险，手写校验语义优先）。
 *
 * 规则：
 * - trips 按 id、其余 trip 属下表按 trip_id 注入「当前用户是该行程成员」子查询；
 *   packing_items / trip_expense_shares / trip_journal_photos 无 trip_id，
 *   通过父表嵌套子查询过滤；
 * - notifications / user_preferences 按 user_id = 当前用户过滤；
 * - users / trip_members / audit_logs / system_settings / trip_journal_photos 等
 *   不适合成员过滤的表忽略（trip_members 的跨成员管理操作由手写校验覆盖，
 *   详见《数据隔离改造清单.md》第三阶段记录）；
 * - 无请求上下文（种子/定时任务/未登录）或显式 bypass 时不追加条件；
 * - 当前用户 ID 仅允许字母数字下划线连字符，防注入兜底。
 */
public class TripScopeDataPermissionHandler implements MultiDataPermissionHandler {

  /** 成员过滤无法成立的表：交给手写校验（trip_members）或与成员无关（users 等） */
  private static final Set<String> IGNORED_TABLES = Set.of(
      "users", "trip_members", "audit_logs", "system_settings");

  /** 经父表嵌套过滤的表 */
  private static final Set<String> NESTED_PACKING_ITEM = Set.of("packing_items");
  private static final Set<String> NESTED_EXPENSE_SHARE = Set.of("trip_expense_shares");
  private static final Set<String> NESTED_JOURNAL_PHOTO = Set.of("trip_journal_photos");

  private static final Set<String> TRIP_SCOPED_TABLES = Set.of(
      "trips", "trip_days", "trip_places", "trip_expenses", "photos",
      "travel_documents", "trip_journals", "packing_lists", "trip_weather", "bookings");

  @Override
  public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
    if (TripScopeContext.isBypassed()) {
      return null;
    }
    String userId = TripScopeContext.getUserId();
    if (userId == null || !userId.matches("[A-Za-z0-9_-]{1,64}")) {
      // 无请求上下文（种子/定时任务）或非法格式：不拦截
      return null;
    }
    String tableName = normalize(table.getName());
    String memberScope = "trip_id IN (SELECT trip_id FROM trip_members WHERE user_id = '"
        + userId + "')";
    String memberSubquery = "(SELECT trip_id FROM trip_members WHERE user_id = '" + userId + "')";
    if (tableName.equals("trips")) {
      return parse("id IN " + memberSubquery);
    }
    if (tableName.equals("notifications")) {
      return parse("user_id = '" + userId + "'");
    }
    if (tableName.equals("user_preferences")) {
      return parse("user_id = '" + userId + "'");
    }
    // user_friends 不在此登记：好友查询需要保留方向语义（user_id/friend_id 各有含义），
    // 且 FriendService 的所有读写都显式按当前用户过滤，此处注入条件会破坏方向判断。
    if (NESTED_PACKING_ITEM.contains(tableName)) {
      return parse("packing_list_id IN (SELECT id FROM packing_lists WHERE trip_id IN "
          + memberSubquery + ")");
    }
    if (NESTED_EXPENSE_SHARE.contains(tableName)) {
      return parse("expense_id IN (SELECT id FROM trip_expenses WHERE trip_id IN "
          + memberSubquery + ")");
    }
    if (NESTED_JOURNAL_PHOTO.contains(tableName)) {
      return parse("journal_id IN (SELECT id FROM trip_journals WHERE trip_id IN "
          + memberSubquery + ")");
    }
    if (TRIP_SCOPED_TABLES.contains(tableName)) {
      return parse(memberScope);
    }
    if (IGNORED_TABLES.contains(tableName)) {
      return null;
    }
    // 未知表：不拦截（新表接入时在此登记）
    return null;
  }

  private String normalize(String raw) {
    return raw == null ? "" : raw.toLowerCase().replace("\"", "").replace("`", "");
  }

  private Expression parse(String condition) {
    try {
      return CCJSqlParserUtil.parseCondExpression(condition);
    } catch (Exception ex) {
      // 解析失败按不拦截处理，交由手写校验兜底
      return null;
    }
  }
}
