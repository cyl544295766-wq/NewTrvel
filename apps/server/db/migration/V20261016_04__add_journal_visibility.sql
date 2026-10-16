-- 攻略（游记）可见性权限：
--   public    公开：所有登录用户可见
--   friends   好友可见：仅作者与互为好友
--   private   私有：仅作者
--   specified 指定好友可见：仅作者与 trip_journal_audience 中选中的好友
-- 存量数据回填为 public，保持「同行成员可看」的既有习惯。
ALTER TABLE "trip_journals" ADD COLUMN IF NOT EXISTS "visibility" TEXT NOT NULL DEFAULT 'private';
UPDATE "trip_journals" SET "visibility" = 'public' WHERE "visibility" = 'private';

CREATE TABLE IF NOT EXISTS "trip_journal_audience" (
    "id" TEXT NOT NULL,
    "journal_id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "trip_journal_audience_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "trip_journal_audience_key" UNIQUE ("journal_id", "user_id")
);
CREATE INDEX IF NOT EXISTS "trip_journal_audience_user_idx" ON "trip_journal_audience" ("user_id");
