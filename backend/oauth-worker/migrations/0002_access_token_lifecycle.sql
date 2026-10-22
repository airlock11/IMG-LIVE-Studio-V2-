-- Track the actual provider access-token lifetime separately from the IMG session.
-- refresh_retry_after serializes YouTube renewal attempts across Worker instances.
ALTER TABLE oauth_accounts ADD COLUMN access_expires_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE oauth_accounts ADD COLUMN refresh_retry_after INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_oauth_accounts_access_expiry ON oauth_accounts(access_expires_at);
