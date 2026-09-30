# CamSure — Transport-Neutral Decode → OBS Completion Report

Date: 2026-09-30 (Asia/Manila).

## 1. Verdict

```text
STATUS: ACCEPTED WITH LIMITATIONS (Samsung one-camera baseline)
REAL CAMERA VIDEO IN OBS: YES (user screenshot and confirmation)
TRANSPORT-NEUTRAL DECODER INPUT: YES
USB-READY DECODER BOUNDARY: YES
```

Implementation, native/.NET builds, synthetic decoded pixels and real
libobs/D3D11 output pass. The user subsequently supplied a real-camera OBS
screenshot, a completed 600.161-second Samsung 1080p30 run with paired sender/
receiver logs, and confirmed acceptable delay with no freezes. Phone stop/start,
source removal/re-add, and normal OBS close/reopen also worked according to the
user. The physical baseline is accepted with limitations: no measured
glass-to-glass latency or physical decoder-side OBS metrics were supplied.
See TESTING.md section 19 for preserved evidence and exact qualification.

## 2. Final Runtime Architecture

```text
Android Camera2 -> MediaCodec input Surface -> H.264 AU sink
 -> existing bounded RTP sender -> LAN UDP
 -> C# RtpH264Receiver (existing reassembly and network counters)
 -> owned EncodedVideoAccessUnit
 -> bounded EncodedAuPipe writer -> local Windows AU named pipe
 -> per-source VideoSession pipe adapter
 -> EncodedVideoAccessUnit -> VideoDecoder (FFmpeg H.264)
 -> owned DecodedVideoFrame (I420)
 -> latest-frame slot (capacity 1)
 -> OBS video tick -> obs_source_output_video -> libobs GPU conversion/render
 -> existing native camsure_camera source
```

The standalone C# receiver remains a required development process. The local
pipe carries complete compressed AUs, not RTP packets. It is not a desktop
preview or another physical transport. Capture/encoding/packetization and
Android discovery have not been rewritten. Each source has independent
session/decoder/thread/frame ownership and a unique pipe name; four real phones
are not implemented or tested by this slice.

## 3. Decoder Choice

**FFmpeg n7.1.1 / libavcodec 61.19.101**, from the existing hash-pinned OBS
obs-deps 2025-07-11 bundle. The development SDK has the headers/import libraries;
the plugin stage carries matching runtime DLLs rather than assuming installed
OBS has the same FFmpeg ABI. Installed OBS 32.2.2 and SDK OBS 31.1.1 are distinct.

The chosen path is software H.264 with two slice workers, low-delay flag and
strict corruption/error rejection. Frame threading is disabled to avoid its
additional frame pipeline delay. Output is 8-bit I420, which OBS accepts without
a CPU RGB conversion. SPS cropping is applied by the decoder. Tests verify
1280x720 and 1920x1080, including an encoder's padded 1088-line H.264 backing.

Media Foundation was evaluated and initially probed because it is native,
supports Annex-B/NV12 and offers DXVA. Its tested direct transform path decoded
pixels but returned regenerated output timestamps beginning at zero despite
nonzero sample PTS. That failed the exact source-PTS gate. The final runtime
uses FFmpeg, whose packet/frame PTS pass the strengthened regression. MF is used
only by the synthetic test encoder; no MF decoder implementation remains.

The API leaves room for a hardware decoder producing supported owned frames.
D3D11VA/GPU surface sharing is not implemented or measured. It should be chosen
after physical latency/load evidence, not claimed from API availability.

The pinned FFmpeg build enables GPL and version3. CamSure source remains
GPL-2.0-or-later; this combined binary package uses GPL-3.0-or-later. Required
transitive imports are shipped: avcodec-61, avutil-59, swresample-5,
libx264-164 and zlib. This does not implement audio or x264 encoding in CamSure.
Notices and GPLv3 text are included. Public distribution still requires exact
corresponding-source/build compliance; this is a local development package.

Official references: [FFmpeg send/receive API](https://ffmpeg.org/doxygen/trunk/group__lavc__encdec.html),
[Microsoft H.264 decoder](https://learn.microsoft.com/en-us/windows/win32/medfound/h-264-video-decoder),
[OBS source API](https://docs.obsproject.com/reference-sources).

## 4. Encoded AU Boundary

Actual native model in `plugins/obs-camsure/src/video-decoder.hpp`:

```cpp
enum class VideoCodec { H264 };
struct EncodedVideoAccessUnit {
    uint64_t session_id = 0;
    VideoCodec codec = VideoCodec::H264;
    std::vector<uint8_t> annex_b, codec_configuration;
    int64_t presentation_time_us = 0;
    bool keyframe = false, discontinuity = false;
    int64_t reconstructed_qpc = 0;
};
class VideoDecoder {
public:
    using Output = std::function<void(DecodedVideoFrame)>;
    virtual ~VideoDecoder() = default;
    virtual void submit(const EncodedVideoAccessUnit &, const Output &) = 0;
    virtual void reset() = 0;
};
```

The C# record carries the same fields using owned `byte[]` snapshots and
`VideoCodec.H264`. Arrays are immutable by contract after publication. Reassembly
is copied before reuse; configuration is independently owned. The RTP adapter
creates a transient session generation and does not export SSRC as decoder
state. This is not persistent device/account identity.

Local IPC v1 is H.264-only. The 48-byte little-endian header has magic CSAU,
version 1, AU/config byte lengths, key/discontinuity flags, reserved word,
64-bit session generation, signed exact PTS microseconds and local reconstruction
QPC. Configuration then Annex-B bytes follow. Boundaries/lengths/version are
validated before allocation. Remote pipe clients are rejected. Pipe names use
1–64 ASCII letters/digits/hyphens. QPC is same-PC instrumentation, not an Android
clock or a synchronization protocol.

The decoder has no sockets, RTP parsing, discovery, USB or receiver CLI code.
The session's pipe adapter translates the local envelope into this model.

## 5. Decoded Frame Boundary

```cpp
enum class PixelFormat { I420 };
struct DecodedVideoFrame {
    uint32_t width = 0, height = 0, stride = 0;
    PixelFormat format = PixelFormat::I420;
    bool full_range = false, bt601 = false;
    int64_t presentation_time_us = 0;
    std::vector<uint8_t> pixels;
};
```

Storage is tight Y, U, V with Y stride `width`, U/V strides `width/2`. The frame
owns its pixels after FFmpeg releases/reuses its AVFrame. Width/height are
visible decoded dimensions, not diagnostic settings or coded padding.
Decoder range and BT.601/BT.709 matrix information reach OBS; unspecified matrix
defaults to BT.709 and unspecified range to limited. Advanced/HDR color is out
of scope. A planar copy removes decoder padding/stride and makes ownership
explicit. It is not a color conversion.

## 6. OBS Integration

`camsure_camera` now registers `OBS_SOURCE_ASYNC_VIDEO`. **Real camera video**
selects the per-source session. The **Local AU pipe** setting associates one
receiver process with that source; default `camsure-camera-1`. Actual dimensions
come from submitted frames. Old diagnostic settings remain for diagnostic mode.

The decoder worker replaces the latest owned frame. OBS's video tick takes
at most one frame, sets VIDEO_FORMAT_I420/three planes/strides/color parameters
and a converted timestamp, then calls `obs_source_output_video`. Audited libobs
copies the planes before returning. Temporary vectors do not outlive their
ownership. OBS performs the normal async YUV-to-GPU presentation path.

Custom graphics drawing was replaced because system-memory decoder output
naturally fits OBS async video. A custom GPU path would add conversion/upload
work and GPU lifetime management without GPU decoder surfaces. The diagnostic
pattern now also uses async RGBA output; its scaled background is cached and
its independent moving marker/settings remain. The installed package passes
the four-source GPU/lifecycle regression.

The source destructor cancels then joins the session worker before destroying
source-owned state. Overlapped pipe reads/connects are canceled and completed
before their buffers/events/handles are freed. The worker never owns a strong
OBS source reference that could prevent destruction and never touches graphics
objects. Source removal during idle connect and partial-header read is tested.

## 7. Buffering Policy

| Stage | Limit | Recovery/drop behavior |
|---|---|---|
| Existing Android sender | 24 AUs / 512 KiB / 500 ms; 2 MiB per AU | Existing flush and IDR recovery preserved |
| Existing RTP reassembly | One AU / 2 MiB / 500 ms | Invalid/incomplete AU discarded; discontinuity propagated |
| C# AU queue | Four AUs / 4 MiB including configuration / 100 ms | Flush, wait for configuration + IDR, mark discontinuity |
| Pipe writer | One in-flight AU; 100 ms write deadline | Cancel/close/reconnect; never block RTP receive thread |
| Local pipe server | 64 KiB OS pipe buffer; one reader | Length bounded, partial payload deadline 100 ms |
| Decoder input tracking | At most 16 PTS entries, not another AU queue | Reset if exceeded; output over 100 ms old discarded |
| Decoder output drain | At most 16 frames per submit | Error/reset if exceeded |
| Decoded handoff | Latest frame only, depth 0/1, 100 ms age | Replacement/stale drops counted |
| OBS | At most one submission per video tick; async unbuffered | Newest async frame preferred; libobs internal cache cap is 30 in audited SDK |

No application queue grows without a limit. OBS cache/render queue details are
internal; actual presentation count and internal frame drops are not claimed.
Frame dimensions and allocations are bounded to 8-bit 4:2:0 through 1080p.

## 8. Timing

The private RTP extension's exact signed encoder PTS is copied in microseconds.
No 90-kHz-to-microsecond reconstruction is substituted for it. Missing,
negative or nonmonotonic PTS cannot silently fabricate a frame timestamp.
`AVPacket.pts` and `pkt_timebase={1,1000000}` preserve it through decoding;
`AVFrame.pts` becomes `DecodedVideoFrame.presentation_time_us`. DTS is left
unknown rather than invented.

For each start/recovery epoch, the first accepted IDR anchors source PTS to
local OBS monotonic time:

```text
OBS timestamp ns = OBS anchor ns + (decoded source PTS us - IDR anchor PTS us) * 1000
```

Ordering/source intervals survive this offset/timebase mapping. A reset starts
a fresh mapping so a restarted phone clock does not cause a giant timestamp
jump. OBS unbuffered mode chooses newest video rather than accumulating a
timestamp-scheduled archival queue. Multi-device clock synchronization, audio
sync and absolute camera-to-PC one-way latency are not solved here.

Latency metrics are local reconstruction -> native submit (`AU-age-max`),
matched source PTS submit -> decoded output, decoded available -> video tick,
and OBS output call copy duration. FPS logs are cumulative per-session averages
including startup/pause/recovery time, not instantaneous camera FPS.

## 9. Recovery

SPS/PPS are cached only from complete validated reassembly. Configuration
changes, RTP loss/incomplete AU, session restart, decoder errors and queue
flushes trigger recovery. Dependent frames are discarded until complete
configuration plus IDR is available. Cached parameter sets are prepended on
keyframes. Corrupt decoded frames are rejected, not sent to OBS.

The pipe writer reconnects with a bounded retry delay and requires a new IDR.
After 1.5 seconds without a header the native session closes that connection,
clears output on the OBS tick and accepts another local client. Partial payload
reads have a 100 ms deadline. Stop/restart and one induced RTP packet loss pass
the synthetic integration test. There is no feedback/request-keyframe channel;
recovery waits for the existing sender's periodic keyframes.

## 10. Performance

These are **synthetic local-host** measurements, not the Samsung camera run:

| Measurement | Latest successful integration probe |
|---|---|
| Visible resolution | 1920x1080 I420 |
| Synthetic sender pacing | 30 source PTS frames/second |
| Complete reconstructed AUs | 208 |
| RTP packets | 2,975 / 215,425 bytes |
| Decoder input / decoded / OBS submitted | 160 / 160 / 160 |
| Actual frames polled by probe | 139; polling is not monitor display count |
| Bridge dropped | 47 (startup, connection/loss recovery; not decoder corruption) |
| Induced network gap / incomplete AUs | 1 / 2 |
| Malformed packets / PTS mismatches | 0 / 0 |
| Decoder errors / session resets | 0 / 5, including startup/reconnect/loss |
| AU queue high-water | 1 / 4; max dequeued age 16 ms |
| Reconstruction -> native submit maximum | 16.74 ms |
| Matched submit -> decoded maximum | 4.46 ms |
| Decoded -> OBS tick maximum | 29.58 ms |
| OBS output-copy maximum | 0.76 ms |
| Decoded handoff | depth at most 1; no recorded stale/replacement drops in this run |
| CPU / GPU use | Not sampled |
| Camera bitrate / thermal / battery | Not measured in this slice |
| Glass-to-glass | Not measured |

The report lines' session-average FPS include the deliberate stop, startup and
keyframe recovery; they must not be presented as sustained physical 1080p30
acceptance. Short host probes do not establish long-run CPU/memory capacity.
Previous Samsung RTP-only bitrate/counters remain historical evidence in
TESTING.md section 18, not new decoder/OBS performance measurements.

## 11. Stability Test

**Ten-minute real phone run: PASS (user-observed).** Samsung SM-X115 rear camera
0, hardware H.264 1920x1080@30, 600.161 seconds, 30.0036 encoded FPS, 17,997 AUs
and 525,111 packets delivered with zero receiver gaps/incomplete AUs. The user
reports acceptable delay and no freezes, and confirmed the short stream/source/
OBS recovery checks. No ADB device or agent-controlled desktop observation was
available; physical decoder FPS/memory metrics and calibrated latency remain
unmeasured. Details and evidence links are in TESTING.md section 19.

Automated passes: exact PTS and owned frame storage at 720p/1080p; reset/replay;
refusal of dependent frames after reset; receiver AU ownership/SPS/PPS/FU-A/
sequence-loss/session isolation; host RTP -> C# -> pipe -> FFmpeg -> libobs/D3D11;
induced loss; visible-source clear and resumed output after stop; ten removals
while waiting for a connection or reading a partial header; libobs shutdown;
four independent diagnostic source edit/duplicate/delete/save/load/GPU regression.

Evidence:

- [Synthetic native OBS probe](evidence/2026-09-30-decode-obs-synthetic.log)
- [Paired synthetic RTP/bridge counters](evidence/2026-09-30-decode-obs-synthetic-receiver.log)
- [Installed diagnostic regression](evidence/2026-09-30-decode-obs-diagnostic-regression.log)

## 12. Visual Validation

Automated GPU readback of decoded synthetic output differs from blank, and
decoded dimensions/luma/PTS were checked through real libobs. The supplied OBS
screenshot shows the real camera image with live mode enabled. The user reports
acceptable perceptual delay and no freezes throughout the ten-minute test.
Color fidelity, exposure transitions, and fine-detail quality were not separately
qualified; neither calibrated latency nor physical decoder telemetry was supplied.

## 13. Remaining Limitations

- Physical baseline visual/ten-minute/stream restart/source re-add/OBS-close
  checks passed by user confirmation. Physical decoder telemetry, memory trend,
  calibrated latency and separate color/detail checks remain unmeasured.
- One receiver process feeds one source's local pipe; unique pipe/UDP endpoints
  avoid shared session state, but real multicamera support is not qualified.
- Software decode and owned planar copy; GPU decode/zero-copy unimplemented.
- 8-bit I420 H.264 only; HEVC/HDR/interlaced/portrait advanced modes unqualified.
- FPS telemetry includes deliberate idle periods; OBS presentation timing and
  internal drop counters are not exposed through this adapter.
- Parameter sets/IDR are required for recovery; no RTCP/retransmission/keyframe
  request/adaptive buffering has been added.
- Existing Q-001/Q-007 LAN offline/firewall/reconnect qualification remains
  separate from the synthetic pipe recovery tests.
- Debug diagnostics now involve async RGBA copies; their earlier custom-draw
  resource profile is not a production camera performance baseline.

## 14. USB Readiness Verdict

```text
Can USB now be implemented as another transport feeding the same decoder?
YES
```

A future USB receiver can produce the same complete owned H.264 AU/config/PTS/
session/discontinuity model and serialize the existing local AU envelope.
Neither `VideoDecoder` nor OBS frame integration needs RTP, UDP, SSRC, discovery
or USB changes. A future in-process adapter can also call the native decoder
API; the present OBS session adapter uses the local pipe. USB reception,
permissions and production device identity are not implemented.

## 15. Files Changed

- `tools/windows-rtp-receiver/Program.cs`: complete AU publication, recovery,
  config cache, CLI bridge and test entry points.
- `EncodedVideoAccessUnit.cs`, `EncodedAuPipe.cs`: owned compressed boundary and
  bounded local bridge.
- `ReceiverSelfTest.cs`, `ReplayTest.cs`: reconstruction/ownership/recovery tests
  and synthetic paced RTP replay with induced loss.
- `plugins/obs-camsure/src/video-decoder.hpp`, `video-decoder.cpp`: neutral
  models/interface and FFmpeg implementation.
- `video-session.hpp`, `video-session.cpp`: per-source worker, safe IPC teardown,
  latest-frame slot, timestamps, telemetry and OBS output.
- `plugin-main.cpp`: live/diagnostic modes on stable source ID, async output and
  actual decoded dimensions.
- `CMakeLists.txt`: FFmpeg targets, runtime DLL staging, decoder test and notices.
- `tests/decoder.cpp`, `tests/lifecycle.cpp`, `tests/run-video-probe.ps1`:
  exact timing/format, real libobs GPU, recovery/shutdown and repeatable runner.
- `data/licenses/README.md`, `data/licenses/FFmpeg/COPYING.GPLv3`: runtime notices.
- Root README; plugin/receiver READMEs; docs README, ARCHITECTURE, DECISIONS,
  OPEN_QUESTIONS, PROGRESS, ROADMAP, TESTING; this report and three evidence logs.

No Android capture/encoder/sender source changed. No USB/audio/control feature,
Git commit, staging of repository files or push was performed. The checkout's
pre-existing files were untracked; no clean Git baseline was assumed.

The staged plugin was installed under the existing ProgramData plugin folder
with OBS closed, retaining a prior-package backup in the ignored build folder.
Installed/staged DLL SHA256:
`5939C9DEE0020E681117422632D372BA0F334ABDB3041A3E08F1062D82FA814E`.
OBS profiles/scenes and OBS's own FFmpeg libraries were not changed.

## 16. Next Recommended Slice

**CamSure USB Transport Foundation** is the next candidate, using the existing
transport-neutral AU/decoder/OBS boundaries. Implementation needs a separately
authorized bounded slice. Physical baseline acceptance is now user-confirmed;
retain the measurement and broader LAN qualification follow-ups above. Audio
and multicamera qualification remain later work.
