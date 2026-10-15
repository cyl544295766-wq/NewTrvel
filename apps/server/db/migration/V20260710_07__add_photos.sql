-- CreateTable
CREATE TABLE IF NOT EXISTS "photos" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT NOT NULL,
    "trip_day_id" TEXT,
    "trip_place_id" TEXT,
    "url" TEXT NOT NULL,
    "caption" TEXT,
    "shot_at" TIMESTAMP(3),
    "is_cover" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "photos_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX IF NOT EXISTS "photos_trip_id_idx" ON "photos"("trip_id");

-- CreateIndex
CREATE INDEX IF NOT EXISTS "photos_trip_day_id_idx" ON "photos"("trip_day_id");

-- CreateIndex
CREATE INDEX IF NOT EXISTS "photos_trip_place_id_idx" ON "photos"("trip_place_id");

-- AddForeignKey
DO $$ BEGIN
  ALTER TABLE "photos" ADD CONSTRAINT "photos_trip_id_fkey" FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- AddForeignKey
DO $$ BEGIN
  ALTER TABLE "photos" ADD CONSTRAINT "photos_trip_day_id_fkey" FOREIGN KEY ("trip_day_id") REFERENCES "trip_days"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- AddForeignKey
DO $$ BEGIN
  ALTER TABLE "photos" ADD CONSTRAINT "photos_trip_place_id_fkey" FOREIGN KEY ("trip_place_id") REFERENCES "trip_places"("id") ON DELETE SET NULL ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
