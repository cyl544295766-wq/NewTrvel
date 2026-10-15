-- CreateEnum
CREATE TYPE "TripPlaceType" AS ENUM ('attraction', 'hotel', 'restaurant', 'transport', 'shopping', 'custom');

-- CreateTable
CREATE TABLE "trip_days" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT NOT NULL,
    "date" TIMESTAMP(3) NOT NULL,
    "day_index" INTEGER NOT NULL,
    "title" TEXT,
    "summary" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "trip_days_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "trip_places" (
    "id" TEXT NOT NULL,
    "trip_id" TEXT NOT NULL,
    "trip_day_id" TEXT,
    "name" TEXT NOT NULL,
    "type" "TripPlaceType" NOT NULL DEFAULT 'custom',
    "address" TEXT,
    "latitude" DECIMAL(65,30),
    "longitude" DECIMAL(65,30),
    "start_time" TIMESTAMP(3),
    "end_time" TIMESTAMP(3),
    "notes" TEXT,
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "trip_places_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "trip_days_trip_id_date_key" ON "trip_days"("trip_id", "date");

-- CreateIndex
CREATE UNIQUE INDEX "trip_days_trip_id_day_index_key" ON "trip_days"("trip_id", "day_index");

-- CreateIndex
CREATE INDEX "trip_places_trip_id_idx" ON "trip_places"("trip_id");

-- CreateIndex
CREATE INDEX "trip_places_trip_day_id_idx" ON "trip_places"("trip_day_id");

-- AddForeignKey
ALTER TABLE "trip_days" ADD CONSTRAINT "trip_days_trip_id_fkey" FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trip_places" ADD CONSTRAINT "trip_places_trip_id_fkey" FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "trip_places" ADD CONSTRAINT "trip_places_trip_day_id_fkey" FOREIGN KEY ("trip_day_id") REFERENCES "trip_days"("id") ON DELETE SET NULL ON UPDATE CASCADE;
