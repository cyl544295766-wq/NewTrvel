-- AI 旅行偏好记忆：AI 助手记住用户的持久旅行偏好（口味/同行人群/预算档/节奏等），
-- 推荐与规划时自动遵守；区别于 user_preferences（账户显示偏好：主题/时区/货币）。
CREATE TABLE IF NOT EXISTS ai_preferences (
  id          text PRIMARY KEY,
  user_id     text NOT NULL,
  content     text NOT NULL,                              -- 偏好内容（自然语言短句，如「不吃辣」）
  category    text NOT NULL DEFAULT 'general',            -- food/activity/budget/accommodation/pace/companion/general
  status      text NOT NULL DEFAULT 'active',             -- active / removed
  created_at  timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ai_preferences_user_status_idx
  ON ai_preferences ("user_id", "status");

ALTER TABLE ai_preferences DROP CONSTRAINT IF EXISTS fk_ai_preferences_user;
ALTER TABLE ai_preferences
  ADD CONSTRAINT fk_ai_preferences_user
  FOREIGN KEY ("user_id") REFERENCES users ("id") ON UPDATE CASCADE ON DELETE CASCADE;
