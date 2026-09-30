# AGENTS.md

## Purpose

This file is the operating handrail for Codex and other implementation agents.

The project is a native Android-to-OBS multi-camera system. The agent must preserve low latency, image quality, camera capability access, and multi-camera scalability.

## Before Making Changes

Always:

1. Read `README.md`.
2. Read `DECISIONS.md`.
3. Read `ARCHITECTURE.md`.
4. Check `PROGRESS.md`.
5. Check `OPEN_QUESTIONS.md` for related unresolved items.
6. Inspect the current implementation before proposing a rewrite.

Do not assume the repository still matches an earlier conversation or plan.

## Development Philosophy

Prefer:

- small, measurable implementation slices
- direct evidence over visual guessing
- instrumentation before optimization
- capability discovery instead of hard-coded device assumptions
- independent camera sessions
- explicit ownership and lifecycle boundaries
- reconnectable components
- minimal buffering
- newest-frame-first behavior for live video
- platform-native camera and codec APIs
- clean separation between transport, decode, control, and OBS presentation

Avoid:

- large rewrites without a demonstrated need
- single-camera global state
- hidden buffering
- magic retry loops
- hard-coded phone models
- hard-coded lens assumptions
- assuming all Android devices expose the same camera controls
- mixing transport logic directly into OBS source logic
- treating Wi-Fi and USB as completely separate products
- premature 4K/HDR work
- cloud dependencies for local camera operation
- browser-source architecture
- virtual-webcam architecture unless explicitly added later as a separate feature

## Mandatory Multi-Camera Rule

The architecture must support multiple independent camera sessions.

Even while only one physical camera is being tested, core runtime objects must not assume a singleton camera.

Preferred conceptual model:

```text
CameraManager
 ├─ CameraSession A
 ├─ CameraSession B
 ├─ CameraSession C
 └─ CameraSession D
```

Each `CameraSession` should independently own or reference:

```text
Device identity
Capability profile
Transport
Control channel
Decoder
Frame queue
Clock/timestamps
Telemetry
Reconnect state
OBS source association
```

## Latency Rule

This is a live-production camera system.

A stale frame is often worse than a dropped frame.

When the consumer falls behind, prefer dropping outdated frames over allowing latency to accumulate indefinitely.

No queue should grow without a defined maximum.

Any buffering introduced for stability must be:

- intentional
- bounded
- measurable
- documented

## Camera Quality Rule

Do not reduce the Android camera engine to the lowest common denominator.

The application should query and report actual device capabilities, including when available:

- logical and physical cameras
- lens/focal-length information
- supported resolutions
- FPS ranges
- high-speed video modes
- manual sensor controls
- exposure compensation
- AE/AWB locks
- manual focus
- stabilization modes
- dynamic range profiles
- codec capabilities
- 10-bit/HDR support
- hardware encoder capabilities

OBS controls must be generated from reported capabilities rather than from assumptions.

## OEM Camera Limitation

Do not claim parity with the manufacturer's stock camera application unless verified.

Android third-party camera access may not expose all OEM computational photography/video processing.

This limitation should be documented rather than worked around with unsupported assumptions.

## Transport Rule

The application should operate fully on a local network.

Internet connectivity is not required for camera transport.

Initial priority:

```text
Android -> Local LAN -> OBS PC
```

The transport API must be abstract enough to allow later USB transport without rewriting camera sessions.

Conceptually:

```text
Transport
 ├─ NetworkTransport
 └─ UsbTransport
```

Do not duplicate the whole camera pipeline per transport.

## OBS Rule

The system should integrate directly into OBS as a native source.

Do not insert unnecessary layers such as:

- virtual camera driver
- browser source
- standalone desktop preview application in the critical video path

A management dock is allowed and encouraged for camera control and telemetry.

## Camera Control Rule

Controls should be remotely operable from OBS where the phone exposes them.

Likely controls:

- lens selection
- zoom
- autofocus trigger
- focus point
- manual focus
- exposure compensation
- AE lock
- white-balance lock
- manual WB when available
- ISO
- shutter
- torch
- stabilization
- resolution
- FPS
- bitrate
- codec

Unsupported controls must be hidden or clearly marked unavailable.

## Licensing Rule

DroidCam and other open-source camera projects may be studied as architectural references.

Do not copy GPL-licensed implementation into a differently licensed codebase without an explicit licensing decision.

Prefer official platform documentation and clean-room implementation.

## Performance Work

Do not optimize based only on intuition.

Measure at least:

```text
capture timestamp
encoder input/output time
packet send time
packet receive time
decoder completion time
OBS frame submission time
OBS presentation time when measurable
```

For multi-camera tests also record:

```text
per-camera bitrate
packet loss
jitter
dropped frames
decoder load
CPU use
GPU use
memory use
thermal state
phone battery behavior
```

## Completion Reports

After every meaningful slice, update `PROGRESS.md` with:

- date
- slice name
- status
- what changed
- what was tested
- measured results
- regressions
- unresolved issues
- next recommended slice

Do not mark a slice complete purely because it compiles.

## Decision Changes

If implementation evidence contradicts a decision in `DECISIONS.md`:

1. Do not silently change direction.
2. Record the evidence.
3. Mark the old decision as `SUPERSEDED` only after a new decision is documented.
4. Update architecture and tests accordingly.

## Scope Protection

When asked to implement a specific slice:

- stay within the slice
- avoid unrelated refactors
- note adjacent issues instead of fixing them opportunistically
- preserve known-good behavior
- make the smallest change that proves or disproves the hypothesis

## Definition of "Done"

A feature is not done until it is:

- implemented
- observable
- manually or automatically tested
- measured when performance-sensitive
- documented in `PROGRESS.md`
- free of known critical regressions

## Current USB Slice (2026-09-30)

The user explicitly authorized USB Network Mode via manually enabled Android
USB tethering. Implementation and host coverage are present; the user deferred
physical acceptance. Read USB_NETWORK_REPORT.md / USB_NETWORK_TESTING.md before
qualifying it. Preserve LAN defaults and the unchanged decoder/OBS boundary.
Do not count source/build/synthetic/third-party evidence as physical USB proof.
Controls, audio and multicamera implementation remain separately authorized slices.
