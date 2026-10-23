-- Preserve only a safe, short, fixed error identifier for troubleshooting.
-- No Google error responses, credentials, stream names or RTMPS URLs.
ALTER TABLE youtube_private_ingest_attempts ADD COLUMN failure_code TEXT;
