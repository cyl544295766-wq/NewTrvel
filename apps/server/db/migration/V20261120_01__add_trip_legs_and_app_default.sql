-- V20261120_01: 移动端「我的行程」与路线段
-- 1) trips.app_default：标记该行程就是 App 端那一条，便于按用户唯一定位；
--    部分唯一索引保证一个用户最多一条，避免并发创建出两条默认行程。
-- 2) trip_legs：App 端 RouteSegment（两点间的驾车路线）在服务端的落点，
--    原先 trips/trip_days/trip_places 没有对应表，导致路线无法双端同步。
-- 幂等：ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS / 部分唯一索引。

ALTER TABLE trips ADD COLUMN IF NOT EXISTS app_default boolean NOT NULL DEFAULT false;

CREATE UNIQUE INDEX IF NOT EXISTS trips_app_default_owner_key
  ON trips (owner_id)
  WHERE app_default;

CREATE TABLE IF NOT EXISTS trip_legs (
    id               TEXT NOT NULL,
    trip_id          TEXT NOT NULL,
    trip_day_id      TEXT,
    segment_index    INTEGER NOT NULL DEFAULT 0,
    distance_meters  DOUBLE PRECISION,
    duration_seconds DOUBLE PRECISION,
    points_json      TEXT,
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT trip_legs_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS trip_legs_trip_id_idx ON trip_legs (trip_id);
CREATE INDEX IF NOT EXISTS trip_legs_trip_day_id_idx ON trip_legs (trip_day_id);
