-- Only a session-owned private broadcast draft may become a test ingest.
-- Do not persist Google access tokens, RTMPS stream names or URLs.
CREATE TABLE IF NOT EXISTS youtube_private_ingest_attempts (
 session_id TEXT NOT NULL REFERENCES oauth_sessions(session_id) ON DELETE CASCADE,
 draft_request_id TEXT NOT NULL,
 broadcast_id TEXT NOT NULL,
 stream_id TEXT,
 state TEXT NOT NULL CHECK(state IN ('processing','complete','failed')),
 created_at INTEGER NOT NULL,
 PRIMARY KEY(session_id,draft_request_id),
 FOREIGN KEY(session_id,draft_request_id) REFERENCES youtube_private_draft_requests(session_id,request_id) ON DELETE CASCADE
);
