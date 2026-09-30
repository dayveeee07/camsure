# CamSure Android Camera Profiler and Phase 3 Experiment

The Phase 2 Scan capabilities workflow is a native Kotlin metadata profiler. It uses Camera2 characteristics
and `MediaCodecList`; it does not open a preview/capture session or start an
encoder. Camera and codec metadata are advertised constraints, not proof that a
capture mode works in practice.

## Build baseline

- Minimum SDK: Android 8.0 / API 26.
- Compile and target SDK: Android 16 / API 36.
- Android Gradle Plugin: 8.13.2.
- Gradle Wrapper: 8.14.5 (binary distribution, checksum-pinned).
- Kotlin Gradle plugin: 2.2.20.
- JDK: 17.
- Android SDK Platform 36 and Build Tools 36.0.0.

The minimum is API 26 so the profiler runs on Android 8+ test phones. Newer
Camera2 and codec fields are represented as unavailable on older Android API
levels. Targeting API 36 opts into Android 16 behavior while using the SDK
already installed for this project. The AGP version supports API 36 and requires
JDK 17 and Gradle 8.13 or newer.

## Build

Install JDK 17 and Android SDK Platform 36 / Build Tools 36.0.0. Set
`ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK path, then run from this
directory:

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:lintDebug
```

The first run downloads the pinned Gradle distribution and Android Gradle/Kotlin
plugins from their configured repositories. The Gradle Wrapper verifies the
distribution SHA-256. No local `local.properties` path is committed.

## Install and profile

Connect an authorized Android phone with USB debugging enabled and confirm it is
the intended test device:

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb devices -l
```

Install and launch the debug app:

```powershell
.\gradlew.bat --no-daemon :app:installDebug
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb shell am start -n com.camsure.profiler/.MainActivity
```

Tap **Scan capabilities**. The app requests only Camera permission when a scan
is requested. If permission is denied, the screen explains how to retry. The
metadata scan runs once on a background worker; a rescan is available after it
finishes. Rotation/activity recreation keeps the in-process scan and completed
report. The scan does not create capture sessions or run codecs.

Tap **Export JSON** and choose a destination in Android's document picker. The
file is pretty-printed JSON with schema version `1.0.0`. Every optional value
has a status (`advertised`, `unsupported`, `unavailable_on_api_level`,
`inaccessible`, `query_failed`, `partial`, or `not_runtime_tested`) and units
where applicable. Compare reports from phones by appending their JSON files to
the same evidence set; the app does not collect serial numbers, account data,
or persistent device identifiers.
See [REPORT_SCHEMA.md](REPORT_SCHEMA.md) for field meanings and comparison rules.

## Phase 3 Camera2 to encoder experiment

Tap **Prepare camera routes** to request Camera permission and enumerate
logical camera routes, logical-camera physical output IDs (where available), and
standalone IDs reported by Camera2. The default is the first rear route. Choose
a route and inspect the direct modes; they are exact Camera2 PRIVATE-output and
hardware H.264 encoder mode intersections at 30 fps. Hardware aliases and
software encoders are excluded from the run candidates.
Hardware-acceleration classification uses platform codec metadata available on
API 29 and later; older supported profiler devices cannot run this hardware-only
experiment.

Tap **Start highest direct mode** for a five-minute run. The experiment selects
the largest exact supported mode up to 1920×1080, configures H.264 at a
device-specific bitrate, creates a MediaCodec input Surface, and configures one
Camera2 repeating target to that Surface. It uses no ImageReader, CPU pixel
copy, preview, transport, OBS ingest, or application-owned frame queue. The app
keeps the display awake while the run is active and stops if the app leaves the
foreground. Stop manually at any time or let the run end after five minutes.

The live report and exported camsure-phase3-runtime-*.json record the selected
codec and hardware classification, codec alignment, bitrate mode and configured
mode, whether Camera2 advertises 1080p and whether the exact direct mode is
supported, encoder output format and crop, sensor and encoded timestamps,
capture/encode cadence, keyframes, frame-drop estimates and Camera2 capture
failures, output bitrate, end-of-stream result, process CPU samples, thermal
status, and battery state. GPU utilization
and MediaCodec's internal queue depth are not exposed by the public APIs used by
the experiment. Capture sensor timestamps and encoder presentation timestamps
are retained separately; the app does not subtract them as a latency result
until the device timestamp bases have been qualified.

## Phase 4 RTP/H.264 with local receiver discovery

The Phase 3 camera and encoder session can optionally feed complete compressed
H.264 access units to the bounded Phase 4 sender. Tap **Find receivers on local
network** and select a resolved CamSure receiver to use its advertised IPv4
address and media port. A typed numeric IPv4 address remains an optional manual
override. Leave both the selection and manual field blank to retain the
capture-only Phase 3 path. A LAN run uses a ten-minute duration; the capture-only
run remains five minutes. Android browses DNS-SD service type
`_camsure-rtp._udp.` while the screen is foregrounded; leaving the screen stops
the browse and releases its multicast lock when one is needed.

The sender copies `MediaCodec.BufferInfo.offset/size` bytes into pooled,
independently owned access-unit storage before releasing the output buffer. It
keeps access-unit timestamps and keyframe flags, ignores codec-config buffers as
video frames, collects codec-specific data from output-format changes, and
honors `BUFFER_FLAG_PARTIAL_FRAME`. Socket I/O occurs on a per-run sender thread.
The sender queue is capped at 512 KiB, 24 access units, and 500 ms; access units
are capped at 2 MiB. When complete access units are dropped, the sender waits
for a keyframe and includes cached SPS/PPS with the recovery keyframe.

The current prototype uses fixed-address RTP/UDP with RFC 6184 H.264
non-interleaved packetization mode 1 and a 90 kHz clock. A private, documented
RFC 8285 header extension carries exact MediaCodec PTS and completed-unit flags
for the matching [Windows receiver](../../tools/windows-rtp-receiver/README.md).
DNS-SD only selects the destination before a run; it does not change the RTP
media path or promise automatic destination switching or stream recovery.
Decoding, preview, OBS ingest, and permanent protocol selection are not
included. A successful build does not prove physical discovery or LAN delivery.

When transport is enabled, the exported runtime report uses schema `1.1.0` and
includes sender queue limits/high-water marks, age, drops, RTP packet/AU counts,
codec configuration, and send failures. The Windows receiver separately reports
packet gaps, out-of-order packets, incomplete and complete access units, IDRs,
reassembly-buffer state, and timestamp-map mismatches. Sender success alone is
not receiver-delivery evidence.

An exact 1920×1080 direct mode is selected only if both Camera2 and a non-alias
hardware H.264 encoder report it at 30 fps. If a camera advertises 1080p but
the encoder point check rejects it due to alignment, the app records the
reported alignment and keeps 1080p visibly unresolved. It does not silently
claim that a 1920×1088 backing surface, 1080-line crop, or GPU conversion works.

## Validation boundary

The validation text below applies to the Phase 2 metadata scan. Phase 3 device
runs have a separate evidence boundary described above.

Camera2 and MediaCodec metadata describe advertised capabilities and constraints.
They do not prove that a stream can be configured, that every advertised camera
size works at every FPS, or that a camera/encoder pair is compatible. No preview,
recording, codec execution, thermal behavior, stock-camera parity, or capture
performance is tested in this phase. The Phase 2 metadata comparison gate passed
on 2026-09-27 using user-supplied Samsung SM-X115 and Xiaomi 2406APNFAG reports
(both API 36); see `docs/PROGRESS.md`. Runtime camera/encoder compatibility and
API-version differences remain untested.
