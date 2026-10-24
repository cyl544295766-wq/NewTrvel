-- V20261024_01: 通知类型补充 friend 值（P0-3 好友请求/好友达成通知）
-- 注意：PG 枚举加值需在事务外执行，psql 直连即可
ALTER TYPE "NotificationType" ADD VALUE IF NOT EXISTS 'friend';
