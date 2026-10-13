-- V20261013_01: 注销账户软删除支持
-- 为 users 表增加注销时间字段（status='deleted' 时记录），保留 30 天后由定时任务物理清理。
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- 用户状态枚举补充 deleted 值（注意：PG 枚举加值需在事务外执行，psql 直连即可）
ALTER TYPE "UserStatus" ADD VALUE IF NOT EXISTS 'deleted';

CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at);
