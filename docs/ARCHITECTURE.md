# ARCHITECTURE.md

## System Overview

Current implementation (2026-09-30): Camera2/MediaCodec and RTP delivery are
implemented with prior physical evidence. The transport-neutral AU/FFmpeg/OBS
bridge is implemented and host-tested. Samsung real phone output, ten-minute
stability and stream/source/OBS recovery are user-confirmed, with measurement
limitations. The following overview includes later manager/control/multicamera
targets; see [the actual Decode → OBS path](DECODE_OBS_REPORT.md#2-final-runtime-architecture).

```text
┌──────────────────────┐
│ Android Camera A     │
│ Camera + Encoder     │
└──────────┬───────────┘
           │
┌──────────▼───────────┐
│ Transport A          │
└──────────┬───────────┘
           │
           │
┌──────────▼───────────────────────────────────────────┐
│                  OBS Plugin                          │
│                                                     │
│ Device Manager                                      │
│  ├─ CameraSession A -> Decoder -> OBS Source A       │
│  ├─ CameraSession B -> Decoder -> OBS Source B       │
│  ├─ CameraSession C -> Decoder -> OBS Source C       │
│  └─ CameraSession D -> Decoder -> OBS Source D       │
│                                                     │
│ Camera Control Dock                                 │
│ Telemetry / Discovery / Reconnect                   │
└─────────────────────────────────────────────────────┘
```

## Android Responsibilities

The Android application owns:

```text
Camera discovery
Physical/logical lens discovery
Capability profiling
Capture configuration
Camera lifecycle
Hardware video encoding
Stream timestamps
Network transport
Control-command receiver
Telemetry reporting
Reconnect behavior
```

## OBS Plugin Responsibilities

The OBS plugin owns:

```text
Device discovery
Device identity mapping
Camera-session lifecycle
Transport reception
Packet/frame reconstruction
Decode
Frame queueing
OBS source output
Camera control UI
Telemetry display
Reconnect behavior
Diagnostics
```

## Camera Capability Profile

Every device should expose a structured capability description.

Conceptually:

```text
Device
  identity
  name

Logical Camera
  id
  facing
  physical cameras[]

Physical Camera
  id
  focal lengths
  apertures
  sensor info
  focus capabilities
  stabilization capabilities

Stream Modes
  width
  height
  fps
  pixel format
  dynamic range
  codec compatibility

Manual Controls
  ISO range
  exposure range
  focus range
  AE/AWB locks
  exposure compensation
```

The protocol does not need to exactly match this shape, but the information model should remain capability-driven.

## Camera Session

A camera session represents one connected phone stream.

Conceptually:

```text
CameraSession
  DeviceIdentity
  CapabilityProfile
  Transport
  ControlChannel
  Decoder
  FrameQueue
  StreamClock
  Telemetry
  ConnectionState
```

No global state should assume that only one session exists.

## Video Data Path

Initial target:

```text
Android Camera
   ↓
Android camera/ISP pipeline
   ↓
MediaCodec hardware encoder
   ↓
H.264
   ↓
Local network
   ↓
OBS plugin receiver
   ↓
Decoder
   ↓
Bounded frame queue
   ↓
OBS source
```

## Control Path

```text
OBS Camera Control Dock
   ↓
CameraSession
   ↓
Control Channel
   ↓
Android
   ↓
Camera2 / device control
```

Examples:

```text
set zoom
select lens
trigger autofocus
set focus point
set exposure compensation
lock AE
lock AWB
set manual focus
set ISO
set shutter
toggle torch
change stream mode
request keyframe
```

The Android side must validate every command against the active camera mode and capability profile.

## Telemetry Path

```text
Android
   ↓
Telemetry
   ↓
OBS Plugin
   ↓
Camera Control Dock
```

Possible telemetry:

```text
capture FPS
encoder FPS
bitrate
queue depth
dropped frames
network packet loss
jitter
round-trip time
phone temperature
battery
charging state
current lens
current exposure
focus state
```

## Discovery

LAN discovery should avoid requiring operators to manually type IP addresses.

Discovery must not be the transport itself.

Discovery finds a device; the camera session then establishes explicit transport/control connections.

## Network Transport

Requirements:

- local-only operation
- bounded buffering
- timestamps
- frame identifiers
- packet ordering information
- keyframe identification
- stream restart handling
- packet-loss visibility
- independent streams per camera

Protocol details remain open until tested.

## Decoding

The current bridge uses FFmpeg/libavcodec software H.264 decode, with exact
source packet/frame PTS and I420 output. Input is an owned transport-neutral AU,
not an RTP packet. D-021 records the decision and the failed Media Foundation
timestamp-preservation probe.

Later optimization may move to platform hardware decoding and GPU-friendly texture handling.

Important:

Do not prematurely make zero-copy hardware decoding a prerequisite for proving the architecture.

## OBS Source Model

The plugin registers one source type.

Users may create multiple instances.

Example:

```text
Source type: CamSure Camera

Instance:
  device = Pulpit
  session_id = ...
  video settings = ...
```

The management dock should coordinate connected devices but should not become a required video hop.

## OBS Camera Control Dock

Operational design target:

```text
CAM 1 | CAM 2 | CAM 3 | CAM 4

Lens
Zoom
Focus
Exposure
White Balance
ISO/Shutter if available
Torch
Stabilization

Status:
resolution
FPS
bitrate
latency
packet loss
battery
temperature
```

Setup parameters and live camera controls should remain conceptually separate.

## Timing

Every encoded frame should preserve a capture-origin timestamp or the closest reliable equivalent.

The architecture should make future camera synchronization possible.

## Failure Handling

Expected failures:

```text
phone leaves Wi-Fi
phone app restarts
camera service restarts
encoder restarts
OBS reloads plugin/source
router changes IP
packet loss burst
decoder loses reference frame
phone overheats
```

Recovery should be per-camera whenever possible.

One failed phone should not disrupt the other three.

## Implemented Phase 1 Boundary (2026-09-26)

Historical Phase 1 snapshot: only the native diagnostic source existed then.
One Source object belongs exclusively to each OBS source create/destroy
pair. It owns settings, one GPU texture, a dirty/failure flag, a mutex, and a bounded
animation clock. No source owns or depends on another source. Future independent
CameraSession ownership can attach here without introducing a global current camera.

A 640x360 RGBA texture is generated only on first render or settings changes.
The selected source dimensions are 1280x720 or 1920x1080. A GPU-drawn moving marker
uses each instance's clock. There is no frame queue or full-resolution CPU upload.
Friendly names persist through OBS settings; the minimal bitmap font displays ASCII
in uppercase and clips after 32 characters. OBS source names remain independently
renameable. Diagnostic IDs/colors are process-local, not persisted camera identities.

### Callback / ownership audit

- create/get_defaults/get_properties: invoked by the OBS caller; assume no graphics
  context or fixed UI thread. create performs CPU-only initialization.
- update: OBS 31.1.1 libobs/obs-source.c defers video-source updates to video_tick.
  Initial create calls the same CPU-only updater directly. A per-instance mutex
  also protects dimension queries and settings against other caller paths.
- video_tick: OBS video execution path; advances only the instance's clock.
- video_render: graphics execution path, with OBS graphics context active; creates,
  replaces, and draws the instance texture.
- destroy: OBS destruction queue, after OBS waits for outstanding context use;
  explicitly enters/leaves the graphics context to destroy the texture. CPU state
  is released with the Source object. No callbacks, workers, or references survive.
- hide/activate/deactivate: no resources depend on these transitions; hiding a scene
  does not destroy its source. OBS may retain a source referenced from another scene.
- settings are persisted by OBS; no custom save callback or global settings exists.
  Duplicate creates a new object; “Add Existing” intentionally references the same
  OBS source and is not an independent camera.

Future receive/control threads must hand off bounded data to session-owned state.
They must not call graphics APIs or mutate OBS/UI state under an assumed thread
affinity. Define thread ownership explicitly in the future transport slice.

## Implemented Phase 2 Boundary (2026-09-27)

`apps/android` contains a native Kotlin capability profiler with four small
responsibilities: Camera2 metadata collection, MediaCodec metadata collection,
typed report assembly, and local UI/export. A single background executor owns a
scan; the Activity attaches to session state and does not own collector work.
Camera permission is requested only after the user starts a scan. JSON export
uses Android's document picker and does not upload data.

The profiler queries advertised camera IDs, logical/physical camera metadata,
stream formats and sizes, duration metadata, FPS ranges, high-speed modes,
controls, stabilization and dynamic-range profiles where supported, plus H.264
and HEVC encoder constraints. Values carry per-field statuses and units. Bounded
inventories are marked partial when truncated. Camera and encoder metadata remain
separate and do not imply that their modes are compatible.

This phase does not open a camera device or capture session, create a preview,
instantiate a MediaCodec encoder, or test thermal/performance behavior. Keep the
future camera engine decision in Q-003 open until device and end-to-end evidence
supports a choice. The Phase 2 exit gate still requires exported reports from
multiple physical phones and a comparison; a successful build does not close it.

## Phase 3 Runtime Experiment Boundary (2026-09-27)

The Android app now includes an opt-in one-camera Camera2-to-H.264 experiment.
It discovers direct 30 fps mode intersections between each selected Camera2
route's PRIVATE outputs and non-alias hardware H.264 encoders. It prefers the
largest exact mode up to 1920×1080, so mode selection remains device-specific.
The selected codec is instantiated by its exact reported name and must configure
and start before the Camera2 session is opened.

Each run owns its CameraDevice, one CameraCaptureSession, one MediaCodec, the
encoder input Surface, a handler thread, and its measurements. The repeating
Camera2 request targets only the encoder Surface. The run has no preview,
ImageReader, CPU pixel-frame queue, transport, OBS receiver, or shared camera
object. Logical rear routes are the default; physical output routes are available
for logical multi-camera devices, and IDs listed independently by Camera2 can be
attempted as standalone routes. The screen stops the run when the app leaves the
foreground and caps a run at five minutes.

The experiment records capture sensor timestamp samples, encoded presentation
timestamp samples, capture/encoder cadence, dropped-frame estimates, Camera2
capture failures, keyframe cadence, codec output dimensions/crop/profile/level,
bitrate, CPU, thermal status, and battery state. The application frame queue is
zero because output is consumed directly from the encoder callback and released;
the internal MediaCodec queue depth and GPU utilization are not exposed by the
public APIs used here. Timestamp streams remain separate because their clock
bases have not been qualified for latency subtraction.

This is a measurement harness, not a lasting choice between Camera2 and CameraX.
Repeated runtime evidence now validates direct Camera2 at Samsung 1080p30 and
Xiaomi 720p30, including logical-camera physical outputs 3 and 6. Q-003 remains
open because no CameraX or Camera2-interop comparison has been run. The Phase 4
prototype can use this working direct path behind a narrow capture boundary
without making it the permanent capture-layer decision. The Xiaomi 1920×1088
backing/crop or other GPU alignment hypothesis is not implemented or proven.
LAN transport is the next product slice; OBS ingest, controls, audio, and
multi-camera operation remain later slices.

## Phase 4 Fixed-Endpoint Prototype Boundary (2026-09-27)

The one-camera Phase 3 session can optionally create one `EncodedAccessUnitSink`
and one `RtpH264Sender`. The sink copies only compressed MediaCodec output into
pooled storage, assembles callback fragments into complete access units, keeps
PTS/keyframe flags, and updates codec configuration from codec-config buffers
and output-format CSD. It enqueues without socket I/O on the camera/encoder
handler. A run owns the sink, queue, socket, sender thread, stream SSRC, and
counters; no singleton camera or shared stream state is introduced.

The sender uses a 512 KiB / 500 ms / 24-unit queue and 2 MiB per-access-unit
limit. Overflow or an encoder configuration change flushes queued access units
and waits for a keyframe. Recovery sends the cached SPS/PPS before the next IDR.
RTP/UDP, H.264 packetization, timestamp mapping, and the fixed IPv4 endpoint are
isolated from capture ownership and from discovery/control.

`tools/windows-rtp-receiver` is a standalone counter-only development receiver.
It validates one RTP stream, reassembles the Phase 4 H.264 packetization subset,
and keeps one 2 MiB / 500 ms in-progress access-unit buffer. Its receive loop is
synchronous and has no additional managed packet queue. It does not call OBS,
decode, or display video. Discovery, reconnect signaling, multi-camera state,
and a permanent protocol decision remain outside this prototype boundary.

## Phase 5 Transport-Neutral Decode → OBS Boundary (2026-09-30)

The earlier Phase 1/4 boundaries above are historical snapshots. The C# RTP
receiver now publishes complete owned `EncodedVideoAccessUnit` snapshots,
caches SPS/PPS and signals discontinuity/session generation. Its optional
`EncodedAuPipe` is bounded to four AUs / 4 MiB / 100 ms and isolates IPC writes
from the network receive loop. Counter-only mode remains available.

Each native source owns a `VideoSession`, one cancelable pipe/decode worker,
`VideoDecoder` and a single latest owned `DecodedVideoFrame`. Source destruction
joins the worker; no decoder thread retains receive/reassembly buffers or OBS
GPU objects. Input is H.264 Annex-B plus owned configuration and exact PTS. Output
is cropped 8-bit I420 with explicit color range/matrix and exact source PTS.

The stable `camsure_camera` type uses OBS async unbuffered video. Its video tick
submits the latest frame via `obs_source_output_video`; actual dimensions come
from that frame, not diagnostic resolution properties. Diagnostic mode uses
async RGBA and remains independent per instance. Local PTS epochs map by an
offset plus microseconds-to-nanoseconds conversion, without claiming wall-clock
synchronization. No USB or audio is implemented.

The required standalone receiver is a development transport process, not a
preview hop. A USB adapter can publish the same local AU envelope without
changing decoder or OBS frame code. See [the detailed boundaries](DECODE_OBS_REPORT.md)
and TESTING.md section 19. Samsung one-phone OBS baseline is accepted with
limitations; physical decoder metrics and calibrated latency remain unmeasured.

## USB Network Mode Boundary (2026-09-30)

USB Network Mode is implemented with physical qualification pending. Existing
Camera2/MediaCodec -> compressed sink -> RTP packetizer uses an explicitly bound
local IPv4 DatagramSocket over manually enabled Android USB tethering. Windows
helper binds the selected adapter IPv4 and rejects foreign peer addresses before
RTP parsing. Both modes publish the unchanged EncodedVideoAccessUnit into the
shared pipe/VideoSession/decoder/native OBS path. LAN defaults are retained.
Each USB run owns its socket, bounded queue, sender and link-monitor worker.
Address/index/prefix loss or ambiguous peer route aborts the run. Windows helper
terminates on adapter loss; reconnect requires reselection and fresh helper/phone
stream, SSRC/generation/configuration/IDR. No second media pipeline, privileged
tether activation, ADB/debugging or native USB protocol is introduced. Actual
limits, ownership, telemetry and polling/OEM limitations are in
[completion report](USB_NETWORK_REPORT.md).
