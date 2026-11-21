-- V20261120_01: AI 幂等键执行归属令牌（修复并发接管竞态）
-- 目的：
--   1) failed / 过期 running / 结果不可读三类"转 NEW"路径必须用数据库条件更新
--      （状态 + 读取时 updated_at 双条件 CAS），只有影响行数 = 1 的请求才接管成功；
--   2) markSucceeded / markFailed / markCancelled / abandon 全部改为
--      "状态 = running AND execution_token = 本次令牌" 的条件更新，
--      旧执行者（被接管后）不能再覆盖新执行者状态，abandon 也不会误删他人占位。
-- 旧数据兼容：
--   execution_token 可空；历史行（迁移前创建）为 NULL——
--   NULL 令牌无法通过任何 "execution_token = ?" 条件更新（视为无人持有），
--   残留 running 行会在 stale-running-ms 后被条件接管并写入新令牌；
--   succeeded / failed / cancelled 的历史行按状态正常处理，无需令牌。
-- 幂等：ADD COLUMN IF NOT EXISTS 可重复重放。

ALTER TABLE ai_request_keys ADD COLUMN IF NOT EXISTS execution_token TEXT;
