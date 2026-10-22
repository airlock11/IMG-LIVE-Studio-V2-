-- Only pre-approved, non-sensitive Google OAuth failure codes may be stored.
-- Never store Google error_description, callback URL, auth code, or tokens.
ALTER TABLE oauth_pairings ADD COLUMN failure_reason TEXT NOT NULL DEFAULT '';
