-- 编辑锁粒度从「攻略」提升到「行程天」：
-- 同一天的行程（地点/天标题/该天的攻略）同一时间只允许一人编辑。
-- scope_id = 行程天 ID；无天归属的攻略用 "journal:{id}" 兜底。
CREATE TABLE IF NOT EXISTS "edit_locks" (
    "scope_id"    TEXT PRIMARY KEY,
    "holder_id"   TEXT   NOT NULL,
    "holder_name" TEXT,
    "acquired_at" TIMESTAMP NOT NULL,
    "expires_at"  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS "idx_edit_locks_expires" ON "edit_locks" ("expires_at");

-- 旧攻略级锁表被天级锁取代（锁为瞬态数据，无迁移价值）
DROP TABLE IF EXISTS "journal_edit_locks";
