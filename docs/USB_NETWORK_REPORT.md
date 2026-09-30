# CamSure — USB Network Mode Completion Report

Implementation started: 2026-09-30. Final build handoff: 2026-10-01 (Asia/Manila).

## 1. Verdict

```text
STATUS: PARTIAL
USB NETWORK VIDEO IN OBS: YES (Xiaomi 14T ten-minute baseline)
ADB REQUIRED: NO
WI-FI REQUIRED: NO
EXISTING DECODER REUSED: YES
EXISTING OBS VIDEO PATH REUSED: YES
LAN REGRESSION: NOT RUN (physical); synthetic regression PASS
```

Implementation and host validation are delivered. On 2026-10-01 the user
confirmed a completed Xiaomi 14T ten-minute USB video run without visible drops.
Matched sender/receiver counts are 17999 complete AUs and 241146 packets, with
zero packet loss. The tested configuration uses the paced Android sender,
readiness-based Windows receiver and explicit 256 KiB receive buffer. See
PROGRESS.md for the evidence and preceding unsuccessful trials. Qualification
remains partial: two-hour operation, battery sustainability, other devices,
multicamera, physical LAN regression and end-to-end latency remain open.
The remaining report records the initial implementation/handoff unless updated.

## 2. Final Architecture

```text
Camera2 -> existing MediaCodec H.264 -> EncodedAccessUnitSink
  -> existing RtpH264Sender (LAN default; USB explicit local IPv4 bind)
  -> UDP/RTP over manually enabled Android USB tethering
  -> Windows selected adapter IPv4 bind + expected-peer filter
  -> existing RtpH264Receiver -> EncodedVideoAccessUnit
  -> existing EncodedAuPipe -> VideoSession -> FFmpeg decoder
  -> existing latest-frame handoff -> native OBS async I420 source
```

## 3. USB Link Selection

Android lists up, usable IPv4 interfaces, excluding loopback, point-to-point,
virtual interfaces and ConnectivityManager-reported Wi-Fi/cellular/VPN upstream
interfaces. Selection is always explicit, including when only one candidate is
listed. The operator confirms which address belongs to manually enabled USB
tethering. Names/subnets are never treated as USB proof. Other Ethernet-like
links can appear: this inventory does not certify physical USB identity.

The selected interface index/address/prefix must remain present. PC peer must be
a usable different host on that prefix; overlapping peer subnets or duplicate
local addresses on other up Android interfaces fail closed. The sender binds
an initially unbound DatagramSocket to that address and connects its fixed peer;
there is no fallback to the LAN/default socket after USB failure. Polling checks
run during idle and fragmented sends, and an independent 100 ms monitor can
close the socket if the sender is blocked.

Windows `--list-adapters` lists up Ethernet-type IPv4 candidates with adapter
IDs/names/prefixes. The operator confirms the USB adapter in Windows Settings.
USB requires `--transport usb --adapter ID --bind LOCAL --peer ANDROID`.
Unknown/absent/ambiguous selections, duplicate addresses, unusable peers and
peers outside the selected subnet fail. Receive socket binds only the selected
local IPv4; source-IP filtering precedes the RTP parser. This is isolation,
not authentication. Port and OBS pipe are per receiver instance.

## 4. Media Reuse

Camera2 capture/codec configuration, MediaCodec compression, compressed sink,
RTP packetization/PTS extension, AU reconstruction and pipe envelope are reused.
The queue receives a clock seam for deterministic tests; its default policy is
unchanged. EncodedVideoAccessUnit, EncodedAuPipe, VideoDecoder, VideoSession and
OBS integration are unchanged. Only the existing native test runner accepts an
optional receiver DLL path, so a live receiver executable need not be replaced.

## 5. Link-Loss Recovery

Android selected-interface loss/change, route ambiguity or USB send error aborts
the queue, closes the socket and posts camera-run stop. Monitor and sender are
interrupted/joined on close. The operator exports the final report, refreshes
selection and starts a fresh experiment; each sender generates a new SSRC.

Windows monitors the selected adapter/address/prefix. Loss/change or terminal
socket error terminates the receiver with exit code 3 and disposes its pipe worker. Reassembly becomes
unreachable, queued pipe work is discarded by disposal, and the existing native
EOF/idle behavior clears video (existing idle threshold 1.5 s). Restart the helper
with reselected addresses and restart the Android stream. USB receiver generations
start from a new time-based epoch; changed SSRC increments generation, clears
configuration and marks discontinuity. A bounded set of 64 retired SSRCs rejects
packets from earlier streams; reaching that bound requires helper restart.
Decoder/pipe recovery still gates on valid SPS/PPS plus IDR. Detection is polling,
not a guarantee against an unplug/replug shorter than the polling interval;
physical cable and OEM behavior remain unqualified.

## 6. Buffering

| Stage | USB policy | LAN policy |
|---|---|---|
| Android complete AU queue | 4 AUs / 512 KiB / 100 ms | unchanged 24 / 512 KiB / 500 ms |
| Android compressed AU assembly | existing 2 MiB ceiling; queue rejects >512 KiB | unchanged |
| Android send | requested 64 KiB; effective value exported | default OS behavior |
| In-flight Android AU | drop remaining fragments after 100 ms residence; counted; wait for keyframe | unchanged |
| Windows receive/send socket | requested 64 KiB; effective values logged | receive unchanged 1 MiB |
| RTP reassembly | 1 AU / 2 MiB / 100 ms | unchanged 500 ms |
| AU pipe | unchanged 4 AUs / 4 MiB / 100 ms | shared |
| Native output | existing one latest frame / 100 ms | shared |

Windows USB receive timeout is 25 ms to service expiry; link checks run every
100 ms. No retransmission/backlog is added. OS buffer occupancy is not measured;
requested buffer size is not a latency measurement. Oversize/stale drops do not
increase limits. Pool retention remains existing bounded 4 MiB, separate from
queued payload bytes. A single UDP send may already have reached the OS before
an in-flight deadline can be observed.

## 7. Telemetry

Android UI/JSON adds transport mode, local interface/address, link state,
effective send buffer, in-flight stale drops and recent sender FPS/RTP bitrate
(interval counter deltas; RTP bitrate includes headers). Existing destination (including
port), SSRC, AU/packet counts, send failures, queue depth/bytes/age/high-water/drop
reasons, encoded FPS/bitrate, CPU/thermal/battery remain available.
Windows logs mode, adapter name/ID, local bind, expected peer, port/pipe, effective
socket buffers, epoch/session, rejected peers, retired-SSRC packets, stale and
oversize AUs, packet/AU/gap/incomplete/PTS counters, reassembly bounds and terminal
link events. Existing native OBS log supplies decoder submissions/frames/errors/
resets and submitted frames; collect it separately. Live native FPS must be
computed from counter/time deltas, not equated with sender FPS. No USB-only
latency claim is added.

## 8. Physical Route Proof

NOT RUN. Host inventory currently showed Ethernet and a Hyper-V virtual adapter;
that is not USB evidence. Binding/self-tests are not packet-capture route proof.
Use [USB acceptance guide](USB_NETWORK_TESTING.md) with Wi-Fi/mobile data/debugging
off, save adapter inventory, Android report and capture from the selected adapter.

## 9. Real Video Test

NOT RUN for USB. No physical resolution/FPS/bitrate/decoder/OBS result is claimed.
The prior Samsung LAN 1080p30 acceptance remains historical LAN evidence.

## 10. Ten-Minute Soak

NOT RUN. No USB counters, sustained memory/CPU/thermal or stability measurements.

## 11. Latency

NOT MEASURED. No samples/p95/max. Qualification targets: p95 <=250 ms and max
<=500 ms, measured glass-to-glass at beginning/middle/end, never PTS subtraction.

## 12. Reconnect Test

Physical attempts 0/10. Recovery times unavailable. Simulated receiver tests
cover fresh generations, discontinuity, configuration isolation and retired SSRC
rejection. Synthetic shared-path stop/restart clears output and resumes video.
These do not establish cable/OEM recovery.

## 13. OBS Lifecycle Test

USB frontend lifecycle NOT RUN. Shared synthetic native probe passed 132 distinct
GPU frames, stop-clear/restart, idle/partial-read source removal x10 and shutdown.
It does not operate the OBS frontend or validate saved scene reopen over USB.

## 14. LAN Regression

Physical LAN NOT RUN. Updated receiver passed existing AU/PTS/FU-A/loss/restart
self-tests. Synthetic RTP -> AU pipe -> unchanged decoder -> native OBS I420/GPU
passed. Android tests preserve LAN 24-unit/500 ms defaults. Restore LAN and run
the physical real-video/start-stop/source-readd gate after USB qualification.

## 15. Limitations

Physical USB route, video, 600 s soak, latency, 10 cable cycles, consumer stall,
frontend OBS lifecycle and post-USB LAN tests are deferred by the user. Interface
inventory requires operator confirmation, not automatic hardware USB attestation.
Public interface/address visibility and actual routing must be qualified on the
target OEM/device. Polling can miss a very brief link epoch. ADB/debugging are not
runtime dependencies; APK sideload/install remains operator work. Manual tethering
and explicit endpoint reselection/helper restart are required. No automatic USB
reconnection is promised. Android API 26+ remains the app baseline; the existing
hardware-only camera experiment still requires API 29+.

## 16. Files Changed

- Android UsbNetworkLink.kt: IPv4 inventory, selection/route validation, mode name.
- RtpH264Sender.kt: explicit bind, USB queue/socket policy, monitor/abort/telemetry.
- CameraToEncoderExperiment.kt: optional link ownership, stop callback and JSON.
- MainActivity.kt: USB toggle, local address selection, PC address/port and status.
- EncodedAccessUnitSink.kt: injectable queue clock, unchanged default policy.
- Android manifest/build: network-state permission and JUnit test dependency.
- UsbNetworkPolicyTest.kt: subnet, bounds, stall, abort and LAN-default tests.
- Program.cs / UsbNetworkSelection.cs / UsbNetworkSelfTest.cs / ReceiverSelfTest.cs:
  explicit Windows selection/bind/filter, link epochs, USB limits and tests.
- Native test runner: optional DLL path only; native source/decoder untouched.
- Project handrails, receiver/Android README and this report/acceptance guide.

## 17. Next Recommended Slice

Complete USB Network Mode physical qualification using the prepared build and
acceptance guide. Only after that passes, evaluate **Camera Manual Controls
Foundation**. No manual controls, audio or multicamera implementation is included.

## Recorded host evidence

- [Android build/lint/unit log](evidence/2026-10-01-usb-android-build-tests.log).
- [Windows build](evidence/2026-09-30-usb-receiver-build.log) and
  [self-tests](evidence/2026-09-30-usb-receiver-self-test.log).
- [Shared native regression](evidence/2026-09-30-usb-shared-native-regression.log):
  latest run 132 distinct GPU frames; earlier run 136. These are separate passes
  of the synthetic test, not physical FPS/throughput measurements.

## Prepared artifact identities (2026-10-01)

- `apps/android/app/build/outputs/apk/debug/app-debug.apk`: 1,212,092 bytes; SHA-256 `F223E40749F65996EDA7BCC16A2D6EC345B70C0200280AC878D4B9CD7B039715`.
- `tools/windows-rtp-receiver/bin/usb-validation/CamSure.RtpReceiver.dll`: 76,800 bytes; SHA-256 `6F7F3B62A9F5ED566478A72373DD2FD78312A5551E7FD22E4F6B338B9F38D157`.
