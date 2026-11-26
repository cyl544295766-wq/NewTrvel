CREATE TABLE IF NOT EXISTS trip_recap_requests (
    trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    selection_hash TEXT NOT NULL,
    journal_id TEXT REFERENCES trip_journals(id) ON DELETE SET NULL,
    PRIMARY KEY (trip_id, user_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_trip_recap_requests_journal ON trip_recap_requests(journal_id);
CREATE INDEX IF NOT EXISTS idx_trip_recap_requests_user ON trip_recap_requests(user_id);
