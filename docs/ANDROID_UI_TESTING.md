# Android preview/settings and continuous streaming — device acceptance

2026-10-01: source/build checks pass; all physical rows below are NOT RUN.
No Android device was connected during implementation. Install
`apps/android/app/build/outputs/apk/debug/app-debug.apk` with the Android package
installer. USB debugging is optional for developer installation, never operation.
Use the existing Windows receiver command and native OBS source/pipe unchanged.
For USB retain the accepted helper/options, explicit adapter/bind/peer and tested
receive buffer; see USB_NETWORK_TESTING.md. Do not substitute older USB defaults
for the actual accepted Xiaomi setup in a regression comparison.

## Device checks

Preview follow-up: the prior APK was user-reported sideways and not full-screen.
The corrected APK uses full-window center-crop with overlay controls; verify
upright rear/front preview in portrait and both landscape directions. Edges may
be cropped locally to fill the display; OBS framing must remain unchanged.
The original artifact hash at the end of this document identifies the initial
UI build; the correction's fresh hash/build evidence is recorded separately.

1. Fresh permission state: deny Camera, verify explanation/retry without a crash;
   allow it and verify immediate preview. Also test permanently denied -> Allow
   Camera -> Android app settings -> grant -> return. Revoke permission while
   backgrounded and repeat. Check rear/front and available physical lens routes,
   portrait/landscape, correct aspect and front mirror; OBS dimensions stay fixed.
2. Settings: choose Wireless; discover/select the PC or enter numeric IPv4.
   Choose camera and 720p/1080p where advertised. On Xiaomi explicitly opt into
   exact 1080p when encoder metadata rejects it; select 1920x1080. 720p remains
   available. 4K remains experimental and is not an acceptance target here.
   Close/reopen the app and confirm valid choices restore. Invalid address/port
   must not overwrite the last valid persisted value. Saved discovered PC uses
   its numeric address/advertised port if discovery is unavailable after restart.
3. Start normal streaming; watch native OBS. Open/back out of Settings repeatedly
   and rotate the phone. Picture/counters must continue with no new stream SSRC,
   encoder mode change, interruption or visible regression. Camera/resolution/
   connection/USB refresh controls must stay disabled until teardown completes.
4. Stop/start at least ten times, including Stop during Connecting. Confirm Stop
   completes, preview returns and each new stream has fresh SSRC/configuration/
   IDR. No stuck Camera in use, codec worker or sender/link-monitor worker. Background
   and lock screen while streaming: stream stops; foreground return opens preview
   and shows the reason, requiring explicit Start. Test another app taking camera
   access/interruption; retain errors instead of claiming seamless recovery.
5. Wireless: disconnect the WAN while local Wi-Fi stays up; discovery and video
   must work without internet. Wireless loss: disable Wi-Fi or disconnect/change the selected local network. Confirm
   explicit failure/teardown and no silent stream recovery into another network.
   Restore/reselect and Start. Exit receiver alone: phone can still report sender
   active because RTP/UDP has no acknowledgement; verify OBS/receiver separately.
6. USB: turn off alternative phone media routes for route qualification, manually
   enable tethering, select the confirmed adapter/local address and PC address.
   Start with USB debugging off. Confirm same native OBS path. Unplug/change
   address: failure/teardown, no LAN fallback. Reconnect, refresh/reselect both
   endpoints and restart helper/phone. Restart app with the unchanged saved link:
   selection restores only if its full identity matches; missing/changed link
   requires selection, never automatic first-candidate substitution.
7. Compare normal preview-enabled streaming against encoder-only timed Diagnostics
   on the same scene, lens, resolution, transport and helper. Use 720p and 1080p
   separately on LAN and USB. Verify actual encoder output/crop/FPS stay selected;
   compare sender/receiver/OBS deltas, visible motion/drops and CPU/thermal/battery.
   No silent mode downgrade if the combined preview/encoder session is rejected.
   Run normal mode beyond ten minutes (minimum 15), verify no automatic stop and
   bounded queues/samples/memory. Run timed RTP Diagnostics to ten minutes and
   capture-only to five minutes: they still stop; export final JSON before the
   next timed run. Profiling and JSON document-picker export remain in Diagnostics.

Record APK SHA256, device/OS, selected route/mode, transport topology/confirmed
band, helper command, run mode, previewIncluded, duration, SSRC and correlated
sender/receiver/native OBS logs. Label each check PASS/FAIL/NOT RUN. Device/setup
results do not qualify all phones or Wi-Fi bands. Accepted Xiaomi historical
results: USB1080 ten-minute matching totals/zero RTP gaps; ~five-minute smooth
5 GHz1080; 2.4 GHz1080 visible drops despite clean sender; short 48-second smooth
2.4 GHz720. These are comparison context, not automatic resolution policy.

| Gate | Result |
|---|---|
| Preview / permissions / orientation | NOT RUN |
| Valid persistence / unavailable selection | NOT RUN |
| Streaming navigation / rotation / controls locked | NOT RUN |
| Start / Stop / restart / background / interruption | NOT RUN |
| Wireless and USB setup / failure / link loss | NOT RUN |
| Matched 720p/1080p preview regression | NOT RUN |
| Continuous >10 min / timed 5 and 10 min / export | NOT RUN |

Build from apps/android using the installed JDK17/SDK36:
`./gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
Host tests cover timer policy, settings locks through stopping, identity-based
restoration, USB selection/subnets, bounded compressed queues, expiry/recovery,
resolution-dependent bounds and pacing. They do not execute Camera2 or prove
physical preview/OBS quality. No audio, remote controls, multicamera or desktop
management change belongs in this acceptance gate.

## Prepared APK / host evidence

Final APK: 1,230,613 bytes; SHA256
`DB8C56CCDE583CA2EE392D2381C6E9AC3D08751BCCB083D49CFA8F54A0609BC0`.
Build/test/lint: `evidence/2026-10-01-android-ui-build-tests.log`.
JUnit XML: `evidence/2026-10-01-android-ui-policy-tests.xml` (3 tests) and
`evidence/2026-10-01-android-ui-transport-tests.xml` (6 tests); zero failures/errors.
Lint: zero errors, 23 warnings (mostly user-visible string localization).
`git diff --check` passed; Windows receiver/native OBS source diff is empty.

## Corrected full-screen/orientation APK

2026-10-01: 1,233,177 bytes; SHA256
`9776FFE0FC84E9D05FEBAD20F252FCD95E2BC839A50BB743804D5F590B1A1E72`.
assembleDebug, 13 unit tests and lintDebug passed. Evidence:
`evidence/2026-10-01-preview-layout-build-tests.log` and
`evidence/2026-10-01-preview-geometry-tests.xml`. Physical retest OPEN.

## Transparent controls APK

2026-10-01: 1233761 bytes; SHA256 `2E81230D6E34FE273BE27A8FC790307D695AD0A30063E190B290160664457CDA`.
Build/lint evidence: evidence/2026-10-01-transparent-controls-build-lint.log.
Check floating controls against bright/dark camera scenes; physical retest OPEN.
# Friendly Settings follow-up

Select Wireless and USB Network in turn: only the relevant connection fields
should appear. Check labelled camera/resolution choices, invalid address/port
feedback, automatic persistence, and the active-stream settings lock. Diagnostics
contains opt-in trials, timed validation and JSON export. Repeat navigation during
streaming and compare accepted 720p/1080p OBS behavior. Physical acceptance OPEN.

Settings UX APK: SHA256 12A7CD8FFD7DD9CB5B70B76C07DF0B4CA95DC2848CD24526605194AECEE7862A. assembleDebug, all 13 unit tests and lintDebug passed. Log: evidence/2026-10-01-settings-ux-build-tests.log. Physical checks remain OPEN.

## 2026-10-01 — Compact camera overlay and expandable tools

Camera screen status no longer exposes receiver IP or raw connection setup details.
It retains Start/Stop and a transparent, accessible tools icon. Tap the icon to
reveal Settings; tap again or press Back to collapse. Opening Settings collapses
tools and preserves the stream. Connection/address fields and actionable errors
remain on Settings. Streaming status says to check OBS, preserving the distinction
between sender activity and PC reception. Tools currently contains Settings only;
zoom, torch and camera-switch controls on Android/OBS are the next slice.

Build/device proof is recorded separately. Physical checks: expand/collapse over
bright/dark scenes, Back behavior, tools/Settings navigation during streaming,
Start/Stop, no IP on main, visible setup failures in Settings, and unchanged
accepted 720p/1080p OBS framing/smoothness. No camera/encoder/transport changes.

Compact tools APK SHA256: 3719634888E9790AFA016B6AC6F1FBEC1989AC54267C13E5F486E4AE45572DFB. assembleDebug, all 13 unit tests and lintDebug passed. Log: docs/evidence/2026-10-01-camera-tools-ui-build-tests.log. Physical UI/streaming acceptance remains OPEN.
