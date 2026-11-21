-- V20261119_02: AI 消息发送幂等键（普通/流式共享）
-- 目的：同一用户+会话+clientRequestId 的重试只执行一次：
--   - succeeded 记录在服务重启后仍能防重（持久化）
--   - running 记录返回“进行中”状态；stale running 由服务端按 ai.idempotency.stale-running-ms 接管
--   - failed 允许同键重试；cancelled 不自动恢复（前端须换新 ID）
-- 清理：created_at 早于保留期的记录由服务端在 begin 时按间隔批量删除（有界增长）。
-- 幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，可重复重放。

CREATE TABLE IF NOT EXISTS "ai_request_keys" (
    "user_id" TEXT NOT NULL,
    "conversation_id" TEXT NOT NULL,
    "client_request_id" TEXT NOT NULL,
    "id" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "result" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "ai_request_keys_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX IF NOT EXISTS "ai_request_keys_unique_key"
  ON "ai_request_keys" ("user_id", "conversation_id", "client_request_id");
CREATE INDEX IF NOT EXISTS "ai_request_keys_created_at_idx"
  ON "ai_request_keys" ("created_at");
