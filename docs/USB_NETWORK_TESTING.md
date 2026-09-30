# USB Network Mode — Operator Setup and Physical Acceptance

All physical results are NOT RUN as of 2026-10-01. The user chose to test later.
Use the checklist below; record failures rather than replacing them with build
or synthetic evidence. Run from repository root in PowerShell.

## Install and select endpoints

1. Install `apps/android/app/build/outputs/apk/debug/app-debug.apk` on Samsung
   SM-X115 using the normal Android package installer (copy/download the APK to
   the device). ADB may be used for development installation if desired, but is
   not required. Turn USB debugging OFF before acceptance and record it.
2. Turn phone Wi-Fi and mobile data OFF; connect USB and manually enable **USB
   tethering** in Android Settings. Keep the CamSure screen foregrounded. Leave
   other phones disconnected for the initial one-camera test.
3. In Windows network settings, identify the newly available USB Ethernet
   adapter. For route proof temporarily remove alternative media routes (PC
   Wi-Fi off and LAN cable disconnected); record this topology. Preserve normal
   network settings for restoration after the test. Keep the console local.
4. On Android check **USB Network Mode**, tap **Refresh USB network addresses**,
   and explicitly select the tethering local IPv4. The selector lists candidates,
   not certified USB devices. Note the selected interface/address/prefix.
5. List Windows candidates using the newly built helper:

   ```powershell
   dotnet .\tools\windows-rtp-receiver\bin\usb-validation\CamSure.RtpReceiver.dll --list-adapters
   ```

   Match the Windows USB adapter and copy its ID and local IPv4. Do not select
   the normal LAN Ethernet or Hyper-V virtual adapter. Inventory must be re-run
   after any address change; do not reuse the LAN PC address from old tests.
6. Enter that Windows IPv4 in Android's receiver field; keep USB media port 5004
   initially. Prepare routes and select the Samsung rear logical route. Confirm
   the plan uses the actual highest direct hardware mode (baseline 1080p30).
7. Stop the older receiver with Ctrl+C in its own console before testing if it
   holds port 5004/pipe `camsure-camera-1`. Start the new helper using values from
   this test, replacing all placeholders:

   ```powershell
   dotnet .\tools\windows-rtp-receiver\bin\usb-validation\CamSure.RtpReceiver.dll --transport usb --adapter "ADAPTER_ID" --bind WINDOWS_USB_IPV4 --peer ANDROID_USB_IPV4 --port 5004 --obs-pipe camsure-camera-1 2>&1 | Tee-Object docs/evidence/usb-physical-receiver.log
   ```

   The helper must report USB_NETWORK, the chosen adapter/address, expected
   Android peer and effective buffers. An unresolved selection is a failure,
   not permission to use a wildcard bind. Each independent source needs its own
   media port and pipe; use the matching Android USB port when changing it.
8. Open OBS using the existing installed CamSure plugin. Add/edit **CamSure
   Camera**, enable **Real camera video**, set **Local AU pipe** to the same
   unique pipe. Start the Android camera run. No new OBS plugin install is
   required: its decoder/source implementation did not change.

## A — Route proof

Start packet capture on the confirmed Windows USB adapter (for example Wireshark
capture interface selection). Filter to `udp.port == 5004`. Save capture and
adapter/interface inventory with source/destination IPs. Record phone debugging,
Wi-Fi and mobile data OFF, tethering ON, and absence of alternate media routes.
Compare capture addresses with Android bound local address and Windows bind/peer
telemetry. Android/Windows counts must increase together. A socket bind report
without actual adapter capture is not a physical pass. A ping alone is not proof
that CamSure media took that path. Peer rejection can be tested from a different
IP on a separate controlled setup; rejected-peer count must rise without RTP/AU
counts rising for those datagrams.

## B/C — Real image and 600 seconds

Confirm native camera image, no Android UI, correct aspect, 1920x1080 around
30 FPS where supported, no persistent corruption/freeze and no fallback. Run at
least 600 s (Android endpoint runs retain the existing ten-minute timer). Export
Android runtime JSON immediately afterward, before starting the next run. Stop
helper with Ctrl+C to capture final counters and retain the OBS current log.
Record beginning/middle/end observations and counter deltas:

| Evidence | Required measurements |
|---|---|
| Android JSON | FPS, bitrate, AUs, packets, SSRC, send failures, all drop reasons, maximum queue depth/bytes/age, CPU, thermal and battery |
| Receiver log | packet/AU/IDR counts, gaps/incomplete/oversize/stale, peer rejects, PTS mismatches, generation/SSRC, effective buffers, pipe writes/drops/errors/high-water |
| OBS log | decoder input/decoded/submitted frame deltas and elapsed time, errors/resets/drops; distinguish submissions from actual display |
| PC samples | receiver/OBS working/private memory and CPU trend, baseline and end; do not label accumulated CPU seconds as instantaneous percent |
| Operator | any freeze, corruption, latency growth, heat, disconnect or crash |

Use Task Manager/Performance Monitor for PC trends; note sampling cadence and
process IDs. Store evidence under `docs/evidence/` without editing historical
files. PASS requires no sustained latency growth/freeze/corruption/runaway memory.

## D — Glass-to-glass

Record a live millisecond timer and its OBS image together using high-speed video.
Take repeated samples at beginning, middle and end; retain recording/frame-rate,
raw paired readings and sample times. Calculate actual differences, p95 and max
only with enough samples; target p95 <=250 ms / maximum <=500 ms. Encoder source
PTS, reconstruction time and decoder timing do not measure glass-to-glass.

## E — Ten cable cycles

On removal, source must clear/stop (existing idle clear <=1.5 s after last input),
helper must report epoch end, sender must abort, and OBS must remain responsive.
After reconnect: manually re-enable tethering if needed, refresh Android
inventory, list Windows adapters again, reselect both endpoints, restart helper,
export previous Android report then start a fresh stream. Record new SSRC and
receiver generation, valid configuration/IDR recovery and absence of stale replay.
At least one removal must occur near a large IDR (existing one-second cadence;
correlate capture/log evidence rather than assuming exact timing).

| Cycle | Near IDR? | Clear time | Workers ended? | New SSRC/generation | Recovery seconds | No stale replay/crash? | Result |
|---|---|---|---|---|---|---|---|
| 1 | NOT RUN | | | | | | NOT RUN |
| 2 | NOT RUN | | | | | | NOT RUN |
| 3 | NOT RUN | | | | | | NOT RUN |
| 4 | NOT RUN | | | | | | NOT RUN |
| 5 | NOT RUN | | | | | | NOT RUN |
| 6 | NOT RUN | | | | | | NOT RUN |
| 7 | NOT RUN | | | | | | NOT RUN |
| 8 | NOT RUN | | | | | | NOT RUN |
| 9 | NOT RUN | | | | | | NOT RUN |
| 10 | NOT RUN | | | | | | NOT RUN |

## F — Consumer stall

On a test scene, briefly pause the downstream OBS process using a debugger or
controlled process suspend/resume, then resume it promptly. Record actual stall
duration and pipe/native queue/drop/memory metrics. Stop sending before teardown
if needed. PASS: queues stay bounded, stale work is dropped, no runaway memory,
resume begins from valid configuration+IDR and old buffered video is not replayed.
The Android deterministic queue unit test simulates a stall but does not replace
this end-to-end test. Record NOT RUN if no safe controlled stall tool is available.

## G — OBS frontend lifecycle

While USB sending: remove/re-add CamSure source, hide/show, close/reopen OBS
normally and restore saved scene/collection. Record each result and logs. Check
no orphan pipe/decoder workers or conflicts and no previous-session stale video.
The synthetic libobs probe is separate evidence.

## H — Physical LAN regression

Stop USB receiver and Android stream; uncheck USB mode, restore Wi-Fi/LAN topology,
start helper without USB flags using the existing LAN command/pipe. Confirm real
video, start/stop and remove/re-add source. Compare to the accepted Samsung LAN
baseline. Record PASS/FAIL/NOT RUN; existing synthetic success does not close it.

## Build and host tests

```powershell
# Android directory; requires documented JDK 17/SDK setup
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
# Repository root; isolated output avoids replacing an active older helper
 dotnet build tools/windows-rtp-receiver -o tools/windows-rtp-receiver/bin/usb-validation
 dotnet tools/windows-rtp-receiver/bin/usb-validation/CamSure.RtpReceiver.dll --self-test
 .\plugins\obs-camsure\tests\run-video-probe.ps1 -ReceiverDll "$PWD/tools/windows-rtp-receiver/bin/usb-validation/CamSure.RtpReceiver.dll"
```

These are host/simulated tests. Physical acceptance stays open until A–H are recorded.
