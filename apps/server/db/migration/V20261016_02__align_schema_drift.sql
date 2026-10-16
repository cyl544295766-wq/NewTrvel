-- 补齐结构漂移：以下表/列此前仅存在于开发库（手工调整未沉淀迁移），
-- 导致生产库缺列、查询 500（如 dashboard / stats/overview 的 split_type）。
-- 全部语句保持幂等，可重复执行。

-- 1) 费用分摊方式枚举
DO $$ BEGIN
  CREATE TYPE "ExpenseSplitType" AS ENUM ('equal', 'ratio', 'amount');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 2) trip_expenses 补列（分摊方式 / 结算状态 / 行程天与地点关联）
ALTER TABLE "trip_expenses" ADD COLUMN IF NOT EXISTS "split_type" "ExpenseSplitType";
ALTER TABLE "trip_expenses" ADD COLUMN IF NOT EXISTS "is_settled" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "trip_expenses" ADD COLUMN IF NOT EXISTS "settled_at" TIMESTAMP(3);
ALTER TABLE "trip_expenses" ADD COLUMN IF NOT EXISTS "trip_day_id" TEXT;
ALTER TABLE "trip_expenses" ADD COLUMN IF NOT EXISTS "trip_place_id" TEXT;

-- 3) AI 摘要与元数据字段（trips/users/photos/trip_days/trip_places/trip_journals）
ALTER TABLE "trips"         ADD COLUMN IF NOT EXISTS "ai_summary" TEXT;
ALTER TABLE "trips"         ADD COLUMN IF NOT EXISTS "metadata" JSONB;
ALTER TABLE "users"         ADD COLUMN IF NOT EXISTS "metadata" JSONB;
ALTER TABLE "photos"        ADD COLUMN IF NOT EXISTS "ai_summary" TEXT;
ALTER TABLE "photos"        ADD COLUMN IF NOT EXISTS "metadata" JSONB;
ALTER TABLE "trip_days"     ADD COLUMN IF NOT EXISTS "ai_summary" TEXT;
ALTER TABLE "trip_days"     ADD COLUMN IF NOT EXISTS "metadata" JSONB;
ALTER TABLE "trip_places"   ADD COLUMN IF NOT EXISTS "ai_summary" TEXT;
ALTER TABLE "trip_places"   ADD COLUMN IF NOT EXISTS "metadata" JSONB;
ALTER TABLE "trip_journals" ADD COLUMN IF NOT EXISTS "ai_summary" TEXT;
ALTER TABLE "trip_journals" ADD COLUMN IF NOT EXISTS "metadata" JSONB;

-- 4) AI 索引表
CREATE TABLE IF NOT EXISTS "ai_indexes" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT,
    "entity_type" TEXT,
    "entity_id" TEXT,
    "content" TEXT,
    "ai_summary" TEXT,
    "metadata" JSONB,
    "occurred_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3),
    CONSTRAINT "ai_indexes_pkey" PRIMARY KEY ("id")
);

-- 5) 行程路线表
CREATE TABLE IF NOT EXISTS "trip_routes" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT,
    "day_id" TEXT,
    "name" TEXT,
    "color" TEXT,
    "encoded_path" TEXT,
    "distance_meters" INTEGER,
    "duration_seconds" INTEGER,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3),
    CONSTRAINT "trip_routes_pkey" PRIMARY KEY ("id")
);

-- 6) 行程轨迹表
CREATE TABLE IF NOT EXISTS "trip_tracks" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT,
    "day_id" TEXT,
    "name" TEXT,
    "color" TEXT,
    "opacity" DOUBLE PRECISION,
    "is_visible" BOOLEAN,
    "points" JSONB,
    "source_file_url" TEXT,
    "distance_meters" INTEGER,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3),
    CONSTRAINT "trip_tracks_pkey" PRIMARY KEY ("id")
);
