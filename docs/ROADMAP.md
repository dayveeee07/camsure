# ROADMAP.md

The roadmap is deliberately sliced so that each stage proves one architectural risk.

Do not skip ahead simply because later features are more visible.

---

# Phase 0 — Repository and Handrail

## Goal

Create the project structure and source-of-truth documents.

## Exit Gate

- handrail files exist
- build targets are defined
- licensing direction is documented
- agent can identify current phase without conversation history

---

# Phase 1 — OBS Source Skeleton

## Goal

Prove native OBS integration.

## Deliverable

A custom OBS source that:

- loads reliably
- can be added multiple times
- produces a generated/test frame
- owns independent instance state

## Critical Test

Create four source instances.

Changing one source must not affect the others.

## Exit Gate

PASS only when there are no singleton assumptions in basic source state and
the four-source OBS frontend lifecycle/save/restart procedure in TESTING.md
section 15 passes. A build or in-process libobs probe alone does not close Phase 1.

---

# Phase 2 — Android Camera Capability Profiler

## Goal

Understand what each test phone actually exposes.

## Deliverable

Android app can enumerate:

- logical cameras
- physical cameras
- stream sizes
- FPS ranges
- manual controls
- stabilization modes
- dynamic-range support
- encoder support

## Exit Gate

Capability data can be exported/logged and compared across multiple phones.

The implementation is metadata-only: Camera2 and H.264/HEVC encoder metadata,
explicit field/API-level statuses, and local JSON export. It does not include
preview/capture, runtime encoding, or camera/encoder compatibility tests.

**Acceptance — 2026-09-27:** PASS WITH LIMITATIONS. User-supplied reports from
Samsung SM-X115 and Xiaomi 2406APNFAG were exported and compared. They show
meaningful differences in logical/physical cameras, high-speed and dynamic-range
metadata, and encoder point checks. Both devices report API 36, so this pair does
not validate API-version differences. Runtime camera/encoder compatibility is
deferred to Phase 3.

---

# Phase 3 — One Android Camera to Hardware Encoder

## Goal

Prove the phone-side real-time video path.

## Initial Target

1080p30 H.264.

Then:

1080p60 H.264.

## Required Instrumentation

- capture timestamps
- encoded-frame timestamps
- encoder output cadence
- keyframe cadence
- dropped frames

## Exit Gate

Stable hardware-encoded output with no unnecessary CPU image-copy path.

---

# Phase 4 — LAN Transport Prototype

## Goal

Send one encoded camera stream across the local network.

## Deliverable

- phone discovers/connects to receiver
- encoded frames arrive
- timestamps survive transport
- packet/frame loss is observable
- queues are bounded

## Exit Gate

One 1080p stream can run for an extended test without latency continuously increasing.

---

# Phase 5 — One Real Camera in OBS

**2026-09-30:** Transport-neutral AU bridge, software H.264 decoder and native
OBS async source are implemented; synthetic GPU/loss/restart/lifecycle tests
pass. Samsung real-camera output, the ten-minute run and stream/source/OBS
recovery are user-confirmed; Phase 5 baseline is ACCEPTED WITH LIMITATIONS.
See TESTING.md section 19 for remaining measurement limits. USB remains
unimplemented; USB Transport Foundation is the next candidate, requiring a
separately authorized implementation slice.

## Goal

Complete the end-to-end pipeline.

```text
Android Camera
-> Encoder
-> LAN
-> OBS Plugin
-> Decoder
-> OBS Source
```

## Exit Gate

A real phone camera appears in OBS as a native source.

Measure end-to-end latency.

Do not optimize blindly before this measurement exists.

---

# Phase 6 — Remote Camera Control

## Goal

Control the phone from OBS.

## Initial Controls

- lens selection
- zoom
- autofocus trigger
- focus point
- exposure compensation
- AE lock
- AWB lock
- torch

Then add supported manual controls.

## Exit Gate

Controls work remotely and adapt to the device capability profile.

---

# Phase 7 — Multi-Camera Validation

## Goal

Scale from 1 to 4 simultaneous phones.

Test sequence:

```text
1 camera
2 cameras
3 cameras
4 cameras
```

## Measure

Per camera:

- FPS
- bitrate
- dropped frames
- queue depth
- latency
- jitter

System:

- CPU
- GPU
- RAM
- decoder load
- OBS render time

## Exit Gate

Four streams remain independent.

A disconnect/restart of one camera does not stop the others.

---

# Phase 8 — Camera Control Dock

## Goal

Make four-camera operation practical.

## Deliverable

An OBS dock that provides:

- camera list
- friendly names
- status
- live controls
- telemetry
- reconnect state

## Exit Gate

An operator can manage all connected cameras without touching the phones for normal operation.

---

# Phase 9 — Latency and Decode Optimization

## Goal

Reduce latency and resource use based on measured bottlenecks.

Possible work:

- queue tuning
- stale-frame dropping
- hardware decode
- GPU texture path
- decoder threading
- keyframe/recovery tuning
- adaptive jitter strategy

## Exit Gate

Optimization is backed by before/after measurements.

---

# Phase 10 — Audio

## Goal

Add optional phone audio.

## Risks

- clock drift
- A/V sync
- multi-camera audio policy
- resampling
- OBS timestamp alignment

## Exit Gate

Audio remains synchronized over long-running tests.

---

# Phase 11 — Native USB Transport

## Goal

Add reliable wired transport without requiring ADB for production use.

## Rule

Do not redesign camera sessions around USB.

USB must implement the transport interface.

---

# Phase 12 — Advanced Video Modes

Only after the core product is stable:

- HEVC
- 4K30
- 4K60 where supported
- 10-bit
- HLG/HDR10 where feasible
- higher bitrate modes
- high-speed capture experiments

Each mode requires independent latency, thermal, and stability validation.

---

# Phase 13 — Camera Synchronization

## Goal

Optionally align multiple cameras in time.

The low-latency mode should remain available separately.

Possible operator modes:

```text
Lowest Latency
Synchronized Cameras
```

Do not sacrifice the low-latency path by default.
