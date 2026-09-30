# OPEN_QUESTIONS.md

These questions are intentionally unresolved.

Do not convert them into assumptions without evidence.

---

## Q-001 — Network Video Protocol

Should the initial transport use:

- custom UDP framing
- RTP
- another established low-latency framing approach

Need to evaluate:

- packet loss behavior
- implementation complexity
- timestamping
- keyframe recovery
- debugging
- future audio synchronization

---

## Q-002 — Control Transport

Options may include:

- TCP
- WebSocket
- QUIC-based control
- control messages over the same connection as another reliable channel

The control path should prioritize reliability over minimum latency.

---

## Q-003 — Android Camera Layer

How much should use:

- direct Camera2
- CameraX
- CameraX with Camera2 interop

Priority:

Maximum reliable access to device capabilities and low-latency video streaming.

Decision should follow testing on multiple phones.

**Evidence update — 2026-09-27:** The Phase 3 direct Camera2-to-hardware-H.264
path completed repeated five-minute baselines on Samsung SM-X115 at 1080p30 and
Xiaomi 2406APNFAG at 720p30. This demonstrates that the controlled direct
Camera2 path works at those selected modes on both devices. Xiaomi logical-
camera physical outputs 3 and 6 also completed 720p30 runs, but neither ID was
tested as an independent camera. No CameraX or Camera2-interop comparison has
been run, so this evidence does not choose the long-term capture layer; Q-003
remains open.

---

## Q-004 — Decoder Strategy

**Update 2026-09-30:** Software FFmpeg H.264 is selected for the current slice
(D-021). Exact PTS, I420 output, 720p/1080p, synthetic loss/restart and native OBS
GPU output pass host probes. Media Foundation's tested regenerated timestamps
failed the input/output PTS gate. Hardware decoding strategy, physical latency
and sustained/multicamera decode load remain open. The older candidate notes
below are retained as context.

Initial:

Software decode via FFmpeg/libavcodec may be simplest.

Later:

Evaluate Windows hardware decode / D3D11 path.

Need measurements before deciding how early hardware decode becomes mandatory.

---

## Q-005 — Exact Four-Camera Target

Candidate baseline:

```text
4 x 1080p60 H.264
```

Need to determine:

- expected bitrates
- CPU/GPU decode cost
- OBS composition cost
- network load
- realistic supported PC baseline

---

## Q-006 — Wi-Fi Buffer Modes

Potential operator modes:

```text
Ultra Low Latency
Balanced
Stable
```

Need measured queue sizes and behavior rather than arbitrary names.

---

## Q-007 — Discovery Protocol

**Status:** Core discovery and delivery are demonstrated on one LAN run; the
question remains open for offline/reconnect acceptance. This is not a permanent
protocol decision.

The prototype uses IPv4 mDNS/DNS-SD service type
`_camsure-rtp._udp.local.`. The Windows receiver advertises its current media
port; Android uses NSD to browse, resolve, and let the operator select a
receiver. Discovery only supplies the endpoint to the existing RTP sender and
does not alter the media path. The implementation and current test record are
in [Phase 4 testing](TESTING.md#18-phase-4--rtph264-over-lan-and-q-007-local-discovery).

In the user-reported 2026-09-28 LAN trial, Android reported “receiver
detected.” The Windows receiver at `192.168.0.146` received three browse
queries from `192.168.0.233:5353` and answered each with the PTR plus SRV/TXT/A
records. A follow-up run supplied after the instruction to select that
receiver delivered 34,356 RTP packets and 2,463 complete AUs by receiver-session
time 6:40, with zero observed gaps or PTS mismatches. This demonstrates the
core discovery-to-media path; the WAN state and exact active firewall
profile/rule were not recorded. The follow-up log contains about 1:22 of media,
which is functional smoke evidence rather than a ten-minute soak.

Still need to verify on the Samsung SM-X115 with the WAN disconnected:

- offline discovery while local Wi-Fi remains active
- the effective Windows firewall profile/rule for the successful browse
- distinct receiver naming when more than one Windows receiver is present
- receiver restart, Android network loss/return, and endpoint reselection

The Windows host reports default inbound block and local rules governed by
Group Policy; the successful browse and media reception prove those paths
worked in this trial, while the active rule/profile is unknown. No firewall
change was made by the agent. Close Q-007 only after the no-internet LAN test,
firewall details, multi-device naming, and reconnect checks are recorded. Keep
discovery separate from RTP transport and recovery.

---

## Q-008 — Friendly Camera Identity

Need a stable scheme combining:

- persistent device/install ID
- user-defined camera name
- current network endpoint

Must survive IP changes.

---

## Q-009 — OBS Preview Click-to-Focus

Potential feature:

Click a point in a camera preview and send normalized coordinates to the phone as AF/AE metering regions.

Need to validate:

- OBS interaction hooks
- coordinate transforms
- crop/scale handling
- portrait/rotation handling
- camera sensor coordinate mapping

---

## Q-010 — HDR Pipeline

Need end-to-end validation before claiming support.

Questions:

- Android capture mode
- 10-bit encoding
- HEVC Main10
- color metadata
- OBS ingest behavior
- OBS color management
- display/output behavior

Do not implement merely because the phone reports HDR capability.

---

## Q-011 — Native USB Production Transport

**2026-09-30 update:** USB Network Mode is implemented via manual Android USB
tethering, without ADB/debugging, using shared RTP/H.264 and decoder/OBS. Physical
acceptance is pending; the user elected to test later. Native USB/AOA is only a
possible future fallback. See USB_NETWORK_REPORT.md. Older rationale follows.

ADB forwarding may be useful for development.

Production USB needs a user-friendly approach without requiring USB debugging.

This is intentionally deferred until LAN streaming is proven.

---

## Q-012 — Licensing

**Update 2026-09-30:** D-021 selects the pinned OBS FFmpeg runtime for this
GPL-2.0-or-later plugin. Its GPL/version3 build makes the combined binary package
GPL-3.0-or-later. Local staging includes DLLs/notices; public corresponding-source
and exact build compliance remain release work. Android licensing is unchanged.

Plugin licensing is resolved by D-019 (GPL-2.0-or-later). Remaining decisions:

- Android application license
- use of FFmpeg
- third-party libraries
- distribution model

No incompatible third-party code should be incorporated before this is settled.

---

## Q-013 — Product Name

RESOLVED 2026-09-26: CamSure is the official product name (D-017).

Keep future protocol identifiers neutral where practical; branding is no longer blocked.

---

## Q-014 — Phase 1 Frontend Acceptance

RESOLVED 2026-09-27: user confirms both the primary four-source/restart test and
the remaining frontend lifecycle and soak checks. Phase 1 acceptance is PASS.
The discovery issue was corrected by installing under ProgramData/obs-studio/plugins.
See PROGRESS.md and TESTING.md for user-reported versus automated evidence.

The minimal diagnostic font is ASCII-only; broader text rendering is not required
for Phase 1. Do not turn the diagnostic generator into the production video path.

## Q-015 — Phase 2 Physical-Device Capability Comparison

**Status:** RESOLVED 2026-09-27

User-supplied reports from Samsung SM-X115 and Xiaomi 2406APNFAG were parsed and
compared. See `docs/evidence/2026-09-27-samsung-sm-x115-capabilities.json`,
`docs/evidence/2026-09-27-xiaomi-2406apnfag-capabilities.json`, and PROGRESS.md.
Both report Android 16/API 36, so cross-API behavior remains untested. Runtime
camera/encoder compatibility remains part of Phase 3, not this metadata question.
