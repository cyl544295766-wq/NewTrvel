-- 用户偏好设置表（账户页「偏好与显示」）
-- 每个用户一行；首次读取时由服务端按默认值懒创建。
CREATE TABLE IF NOT EXISTS user_preferences (
  id              text PRIMARY KEY,
  user_id         text NOT NULL UNIQUE,
  theme           text NOT NULL DEFAULT 'system',
  timezone        text NOT NULL DEFAULT 'Asia/Shanghai',
  distance_unit   text NOT NULL DEFAULT 'km',
  currency        text NOT NULL DEFAULT 'CNY',
  date_format     text NOT NULL DEFAULT 'cn',
  week_start      text NOT NULL DEFAULT 'mon',
  notify_trip     boolean NOT NULL DEFAULT TRUE,
  notify_document boolean NOT NULL DEFAULT TRUE,
  notify_member   boolean NOT NULL DEFAULT TRUE,
  notify_summary  boolean NOT NULL DEFAULT FALSE,
  created_at      timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS user_preferences_user_id_key ON user_preferences (user_id);

ALTER TABLE user_preferences DROP CONSTRAINT IF EXISTS fk_user_preferences_user;
ALTER TABLE user_preferences
  ADD CONSTRAINT fk_user_preferences_user
  FOREIGN KEY (user_id) REFERENCES users (id) ON UPDATE CASCADE ON DELETE CASCADE;
