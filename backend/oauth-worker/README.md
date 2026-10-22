# IMG LIVE Studio V2 — Cloudflare OAuth service

**Deployment:** A separate Cloudflare Worker named `img-live-studio-oauth` is deployed at
`https://img-live-studio-oauth.magsipocarnie.workers.dev`.

**Access state:** Full production OAuth remains **disabled** (`OAUTH_ENABLED = "false"`). A separate `YOUTUBE_TEST_ENABLED` flag may temporarily enable **YouTube only** for Google Cloud's explicitly registered OAuth test users. The Cloudflare-only `YOUTUBE_TEST_EXPIRES_AT` timestamp closes this test window automatically. Facebook stays disabled. `wrangler.toml` defaults both flags to `false`, so normal source redeploys fail closed.

**YouTube test configuration (October 22, 2026):** Cloudflare has a 24-hour testing window expiring on **October 23, 2026, 11:27 UTC** (7:27 PM Philippine time). The Google OAuth app is still in Testing with a registered test account. This enables a real-account **login test**, not public OAuth availability or live streaming. Don't extend it or turn on full OAuth without an additional security review.

The existing IMG website and the original V1 app/Workers are not changed.

## Provisioned Cloudflare configuration

- `OAUTH_DB`: dedicated V2 D1 database `img-live-studio-v2-oauth`, schemas `migrations/0001_oauth_transactional.sql`, `migrations/0002_access_token_lifecycle.sql`, and `migrations/0003_youtube_private_drafts.sql`
- `oauth_sessions`, `oauth_accounts`, `oauth_pairings`, and `oauth_states` use D1 for reliable transactional writes and atomic state claims
- `PUBLIC_ORIGIN`: the HTTPS Worker URL above
- `OAUTH_ENABLED`: explicitly `false`, checked on all account API endpoints
- `TOKEN_ENCRYPTION_KEY`: 32 cryptographically random bytes (base64url), installed **only as a Cloudflare Worker secret**, never committed
- Scheduled D1 cleanup (`0 3 * * *`, UTC): removes expired sessions, authorization states, pairings and provider account records; remains active while OAuth is disabled.
- `GOOGLE_CLIENT_ID`: configured as a public Worker variable; `GOOGLE_CLIENT_SECRET`: configured as a Worker secret. Facebook provider credentials are not configured yet.

The Android build's public `imgOAuthBackendUrl` property pins this service at build time. The Android native OAuth client makes API requests, stores the session cookie encrypted using Android Keystore, and returns only sanitized connection/pairing state to the WebView. No provider access token or IMG session cookie is passed to page JavaScript.

The Account Connections screen now supports **Sign out of all accounts**. Confirmed logout deletes the D1 session (cascading provider connections and outstanding authorization attempts), then erases its encrypted session on Android.

Provider **access-token expiry is tracked separately from the 30-day IMG session**. Facebook connections are not represented as active beyond a known token lifetime. YouTube can automatically exchange an encrypted server-only refresh token for a new access token, using an atomic D1 refresh lease and retry cooldown to prevent parallel refresh storms. Refresh tokens never enter WebView JavaScript. Google/YouTube channel verification is implemented at OAuth connection time, but Meta identity validation and full publishing permissions are still pending.

## Expected disabled-stage verification

GitHub Actions workflow `IMG V2 Cloudflare OAuth Deployment Smoke` checks the actual public Worker endpoint:

- With the temporary YouTube test active: `GET /health` → 200, `mode:"youtube_testing"`, `providers.youtube:true`, `providers.facebook:false`.
- With the temporary YouTube test active: `GET /api/connections` without a session → 200, both providers disconnected; `POST /api/connections/facebook/authorize` → 503 `provider_not_enabled`.
- After expiry or while testing is disabled: `GET /health` → 503, `GET /api/connections` → 503 `oauth_not_enabled`.
- Responses must carry `Cache-Control: no-store`.

The regular Fast OAuth Checks run Worker and native WebView bridge regression tests. APK and emulator workflows verify Android builds.
## YouTube live readiness (read-only)

The native account screen offers **Check live readiness** after YouTube is verified. Android makes a GET request to `/api/connections/youtube/live-readiness` through its Keystore-protected session transport. The Worker decrypts the channel token **only server-side** and calls YouTube `liveBroadcasts.list` (`mine=true`, `broadcastType=all`) to check API access and retrieve existing active/upcoming broadcasts. UI output is sanitized to basic titles, status, privacy, schedule, and whether a broadcast has a bound stream.

- No user tokens, refresh tokens, CDN ingestion URLs, or stream keys ever leave the Worker.
- The check cannot create, start, or transition a broadcast.
- A successful `liveBroadcasts.list` response means Live API access is available, **not** that video encoding, publishing, or actual streaming has been tested.
- Google `liveStreamingNotEnabled` and `insufficientLivePermissions` responses show actionable but non-sensitive status messages.
- Requests without a valid native session return 401; unauthorized origins return 403; the expired staging window returns 503.
- Tests cover encrypted-token isolation, error sanitization, authentication gating, and real Worker smoke checks.

When the User finishes login on Android, open **Settings → Manage connections → YouTube live readiness → Check live readiness**, then report the visible result. The operator-confirmed **private draft creation** stage is now implemented, but binding RTMPS ingestion credentials, a native broadcast encoder, stream transmission, and Go Live remain separate future stages.


## Private YouTube broadcast draft creation (test-only)

- Available only when **both** the temporary, expiring YouTube OAuth test mode and separate `YOUTUBE_DRAFT_ENABLED` are active. Both switches are `false` in `wrangler.toml` by default. Full production `OAUTH_ENABLED` remains `false`.
- Operator must sign in to a verified YouTube channel, select **Check live readiness**, then use the **Private broadcast draft** form. The operator explicitly enters a title, local start time (at least 10 minutes ahead, at most 7 days), and made-for-kids audience classification, then confirms the action.
- The Worker invokes only YouTube `liveBroadcasts.insert` with `privacyStatus: private` and `enableAutoStart: false`. It does **not** call `liveStreams.insert`, retrieve ingestion keys, bind a stream, start video, change privacy, or transition a broadcast to live. A draft is real content in the linked user's YouTube account, even though it is private.
- D1 table `youtube_private_draft_requests` enforces a session-owned idempotency key and at most **three** draft requests per session per rolling 24 hours. A completed retry returns the original draft; failed or in-progress keys cannot create a duplicate. Daily cleanup purges draft request logs after 30 days.
- No OAuth access or refresh token, Google channel ID, RTMP key, or CDN ingestion URL is returned to Android JavaScript. The Android client independently validates `privacy: private` in the response.
- GitHub tests mock all Google write operations; no real draft is created automatically by CI. The separate Cloudflare deployment smoke checks reject unauthenticated and cross-origin attempts without creating a broadcast.
- On the live Worker the temporary operator test window ends **October 23, 2026 at 11:27 UTC**. The private-draft capability closes at that time even if its feature flag remains set. Do not extend for public release without completing the remaining security reviews.

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

## YouTube test runbook

1. Install the latest **Android APK** from the latest successful `IMG V2 Android APK` GitHub Actions artifact. The app bundles the HTTPS OAuth Worker origin; no developer secrets are entered into the APK.
2. Open Settings → Connected accounts → YouTube → **Connect**, approve the Google authorization in the external system browser using a Google account listed in Google Cloud **Audience → Test users**.
3. Return to IMG LIVE Studio. The Android onResume event automatically checks pairing; if necessary tap **Verify**. The UI must not show **Connected** until the server proves the authorization and YouTube channel identity.
4. If connection is successful, use **Sign out of all accounts** to delete the server D1 session and Android-encrypted session. Verify both connection indicators return to disconnected.
5. If `youtube_channel_not_found` appears, ensure the Google test account has an active YouTube channel. If `token_exchange_failed` appears, confirm Google's exact redirect URI and that the exposed old Google Client Secret was revoked.
6. **Google Cloud OAuth Testing mode has special limitations**, including a seven-day lifetime for some refresh tokens. Do not assume test credentials will behave like production credentials.

Additional safeguards needed before public release: validated Android app/device identity, rate limiting, robust provider identity checks, Meta app approval and OAuth scopes, secure key rotation, and native RTMP publishing.
