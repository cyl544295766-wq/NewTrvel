-- 攻略广场：行程可见性与热度统计。
--   public    公开：所有人（登录后）可浏览、可复制
--   friends   好友可见：仅作者与互为好友（好友页签里展示）
--   private   私有：仅作者（默认）
-- 存量行程一律回填为 private，是否公开由作者自行决定。
ALTER TABLE "trips" ADD COLUMN IF NOT EXISTS "visibility" TEXT NOT NULL DEFAULT 'private';
ALTER TABLE "trips" ADD COLUMN IF NOT EXISTS "published_at" TIMESTAMP(3);
ALTER TABLE "trips" ADD COLUMN IF NOT EXISTS "view_count" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "trips" ADD COLUMN IF NOT EXISTS "copy_count" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "trips" ADD COLUMN IF NOT EXISTS "source_trip_id" TEXT;

-- 首次设为公开时先补一次发布时间，方便「最新发布」排序
UPDATE "trips" SET "published_at" = COALESCE("published_at", NOW())
WHERE "visibility" = 'public' AND "published_at" IS NULL;

CREATE INDEX IF NOT EXISTS "trips_visibility_idx" ON "trips" ("visibility");
CREATE INDEX IF NOT EXISTS "trips_source_trip_id_idx" ON "trips" ("source_trip_id");

-- 浏览量 + 复制量构成热度：view_count × 1 + copy_count × 5
CREATE INDEX IF NOT EXISTS "trips_square_hot_idx" ON "trips" (("view_count" + "copy_count" * 5))
WHERE "visibility" = 'public' AND "deleted_at" IS NULL;
