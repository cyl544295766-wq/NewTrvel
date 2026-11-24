-- A receipt records both sent and preference-suppressed events.
CREATE TABLE IF NOT EXISTS notification_event_receipts (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_key TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, event_key)
);
