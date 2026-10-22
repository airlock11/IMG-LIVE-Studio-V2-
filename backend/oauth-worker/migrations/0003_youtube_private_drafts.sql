-- Private YouTube broadcast draft requests are operator initiated and never public.
-- A per-device idempotency key is unique; retrying a completed request returns
-- the stored result and does not create another YouTube broadcast.
CREATE TABLE IF NOT EXISTS youtube_private_draft_requests (
 session_id TEXT NOT NULL REFERENCES oauth_sessions(session_id) ON DELETE CASCADE,
 request_id TEXT NOT NULL,
 payload_hash TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('processing','complete','failed')),
 broadcast_id TEXT,
 title TEXT NOT NULL,
 scheduled_start TEXT NOT NULL,
 created_at INTEGER NOT NULL,
 PRIMARY KEY(session_id,request_id)
);
CREATE INDEX IF NOT EXISTS idx_youtube_draft_requests_cleanup ON youtube_private_draft_requests(created_at);
