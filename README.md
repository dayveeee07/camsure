# CamSure
A native Android multi-camera live-production system for OBS Studio.

Validated slices: Phase 1 native diagnostic OBS source, Phase 2 Android
capability profiler with a two-device metadata comparison, and tested Phase 3
direct Camera2-to-hardware-H.264 baselines on Samsung and Xiaomi devices.
Phase 4 has physically validated fixed-address RTP delivery and functional LAN
discovery evidence. Phase 5 now implements a transport-neutral H.264 AU bridge,
FFmpeg decoder and native OBS async video, with synthetic GPU/recovery tests.
Samsung 1080p30 real camera video, a ten-minute run, and source/stream/OBS
recovery are user-confirmed. This slice is accepted with measurement limitations;
USB, audio, and multicamera operation remain future work.
See [the Decode → OBS report](docs/DECODE_OBS_REPORT.md) and
docs/PROGRESS.md for evidence and limits.
Read the complete [project handrails](docs/README.md) before implementation.
See the [Android profiler guide](apps/android/README.md),
[Windows build instructions](plugins/obs-camsure/README.md), and
[verified status](docs/PROGRESS.md).

- apps/android: Phase 2 capability profiler and Phase 3 one-camera encoder experiment
- tools/windows-rtp-receiver: RTP/H.264 counters and optional bounded OBS AU bridge
- plugins/obs-camsure: native OBS module
- shared: reserved for future protocol/schema definitions
- docs: authoritative handrails
