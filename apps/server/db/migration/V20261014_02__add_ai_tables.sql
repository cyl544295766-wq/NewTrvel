-- V20261014_02: AI 旅行助手（设计文档 3.5 第一期）
-- 幂等：可重复执行；不得含 ALTER TYPE ... ADD VALUE

-- 1) 对话表（user_id 让数据权限拦截器兜底）
CREATE TABLE IF NOT EXISTS ai_conversations (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  trip_id    TEXT,
  title      TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ai_conversations_user FOREIGN KEY (user_id)
    REFERENCES users (id) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_conversations_user ON ai_conversations (user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_ai_conversations_trip ON ai_conversations (trip_id);

-- 2) 消息表（冗余 user_id 便于数据权限兜底；tool_calls 存工具调用/草稿 jsonb）
CREATE TABLE IF NOT EXISTS ai_messages (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL,
  user_id         TEXT NOT NULL,
  role            TEXT NOT NULL,
  content         TEXT NOT NULL DEFAULT '',
  tool_calls      JSONB,
  draft_status    TEXT,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ai_messages_conversation FOREIGN KEY (conversation_id)
    REFERENCES ai_conversations (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT fk_ai_messages_user FOREIGN KEY (user_id)
    REFERENCES users (id) ON UPDATE CASCADE ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_messages_conversation ON ai_messages (conversation_id, created_at);
