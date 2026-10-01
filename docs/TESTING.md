# TESTING.md

Transparent-controls follow-up (2026-10-01): verify camera-screen Settings and
Start/Stop have no filled button background or footer panel, remain readable
over bright/dark camera scenes, and retain their touch/disabled behavior.
Settings/Diagnostics remains a separate page. Physical visual retest OPEN.

## Preview sizing/orientation regression (2026-10-01)

User reports previous APK preview was not full-screen and sideways in portrait.
Retest the replacement APK: rear preview upright in portrait, both landscape
directions and reverse portrait where supported; front orientation/mirroring;
camera fills the display behind overlay controls, including after navigation,
rotation and resize. Center-crop must preserve aspect (some edge content is
cropped locally); encoder/OBS framing and resolution must remain unchanged.
Check 180-degree rotations without a resize. Automated geometry tests cover
fill/no-stretch and no duplicate sensor rotation; they do not inspect device
camera pixels. Corrected-build physical results remain NOT RUN / OPEN.

## 2026-10-01 Android UI / continuous-mode acceptance gate

Source/build gate passes: assembleDebug, testDebugUnitTest (nine tests, zero
failures/errors) and lintDebug. See evidence/2026-10-01-android-ui-build-tests.log.
Physical gate is **NOT RUN / OPEN**: no device was connected. Follow
[Android UI device checklist](ANDROID_UI_TESTING.md), including preview versus
encoder-only Diagnostics comparison at identical selected 720p/1080p modes.
The older no-preview/no-launch-permission procedures below describe historical
phases. The current launch requests Camera for preview; profiler collection itself
remains metadata-only. Timed validations remain five/ten minutes; normal Start
is continuous. Build proof does not establish preview quality or service stability.

## 2026-10-01 higher-resolution checkpoint gates

Android assembleDebug, testDebugUnitTest and lintDebug cover opt-in 1080p/4K
selection and bounded sender policies. Native decoder tests cover 720p,
1080p and 4K with exact PTS and colored Y/U/V assertions; run-video-probe.ps1
supports -Uhd for synthetic 4K recovery/teardown. CAMSURE_FORCE_SOFTWARE=1
provides a diagnostic software-only run. Synthetic success does not qualify
real 4K performance or hardware-failure recovery on every PC.
See PROGRESS for matched ten-minute Xiaomi USB1080 sender/receiver evidence
and user-observed 5 GHz1080 / short 2.4 GHz720 results. 2.4 GHz1080 has visible
drops despite clean sender counters. Receiver loss correlation, multiple phones,
service-duration battery/thermal behavior and 4K grain/stutter remain open.

## Purpose

The project must be tested as a live-production system, not merely as a video-demo application.

---

# 1. Core Functional Matrix

Test every supported release against:

```text
1 camera
2 cameras
4 cameras
```

At minimum:

```text
720p30
1080p30
1080p60
```

Advanced modes are added to the matrix only after implemented.

---

# 2. Multi-Camera Independence

For four connected cameras:

- disconnect Camera 2
- Camera 1/3/4 continue
- restart Camera 2 app
- Camera 2 reconnects
- change Camera 3 lens
- Camera 1/2/4 remain unchanged
- change Camera 4 bitrate
- other sessions remain unchanged

Any shared-state leakage is a failure.

---

# 3. Latency Testing

Latency should be measured rather than described as "feels fast".

Recommended test:

Display a high-resolution running timer on a monitor.

Capture the timer using the phone.

Show both the original timer and OBS preview/output in the same high-frame-rate recording.

Calculate time difference.

Record:

```text
phone model
camera/lens
resolution
FPS
codec
bitrate
connection type
buffer mode
OBS settings
PC hardware
measured latency
```

---

# 4. Queue Stability

Run a camera for an extended session.

Track:

```text
frame queue depth
decoder queue depth
memory
latency
```

Failure condition:

Latency continuously increases during an otherwise stable connection.

---

# 5. Network Degradation

Simulate or create:

- weak signal
- temporary packet loss
- brief AP interruption
- congestion
- reconnect
- IP change if applicable

Desired behavior:

- bounded latency
- visible health metrics
- fast recovery
- no crash
- other cameras unaffected

---

# 6. Device Reconnect

Test:

- app restart
- Wi-Fi reconnect
- screen off/on
- cable reconnect when USB exists
- phone reboot
- OBS source disable/enable
- OBS restart

Persistent camera identity should be maintained where possible.

---

# 7. Camera Controls

Per supported device:

- lens switching
- zoom min/max
- focus
- exposure
- AE lock
- AWB lock
- torch
- manual controls
- stabilization

Unsupported controls should never silently pretend to succeed.

---

# 8. Thermal Testing

Run:

```text
1080p30
1080p60
4K later
```

for realistic event durations.

Observe:

- thermal throttling
- encoder FPS
- battery percentage
- charging state
- device surface temperature if measurable
- unexpected camera shutdown
- automatic FPS reduction

---

# 9. Power Testing

For long live sessions test:

- battery only
- direct charging
- powered USB hub later

Record whether the phone gains, maintains, or loses battery while streaming.

---

# 10. OBS Stress Test

OBS workload should include realistic usage:

- multiple scenes
- overlays
- browser sources if normally used
- streaming encoder
- recording
- transitions
- four phone cameras

The camera plugin should be tested in the real production workload, not only in an empty OBS scene.

---

# 11. Frame-Rate Stability

Record:

```text
requested FPS
capture FPS
encoded FPS
received FPS
decoded FPS
submitted OBS FPS
```

This identifies where frame loss begins.

---

# 12. A/V Sync

When audio is implemented:

Test over long sessions.

Check:

- initial sync
- drift after 10 min
- drift after 30 min
- drift after 1 hr
- reconnect behavior

Do not consider audio complete based on a short test.

---

# 13. Camera Synchronization

When sync mode is implemented:

Use one visual timing source visible to all cameras.

Measure inter-camera offset.

Report both:

```text
lowest-latency mode
synchronized mode
```

---

# 14. Regression Rule

Every fixed critical bug should gain a reproducible test procedure in this file or an automated test where practical.

---

# 15. Phase 1 — Native Diagnostic Acceptance Gate

Build/install procedure: ../plugins/obs-camsure/README.md.

Use a dedicated test scene collection, OBS at 1920x1080/60 FPS, and four
**Create new** CamSure Camera sources. Arrange them in four visible quadrants
(960x540 each, retaining aspect ratio). Do not use Add Existing to create cameras.

| Source | Friendly Name | Resolution | FPS | Pattern |
|---|---|---|---|---|
| CAM 1 | Pulpit | 1920x1080 | 30 | Color Band |
| CAM 2 | Wide | 1920x1080 | 30 | Checkerboard |
| CAM 3 | Keyboard | 1920x1080 | 60 | Color Band |
| CAM 4 | Drums | 1280x720 | 60 | Checkerboard |

1. Confirm four distinct instance IDs, names, dimensions and moving markers.
2. Change CAM 2's Friendly Name to Wide Changed. CAM 1/3/4 must stay unchanged.
3. Change CAM 3 to 1280x720. Confirm its dimensions and output change while
   CAM 1/2/4 stay unchanged. Refit its scene transform if necessary.
4. Change one FPS/pattern setting. Confirm only that source changes.
5. Delete CAM 2, including its last scene reference. CAM 1/3/4 must continue.
   Confirm a matching instance destruction log after references are released.
6. Add CAM 2 again as a new source. Confirm a new instance ID and stable output.
7. Duplicate CAM 1 as an independent source (Paste Duplicate where offered).
   Change the duplicate's Friendly Name; original CAM 1 must remain unchanged.
   Delete the duplicate.
8. Rename an OBS source. Its friendly diagnostic name must not change.
9. Hide/show a source with the eye control. Disable/enable where supported.
   Confirm output returns and the other instances continue.
10. Create a second scene referencing the existing sources, switch scenes several
    times, and return. Existing references deliberately share their source;
    settings and source count must remain consistent.
11. Save/export the test scene collection. Record all four final settings.
12. Close OBS normally. Confirm no crash/hang. Restart OBS and reopen the collection.
    Confirm four sources restore the recorded names, dimensions, FPS and pattern.
    IDs may change across restart; settings and source assignments must not.
13. Run for 10 minutes, repeat delete/recreate 20 times, and check for steadily
    growing memory or repeated error logs. Record observations, not a leak-free claim.

Record: date, tester, OBS version, build DLL SHA256, PC/GPU, steps passed/failed,
log path, observed memory behavior and any crash. **Phase 1 remains incomplete
until steps 1–12 pass in the OBS frontend.** Step 13 is the lifecycle stress check.

Automated evidence is in evidence/2026-09-26-lifecycle.log. It exercises real libobs
and D3D11 readback, but save/load is in-process and does not prove scene collection
disk persistence or application restart.

## Recorded manual result — 2026-09-27

User reports "all passed" for the primary four-source checklist: four independent
sources, CAM 2 friendly-name isolation, CAM 3 resolution isolation, CAM 2
delete/recreate with survivors continuing, and scene collection save/OBS restart
with settings restored. Plugin discovery/loading was also confirmed after the
installation path was corrected. See PROGRESS.md for the evidence boundary.

The initial response covered the primary checklist; the follow-up below closes the remaining checks.

### Follow-up acceptance — 2026-09-27

The user subsequently confirmed the remaining duplication/edit, rename, hide/show,
scene-switching and 10-minute four-source delete/recreate soak checks were already
done. Phase 1 manual acceptance is recorded as PASS. This is user-reported evidence;
no numerical memory or effective-FPS measurements were supplied.

# 16. Phase 2 — Android Capability Profiler

Build from `apps/android` with JDK 17, Android SDK Platform 36, and Build Tools
36.0.0:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

For each physical phone, record manufacturer/model, Android release/API, app
version, date, and the device used. Install and open the app, verify Camera
permission is not requested at launch, then tap **Scan capabilities** and
exercise both permission-granted and permission-denied/retry paths. Rescan after
completion and rotate/recreate the Activity during a scan to confirm the single
in-process scan is retained. Export a JSON report through the document picker.
Parse the file and confirm `schemaVersion`, collection time, device/build
metadata, camera/encoder inventories, per-field statuses/units, and accuracy
notes. Verify no serial number or persistent device identifier is included.

Repeat on at least two physical phones with distinct Android/API and camera
hardware where possible. Compare camera IDs/lens facing, logical/physical
camera metadata, stream formats/sizes/durations, FPS/high-speed ranges, controls,
dynamic-range profiles, and H.264/HEVC encoder constraints. Keep Camera2 stream
sizes/FPS and encoder constraint ranges as separate facts; do not infer every
camera/encoder pairing works. Report inaccessible, query-failed, partial, and
API-unavailable fields separately from unsupported values.

No preview, capture session, runtime encode, thermal, or performance result is
claimed by this procedure. Phase 2 exit requires the exported reports and
cross-device comparison. Record build/lint, each device and manual outcome, JSON
parsing result, comparison summary, observed issues, regressions, and remaining
runtime questions in `PROGRESS.md`.

### Recorded tablet report — 2026-09-27

The user supplied `docs/evidence/2026-09-27-samsung-sm-x115-capabilities.json`,
exported from a Samsung SM-X115 tablet running Android 16/API 36 and app 1.0.0.
It parses as schema 1.0.0 and includes two camera profiles and seven encoder
entries, with no collection issues. The artifact demonstrates that a scan and
JSON export completed on this tablet. It does not establish that the permission
denial/retry path, rescan, or rotation-during-scan path was exercised.

The report's rear and front profiles both advertise hardware level 3 and manual
sensor capability. Rear PRIVATE/YUV outputs include 1080p30 and 720p30; front
PRIVATE/YUV outputs include 720p but not 1080p. Both AE FPS inventories top out
at 30 FPS and report no constrained high-speed sizes or dynamic-range profile
values. MediaTek hardware H.264/HEVC entries advertise the 720p30 and 1080p30
point checks but not 1080p60. These fields are metadata; this result is not a
runtime capture or encode test. One device is insufficient to close the Phase 2
cross-device exit gate.

### Second device and comparison — 2026-09-27

The user supplied `docs/evidence/2026-09-27-xiaomi-2406apnfag-capabilities.json`,
from a Xiaomi 2406APNFAG running Android 16/API 36 and app 1.0.0. It parses as
schema 1.0.0 with two camera profiles, seven encoder entries, and no collection
issues. Compare it with the Samsung SM-X115 report recorded above.

The Xiaomi rear camera advertises logical multi-camera metadata with physical
IDs 3 and 6; its front camera does not. Both cameras report two constrained
high-speed sizes and dynamic-range profiles. Both expose 1080p video-relevant
sizes. The hardware encoder point checks report 720p30 supported and 1080p30
unsupported for H.264 and HEVC. Samsung instead reports no logical/physical or
high-speed/dynamic-range metadata, and its hardware encoder point checks report
1080p30 supported. Both report 1080p60 unsupported. Xiaomi reports 16x16
encoder alignment; 1920 is aligned but 1080 is not divisible by 16. Samsung
reports 2x2 alignment. This explains why Xiaomi's exact 1920x1080@30 point
query is unsupported despite its broad width/height ranges including those
dimensions.

The two reports have different manufacturer/model values but the same API 36,
so they satisfy the Phase 2 multi-device metadata comparison gate but do not
exercise API-level differences. These are advertised constraints, not proof of
runtime camera/encoder compatibility; leave that validation to Phase 3. This
second report supersedes the earlier single-device status above.

# 17. Phase 3 — One Camera to Hardware Encoder

Build and install the Android app using the procedure in apps/android/README.md.
The Android app requests Camera permission only after the user selects Scan
capabilities or Prepare camera routes. Keep the app in the foreground during a
run; the screen stays awake and leaving the app stops the test.

## Samsung SM-X115 — 1080p30 H.264 baseline

1. Record phone model, Android/API/build, app version, date, battery and charge
   state, and starting thermal status.
2. Tap Prepare camera routes and grant Camera permission. Select the rear
   logical camera route.
3. Confirm the direct-mode list includes exact 1920×1080 at 30 fps and that the
   selected encoder is reported as hardware accelerated. This is still metadata
   until the run configures successfully.
4. Start the highest direct mode and let the five-minute run complete. Record
   configured state, actual encoder name, output width/height/crop, profile and
   level, capture and encoded FPS, keyframe interval, bitrate, timestamps,
   dropped-frame estimates, capture failures, CPU, thermal, battery, EOS, and
   queue limitations from the screen and exported JSON.
5. Repeat the same run once. Compare the two outputs and report any setup,
   capture, codec, thermal, or cadence failures.

## Xiaomi 2406APNFAG — 720p30 baseline and physical routes

1. Repeat the baseline procedure on the rear logical route. The direct exact
   1920×1080 point is expected to remain unsupported by the Phase 2 metadata;
   select its highest exact shared direct mode, expected to be 1280×720@30.
2. Repeat the five-minute 720p30 run once and compare both JSON reports.
3. Select logical-camera physical output routes 3 and 6 separately when Camera2
   reports them. Record whether each output route can configure and encode.
4. If IDs 3 or 6 also appear as standalone Camera2 routes, attempt each
   standalone route separately and record whether Camera2 can open it. Do not
   infer independent-open support from a physical-output route working.

## Xiaomi aligned 1080p investigation

The direct-surface experiment does not implement a padded encoder input or GPU
crop. Keep the camera-advertised 1920×1080 mode visible in the result. A
1920×1088 backing surface with a measured visible 1080-line crop or another
GPU-side alignment path is a separate experiment; document its configured
dimensions, crop, CPU/GPU path, cadence, and dropped frames if implemented.
Do not treat an unsupported exact-size metadata query as runtime evidence for or
against that path.

## Evidence and gate

Export each run's Phase 3 runtime JSON. It includes Camera2 sensor timestamp
samples and encoder presentation-time samples as separate clocks, capture and
encoded frame counts/cadence, keyframes, per-gap drop estimates, Camera2
capture-failure counts, encoder format/crop/profile/level, requested and
measured bitrates, process CPU, thermal and battery state, and EOS. Camera2 and
MediaCodec internal buffering beyond the app-owned queue is not publicly
measurable in this experiment. GPU utilization is unavailable through its app
APIs. Use Android logcat filtered to CamSurePhase3 for setup and output errors.

Phase 3 is not accepted by a successful build or capability query. Acceptance
requires repeatable physical-device capture sessions and hardware encoder runs,
the reported output mode and timestamps, measured cadence and drops, and an
audited direct-surface path with no CPU frame-copy stage. Keep Samsung 1080p30,
Xiaomi 720p30, Xiaomi aligned 1080p, and physical-camera results separate.

### User-supplied runtime reports — 2026-09-27

The user supplied these initial physical-device exports. Their JSON parses as
schema 1.0.0; the original data is preserved unchanged:

- [Samsung SM-X115 / Samsung A9, 1080p30 run 1](evidence/2026-09-27-samsung-sm-x115-phase3-1080p30-run-1.json) — SHA-256 `C94B986CA6229104399AA69CA052F76407714A481A6D75D9EA754583B0CB28D7`.
- [Xiaomi 2406APNFAG / Xiaomi 14T, 720p30 run 1](evidence/2026-09-27-xiaomi-2406apnfag-phase3-720p30-run-1.json) — SHA-256 `389A2263C295F4B68C5E1E1E8B422BAF2F1BC81C7F7DF6E6005C2AE3F80B1C20`.
- [Xiaomi 2406APNFAG / Xiaomi 14T, 720p30 run 2](evidence/2026-09-27-xiaomi-2406apnfag-phase3-720p30-run-2.json) — SHA-256 `DEB2A5E8467BABC3AB87643DF40CCF4425545600483B9474370672C70EA171FF`.

All three initial exports report a completed five-minute direct Camera2-to-encoder-
Surface session, hardware `c2.mtk.avc.encoder`, successful encoder and capture
session configuration, full-frame output crop, approximately 30 FPS, zero
estimated drops, zero capture failures, and monotonic timestamp sequences.
Samsung's one 1920×1080 run measured 30.0036 capture/encoded FPS and 7.989 Mbps.
The two Xiaomi 1280×720 runs measured 30.0099 and 30.0078 FPS, with 3.625 Mbps
in both runs. These results support the tested direct baselines; they do not
prove encoded-file playback or visual quality because the experiment does not
save a bitstream.

At this point, the Samsung baseline still needed a second run and the Xiaomi
baseline had a two-run repeat. These initial exports omit
`measurements.endOfStreamSeen`; the exporter was updated to include it.

### Follow-up reports — 2026-09-27

The user supplied one additional Samsung run and one additional Xiaomi run:

- [Samsung SM-X115 / Samsung A9, 1080p30 run 2](evidence/2026-09-27-samsung-sm-x115-phase3-1080p30-run-2.json) — SHA-256 `AACAF90C84D440EC068FCBFDFBD0EC7B7AD4C86EB324231CEA02EE73740474CE`.
- [Xiaomi 2406APNFAG / Xiaomi 14T, 720p30 run 3](evidence/2026-09-27-xiaomi-2406apnfag-phase3-720p30-run-3.json) — SHA-256 `0F290A4C4A0EE608077636F04F4A759FAE3427123305D4C3EA6A5884E91733EB`.

Both reports show a completed five-minute session using hardware
`c2.mtk.avc.encoder`, successful encoder and capture-session configuration,
full-frame crop, monotonic timestamp sequences, zero capture failures, and
`measurements.endOfStreamSeen: true`. Samsung measured 30.0003 capture/encoded
FPS at 1920×1080 and 7.990 Mbps. Xiaomi measured 30.0044 FPS at 1280×720 and
3.624 Mbps. Each report records one estimated gap in both the capture and
encoded timestamp streams; treat those as separate estimates, not proof of two
unique lost frames. Keyframe count was 300 in both runs.

The Samsung direct 1080p30 baseline is now repeated twice; Xiaomi direct 720p30
is repeated three times. These results meet the repeatability gate for those
baseline modes. The latest run on each device explicitly confirms EOS; the
first three exports remain unchanged and still lack that field. The user
confirmed both follow-up baseline runs used the newly installed APK containing
the exporter fix; the JSON app version remains 1.0.0. The reports do not prove
encoded-file playback or visual quality because the experiment does not save a
bitstream. No logcat was supplied.

### Xiaomi physical output routes — 2026-09-27

The user supplied one completed 720p30 run for each physical output of the
logical rear camera:

- [Physical output 3](evidence/2026-09-27-xiaomi-2406apnfag-phase3-physical-output-3-720p30.json) — SHA-256 `963F5A3D4060BE72FE8D3A64245AE5CF542F4686E52F69D0725B5B878CF9E65F`.
- [Physical output 6](evidence/2026-09-27-xiaomi-2406apnfag-phase3-physical-output-6-720p30.json) — SHA-256 `192F5071CC732C6CD8669ECE18334D7A5226FE3471BF667599DA60DCCCFC2F18`.

Both reports select `Camera 0 · physical output N` through the logical-camera
physical-output route. Hardware `c2.mtk.avc.encoder` configured successfully
at 1280×720 with a full-frame crop. Output 3 measured 30.008 FPS and 3.624 Mbps;
output 6 measured 30.007 FPS and 3.625 Mbps. Both report 300 keyframes, zero
estimated drops, zero capture failures, monotonic timestamps, and
`endOfStreamSeen: true`. These results show that both logical-camera output
routes can feed the tested encoder mode. They do not establish that camera IDs
3 or 6 can be opened independently. Xiaomi aligned 1080p remains untested.

# 18. Phase 4 — RTP/H.264 over LAN and Q-007 Local Discovery

This is a development receiver and transport measurement. It does not decode,
display video, or exercise OBS. Keep camera-to-encoder evidence separate from
network delivery evidence. Q-007 uses DNS-SD service type
`_camsure-rtp._udp.local.`: the Windows receiver advertises its current media
port, and Android NSD browses, resolves, and displays an operator-selectable
IPv4 endpoint. Selecting a result feeds the existing RTP sender destination;
the media packet format and send path are unchanged. Typing an IPv4 address
remains available as a fallback.

## Build and receiver setup

1. Build the Android app from `apps/android` with the documented JDK/SDK and
   run `:app:assembleDebug :app:lintDebug`.
2. On the Windows PC, run
   `dotnet build tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj`, then
   `dotnet run --project tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj
   -- --bind 0.0.0.0 --port 5004`. The receiver advertises its bound media port
   over mDNS on UDP 5353. Permit the receiver on the Windows Private network
   for both UDP 5004 (media) and UDP 5353 (discovery), subject to firewall
   policy. Keep multicast enabled on the LAN and access point.
3. Record the Windows PC's IPv4 address and network profile. Ethernet for the
   PC is preferred; the Android device and PC must share the same local network.

## Q-007 discovery and no-internet trial

1. Leave the local access point/router running, disconnect its WAN/internet
   uplink, and verify the phone and PC still have local-network connectivity.
   Record the SSID/LAN arrangement and how the internet disconnection was
   confirmed.
2. Start the Windows receiver and note its advertised service name, IPv4
   address, and UDP media port. On Android, tap **Find receivers on local
   network**, wait for resolution, and select the intended receiver. Record
   time to appear, displayed address/port, selection, and any NSD errors.
3. Prepare camera routes, select the Samsung SM-X115 rear logical route, and
   confirm 1920×1080@30 is the highest exact hardware H.264 mode. Start the
   ten-minute LAN run; do not silently select a smaller mode to make transport
   pass. Leave the typed receiver address and selected receiver empty for a
   separate five-minute Phase 3 capture-only run.
4. Confirm the Windows receiver reports the same SSRC, complete access units,
   increasing 90 kHz RTP timestamps, matching exact source PTS extensions,
   keyframe/IDR and SPS/PPS observations, and sequence-gap/out-of-order counts.
   Compare Windows packet gaps with Android sender queue drops; keep them as
   separate causes.
5. Record Windows firewall profile, inbound policy, and the effective allow
   rule for UDP 5353 and UDP 5004. Do not infer firewall passage from a working
   fixed-address media run: fixed-address delivery does not exercise mDNS.
6. With discovery still active, stop and restart the receiver. Record when the
   old service disappears, when the restarted receiver reappears, and whether
   Android can select its resolved endpoint again. Repeat after disconnecting
   and restoring the phone's local network. This slice does not promise that an
   active RTP sender changes destination or recovers automatically; stop the
   experiment and select the current receiver endpoint before another run.

## Extended run and loss/recovery evidence

Record device/model, camera route/mode, measured encoder bitrate, phone/PC
network arrangement, run duration, sender and receiver queue limits/high-water
marks, oldest-item/high-water age, queue byte-depth trend, sender drops by
reason, RTP packets sent, receiver sequence gaps/out-of-order packets, complete
and incomplete access units, keyframes, codec-configuration presence, exact PTS
mapping results, and all failures. The app runs ten minutes when LAN sending is
enabled; that is a practical prototype soak, not a permanent acceptance limit.

For the separate Q-001 loss/recovery trial, interrupt the local network briefly,
restore it, and record receiver sequence gaps, incomplete access units, sender
drops, and whether SPS/PPS plus a later IDR restores a complete stream. Compare
the prototype's private RTP header extension and recovery behavior with the
alternatives considered in the Q-001 decision; one clean run alone does not
settle the protocol trade-off. The sender uses UDP and has no receiver feedback
channel, so receiver loss must not be inferred from sender counters. Do not
claim absolute one-way latency: phone and PC clock synchronization is not part
of this test.

## Evidence — 2026-09-28

The user supplied a fixed-address Samsung SM-X115 Android 16/API 36 run using
rear camera 0, hardware `c2.mtk.avc.encoder`, H.264 1920×1080@30, and destination
`192.168.0.146:5004`. The run lasted 600.16 seconds at 30.0036 encoded FPS.
Android recorded 17,996
encoded access units, 299,793 RTP packets, SSRC 604953840, zero send failures,
600 keyframes, 4.50 Mbps average bitrate, and a sender queue high-water mark of
231,729 bytes / 14 ms. It also reported 17,994 captured frames, one estimated
drop in each capture and encoded timeline, zero capture failures, and EOS.

The paired Windows receiver console reports the matching SSRC, 17,996 complete
access units, 299,793 packets / 347,882,535 bytes, 600 IDRs/configuration AUs,
zero incomplete AUs, sequence gaps, out-of-order packets, malformed or
oversized packets, PTS-map mismatches, non-monotonic PTS, or key-flag mismatches.
Receiver reassembly high-water was 231,762 bytes / 32 ms. The paired files are
[corrected Android JSON](evidence/2026-09-28-samsung-sm-x115-phase4-fixed-address-1080p30.json)
and [Windows receiver console log](evidence/2026-09-28-camsure-phase4-windows-receiver-console.log).
The last complete console row is at 12:22; the supplied log ends with a partial
12:23 row. The user confirms the matching counters held through the end of the
run.
The Android JSON's raw supplied form had a capped-sample summary bug:
`encodedLastPresentationTimeUs` showed the last of 10,000 retained samples
(66,251,818,420), while the transport sender's full-run
`lastSentPresentationTimeUs` was 66,518,352,971. The in-repository evidence copy
corrects that summary field from the transport counter; its sample arrays remain
capped at 10,000 and are otherwise unchanged.

The Phase 4 sender's exporter was also corrected to source last capture/encoded
timestamps from live full-run counters rather than the bounded display samples
for subsequent reports. The supplied report's capture sample array is capped;
no independent full-run camera-sensor endpoint was available to reconstruct an
exact capture-last timestamp for that historical JSON.

The user then supplied a live Android LAN discovery result: the app reported
“receiver detected,” and the Windows receiver at `192.168.0.146` logged three
browse queries from `192.168.0.233:5353`, answering each with one record and
three additional records. This confirms that a client browse reached the
advertiser and that it sent DNS-SD answers in this trial. The full user-supplied
[receiver log](evidence/2026-09-28-camsure-dnssd-discovery-receiver.log) is
preserved alongside a short [transcribed excerpt](evidence/2026-09-28-camsure-dnssd-discovery-console-excerpt.txt).
The full log stays at zero RTP packets/access units through its final report at
about 3:54, so it does not show video sent through the discovered selection.
Earlier host-only multicast capture did not observe a packet.

A later user-supplied [partial live receiver log](evidence/2026-09-28-camsure-phase4-receiver-live-run-partial.log)
shows RTP arriving after discovery traffic. Its receiver-session clock first
reports a packet at 5:18; at 6:40 it reports 34,356 packets / 39,590,780 bytes,
2,463 complete AUs, SSRC 1748780090, and zero gaps, incomplete AUs, out-of-order
packets, malformed packets, or PTS/key-flag mismatches. Receiver reassembly
high-water is 234,545 bytes / 34 ms. The log stops at 6:40 without final
counters, so it covers about 1:22 of media. This is sufficient functional
evidence that the discovered receiver endpoint carried complete RTP access
units; the longer run is a separate soak check, not a prerequisite for proving
discovery works.

The observed Windows firewall configuration has Domain/Private/Public profiles
enabled, default inbound block, outbound allow, and local firewall rules
controlled by Group Policy (`LocalFirewallRules: N/A`). The successful query
shows effective UDP 5353 reachability for this trial, but the active firewall
profile/rule and whether the WAN was disconnected were not recorded. No
firewall policy was changed by the agent. The user supplied this receiver log
after the instruction to select the discovered receiver, so it is recorded as
the discovery-to-RTP smoke result; the console itself does not encode which UI
control supplied the endpoint. WAN state and receiver/network reconnect
behavior also remain unrecorded. The agent host had no ADB device attached for
independent Android inspection.

The fixed-address run is a clean baseline, not an induced-loss/recovery trial.
Q-001 remains open pending the documented protocol trade-off and loss/recovery
evidence. Core discovery-to-RTP behavior is demonstrated on this LAN. The
remaining Q-007 acceptance checks are discovery with WAN disconnected,
effective firewall profile/rule details, and receiver/network reconnect
behavior. The broader Phase 4 exit gate also requires the relevant transport
acceptance. This prototype does not establish decoding, image quality,
synchronized latency, multi-camera capacity, or OBS integration.

# 19. Transport-Neutral Decode → OBS Physical Acceptance

The physical Samsung one-camera baseline is ACCEPTED WITH LIMITATIONS following
the user test on 2026-09-30. Synthetic and physical evidence remain distinct.
See [completion report](DECODE_OBS_REPORT.md) for models, bounds and evidence.

## Physical evidence — 2026-09-30

The user supplied [Android JSON](evidence/2026-09-30-samsung-sm-x115-decode-obs-1080p30.json),
[receiver console](evidence/2026-09-30-decode-obs-physical-receiver.log), and an
[OBS screenshot](evidence/2026-09-30-decode-obs-physical.png), then reported
acceptable delay and no freezes. Samsung SM-X115 Android 16/API 36, rear camera
0, hardware `c2.mtk.avc.encoder`, H.264 1920x1080@30 to 192.168.0.146:5004 ran
600.161 seconds (19:28:25–19:38:25 Asia/Manila). Encoded FPS 30.0036, average
bitrate 7.996 Mbps, 17,997 encoded AUs/600 keyframes/525,111 packets. Zero
estimated capture/encoded drops, capture failures, nonmonotonic timestamps,
sender queue drops, malformed output, configuration failures or send failures.
Sender high-water queue depth 2, bytes 130,769, age 8 ms, EOS seen.

Receiver SSRC 4011457692 matches. Cumulative 533,933 packets/18,298 complete
AUs include the earlier SSRC 2078493280 short stream (8,822 packets/301 AUs).
Subtracting gives exactly the phone's 525,111 packets/17,997 AUs. Receiver gaps,
out-of-order, incomplete AUs, malformed packets and PTS/key mismatches stayed
zero. Reassembly high-water 130,802 bytes/23 ms. Bridge cumulative written 18,238,
dropped 58, connection errors 107; most connection retries precede video/source
connection, and drops span startup/restart. Queue high-water depth 3/4, age
34/100 ms; reconstruction-to-write maximum 46,284 us. These do not measure OBS
decoder completion or glass-to-glass latency. Log ends at 13:59 receiver uptime
with idle counters; it is not a Ctrl+C final summary.

User subsequently confirmed phone stop/start, source removal/re-add and normal
OBS close/reopen all worked. These three short recovery checks are PASS by user
confirmation. Real camera visible and ten-minute stability are PASS by screenshot,
logs and user observation. Separate induced LAN interruption and receiver restart
were not explicitly reported. No physical OBS log was supplied, so decoder FPS,
decoder errors/resets, PC memory trend and calibrated latency remain unmeasured.
Separate color/detail quality checks are not claimed. Phone CPU average 54.82%
(app metric), thermal status moderate throughout, battery 16%→7%, temperature
32→35.1 C. This acceptance does not qualify multicamera, USB or WAN-offline LAN.

## Setup

1. Build/stage/install the plugin using `plugins/obs-camsure/README.md`. Keep
   all five decoder DLL dependencies beside the plugin. Close OBS before copying
   the package; never replace libraries in OBS's application bin directory.
2. Start the normal OBS frontend and use a test scene. Add **CamSure Camera** or
   edit an existing instance. Enable **Real camera video**; set **Local AU pipe**
   to `camsure-camera-1`. Diagnostic name/resolution/FPS/pattern settings control
   diagnostic mode; actual camera dimensions come from decoded frames.
3. From repository root run:

   ```powershell
   dotnet run --project tools/windows-rtp-receiver -- --port 5004 --obs-pipe camsure-camera-1
   ```

4. On the Samsung SM-X115, use the already validated rear Camera2/MediaCodec
   1920x1080@30 LAN experiment. Select the discovered receiver or type the PC's
   current LAN address; on 2026-09-30 it was `192.168.0.146:5004`. Keep Android
   foregrounded. Record endpoint/network/OBS build/DLL SHA256/device/route/codec.
5. Verify a **real camera image** appears in the native source, not a diagnostic
   pattern. OBS must report actual 1920x1080. Fit the source retaining aspect.
   For Xiaomi use its validated 720p30 mode separately, not a claimed 1080p pass.

## Visual checklist and ten-minute run

- Pan slowly/quickly; observe motion smoothness and subjects moving across frame.
- Inspect fine detail, edges and aspect ratio; check no padded black/green bottom
  rows or persistent corruption. Verify transmitted video has no Android UI.
- Check color, brightness, exposure transitions and lighting changes; record
  any color cast/range mismatch instead of inferring correctness from metadata.
- Run continuously for at least 600 seconds at Samsung 1080p30 if the tested
  device/network permits. Record source/encoder bitrate/FPS and native decoded
  FPS, not just packet counters. Do not call a short smoke run the soak.
- Save Android JSON, receiver final counters/bridge report and OBS log with
  `[CamSure] Video` metrics. Record complete/incomplete AUs, packet gaps,
  malformed/PTS mismatch, bridge/native/replacement/stale drops, decoder errors/
  resets, high-water queue depth/age, decoder latency and video-tick/copy timing.
- Record CPU/GPU/memory trend where available. Separate startup/loss recovery
  drops/resets from continual errors. Internal OBS display/drop counts may be
  unavailable; submission count does not mean monitor presentation count.
- Measure glass-to-glass externally using the timer/high-speed recording method
  in section 3. Source PTS alone is not a synchronized one-way latency measure.

## Recovery and shutdown checklist

1. Stop Android streaming while OBS remains open. Source output should clear
   after the session's 1.5-second idle timeout; OBS must remain responsive.
2. Restart the phone experiment. New session/configuration + IDR must restore
   video; no continued decoder error loop or accumulating latency.
3. Briefly interrupt LAN delivery and restore it. Observe bounded dependent
   frame discard and resume at configuration + keyframe. No RTCP/retransmission
   or automatic bitrate adaptation is promised.
4. Remove the source while streaming and add it again with the same pipe name.
   Receiver should reconnect and resume at IDR; no stale worker/pipe ownership.
5. Stop the receiver with Ctrl+C; restart it; confirm source/phone recovery after
   endpoint reselection if needed. Verify no worker survives source destruction.
6. Close OBS normally while sending; restart OBS and verify saved live/pipe
   settings. Stop the receiver after testing. Do not forcibly terminate OBS as
   a substitute for normal shutdown acceptance.

Record each step as PASS/FAIL/NOT RUN, observed image quality, effective FPS,
duration, final metrics, latency method/result and any crash/hang. Close Phase 5
only after the real phone visual/ten-minute/shutdown gate passes.

## Host regression procedure

After building .NET/native targets with CAMSURE_BUILD_TESTS=ON, run:

```powershell
dotnet run --project tools/windows-rtp-receiver -- --self-test
./plugins/obs-camsure/tests/run-video-probe.ps1
```

The runner uses private test UDP 5018 and pipe `camsure-test-video`, generates
valid synthetic H.264, induces one lost RTP packet, stops/restarts its stream,
checks I420 pixels/dimensions and actual D3D11 readback, then removes sources
while connecting/reading a partial header. It owns/cleans only its own processes.
Run the existing diagnostic lifecycle probe separately as in the plugin README.
The three successful 2026-09-30 logs under evidence are synthetic/host evidence;
the physical baseline evidence above is recorded separately.

# 20. USB Network Mode Qualification

**2026-09-30:** Implemented and host-tested; physical USB acceptance NOT RUN.
User chose to test later. Follow [operator setup and A-H acceptance checklist](USB_NETWORK_TESTING.md).
[Completion report](USB_NETWORK_REPORT.md) separates current build/simulated
results from route capture, real image, 600 s soak, glass-to-glass samples, ten
cable cycles, consumer stall, OBS frontend lifecycle and post-USB LAN regression.
None of the older LAN or third-party tethering evidence closes this gate.
# Settings usability follow-up — physical checks open

Check Wireless/USB selection shows only the relevant setup fields and help;
discovered PC selection and manual fallback both work; invalid IPv4/USB ports show
inline errors; valid selections survive restart. While streaming, navigate to
Settings and back, verify restart-dependent controls are disabled with an
explanation, and confirm OBS continues smoothly. Expand Diagnostics to verify
explicit trials, timed tests and JSON export remain accessible.
