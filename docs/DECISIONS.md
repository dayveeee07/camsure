# DECISIONS.md

## D-024 — Per-source OBS ownership of the existing Windows receiver

**Status: ACTIVE implementation; physical/frontend acceptance OPEN (2026-10-01).**

Keep the existing receiver executable and transport-neutral AU/decoder boundary.
Managed reception is an opt-in source setting so existing external receiver scenes
retain their behavior. Start launches the packaged executable directly, hidden,
without a shell. Each source owns its worker, ready/stop events, restricted inherited
I/O handles and kill-on-close job; source destruction/OBS exit cleans its receiver.
Stop requests graceful teardown with a three-second forced-cleanup fallback.
No shared global camera/receiver and no automatic startup after scene load.

Persist explicit adapter/address/prefix, mode, port and USB peer in OBS settings.
Validate selection against live inventory at Start; never substitute an absent
adapter. USB retains existing subnet/peer filtering and tested 256 KiB receive
buffer; selected-link loss ends the epoch without fallback. Managed LAN advertises
only its selected bind endpoint. Bounded diagnostics and recent decoded-video
timestamps distinguish waiting from streaming; stale video alone is not proof of
network failure. Existing CLI defaults/timed tests remain compatible. A Windows
x64 .NET 10 runtime is required by this framework-dependent local package.
No remote camera controls, audio, new decoder or multicamera qualification.

2026-10-01 follow-up: after real Xiaomi 4K encode proof, the user authorized
extending shared native decode through 3840×2160 for a USB-to-OBS trial.
Normal mode defaults remain unchanged; this is not production qualification.

2026-10-01 scope clarification: the user authorized a bounded higher-resolution
camera/encoder diagnostic and capture-only trial through 4K30. Normal mode
selection and the accepted USB baseline remain unchanged. This is not approval
or qualification of 4K/HDR production delivery; D-012's delivery priority and
the native decoder's then-current 1080p bound were retained for that capture-only
step. The authorized shared 4K decoder trial above subsequently expanded the bound.

This file records decisions that should not drift silently.

## D-023 — Preview-first Android operation using the existing camera owner

**Status: ACTIVE implementation; physical acceptance OPEN (2026-10-01).**

Reuse CameraToEncoderExperiment for preview-only and camera-to-encoder sessions.
Normal streaming adds a bounded-size TextureView Surface to the same Camera2
session as the unchanged encoder target. No second camera pipeline or CPU raw
frame queue. Encoder-only Diagnostics retains the old five/ten-minute runs;
operator Start has no automatic deadline. Settings navigation is an in-activity
overlay retaining the preview Surface; restart-dependent edits stay disabled
through teardown. Backgrounding stops capture/streaming; return opens preview
but requires explicit Start. Rotation retains the session.

Save capability-validated camera/mode choices and valid endpoint selections.
Restore a USB selection only by exact interface/address/prefix/index identity;
always repeat presence/exclusive-route/bind validation. No inferred USB identity,
Wi-Fi band, automatic resolution or metadata-wide bypass. Exact 1080p remains
an explicit trial, 4K experimental. Receiver command and transport-neutral native
OBS boundary remain unchanged. UDP sender state cannot attest PC reception.

This supersedes only D-020's historical launch-permission/no-preview UI behavior;
its metadata profiler and evidence rules are retained. Physical session-combination,
orientation, lifecycle and performance qualification remains open.

Status values:

- `ACTIVE`
- `PROVISIONAL`
- `SUPERSEDED`
- `REJECTED`

---

## D-001 — Native OBS Integration

**Status:** ACTIVE

The desktop side will be a native OBS plugin/source rather than a browser source or virtual webcam.

### Reason

The project is specifically intended for OBS live production and should avoid unnecessary intermediate layers.

---

## D-002 — Multi-Camera From the Architecture Level

**Status:** ACTIVE

The system must be designed for multiple independent camera sessions from the beginning.

Initial target is up to 4 simultaneous Android phones.

### Reason

Retrofitting multi-camera support after building a singleton pipeline risks shared-state and lifecycle problems.

---

## D-003 — Local-First Transport

**Status:** ACTIVE

Video transport should work entirely on the local network.

The internet is not required.

### Reason

The target use case is local live production. Internet routing would add unnecessary latency and failure modes.

---

## D-004 — OBS PC Preferably Wired

**Status:** ACTIVE

For Wi-Fi camera operation, the recommended topology is:

```text
Phone(s) -> Wi-Fi AP -> Ethernet -> OBS PC
```

### Reason

This removes the second wireless hop and reduces contention, jitter, and packet loss.

---

## D-005 — Hardware Encoding on the Phone

**Status:** ACTIVE

The phone should hardware-encode video using Android platform codec APIs.

H.264 is the initial codec.

HEVC/HDR may be added later.

### Reason

Raw video would require excessive bandwidth and memory transfer.

---

## D-006 — Capability-Driven Camera Engine

**Status:** ACTIVE

The Android application will query the actual capabilities of each phone and physical camera.

The UI and OBS controls should adapt to what the device exposes.

### Reason

Android camera capabilities vary greatly by manufacturer, model, lens, resolution, and capture mode.

---

## D-007 — Direct Camera Control Is a Core Feature

**Status:** ACTIVE

Remote camera controls in OBS are a core requirement, not an optional afterthought.

A dedicated OBS dock is preferred for operational controls.

### Expected Controls

- lens selection
- zoom
- focus / focus point
- exposure compensation
- AE lock
- AWB lock
- manual focus when available
- ISO/shutter when available
- torch
- stabilization
- stream mode

---

## D-008 — Full OEM Camera-App Parity Is Not Assumed

**Status:** ACTIVE

The project should use as much of the hardware and Android camera pipeline as the device exposes, but must not assume access to all proprietary OEM computational-video features.

### Reason

Manufacturer camera applications may use private processing pipelines unavailable to third-party applications.

---

## D-009 — Newest Frame Beats Old Frames

**Status:** ACTIVE

For low-latency live production, stale frames should be dropped instead of allowing unbounded queues to accumulate.

### Reason

Maintaining chronological completeness at the expense of increasing latency is undesirable for a live camera.

---

## D-010 — Transport Abstraction

**Status:** ACTIVE

The camera session should not be tightly coupled to Wi-Fi or USB.

Conceptual interface:

```text
Transport
 ├─ NetworkTransport
 └─ UsbTransport
```

### Reason

USB support should be addable later without duplicating the entire application architecture.

---

## D-011 — LAN Before Native USB

**Status:** ACTIVE

The first working end-to-end implementation should use the local network.

Native USB transport is deferred until the camera, encoding, decoding, OBS, and control architecture is proven.

### Reason

Custom USB work can consume significant development effort without proving the core product.

---

## D-012 — 1080p Before 4K/HDR

**Status:** ACTIVE

Initial performance work should focus on reliable 1080p30 and then 1080p60.

4K, HEVC, 10-bit, and HDR are later milestones.

### Reason

The first risk is architecture and latency, not headline resolution.

---

## D-013 — One Plugin, Multiple Source Instances

**Status:** ACTIVE

OBS loads one plugin implementation.

Users can create multiple source instances, each mapped to a different camera session.

### Example

```text
CamSure Camera - Main
CamSure Camera - Wide
CamSure Camera - Keys
CamSure Camera - Drums
```

---

## D-014 — Persistent Device Identity

**Status:** ACTIVE

Each Android installation/device should expose a persistent identity and a human-readable camera name.

OBS mappings should survive reconnects and enumeration-order changes.

### Reason

Live operators need stable assignments such as "Pulpit", "Wide", and "Keyboard".

---

## D-015 — Camera Synchronization Is a Later Feature

**Status:** PROVISIONAL

The system should preserve timestamps needed for future multi-camera synchronization.

Automatic delay alignment is not required for the first working version.

### Reason

Timestamp preservation is cheap to design in early; full synchronization logic can be added after stable multi-camera streaming exists.

---

## D-016 — Clean-Room Implementation

**Status:** ACTIVE

External open-source camera/OBS projects can be studied for architecture and behavior.

Do not copy code with incompatible licensing into this project.

---

## D-017 — Product Name: CamSure

**Status:** ACTIVE

CamSure is the official product name, confirmed 2026-09-26. Use CamSure in user-facing branding.

Keep future protocol/schema identifiers neutral where useful; do not rename unrelated technical concepts for branding.

---

## D-018 — Phase 1 Native Diagnostic Source

**Status:** SUPERSEDED for rendering by D-021; retained as Phase 1 history.

Register one stable OBS type, camsure_camera, displayed as CamSure Camera.
Every create callback allocates independent settings, texture, and animation state.
The only mutable global is a process-local diagnostic ID allocator. IDs are not
persistent device identities and may change after reload or duplication.

Use a synchronous custom-draw source and a generated 640x360 diagnostic texture,
scaled to 720p or 1080p. The 30/60 FPS setting controls marker cadence, capped by
OBS's video cadence. It is not a camera capture rate. No worker threads, transport,
decoder, manager, dock, or protocol classes are needed for this slice.

## D-019 — Plugin Scaffolding and Licensing

**Status:** ACTIVE

Use the official obsproject/obs-plugintemplate CMake foundation at commit
3e7d7ac3b5342cd7d9b88890b9c70b472d1520fc, retaining its hash-pinned
OBS 31.1.1 and obs-deps 2025-07-11 baseline. Current installed runtime validation
targets OBS 32.2.2 x64. SDK baseline and installed runtime are distinct.

Native plugin and its tests use GPL-2.0-or-later, matching the template's source
license direction. Preserve the upstream license and attribution. This decision
does not choose a license for the future Android app or future decoder dependencies.
Windows is the only supported build target in this slice.

---

## D-020 — Phase 2 Android Capability Profiler

**Status:** ACTIVE

Implement Phase 2 as an Android-native, metadata-only profiler using Camera2
characteristics and `MediaCodecList`. Support Android API 26+ and represent
newer fields as `unavailable_on_api_level` when their required API is absent.
Each reported field carries a status; missing or failed values are not coerced
to zero or treated as unsupported. Keep camera stream metadata and encoder
constraints separate, and label representative codec size/rate checks as
metadata queries rather than runtime tests.

The app requests Camera permission only when the user starts a scan, runs
collection off the UI thread, and exports a versioned JSON report locally via
the system document picker. The report omits persistent device identifiers and
is not uploaded. Do not open a preview, capture session, or encoder in this
phase. This decision does not select Camera2 or CameraX for the future capture
engine; Q-003 remains open pending device and runtime evidence.

## D-021 — Transport-Neutral H.264 Decode and OBS Async Video

**Status:** ACTIVE; Samsung physical baseline ACCEPTED WITH LIMITATIONS
(2026-09-30). See TESTING section 19 for user-observed video/recovery evidence.

Complete owned H.264 Annex-B AUs, exact source PTS, session generation, SPS/PPS
and discontinuity form the decoder input. RTP remains an adapter. The existing
C# receiver passes these over a local bounded named pipe to a per-source native
session; decoder code has no sockets, RTP, discovery, USB or CLI dependency.

Choose FFmpeg n7.1.1 from the pinned OBS dependency bundle, software H.264 with
two slice workers, exact packet/frame PTS and I420 output. The tested direct
Media Foundation decoder returned regenerated timestamps beginning at zero,
failing the preservation gate despite valid decoded pixels. FFmpeg passes the
strengthened timing test; do not map frames to guessed FIFO timestamps.

Use OBS_SOURCE_ASYNC_VIDEO, unbuffered output and a single latest owned decoded
frame, submitted on the OBS video tick. Diagnostic mode retains its independent
settings/marker using async RGBA. Width/height come from the output frames.
The 2026-10-01 authorized high-resolution trial attempts D3D11VA above 1080p,
with CPU readback/conversion and software recovery on a fresh IDR. Lower modes
retain software decode. Zero-copy remains future work; real 4K stutter remains
unresolved and this extension is not production qualification.

AU queue: four / 4 MiB including configuration / 100 ms. Decoder/session has one
in-flight AU and at most 16 pending PTS entries. Decoded handoff: one / 100 ms.
Overflow, stale input or loss flushes dependent work and waits for SPS/PPS+IDR.
All worker teardown cancels/completes pipe I/O and joins before source release.

The FFmpeg build enables GPL/version3. CamSure's GPL-2.0-or-later source remains
compatible; combined runtime distribution is GPL-3.0-or-later. Package matching
decoder/transitive DLLs and notices beside the plugin, never overwrite OBS's
own DLLs. Public distribution needs exact corresponding-source/build compliance.

See [completion report](DECODE_OBS_REPORT.md) for evaluated alternatives,
ownership, timing, measurements and limits. Synthetic tests are not real-phone
OBS acceptance. USB, audio, controls and multicamera are outside this slice.

## D-022 — USB Network Mode via Android USB Tethering

**Status:** ACTIVE implementation choice; physical acceptance OPEN (2026-09-30).

Reuse the LAN RTP/H.264 stream and transport-neutral AU/decoder/OBS path over
manually enabled USB tethering. LAN stays default. Require explicit local
address/adapter and peer selection, subnet/ambiguity checks, socket binding and
fail-closed link/session teardown; no route fallback. USB queue/reassembly age
is 100 ms and requested socket buffers are 64 KiB. No native USB bulk, UVC,
AOA, WinUSB, ADB forwarding or debugging transport is implemented. ADB is not
required for operation. Custom AOA/native USB is only a possible future fallback.
Manual confirmation/capture establishes physical USB identity; interface names
and subnets alone do not. See USB_NETWORK_REPORT.md and USB_NETWORK_TESTING.md.

Platform binding uses the public [Android DatagramSocket API](https://developer.android.com/reference/java/net/DatagramSocket).
API 36 exposes [tethered-interface callbacks](https://developer.android.com/reference/android/net/TetheringManager.TetheringEventCallback);
this API 26+ slice uses operator selection and interface monitoring throughout.

D-024 clarification (2026-10-01): managed Start assigns a UUID-based pipe for inherited or automatic settings; source duplication re-identifies that automatic pipe. Explicit custom pipes and external receiver defaults remain supported. This closes the demonstrated default-pipe collision, not physical multicamera qualification.
