-- Enforce new day references without rewriting historical orphaned rows.
DO $$ BEGIN
  ALTER TABLE trip_expenses ADD CONSTRAINT trip_expenses_trip_day_id_fkey
    FOREIGN KEY (trip_day_id) REFERENCES trip_days(id) ON DELETE SET NULL NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  ALTER TABLE trip_weather ADD CONSTRAINT trip_weather_trip_day_id_fkey
    FOREIGN KEY (trip_day_id) REFERENCES trip_days(id) ON DELETE SET NULL NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
  ALTER TABLE trip_legs ADD CONSTRAINT trip_legs_trip_day_id_fkey
    FOREIGN KEY (trip_day_id) REFERENCES trip_days(id) ON DELETE SET NULL NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS trip_expenses_trip_day_id_idx ON trip_expenses(trip_day_id);
CREATE INDEX IF NOT EXISTS trip_weather_trip_day_id_idx ON trip_weather(trip_day_id);
