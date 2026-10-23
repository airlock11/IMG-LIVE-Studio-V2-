# IMG LIVE Studio V2 — October 23, 2026 continuation checkpoint

**Correct repository:** `airlock11/IMG-LIVE-Studio-V2-` (note final hyphen).
**Branch:** `fix/v2-fourstep-clean-streaming-20261023`
**Base:** V2 PR #11, commit `269c9e440c78dbf0d9dfcbbbec36cdfb745b4030` (`feat/v2-reference-scoreboards-20261023`).
**Do not develop V2 in:** `airlock11/IMG-LIVE-Studio` — that is the wrong Android project.

## Approved UX scope
- Preserve V2's actual native scoreboards and on-air compositor, established charcoal/green design, functioning browser/native flow and YouTube OAuth.
- Four steps: Choose Sport; Teams & Scoreboard; Sponsors; Streaming Setup.
- Step 2 scoreboard preview **landscape 16:9 frame only**, with actual on-air proportional placement/size; no setup camera.
- Normal preview stays in Scoreboard section. After its normal frame leaves the scroll viewport while Scoreboard settings are still in view, a second synced 16:9 preview docks directly above the fixed CTA; scrolling back up restores the in-section preview. Never sticky outside Step 2.
- Step 4 section 1 platform rows with YouTube/Facebook colorful logos, connection-state switches, auto expanding when row tapped, **no chevron**, simple Connect/Disconnect. The YouTube row does not launch consent until Connect is tapped.
- Step 4 section 2 Stream Details only when YouTube verification has succeeded; section 3 Video Quality 720p/1080p, 30/60 fps; section 4 Save locally / Offline recording switches. Remove bottom Back button, remove top arrow square, remove long helper descriptions throughout wizard.
- YouTube's mandatory made-for-kids classification is NOT in Step 4; an explicit choice dialog is triggered if unset just before creating a broadcast. Never guess whether a video is made for kids.

## Commits / changes completed on branch
- `app/src/main/assets/media.js`: setup scoreboard canvas 1280×720; draws the same native bitmap at the same native GL placement fractions as the transmitted video. Existing native renderer untouched.
- `app/src/main/assets/app.js`: compact platform rows/logo SVG, state-based YouTube Connect/Disconnect, no automatic Google consent on row expansion, 4 numbered Step 4 sections, separate local/offline toggles, real 16:9 preview canvas and viewport-sensitive sticky second canvas, smaller visible UI, only ask made-for-kids in on-demand modal.
- `app/src/main/assets/app.css`: dark original theme retained; sticky viewport area; landscape frame; switch and arrow styling, no bottom Back button.
- `account-connections.test.mjs`: regression checks updated to match new consent interaction.
- `BroadcastSettingsUiTest.kt`: actual V2 emulator UI assertions updated for changed connect button and landscape preview.

## Known dependencies and remaining items
1. Check GitHub Actions on THIS branch, including fast OAuth checks, Android APK, side-by-side APK and emulator screenshots. Fix compilation/behavior regressions on this branch.
2. **YouTube visibility remains private-only** in `backend/oauth-worker/src/index.js` and `private-ingest` refuses any non-private event. Do not claim unlisted/public works until secure server and native authorization protocol, idempotency, session ownership, UI and tests support them. Disabled UI options currently honestly show unavailable.
3. **YouTube thumbnail publishing not supported** by current authorized Worker; disabled UI remains honest. Requires authenticated upload proxy and exact broadcast ownership, rate/size/type checks and safe redirection policy. Do not claim operational.
4. No physically verified YouTube ingest stream at 720p/1080p 30/60 FPS yet, only hardware profile checks and CI.
5. Facebook still uses original V2 live stream key flow; new short platform panel keeps stream key field, not a fake Facebook OAuth account sign-in. Test its existing actual video behavior.
6. Sponsor clips are muted in the browser media renderer, but native H.264 video composition requires real device verification. Do not claim native broadcast clip parity.
7. Full-app cleanup beyond wizard is not yet complete; essential status and permission messages must remain visible.
8. Do not merge without emulator/on-device testing and V2 user visual review.

## Resume commands / links
- PR #11 baseline: https://github.com/airlock11/IMG-LIVE-Studio-V2-/pull/11
- Work branch: https://github.com/airlock11/IMG-LIVE-Studio-V2-/tree/fix/v2-fourstep-clean-streaming-20261023
- GitHub Actions: https://github.com/airlock11/IMG-LIVE-Studio-V2-/actions
- Continue fixing THIS repository only. Preserve all native scorebug references. Ship a V2 APK from this branch only.

## Live Cloudflare findings, October 23
Live Worker `img-live-studio-oauth` has `OAUTH_ENABLED=false`, `YOUTUBE_TEST_ENABLED=true`, `YOUTUBE_DRAFT_ENABLED=true`, and `YOUTUBE_INGEST_TEST_ENABLED=true`, but the test expiry `YOUTUBE_TEST_EXPIRES_AT` was `2026-10-23T11:27:17.678Z`; when checked after 12:50Z, it had expired.
Code gate `youtubeTestActive()` denies YouTube auth and draft creation after expiry even if other flags say true. **The Cloudflare settings were READ-ONLY inspected; no production flags or secret bindings were changed.** Production `OAUTH_ENABLED` intentionally remains false until hardening. The next safe controlled-test step is to extend a short explicit, time-bounded test window without globally enabling production authentication, then validate channel eligibility and upload with a permitted test account. Do not enable general production OAuth or public/unlisted publishing merely to bypass this safety gate.

## Verified October 23 build and emulator results
- Native Android APK from SHA `203f82d8bed0faa4a60993390aea37472fda30e7` built successfully via GitHub Actions run `39958190804`.
- The V2 emulator smoke run `39958190684` **passed 11 instrumentation tests**, including actual WebView 16:9 scoreboard canvas, scroll docking/un-docking, wizard, and other native graphic tests.
- JS/Cloudflare fast OAuth checks passed on earlier code; UI changed after that point only for viewport constraint. Build remains an Android debug/test APK, not a signed production release.
- Code fix cause: nested `.img-wizard-scroll` pane was expanding to content height (`scrollTop=0,maxScroll=0`) instead of being bounded to Android viewport; CSS now forces constrained flex scroll container and native instrumentation passed.
- Test APK from V2 GitHub Actions `IMG-LIVE-Studio-V2-debug-apk`, run `39958190804`, artifact ID `12502939151`.
- Physical Android YouTube login/session/live stream quality **NOT yet verified**; backend OAuth limited testing window expired, no Cloudflare prod setting changed.
- PR #12 stays draft/unmerged, so no production update until user's review and backend/live tests.
