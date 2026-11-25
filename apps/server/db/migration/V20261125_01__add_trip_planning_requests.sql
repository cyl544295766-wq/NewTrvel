CREATE TABLE IF NOT EXISTS trip_planning_requests (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    trip_id TEXT REFERENCES trips(id) ON DELETE SET NULL,
    conversation_id TEXT REFERENCES ai_conversations(id) ON DELETE SET NULL,
    PRIMARY KEY (user_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_trip_planning_requests_trip ON trip_planning_requests(trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_planning_requests_conversation ON trip_planning_requests(conversation_id);
