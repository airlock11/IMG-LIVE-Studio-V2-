-- IMG LIVE Studio V2 OAuth transactional state, isolated D1 database.
-- All expiry values are Unix milliseconds. Never store provider tokens without encryption.
CREATE TABLE IF NOT EXISTS oauth_sessions (
  session_id TEXT PRIMARY KEY NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_oauth_sessions_expiry ON oauth_sessions(expires_at);

CREATE TABLE IF NOT EXISTS oauth_accounts (
  session_id TEXT NOT NULL REFERENCES oauth_sessions(session_id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK(provider IN ('facebook','youtube')),
  encrypted_token TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY(session_id,provider)
);
CREATE INDEX IF NOT EXISTS idx_oauth_accounts_expiry ON oauth_accounts(expires_at);

CREATE TABLE IF NOT EXISTS oauth_pairings (
  pairing_hash TEXT PRIMARY KEY NOT NULL,
  session_id TEXT NOT NULL REFERENCES oauth_sessions(session_id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK(provider IN ('facebook','youtube')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','complete','cancelled','declined')),
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_oauth_pairings_expiry ON oauth_pairings(expires_at);

CREATE TABLE IF NOT EXISTS oauth_states (
  state_hash TEXT PRIMARY KEY NOT NULL,
  session_id TEXT NOT NULL REFERENCES oauth_sessions(session_id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK(provider IN ('facebook','youtube')),
  verifier TEXT NOT NULL,
  pairing_hash TEXT NOT NULL REFERENCES oauth_pairings(pairing_hash),
  expires_at INTEGER NOT NULL,
  used_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_oauth_states_expiry ON oauth_states(expires_at);
