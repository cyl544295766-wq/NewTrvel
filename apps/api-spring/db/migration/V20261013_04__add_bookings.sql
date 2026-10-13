-- V20261013_04: 预订管理 bookings 表（设计文档 3.3.1 / 3.3.2）
-- 幂等：CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS bookings (
  id                TEXT PRIMARY KEY,
  trip_id           TEXT NOT NULL REFERENCES trips(id),
  trip_day_id       TEXT REFERENCES trip_days(id),
  trip_place_id     TEXT REFERENCES trip_places(id),
  type              TEXT NOT NULL,
  title             TEXT NOT NULL,
  start_time        TIMESTAMP,
  end_time          TIMESTAMP,
  order_no          TEXT,
  confirm_code      TEXT,
  amount            NUMERIC,
  currency          TEXT DEFAULT 'CNY',
  attachment_url    TEXT,
  reminder_minutes  INTEGER,
  reminder_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
  reminder_sent_at  TIMESTAMP,
  extra             JSONB,
  creator_id        TEXT REFERENCES users(id),
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bookings_trip ON bookings (trip_id);
CREATE INDEX IF NOT EXISTS idx_bookings_trip_start ON bookings (trip_id, start_time);
-- 提醒引擎扫描索引：到点未发送的预订（部分索引）
CREATE INDEX IF NOT EXISTS idx_bookings_reminder_due
  ON bookings (start_time)
  WHERE reminder_enabled = TRUE AND reminder_sent_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_bookings_creator ON bookings (creator_id);
