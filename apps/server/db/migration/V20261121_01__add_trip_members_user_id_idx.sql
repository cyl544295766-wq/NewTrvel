-- 统计/首页/行程列表入口按 user_id 查询成员资格（TravelStatsService.findTripsForUser、
-- DashboardService、TripService 列表等）。trip_members 原仅有 (trip_id, user_id) 复合唯一索引：
-- user_id 单列过滤可经该索引「全索引扫描 + 过滤」兜底（非全表扫描），但代价仍随表规模线性上升；
-- 本索引把成本约束到 user_id 命中结果集（20 万行模拟表实测：Seq Scan 11.9ms → Bitmap 0.058ms，
-- 见交接文档 §16.2）。仅新增索引（幂等可重放），不改动任何列与数据。
CREATE INDEX IF NOT EXISTS "trip_members_user_id_idx" ON "trip_members"("user_id");
