# Phase 4 Handoff — One Encoded Camera Stream over LAN

## Starting Point

Phase 3 established repeatable direct Camera2-to-hardware-H.264 runs on two
physical Android devices. The user supplied seven completed five-minute runtime
reports:

- Samsung SM-X115 / Samsung A9: two 1920×1080@30 runs, hardware
  `c2.mtk.avc.encoder`, approximately 30 FPS and 7.989–7.990 Mbps.
- Xiaomi 2406APNFAG / Xiaomi 14T: three logical-camera 1280×720@30 runs and one
  run each through logical-camera physical output routes 3 and 6. These ran at
  approximately 30 FPS and 3.624–3.625 Mbps.

All seven runs completed without capture failures. The four follow-up reports
include `endOfStreamSeen: true`; the original three exports predate that
exporter field. These are user-supplied device reports; they establish the
tested camera-to-encoder modes, not encoded-file playback, network streaming,
or visual-quality acceptance. See the [Phase 3 results in PROGRESS](PROGRESS.md)
and the [runtime evidence index in TESTING](TESTING.md).

The current [`CameraToEncoderExperiment.kt`](../apps/android/app/src/main/java/com/camsure/profiler/phase3/CameraToEncoderExperiment.kt)
output callback counts encoded bytes and timestamps, then releases the
MediaCodec output buffer. It does not preserve or transmit a video bitstream.
The Phase 3 direct Camera2-to-encoder Surface path should remain the capture
baseline; do not insert a CPU raw-frame copy stage.

## Phase 4 Objective

Prove that one hardware-encoded camera stream can travel from an Android phone
to a Windows PC over the local network, with presentation timing retained,
loss visible, and every application queue bounded.

Use the tested Samsung 1920×1080@30 mode as the first transport source. The
Xiaomi 1280×720@30 mode is a useful fallback if the network prototype needs a
second known-good input. Do not lower the Samsung mode silently to make a
transport test pass.

```text
Camera2
  -> MediaCodec H.264 surface encoder
  -> encoded access units + codec metadata + presentation timestamps
  -> bounded sender queue
  -> LAN transport
  -> Windows test receiver
  -> access-unit / timestamp / loss counters
```

Discovery is a separate step from media transport:

```text
Windows receiver advertises on the local network
  -> Android discovers and resolves the receiver
  -> Android opens the selected media connection
```

## Scope

### Included

- One independently owned camera session and one encoded stream.
- H.264 encoded access-unit extraction from MediaCodec output buffers.
- A Windows development receiver that reconstructs and counts incoming access
  units and reports packet gaps, out-of-order packets, and incomplete units.
- Bounded, measured sender and receiver buffering with explicit overflow/drop
  counters.
- Presentation timestamps and keyframe/codec-configuration information
  carried through the transport.
- A fixed-address LAN smoke test followed by a local discovery test.
- An extended single-stream run that shows queue age does not keep growing.

### Not part of this handoff

- OBS ingest, decoding, preview, or presentation. Phase 5 owns the first real
  camera inside OBS.
- Multiple simultaneous cameras, remote camera controls, audio, USB transport,
  cloud services, or internet connectivity.
- Xiaomi aligned 1080p or independent opens of Xiaomi camera IDs 3 and 6. Those
  remain separate investigations.
- A permanent protocol decision. Q-001, Q-002, and Q-007 remain open until the
  prototype evidence is recorded and the project decision is updated.

The one-stream prototype must still use per-session ownership so a later second
camera does not require replacing singleton state with a new architecture.

## Recommended Implementation Sequence

### 1. Define the prototype contract

Write down the access-unit boundary, codec-configuration delivery, stream
identity, presentation timestamp mapping, packet ordering, end-of-stream and
restart behavior, queue limits, and the counters the receiver must expose.
Keep media and discovery/control responsibilities separate.

The Android source is `MediaCodec.BufferInfo`: use its `offset`, `size`,
`presentationTimeUs`, and flags when extracting encoded output. Keep codec
configuration data from output-format changes available to the receiver, and
identify keyframes. Do not treat codec-config buffers as video frames. Honor
partial-frame flags and establish complete access-unit boundaries; do not
assume every output callback is one whole frame. See the [Android BufferInfo
reference](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo).

### 2. Add a narrow encoded-output sink boundary

In the MediaCodec output callback, copy only the compressed H.264 bytes needed
for the access unit into independently owned, pooled storage. Preserve its PTS,
keyframe/configuration flags, and stream identity. Enqueue it without waiting
for network I/O, then release the MediaCodec output buffer promptly. The current
callback is serialized on the camera/encoder handler, so it must not block on a
socket or receiver.

Bound the queue by both bytes and media age, and report current/high-water depth,
oldest-item age, enqueue/send counts, and drops by reason. Choose the initial
limits from the measured bitrate and an explicit latency budget; record those
values with the run. On overflow, drop complete stale access units and define
how the stream resumes at a decodable keyframe with codec configuration. Never
silently discard arbitrary bytes or let an unbounded backlog build.

### 3. Prototype media transport against a fixed LAN endpoint

Build a small Windows test receiver outside the OBS video path. First connect
to a known local address so transport failures can be diagnosed independently
of discovery. The receiver should validate packet ordering, identify gaps and
incomplete access units, recover presentation timing, count complete access
units/keyframes, and report its own bounded queue state. It does not need to
decode or display video in this phase.

**Protocol hypothesis to test:** prototype H.264 over RTP/UDP. RTP supplies
packet sequence numbers and timestamps useful for observing loss and timing;
RFC 6184 defines the H.264 RTP payload format and uses a 90 kHz media clock.
Map MediaCodec presentation timestamps to that clock from a documented stream
origin, and verify reconstructed frame timing against the source PTS. The
mapping is a transport representation; the MediaCodec PTS remains the Android
source of truth. The receiver must reassemble the chosen RTP H.264 packetization
mode, including fragmented large NAL units, and mark an access unit incomplete
when a packet is missing. Keep packet payloads below the tested path MTU to
avoid relying on IP fragmentation. Compare sender queue-drop counters with
receiver sequence gaps so application drops and network loss can be diagnosed
separately.

This is a reversible prototype choice, not a project-wide decision. Compare its
loss handling, implementation cost, timestamp behavior, keyframe recovery, and
debuggability against the alternatives in [Q-001](OPEN_QUESTIONS.md#q-001--network-video-protocol)
before closing that question. RTP does not itself make UDP reliable; packet
loss must remain visible. [RFC 3550](https://www.rfc-editor.org/info/rfc3550/)
describes RTP sequence numbers, timestamps, and loss monitoring; [RFC
6184](https://www.rfc-editor.org/info/rfc6184/) specifies the H.264 payload
format.

### 4. Add local receiver discovery

After the fixed-address path works, have the Windows receiver advertise a
CamSure receiver service on the local network and let Android discover and
resolve it. Android `NsdManager` supports DNS-SD service discovery and
registration; Windows behavior, firewall prompts, and reconnect handling still
need testing. Discovery only finds the receiver. The media connection remains
an explicit, separately managed transport. See the [Android NSD guide](https://developer.android.com/develop/connectivity/wifi/use-nsd)
and [NsdManager reference](https://developer.android.com/reference/android/net/nsd/NsdManager).

### 5. Run the single-stream acceptance test

Use the Samsung 1080p30 baseline with the PC preferably on Ethernet and the
phone on the same local network. Record the selected mode, measured bitrate,
test duration, sender/receiver queue limits and high-water marks, queue-age
trend, sender drops, receiver sequence gaps, incomplete access units, keyframe
count, and timestamp continuity. Start with a 10-minute run as a practical
prototype soak; the roadmap requires an extended run but does not prescribe a
duration, so report the chosen duration rather than treating ten minutes as a
permanent threshold.

If the network is interrupted, record reconnect behavior and whether a fresh
codec configuration/keyframe restores a clean stream. Compare clock-relative
PTS and arrival intervals; do not claim absolute one-way latency until the phone
and PC clocks have been synchronized. A clean sender report alone is not proof
of receiver delivery.

## Phase 4 Exit Evidence

Phase 4 can pass when the evidence shows:

- The phone discovers or connects to the receiver on a local network without
  internet access.
- The receiver obtains complete encoded H.264 access units and preserves their
  presentation-time progression and keyframe/configuration information.
- Sender-side drops and receiver-side packet gaps are separately observable.
- Sender and receiver queues have documented byte/age bounds, and the extended
  run shows no continuously increasing queue age or latency trend.
- The selected test duration, camera mode, bitrate, network arrangement,
  reconnect result, and any failures are recorded in `TESTING.md` and
  `PROGRESS.md`.

The acceptance boundary is transport delivery and measurement. It does not
prove decoding, visual quality, synchronized glass-to-glass latency, multi-
camera capacity, or OBS integration.

## First Coding Task

Add a narrow encoded-access-unit sink to the existing Phase 3 output callback,
with pooled storage, bounded queue limits, timestamp/flag preservation, and
drop counters. Feed that seam to a fixed-endpoint RTP/H.264 prototype receiver
for the first measured LAN trial. Keep protocol, discovery, and OBS concerns
behind separate boundaries, and record evidence before promoting the protocol
hypothesis into a decision.
