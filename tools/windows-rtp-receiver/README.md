# CamSure fixed-endpoint RTP/H.264 receiver

This is a Windows receiver for the Phase 4 LAN prototype. By default it only
reports counters. It binds
to an IPv4 UDP port, validates CamSure RTP packets, reassembles H.264 access
units (including FU-A fragmented NAL units), and reports timing, loss, and
bounded reassembly-buffer counters. It also advertises the bound media endpoint
with DNS-SD service type `_camsure-rtp._udp.local.` over IPv4 mDNS. It does not
decode or display video itself. The optional `--obs-pipe camsure-camera-1`
adapter forwards owned transport-neutral access units into the native OBS
plugin, where decoding and frame submission occur.

## Build and run

The project targets .NET 10 and has no external package dependencies. From the
CamSure repository root:

```powershell
dotnet build tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj
dotnet run --project tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj -- --bind 0.0.0.0 --port 5004
```

For native OBS video, enable **Real camera video** on CamSure Camera and leave
**Local AU pipe** as `camsure-camera-1`, then add `--obs-pipe camsure-camera-1`
to the receiver command. Run `dotnet run --project
tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj -- --self-test` for the
reassembly/ownership regression. See `docs/TESTING.md`, section 19, and
`docs/DECODE_OBS_REPORT.md` for the complete bridge contract and acceptance gate.

The adapter queue is bounded to four units, 4 MiB including configuration, and
100 ms residence age, with one timed in-flight write. Overflow, reconnect, and
loss flush dependent video and wait for configuration plus an IDR. The local
pipe is a prototype adapter; the decoder has no RTP or networking dependency.

Use `ipconfig` on the Windows PC to find its IPv4 address. Keep the phone and
PC on the same local network; Ethernet for the PC is preferred. Permit inbound
UDP 5004 for RTP and UDP 5353 for mDNS on the applicable Windows network
profile, subject to local firewall policy. Ensure the LAN/access point passes
multicast and does not isolate clients. The receiver advertises its selected
media port in DNS-SD, so a non-default `--port` is resolved by the app. Press
Ctrl+C to stop, send a DNS-SD goodbye, and print final counters.

On Android, prepare camera routes and tap **Find receivers on local network**.
Select the resolved CamSure service, then start the selected direct mode. The
optional typed IPv4 field remains a manual override; leave it and the selection
blank for the Phase 3 capture-only run. A LAN run uses the selected direct mode
for ten minutes; the initial Samsung target is rear 1920×1080@30. The app
reports sender counters and exports them with the runtime report. Read packet
gaps and complete/incomplete access-unit counts from this Windows process.
Sender counts alone do not prove receiver delivery. Discovery only chooses the
destination before a run; it does not automatically retarget an active RTP
sender after a receiver or network change.

## Prototype wire contract

- IPv4 UDP, fixed port 5004, RTP version 2, dynamic payload type 96.
- H.264 RFC 6184 non-interleaved packetization mode 1. The Android sender emits
  single-NAL packets and FU-A fragments. The receiver also accepts STAP-A.
- RTP timestamps use a 90 kHz clock and map MediaCodec PTS relative to the first
  sent access unit from a random 32-bit stream base. The mapping wraps modulo
  2^32 as RTP requires. MediaCodec PTS remains the source timeline.
- A prototype RFC 8285 one-byte RTP header extension (profile `0xBEDE`) carries
  the source `presentationTimeUs` in element ID 1 (8-byte signed big-endian),
  the completed access unit's MediaCodec flags in ID 2 (4-byte big-endian), and
  keyframe/configuration-included bits in ID 3. This extension is defined
  out-of-band by this fixed receiver and is not a permanent CamSure protocol.
- The RTP marker bit terminates an access unit. The receiver compares exact PTS
  extensions with the 90 kHz RTP timestamp mapping and records mismatches.
- Each RTP datagram is capped at 1,200 bytes. The sender uses a random initial
  sequence number and per-run SSRC. A new run therefore appears as a new RTP
  stream.
- MediaCodec SPS/PPS from output-format codec-specific data are cached and sent
  with an IDR access unit when needed. Queue overflow flushes queued complete
  access units and waits for an IDR; the next IDR is sent with codec
  configuration before its video NAL units.

## Buffer bounds and reported counters

The Android sender queue is limited to 512 KiB, 24 access units, and 500 ms of
queue residence age. The byte cap is about 0.52 seconds at the measured Samsung
8 Mbps baseline; the 500 ms age cap enforces the latency budget. Each encoded
access unit is limited to 2 MiB. The sender reports current/high-water queue
depth and bytes, oldest-item age, enqueued/sent access units and packets, and
drops for overflow, stale age, waiting for an IDR, oversized units, missing
codec configuration, malformed output, and send failures.

The receiver processes datagrams synchronously, so it has no separate managed
packet queue. Its one in-progress access-unit reassembly buffer is bounded to
2 MiB and 500 ms. The console reports current and high-water reassembly bytes
and age, complete and incomplete access units, IDR count, SPS/PPS presence,
sequence gaps, out-of-order packets, malformed/oversized packets, and RTP/PTS
mapping mismatches. The requested Windows socket receive buffer is reported at
startup; its OS-level occupancy is not exposed by this prototype.

## Evidence boundary

A successful build only proves that the receiver source compiles. A user-supplied
fixed-address Samsung 1080p30 run is recorded in `docs/TESTING.md` and its
evidence files. Android same-LAN discovery without internet, effective firewall
passage, service/network reconnect behavior, and induced packet-loss recovery
remain to be observed. This prototype does not establish decoding, visual
quality, one-way latency, Wi-Fi reliability, or a permanent RTP protocol
decision.

Standards references: [RTP (RFC 3550)](https://www.rfc-editor.org/rfc/rfc3550),
[H.264 RTP payload format (RFC 6184)](https://www.rfc-editor.org/rfc/rfc6184),
and [RTP header extensions (RFC 8285)](https://www.rfc-editor.org/rfc/rfc8285).
