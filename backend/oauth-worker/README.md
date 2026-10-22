# IMG LIVE Studio V2 — Cloudflare OAuth service

**Deployment:** A separate Cloudflare Worker named `img-live-studio-oauth` is deployed at
`https://img-live-studio-oauth.magsipocarnie.workers.dev`.

**Access state:** `OAUTH_ENABLED = "false"`. This is an intentionally **disabled staging deployment**, not a production account-login service. `/health` and account API endpoints return HTTP 503 while disabled. Do not switch it on simply because the Worker is reachable.

The existing IMG website and the original V1 app/Workers are not changed.

## Provisioned Cloudflare configuration

- `OAUTH_DB`: dedicated V2 D1 database `img-live-studio-v2-oauth`, schemas `migrations/0001_oauth_transactional.sql` and `migrations/0002_access_token_lifecycle.sql`
- `oauth_sessions`, `oauth_accounts`, `oauth_pairings`, and `oauth_states` use D1 for reliable transactional writes and atomic state claims
- `PUBLIC_ORIGIN`: the HTTPS Worker URL above
- `OAUTH_ENABLED`: explicitly `false`, checked on all account API endpoints
- `TOKEN_ENCRYPTION_KEY`: 32 cryptographically random bytes (base64url), installed **only as a Cloudflare Worker secret**, never committed
- Scheduled D1 cleanup (`0 3 * * *`, UTC): removes expired sessions, authorization states, pairings and provider account records; remains active while OAuth is disabled.
- `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `FACEBOOK_APP_ID`, `FACEBOOK_APP_SECRET`: **not configured yet**

The Android build's public `imgOAuthBackendUrl` property pins this service at build time. The Android native OAuth client makes API requests, stores the session cookie encrypted using Android Keystore, and returns only sanitized connection/pairing state to the WebView. No provider access token or IMG session cookie is passed to page JavaScript.

The Account Connections screen now supports **Sign out of all accounts**. Confirmed logout deletes the D1 session (cascading provider connections and outstanding authorization attempts), then erases its encrypted session on Android.

Provider **access-token expiry is tracked separately from the 30-day IMG session**. Facebook connections are not represented as active beyond a known token lifetime. YouTube can automatically exchange an encrypted server-only refresh token for a new access token, using an atomic D1 refresh lease and retry cooldown to prevent parallel refresh storms. Refresh tokens never enter WebView JavaScript. Google and Meta identity validation and full publishing permissions are still pending.

## Expected disabled-stage verification

GitHub Actions workflow `IMG V2 Cloudflare OAuth Deployment Smoke` checks the actual public Worker endpoint:

- `GET /health` → 503, `{"ok":false,"configured":false,...}`
- `GET /api/connections` → 503, `{"error":"oauth_not_enabled"}`
- Responses must carry `Cache-Control: no-store`.

The regular Fast OAuth Checks run Worker and native WebView bridge regression tests. APK and emulator workflows verify Android builds.

## Official provider redirect addresses (for future configuration)

- Google / YouTube: `https://img-live-studio-oauth.magsipocarnie.workers.dev/api/connections/youtube/callback`
- Meta / Facebook: `https://img-live-studio-oauth.magsipocarnie.workers.dev/api/connections/facebook/callback`

Only the operator of the legitimate Meta/Google developer applications can supply, authorize, and review these credentials and redirect URIs. Never commit app secrets, OAuth session cookies, user tokens, or stream keys.

## Production blockers — do not enable OAuth yet

1. **D1 race resistance (implemented, verify in real world):** Atomic single-use callback state, pending/processing/cancelled pairing CAS, and independently updated provider rows are implemented. Unit tests exercise concurrent replay and cancellation; cross-region live-service stress tests, transactional failure injection, and recovery procedures remain pending.
2. **Session hardening:** Global session revocation and native encrypted session deletion are implemented. Still required: app-bound identity, session renewal and cross-device management, rotation of at-rest secrets, rate limiting, abuse monitoring, and recovery. D1 session expiry is checked and scheduled cleanup is deployed; verify cron execution in production monitoring.
3. **OAuth correctness:** The backend now tracks provider access expiration and renews Google/YouTube access tokens when an offline refresh grant exists. Further work is required for durable error recovery, provider revocation, Meta long-lived tokens, approved scopes, and verifying actual provider account identities.
4. **App return and verification:** Finish production-verified Android App Links or secure browser return handling and end-to-end Android pairing tests on a real device.
5. **Authorized provider setup:** Configure Google and Meta applications, approved scopes, the exact redirects above, and production OAuth client credentials in Cloudflare secrets.
6. **Publishing:** Facebook Login or Google/YouTube sign-in does **not** by itself enable broadcasting. Live publishing permissions, video destinations, account selection, a native encoder, and actual RTMP publishing require independent integration and testing.

Only after these checks should `OAUTH_ENABLED` be set to `true` and actual Facebook/YouTube accounts be used for E2E validation.
