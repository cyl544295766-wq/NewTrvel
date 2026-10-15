CREATE TABLE "trip_weather" (
  "id" TEXT NOT NULL,
  "trip_id" TEXT NOT NULL,
  "trip_day_id" TEXT,
  "date" TIMESTAMP(3) NOT NULL,
  "destination" TEXT NOT NULL,
  "condition" TEXT NOT NULL,
  "temp_high" DECIMAL(65,30) NOT NULL,
  "temp_low" DECIMAL(65,30) NOT NULL,
  "rain_chance" INTEGER,
  "icon_code" TEXT,
  "fetched_at" TIMESTAMP(3) NOT NULL,
  "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT "trip_weather_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "trip_weather_trip_id_idx" ON "trip_weather"("trip_id");
CREATE INDEX "trip_weather_trip_id_date_idx" ON "trip_weather"("trip_id", "date");

ALTER TABLE "trip_weather"
  ADD CONSTRAINT "trip_weather_trip_id_fkey"
  FOREIGN KEY ("trip_id") REFERENCES "trips"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
