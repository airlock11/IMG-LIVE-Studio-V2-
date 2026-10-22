# IMG LIVE Studio — OAuth backend (development scaffold)

This is **not deployed or production ready**. The Android client is still a prototype. Facebook and YouTube client credentials and app reviews are required.

## Cloudflare bindings

- KV namespace `OAUTH_STATE` (10-minute, single-use authorization state).
- KV namespace `ACCOUNT_SESSIONS` (session metadata; currently includes provider tokens).
- Secret `GOOGLE_CLIENT_SECRET`.
- Secret `FACEBOOK_APP_SECRET`.
- Variables `GOOGLE_CLIENT_ID`, `FACEBOOK_APP_ID`, `PUBLIC_ORIGIN` (HTTPS base origin, ending in /).

Register callback URLs:
- `https://YOUR-OAUTH-DOMAIN/api/connections/youtube/callback`
- `https://YOUR-OAUTH-DOMAIN/api/connections/facebook/callback`

Do **not** deploy with real users until:
1. A trusted Android app-to-backend authentication/session design is implemented. The current cookie approach is not reliable with Android WebView's disabled third-party cookies and cross-site SameSite restrictions.
2. Encrypt tokens before writing to KV using a server-side managed encryption key, and implement token refresh, rotation, and revocation.
3. Replace the browser-only callback completion with verified Android App Links and an authenticated state reconciliation endpoint.
4. Add CSRF defenses for connection actions, rate limiting, session expiration/logout, audit logging, and provider-specific error handling.
5. Configure and review the Meta/Google applications and permissions. Facebook user login alone does not guarantee access to Live Video publishing.
6. Implement YouTube liveBroadcasts/liveStreams provisioning, account destination selection, and a native RTMP publisher. No automatic Go Live is implemented yet.

## Implemented API surface
- `GET /health`
- `GET /api/connections` (connection booleans)
- `POST /api/connections/:provider/authorize` (creates state, returns official login URL)
- `GET /api/connections/:provider/callback` (exchanges authorization code server-side)

The Android UI is under Settings → Manage connections. It intentionally shows disconnected unless the backend verifies the connection.
