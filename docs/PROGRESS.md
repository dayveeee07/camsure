# PROGRESS.md

This file tracks verified implementation status.

## 2026-10-01: Xiaomi wireless band comparison checkpoint

User identifies phase4-lan-20261001-072330 as 5 GHz and 072503 as 2.4 GHz.
Both actually encode 1920x1080 at approximately 30 FPS with zero capture
failures, estimated frame drops or sender queue/send failures. The 5 GHz run
lasts 318.717 s, sends all 9557 frames at 7.895 Mbps, and is user-observed
smooth in OBS. The 2.4 GHz run lasts 57.763 s, sends all 1725 frames at
8.098 Mbps, but the user observes OBS drops. No matching receiver counters
were supplied, so network loss versus downstream timing is unresolved.
The 2.4 GHz 720p follow-up, phase4-lan-20261001-073121, lasts 47.996 s:
actual 1280x720 at 30.008 FPS, 3.608 Mbps, all 1432 frames sent and zero
capture/encoder estimates, queue drops or send failures. User says it seems
fine in OBS. This is short single-device visual evidence, not multi-phone
or service-duration qualification. Current operator preference is 1080p
USB/5 GHz and 720p on this 2.4 GHz setup; defaults remain unchanged.
Next proposed slice: preview-first UI/settings and simpler connection setup.
That UI and continuous service mode have not been implemented in this checkpoint.

## 2026-10-01: Xiaomi 1080p USB ten-minute sender/receiver baseline

Report camsure-usb-network-20261001-071629.json completed 600.1087 s,
SSRC 467604331. Actual output 1920×1080/full crop, approximately 30.006 FPS,
17999 encoded/sent AUs and 517565 packets. Receiver totals match exactly:
17999 complete AUs and 517565 packets, zero incomplete/sequence gaps,
stale/oversized AUs or PTS-map mismatches, no SSRC restart. Phone zero capture
failures/estimated frame drops, queue/oversized/stale/recovery drops and send
failures. Bridge 17969 written/29 dropped/one connection-error, including
startup; presentation replacements previously recorded remain a separate
nonzero counter. User earlier reports smooth five-minute movement testing;
full-run visible-result confirmation is separate from matched transport proof.
Last sampled ISO 269 and exposure ~9.997 ms, not a full exposure history.
USB baseline is now demonstrated for this Xiaomi/PC at 1080p for ten minutes.
Separate 1080p LAN run, other phones, long service/battery and visual quality
qualification remain open. Metadata rejection does not invalidate this
specific runtime proof or justify unconditional overrides on other devices.

## 2026-10-01: Early real 1080p USB/OBS movement result

User reports five minutes without visible drops. Current saved receiver log
at t=4:13, SSRC 467604331: 215346 packets, 7483 complete AUs, zero sequence
gaps/incomplete/stale/oversized AUs, bridge written 7453, drops 29 (startup
recovery included), one connection/error, max bridge age 18 ms. OBS
2026-10-01 07-04-10.txt at 07:10:34 confirms 1920×1080, 7465 inputs/decoded,
zero AU drops/stale frames/decode errors, 1110 decoded-frame replacements,
three cumulative resets. Replacements mean not every decoded frame is
presented; the user's no-stutter observation is distinct from zero drops
at all stages. Cumulative FPS includes pre-stream idle. Full ten-minute
sender/receiver correlation and separate LAN qualification remain pending.

## 2026-10-01: Xiaomi exact 1080p runtime proof despite metadata rejection

Report camsure-phase3-runtime-20261001-070257.json completed 300.173 s.
Rear logical route 0 and c2.mtk.avc.encoder configure/capture session succeed.
Actual output 1920×1080 with full crop 0,0–1919,1079, without padding/scaling.
8985 encoded frames, approximately 29.993 FPS, 7.868 Mbps measured, zero
capture failures and five timestamp-gap-based estimated capture/encode drops.
This contradicts any claim that advertised 16×16 alignment makes runtime
1080p impossible on this device. exactDirect1080pSupported=false is still the
metadata result, not the runtime verdict. Do not generalize bypass to all
devices or mark wired/wireless delivery qualified by this capture-only run.
Last sampled ISO 4291, exposure 29.992032 ms; grain needs separate qualification.
Next: same explicit 1080p trial over USB/OBS, then LAN independently. Retain
720p default until delivery/visible quality pass; no permanent capability
override for other phones is established.

## 2026-10-01: Explicit exact-1080p configuration trial

User authorized investigating smooth 1080p after continued 4K performance
failures. Added opt-in exact 1920×1080@30 configuration trial, restricted to
camera-advertised MediaCodec output size/FPS and hardware H.264 encoders.
This trial permits attempting actual encoder configuration despite a false
areSizeAndRateSupported result. Exact encoder metadata support remains false
in plan/runtime reporting; actual configuration/session/output fields establish
runtime results independently. No padding/scaling/crop workaround implemented.
Trial overrides high-resolution selection, never silently falls back to 720p,
and is disabled during active runs. Existing defaults remain unchanged.
The trial does not guarantee configure success or smooth 1080p. If rejected,
preserve error JSON and investigate a correctly specified aligned/GPU path.
Physical check: install APK, prepare rear route, check 1080p configuration
trial, first short capture-only run and export JSON. If successful with actual
visible 1920×1080, qualify the same mode over USB/OBS.

## 2026-10-01: Paired 4K/720p sender and exposure reports

4K report camsure-usb-network-20261001-061218.json, SSRC 4171557510:
112.064 s, 3353 encoded/sent AUs, 30.005 FPS, 37.721 Mbps; last sampled
ISO 1263, exposure 19.994688 ms, noise/edge modes FAST/FAST (1/1).
720p report camsure-usb-network-20261001-061607.json, SSRC 808088820:
84.181 s, 2517 encoded/sent AUs, 30.004 FPS, 3.619 Mbps; last sampled
ISO 932, exposure 9.997344 ms, noise/edge modes FAST/FAST (1/1).
Both route 0, zero oversized/overflow/waiting/stale/in-flight sender drops.

This confirms sender starvation is resolved in these runs. Exposure differs,
so the comparison does not isolate resolution or denoising: both use FAST,
but identical mode names do not guarantee identical OEM processing. Single
last-sampled values cannot establish exposure throughout the runs. User
observes clean/smooth 720p versus noisy/stuttering 4K; retain both observations.
Next noise investigation requires exposure history and controlled same scene
comparison (including optional stock-camera reference), without forcing an
ISO reduction that could underexpose. Performance investigation remains
GPU readback/presentation audit; no smooth 4K acceptance.

## 2026-10-01: Real stage-timing comparison — 4K still fails, 720p smooth

User reports frequent drops and grain at 4K, then cleaner video with no visible
drops after selecting lower resolution. OBS log 2026-10-01 06-09-09.txt confirms
the lower mode is 1280×720/software. By 06:13:12 AU drops/resets remain
unchanged at 1790/77 while decoded/submitted frames advance.
4K GPU-transfer cumulative average is 21.464 ms and maximum 92.284 ms.
Max codec API call is 71.273 ms; max submit-to-decoded 101.66 ms and AU age
165.55 ms. Maxima across operations are not additive and include scheduling;
transfer includes waiting for GPU completion. Switching to software 720p
does not reset these historical maxima. Transfer/readback is a measured
significant contributor; direct GPU/native NV12 presentation needs an audit
before another architecture change. Grain cause remains unconfirmed pending
paired scene/exposure reports. Preserve 720p as the qualified fallback; no
smooth or clean 4K qualification established by this trial.

## 2026-10-01: 4K stage timings and D3D11 device reuse trial

Added decoder-thread cumulative count/average/maximum measurements for GPU
frame transfer, packed pixel allocation/copy/NV12-to-I420 conversion, and
individual FFmpeg send/receive calls. OBS logs them once per second alongside
existing AU age/presentation/bridge timings. GPU transfer includes waiting
for GPU completion; codec-call average is per API call, not per frame. These
measurements cannot be equated to end-to-end latency or device-only work.

Inspection showed decoder resets recreate D3D11 devices. One device reference
is now retained per decoder session across codec resets, released on decoder
destruction or selected hardware failure. Codec/frame pools are still freed
at reset, and software fallback, source independence and queue limits remain.
This removes repeated device creation but is not a demonstrated lag fix.

Native build, hardware/software decoder tests at 720p/1080p/4K, and the UHD
RTP/native OBS lifecycle probe pass. First instrumented synthetic run: transfer
average 9.716 ms, pixel conversion 5.051 ms, codec-call maximum 76.818 ms.
Device-reuse run: transfer average 15.656 ms (max 35.999), conversion 4.850 ms,
codec-call max 59.353 ms, 102 distinct frames presented. Runs are uncontrolled;
different results do not establish improved sustained throughput. Physical
4K correlation remains required. Camera image-processing requests unchanged.
Next: short real 4K run for stage timing, then matched same-subject 720p/4K
runtime JSONs for exposure/noise comparison; preserve lighting/framing and
capture original scene rather than a monitor feedback loop.

## 2026-10-01: Real hardware-decoded 4K trial — improved, still unqualified

Report camsure-usb-network-20261001-060204.json, SSRC 361553449, 101.145 s.
3026 encoded and sent AUs, 410596 packets; receiver matches both totals exactly,
zero gaps/incomplete AUs and zero sender drops/failures. OBS confirms D3D11VA
and 3840×2160. Receiver bridge written=2889, dropped=136, max depth four,
age 111 ms, reconstruction-to-write 151.384 ms. OBS cumulative at 06:02:01:
2880 input, 2740 decoded, 141 AU drops, 353 replaced frames, 30 resets,
zero decode errors; max decode 101.25 ms. These maxima/cumulative counters
do not establish average end-to-end FPS or latency.

User still sees drops and grain even in a well-lit area, and observes cleaner
720p. Last sampled camera ISO 483, exposure 9997344 ns (~10 ms), noise
reduction FAST (1), edge FAST (1). These are one sampled capture result,
not complete exposure history. Noise reduction is not disabled. Grain cause
remains unconfirmed; stock-processing differences and noise visibility at
different resolutions must not be presented as proved. Next investigate
GPU-to-CPU transfer/I420 conversion/pipe backpressure timing, and controlled
same-scene 720p/4K exposure/processing comparisons before changing denoise.

## 2026-10-01: 4K hardware-decode and camera-processing diagnostics

Explicitly authorized following decoder-bound 4K trial. Shared FFmpeg decoder
prefers D3D11VA for dimensions above 1080p when offered by the decoder/device;
smaller modes retain software decoding. Hardware initialization failure
selects software; a failure after hardware selection disables hardware for
that decoder session, resets it and recovers in software at a subsequent
keyframe. This is not a seamless same-frame retry. Optional process environment
CAMSURE_FORCE_SOFTWARE bypasses hardware for diagnostic testing.
GPU frames are transferred to CPU NV12 and converted into the existing owned
I420 frame; this is not zero-copy. Dimensions/max_pixels, one latest decoded
slot, AU/queue bounds and 100 ms freshness policies are retained. OBS records
the selected backend per source when it changes.

Native build passed. 720p/1080p software and 4K D3D11VA decoder tests passed,
including exact PTS, reset/replay, distinct U/V samples and owned I420. Forced
software tests passed at all sizes, including the final distinct-chroma update.
The final colored 4K native OBS/GPU integration passed with 132 distinct frames,
loss/restart/clear-output, idle/partial teardown x10 and shutdown. Synthetic
tests do not establish real-scene 4K30 throughput or latency. Runtime GPU
failure and unavailable-device paths still need qualification.

Android now exports latest sampled capture-result ISO, exposure time, noise
reduction mode and edge mode (sampled every 30 frames, null if unreported).
Capture/processing requests are unchanged. Android build, six unit tests and
lint passed. Next: install plugin with OBS fully exited, install APK and run
real 4K USB against a well-lit subject, exporting sender JSON and correlated
receiver/OBS logs. Hardware throughput and grain diagnosis remain pending.

Installed after the user exited OBS; installed/staged DLL hashes match:
BC238199C1C4E5E3AE1A7559EC6118EE58787E5203E69F52597092943FFD2096.
Previous DLL retained at build_vs2026/pre-hardware-20261001-055748.dll.
APK SHA256: EF87B46C7CD3F3373D4D38B9698C9AFF93044D7F37F6F36B3748107E7E6F8142.

## 2026-10-01: Resolution-aware sender size/pacing correction

Following SSRC 577859424 sender starvation, user authorized the next fix.
For selected dimensions above 1920×1080, SenderPolicy now permits a 2 MiB
individual compressed AU and 4 MiB total queued bytes. Smaller modes retain
512 KiB individual/total bounds. USB retains four queued AUs and 100 ms age;
LAN retains 24 AUs/500 ms. USB higher-resolution pacing uses 32 datagrams
per minimum 1 ms batch interval versus eight for baseline modes. No idle
credit accumulates. Larger total bytes do not relax stale-frame deadlines.
The compressed assembly/receiver/decoder AU cap remains 2 MiB. Codec config
and Annex B reconstruction can add bytes; receiver oversize telemetry remains
required at the cap boundary. Throughput and packet-loss improvement need
physical proof. Native decoder is unchanged in this step and remains a known
performance constraint.

Added tests for accepting 768 KiB keyframes and dependent frames, rejecting
over-2-MiB AUs/recovery gating, 100 ms expiry, baseline policy preservation,
and 32-packet pacing. Next physical trial: same OBS plugin/readiness receiver
and 256 KiB buffer, latest APK/high-resolution USB, short movement run and
fresh JSON. Compare encoded/sent counts and oversized/waiting-keyframe drops
before assessing decoder throughput. Do not call smooth 4K fixed on build proof.

## 2026-10-01: Matched 4K sender report reveals size-limit starvation

Report camsure-usb-network-20261001-054405.json, SSRC 577859424, 94.1955 s.
Phone encodes 2817 AUs at approximately 30.0045 FPS, measured 37.5137 Mbps,
zero estimated capture/encode drops or capture failures. Only 300 AUs and
38951 packets are sent: senderQueue droppedOversized=84 and
droppedWhileWaitingForKeyFrame=2433 (300+84+2433=2817). Queue byte cap
524288, high-water bytes 511650; no overflow/stale/in-flight drops or send
failures. Temperature 41.7→42.8 C, thermal none.

This corrects the prior incomplete diagnosis: the sender cap rejects large
4K AUs, causing sustained keyframe-recovery starvation, in addition to
measured decoder lag. Zero receiver packet gaps apply only to packets sent.
Receiver log contains another prior SSRC and currently ends mid-run, so
cumulative receiver totals cannot be equated with this completed sender run.
Next bounded fix must address per-AU/total-byte limits and 4K packet pacing
together, with separate retained 720p defaults and the 100 ms freshness bound;
then qualify decoder throughput. Do not infer which exact oversized frames
were keyframes without per-AU evidence, or declare GPU decoding the sole fix.

## 2026-10-01: First real 4K USB/OBS trial — video present, performance failed

User reports severe lag. usb-4k-receiver.log at t=1:27 reports zero RTP gaps
and incomplete AUs; largest reconstructed AU 511535 bytes, age high-water
76 ms. Bridge has repeated drops/errors and reaches approximately 103.5 ms
reconstruction-to-write. Current OBS log 2026-10-01 05-37-11.txt confirms
3840×2160 decoded output. Around 05:42:52 it reports 93 input, 42 decoded,
51 AU drops, 10 resets, maximum submit-to-decoded 76.55 ms, AU age 105.11 ms.
Prior session max decode was 71.31 ms, with frames rejected after 100 ms.
These maxima do not represent average FPS; cumulative FPS includes idle time.

4K delivery and native display are demonstrated but smooth 4K30 is not.
Synthetic flat-image coverage did not establish real-scene decoding throughput.
Do not loosen freshness deadlines to hide backlog. Next investigate decoder
throughput (including hardware decode on the target PC) and USB keyframe
pacing/residence. A matching fresh phone JSON is needed to assess sender
queue drops/stale sends in this run. Retain accepted 720p service baseline.

## 2026-10-01: Shared 4K decode/native OBS trial — host passed

Explicitly authorized following Xiaomi 4K capture proof. Shared H.264 decoder
now accepts even 8-bit I420 dimensions through 3840×2160, with FFmpeg
max_pixels=3840×2160 guarding decoder allocation. One packed I420 4K frame
is 12441600 bytes; latest decoded-frame slot remains one, with bounded
in-flight decode/presentation ownership. Existing AU bounds (2 MiB), bridge
queue (4 AUs/4 MiB/100 ms), and freshness policies remain unchanged.
Removed temporary Android capture-only restriction; high-resolution mode
remains opt-in. Default selection and USB pacing/readiness remain unchanged.

Native build passed. Synthetic encoder/decoder tests pass at 720p, 1080p and
3840×2160, 35 encoded/70 decoded per size including reset/replay and exact
PTS. UHD and existing 1080p RTP-to-AU-pipe-to-OBS-I420/GPU probes each pass
with 136 distinct frames, induced loss, stop/clear/restart, idle/partial-read
removal x10 and shutdown. Fixtures are synthetic and low-complexity; this
does not qualify real phone bitrate, 4K30 throughput or end-to-end latency.
Android assembleDebug, unit tests and lint pass. Staged package installed
into the existing CamSure plugin directory while OBS was closed; prior DLL
saved at plugins/obs-camsure/build_vs2026/usb-baseline-backup/obs-camsure.dll.
Next: install latest APK, reopen OBS, use high-resolution trial with existing
USB readiness receiver and 256 KiB buffer, run a short movement test, export
runtime JSON and correlate the new receiver/OBS logs. Physical 4K pending.

## 2026-10-01: Xiaomi 14T 4K30 capture/encode proof

Supplied capability report camsure-capabilities-20261001-052951.json and runtime
report camsure-phase3-runtime-20261001-053120.json. Rear logical route 0,
3840×2160@30, c2.mtk.avc.encoder hardware H.264 configured successfully and
Camera2 capture session configured. Actual encoder output is 3840×2160 with
full visible crop 0,0–3839,2159. Run stopped after 15.7167 s, 455 encoded
frames, approximately 30.0276 FPS, zero estimated capture/encode drops and
capture failures. Requested VBR 32 Mbps, measured average 36.6603 Mbps.
Thermal status none; battery temperature 41.2 C at start/end.

Direct exact 1080p remains rejected by the reported 16×16 encoder alignment;
2160 is divisible by 16 and exact 4K30 is advertised and now demonstrated.
This proves short-run 4K Camera2/hardware encode access, not stock-camera
processing parity, sustained 4K, transport, decode or OBS display. Native
decoder still limits frames to 1080p. Next bounded slice: audit and extend
shared decoder frame bounds/buffering for 4K with synthetic coverage, then
explicitly qualify USB delivery at the measured bitrate; retain 720p fallback.

## 2026-10-01: Higher-resolution capability and capture trial

User explicitly authorized investigating Xiaomi 14T 1080p/4K. Audit found
two software limits: CameraEncoderExperimentDiscovery excluded sizes above
1920×1080 before encoder checks, and native video-decoder.cpp rejects decoded
dimensions above 1920×1080. The first does not establish phone incapability.
Added an opt-in high-resolution checkbox, expanding exact Camera2 + hardware
H.264 size/FPS planning through 3840×2160@30. Existing selection stays default.
Trial notes report camera output advertisement/minimum duration and each
hardware encoder's exact 1080p/4K30 support/alignment. Capability scan additionally
checks 4K30/60. The existing runtime experiment verifies session configuration
and actual encoder output dimensions. High-resolution streaming into the
existing OBS decoder is blocked with capture-only instructions.

Validation: Android assembleDebug, testDebugUnitTest and lintDebug pass.
No phone or 4K OBS result yet. This diagnostic does not implement a padded
1080p path, HEVC, HDR, or extend native decode. Next: user installs APK, scans
capabilities, prepares rear camera, enables high-resolution trial, examines
eligible modes and runs capture-only with USB unchecked/no receiver selected.
Export both capability and runtime JSON; if 4K is not eligible, report notes
and capture-only selected dimensions instead of claiming a 4K test.

## 2026-10-01: Xiaomi USB readiness trial — ten-minute video baseline passed

User confirms no visible drops for the completed 600.1009-second run in
camsure-usb-network-20261001-051704.json, SSRC 2359927604, 720p/30 FPS.
Android sent 17999 AUs and 241146 packets, with zero capture failures,
send failures, queue drops or in-flight stale drops. Subtract the receiver's
prior-SSRC baseline (1848 packets and 133 complete AUs) from final cumulative
242994 packets and 18132 complete AUs: exactly 241146 packets and 17999
complete AUs received. Zero gaps, incomplete, stale or oversized AUs. All 23
PTS-map mismatch counts already existed before this SSRC began; no additional
mismatches in the matched run. Bridge drop count remained 39 after startup.
Tested combination: paced APK SHA256
824CE1030AE5892203A2DF19D75999951D61521AD81D3E74444A04B25796F189,
usb-readiness receiver, explicit 256 KiB socket receive buffer.

This is one phone/PC ten-minute proof, not an isolated proof of the timeout
race hypothesis or two-hour/multiphone qualification. Battery fell 9% to 7%,
temperature 44.7 to 45 C, thermal status none. The experiment still stops at
ten minutes and requires a separate service-duration slice before two-hour
use. Receiver log terminates with Interrupted after media completion; stop
versus genuine link-loss classification requires follow-up.

## 2026-10-01: USB readiness-based receive trial

NIC capture had contiguous RTP sequences while receiver gap count increased.
To investigate loss in the Windows receive path, USB now waits for socket
readiness with Poll(25 ms), then uses a receive without SO_RCVTIMEO. This
avoids repeatedly timing out receive operations between 30 FPS bursts.
A timeout/cancellation race is a hypothesis, not a diagnosed platform defect.
Idle iterations still expire incomplete AUs at the existing 100 ms deadline;
link monitor socket closure and operator stop remain in place. LAN behavior
and selected buffer size are unchanged. Build and self-tests pass, including
idle readiness and 128 sequential loopback sends/receives. Artifact:
tools/windows-rtp-receiver/bin/usb-readiness. Physical result remains pending.

Early physical result: user reports two minutes without visible drops. At
receiver t=2:44, cumulative 62064 packets, 4584 complete AUs, zero sequence
gaps/incomplete/stale AUs. Bridge drops are 39 (including startup), with one
connection/error and two generations observed. PTS-map mismatches total 23
and require separate examination; nonmonotonic PTS remain zero. This is an
encouraging short-run result, not ten-minute or two-hour service qualification.

## 2026-10-01: First NIC ingress capture result

User reports no visible drops during the minute. Capture
usb-capture-20261001-050209.pcapng contains 24388 RTP packets for SSRC
2642161008 from 21:02:09.619782Z to 21:03:09.736135Z. Header parsing finds
zero sequence gaps, adjacent duplicates, or backwards sequences. Packet
Monitor conversion reports zero capture drops (not end-to-end delivery proof).
During the corresponding receiver interval (~t=7:48 to 8:49), receiver gaps
increase from 15 to 16, first reported at t=8:33. This points toward loss
between observed NIC ingress and receiver processing. Exact gap sequence
logging is needed to align the missing datagram directly; capture can affect
runtime timing. One visually clean minute does not establish stability.

## 2026-10-01: Windows ingress capture preparation

Added tools/windows-rtp-receiver/capture-usb.ps1 for a 60-second NIC capture
using installed Windows Packet Monitor, filtered by phone IP and UDP port.
Captures 160-byte packet headers to a bounded 128 MiB log and exports pcapng.
Preserves existing filters; any pre-existing filters can broaden capture.
Removes only its own filter and stops only a capture it successfully started.
PowerShell syntax checked. Packet Monitor requires Windows Administrator;
this session's status query returned Access denied. Capture is not yet run.
NIC ingress RTP sequences can be compared against the matched receiver log;
capture loss/duplicate observations must be considered before attribution.

## 2026-10-01: USB sender burst pacing trial — physical acceptance pending

The user reports 7–8 visible drops within one minute with the timing helper.
At receiver t=2:36 the log has 9 missing packets and 9 incomplete AUs,
maximum application service pause 2.538 ms, receive wait 39.405 ms, and
maximum observed 1 ms bucket 51600 bytes. These cumulative observations do
not isolate the kernel/driver or prove burst-induced loss.
Added USB-only sender pacing: at most eight datagrams (up to 9600 bytes) per
batch, with at least 1 ms between batch starts. Idle time accumulates no
burst credit, early wakeups are rechecked, and interruption propagates to
shutdown. Pacing executes on the sender worker, retaining the 100 ms AU
freshness checks, bounded sender queue, camera resolution/FPS and LAN path.
This is a bounded hypothesis test, not a confirmed fix. Android build/unit
tests/lint results and the physical follow-up must be recorded separately.

Validation: assembleDebug, testDebugUnitTest (four tests, zero failures/errors),
and lintDebug passed. APK SHA256:
824CE1030AE5892203A2DF19D75999951D61521AD81D3E74444A04B25796F189.
Physical follow-up: install this APK, retain the usb-timing helper and 256 KiB
receive-buffer trial, and export a fresh phone JSON after the movement run.

Physical result: report camsure-usb-network-20261001-045621.json, SSRC
4099601097, duration 101.854 s, 3048 encoded/sent AUs, 41217 successfully sent
packets. Receiver has 41209 packets, 3040 complete plus 8 incomplete AUs,
8 sequence gaps, no restart. Sender queue drops, send failures, in-flight stale
drops and capture failures are zero. Observed 1 ms receive bucket maximum
fell to 19200 bytes, but packet loss persists (approximately 4.7 packets/minute
versus 4.4/minute in the preceding matched 162.47 s buffer trial). The user
reports under five visible drops per minute. Different movement/run lengths
prevent a controlled comparison; no packet-loss improvement is established.
Next: paired packet capture at sender/Windows ingress, or Windows ingress
capture plus receive counters, to isolate loss before further tuning.

## 2026-10-01: Receiver timing diagnostics — implemented, host tested

Added constant-space receive timing counters: maximum service pause between
a successful receive and the next receive call, maximum blocking receive wait,
and maximum bytes observed in a one-millisecond bucket starting on receipt.
These are application observations, not kernel arrival timestamps or proof of
socket overflow. Timeout idle periods are excluded from service pauses.
Build and receiver self-tests, including deterministic service/burst/idle
accounting, pass. Artifact: tools/windows-rtp-receiver/bin/usb-timing.
Physical correlation remains pending. Resolution, FPS, buffer defaults,
freshness deadlines, sender behavior, and decoder behavior remain unchanged.

## 2026-10-01: USB receive burst-buffer trial — physical result pending

The refined single-phone run still stutters. At receiver t=4:24, SSRC
1581680764 reports 22 packet gaps, 22 incomplete AUs, 313 bridge drops,
no stream restart, and a largest reconstructed AU of 122348 bytes versus a
65536-byte effective Windows receive buffer. Burst overflow is a hypothesis;
receiver gaps cannot distinguish sender loss from OS loss.
Added explicit USB-only `--receive-buffer-kib` (64–256, default 64) for a
bounded 256 KiB comparison. LAN defaults, application AU deadline (100 ms),
and decoder/OBS behavior remain unchanged. Build and receiver self-tests pass.
Physical improvement and end-to-end latency remain unverified.
Trial artifact: tools/windows-rtp-receiver/bin/usb-buffer-trial.

Physical follow-up: Xiaomi SSRC 2001963800 ran 162.47 seconds, sending 4867
AUs and 65868 packets with zero sender queue drops, stale drops, or send
failures. After subtracting the preceding SSRC, Windows received 65856 packets,
4855 complete AUs and 12 incomplete AUs, with 12 missing packets. The user
reports slight improvement but two visible drops in under 30 seconds. The
buffer trial is not a fix; loss remains between socket send acceptance and
Windows receipt. See evidence/2026-10-01-usb-buffer-trial-analysis.md.

Do not use it as a wishlist.

---

## Current Status

**2026-09-30 newest slice:** USB Network Mode implementation and host coverage
are delivered; STATUS PARTIAL pending physical qualification deferred by user.
LAN remains the default and the native decoder/OBS path is unchanged. See
[USB report](USB_NETWORK_REPORT.md) and [physical setup/checklist](USB_NETWORK_TESTING.md).
The following accepted decode baseline remains valid historical LAN evidence.

**2026-09-30 current slice:** Transport-Neutral Decode → OBS is ACCEPTED WITH
LIMITATIONS on the Samsung one-camera baseline.
AU publication/IPC, FFmpeg H.264 decode, latest-frame handoff and native OBS
async I420 are implemented. Exact PTS, 720p/1080p synthetic decode and actual
libobs/D3D11 GPU output, induced loss, stream restart and worker teardown pass.
Installed plugin matches the staged DLL. The user supplied a real-camera OBS
screenshot, paired ten-minute sender/receiver evidence and confirmed acceptable
delay with no freezes; stream restart, source re-add and OBS reopen worked.
Physical decoder metrics, memory trend and calibrated latency remain unmeasured.
See [completion report](DECODE_OBS_REPORT.md) and TESTING.md section 19.

**Phase:** Phase 0 PASS; Phase 1 PASS (user-confirmed 2026-09-27); Phase 2
PASS WITH LIMITATIONS; Phase 3 direct Camera2-to-hardware-H.264 baselines PASS
WITH LIMITATIONS on Samsung and Xiaomi. Phase 4 fixed-address RTP delivery has
a user-supplied PASS baseline. Q-007's browse/answer and discovery-to-RTP path
are demonstrated on one LAN run; WAN-offline and reconnect acceptance remain
open, as does the Phase 4 exit gate.

**Earlier Phase 4 implementation summary (superseded for decoder/OBS by the
2026-09-30 entry):** The Windows diagnostic OBS source remains intact. The
Android app retains the Phase 2 profiler and opt-in Phase 3 experiment, with an
optional Phase 4 RTP sender and Android NSD receiver selection. The Windows
counter receiver advertises its media endpoint with DNS-SD. Seven user-supplied
Phase 3 reports cover the Samsung 1080p30 and Xiaomi 720p30
logical/physical-output routes. A user-supplied fixed-address Phase 4 run now
has paired sender/receiver evidence. A live Android browse/Windows answer and a
follow-up RTP run were reported after selecting the discovered receiver; the
Windows log shows complete access units and no observed loss during the sample.
Decode and OBS ingest remain unevidenced.

**Primary next slice:** Physical USB Network Mode qualification using the
prepared build and USB_NETWORK_TESTING.md; implementation is now authorized
and delivered. Existing Phase 4 follow-ups remain separate:
[Phase 4 Q-007 discovery procedure](TESTING.md#18-phase-4--rtph264-over-lan-and-q-007-local-discovery)
on the Samsung SM-X115 with WAN disconnected; capture firewall, receiver
restart, and network reconnect behavior. Separately perform the Q-001 protocol
trade-off and induced-loss/recovery trial. Keep Xiaomi aligned 1080p and
independent camera opens as separate follow-ups; they do not block the tested
baseline modes.

## 2026-09-30 — Transport-Neutral H.264 Decode → OBS

**Acceptance update (after the implementation snapshot below):** ACCEPTED WITH
LIMITATIONS. Samsung SM-X115 rear 1080p30 real video is visible in OBS. The user
supplied a screenshot and a completed 600.161-second run: 30.0036 encoded FPS,
17,997 AUs, 525,111 RTP packets, zero capture failures, sender drops/send failures,
receiver gaps/incomplete AUs or timestamp mismatches. Matching SSRC 4011457692;
receiver cumulative totals include an earlier 301-AU/8,822-packet short stream.
Bridge maximum queue age 34 ms/depth 3; reconstruction-to-write maximum 46.284 ms,
not glass-to-glass latency. Startup/restart bridge drops are separate from network
loss. User reports acceptable delay and no freezes, and confirmed stop/start,
source removal/re-add and normal OBS close/reopen all worked. See TESTING section
19 for evidence. Physical OBS decoder metrics, memory trend, calibrated latency,
separate color/detail inspection and induced LAN-loss trial remain unqualified.
Next candidate: USB Transport Foundation; no USB implementation authorized here.

The remaining entry preserves the earlier implementation-time snapshot.

**Status:** PARTIAL. Implemented, built, host-tested and installed. The user
explicitly deferred physical validation; no real-camera OBS or ten-minute
acceptance is claimed.

**Changes:** Complete owned AU publication from existing C# RTP reassembly;
SPS/PPS/session/discontinuity metadata; bounded local named-pipe AU adapter;
native per-source software FFmpeg H.264 decoder; exact source packet/frame PTS;
I420 output with actual crop/dimensions/color parameters; single latest decoded
frame; OBS async unbuffered source; preserved diagnostic mode/settings; safe
worker/pipe shutdown and local-stage timing/drop/depth/error telemetry. Android
capture, encoder and transport behavior remain unchanged.

**Choice/evidence:** Existing OBS pinned dependencies contain FFmpeg n7.1.1.
The MF decoder probe returned regenerated PTS beginning at zero and failed the
exact timestamp gate. FFmpeg passes exact packet/frame PTS and avoids guessing
output timestamps. D-021 records the choice and combined GPLv3 runtime package.

**Tests:** .NET 10 build has zero warnings/errors. Native x64 RelWithDebInfo
build/stage pass with warnings as errors; SDK OBS 31.1.1, runtime OBS 32.2.2.
Receiver self-test passes owned-buffer/SPS/PPS/FU-A/loss/restart isolation.
Synthetic encoder/decoder tests each produce 35 frames and 70 decoded outputs
including reset/replay at 720p and 1080p, with exact ordered PTS. Real libobs
GPU readback differs from blank. Paired host RTP/pipe/decoder/OBS test passes
induced loss, output clear, resumed video and ten source removals including
partial-header reads; diagnostic four-source GPU/save-load regression passes
against the installed package. No actual OBS frontend/phone was operated.

**Measurements (synthetic only):** Receiver 2,975 packets, 208 complete / 2
incomplete AUs, one deliberately induced gap, no malformed/PTS mismatches.
Native decoder input/decoded/OBS submitted = 160/160/160; probe polled 139 unique
frames, not monitor presentation count. Bridge drops 47 during startup/loss/
connection recovery; native decoder errors zero, resets five including startup.
Maximum AU queue depth one of four, age 16 ms; reconstruction→submit 16.74 ms,
submit→decoded 4.46 ms, decoded→video tick 29.58 ms, OBS copy 0.76 ms. FPS logs
include deliberate idle/recovery. CPU/GPU/glass-to-glass not measured.

**Evidence:** The three 2026-09-30 decode-obs logs under `docs/evidence/` and
[completion report](DECODE_OBS_REPORT.md). Real ten-minute run remains open.

**Installation:** Existing ProgramData plugin package updated with OBS closed,
prior files backed up under ignored `build_vs2026/installed-backup`. DLL SHA256
`5939C9DEE0020E681117422632D372BA0F334ABDB3041A3E08F1062D82FA814E` matches
stage. Matching FFmpeg/transitive DLLs/notices included; OBS scene/profile files
and OBS's own libraries untouched. Receiver was started on 192.168.0.146:5004
for the offered physical test; the user chose to test later.

**Regressions/limits:** Diagnostic native regression passes after async timing
adjustment. Physical smoothness/color/corruption/latency and ten-minute memory/
shutdown/reconnect tests are not proven. Software decode/owned I420 copies,
one development receiver/source mapping and no hardware acceleration. Q-001/
Q-007 physical offline/loss/reconnect qualification remains separately open.
No USB/audio/control/multicamera implementation or Git commit/push.

**Next:** Finish the current physical one-camera acceptance gate; only after
that passes evaluate CamSure USB Transport Foundation as the next candidate.

---

## Confirmed Product Requirements

- Native OBS integration
- Android companion app
- Local-network operation
- Internet not required
- Up to 4 simultaneous phone cameras
- Independent OBS source instances
- Low latency
- High image quality
- Hardware encoding on Android
- Remote camera controls in OBS
- Capability-driven camera support
- Future USB transport
- Stable device identity/friendly names
- Live-production focus

---

## Known Architectural Risks

### Android Camera Variability

Different phones may expose different:

- lenses
- camera controls
- frame rates
- stabilization
- HDR modes
- codec capabilities

Mitigation:

Capability profiling is an early phase.

### Hidden Buffering

Latency can accumulate in:

- camera capture
- encoder
- transport
- network receive
- decode
- frame queue
- OBS render

Mitigation:

Timestamp and instrument every stage.

### Four-Camera Decode Load

Four 1080p60 streams may stress software decode depending on codec, bitrate, CPU, and OBS workload.

Mitigation:

Prove function first, then evaluate hardware decoding using measurements.

### OEM Feature Restrictions

Third-party Android camera apps may not receive the exact same computational-video pipeline as the stock camera app.

Mitigation:

Expose real available capabilities and avoid unsupported parity claims.

### Wi-Fi Reliability

Raw bandwidth is not expected to be the primary problem.

More important:

- packet loss
- interference
- jitter
- congestion
- access-point quality

Mitigation:

OBS PC should preferably use Ethernet; transport must have bounded queues and observable packet loss.

---

## Progress Entry Template

Copy this section for every meaningful slice.

### YYYY-MM-DD — Slice Name

**Status:** NOT STARTED / IN PROGRESS / PASS / PASS WITH LIMITATIONS / BLOCKED / REJECTED

**Goal**

Describe exactly what the slice was intended to prove.

**Changes**

- ...

**Tests**

- ...

**Measurements**

```text
Resolution:
FPS:
Bitrate:
Capture -> Encode:
Transport:
Decode:
OBS:
Estimated glass-to-glass:
Packet loss:
Dropped frames:
CPU:
GPU:
RAM:
```

**Result**

...

**Regressions**

- None / ...

**Open Issues**

- ...

**Decision Impact**

- No decision changes
- or reference updated decision IDs

**Next Recommended Slice**

...

---

## 2026-09-26 — Phase 0 / Phase 1 Implementation Report

Historical report at build time; installation and primary acceptance status are superseded by the 2026-09-27 entry below.

**Status:** BLOCKED on manual frontend acceptance; implementation/build/native probe pass.
Phase 0 foundation is established. Phase 1 is explicitly NOT COMPLETE.

**Goal**

Create a Windows-native CamSure OBS source with four independent diagnostic
instances and safe ownership before any Android/streaming work.

**Changes**

- Read all eight existing handrails under docs before implementation.
- Resolved the old undecided product-name decision to CamSure (D-017).
- Initialized local Git main without committing, staging, or adding a remote.
- Added apps/android and shared placeholders, root entry documents and ignore rules.
- Added plugins/obs-camsure using the official template CMake foundation.
- Added native CamSure Camera type, generated diagnostic texture, instance ID,
  friendly name, resolution, 30/60 FPS marker cadence and pattern properties.
- Each source owns settings, texture, clock and mutex; no worker threads.
- Added real libobs/D3D11 lifecycle probe and staged installable package.
- Updated architecture, decisions, test gate and unresolved frontend acceptance.
- No Android, capture, networking, decoder, audio, USB or control implementation.

**Build environment**

Windows 11 build 26200; Visual Studio Community 2026 18.9.1; MSVC 19.51.36256;
Windows SDK 10.0.26100.0; CMake 4.3.2; x64 RelWithDebInfo.
Official template commit: 3e7d7ac3b5342cd7d9b88890b9c70b472d1520fc.
SDK baseline: OBS 31.1.1, obs-deps/Qt bundle 2025-07-11, template SHA256 pins.
Installed runtime used by probe: OBS 32.2.2. Ryzen 5 3600, RTX 3060.
Configure, build with warnings treated as errors, and stage install passed.
VS 2022 preset supplied but not independently tested on this machine.

**Manual tests**

NOT RUN. Native desktop UI controls are unavailable in this agent session.
No installation into the user's OBS or change to existing collections was made.
The exact operator procedure is TESTING.md section 15.

**Four-source test result**

Automated PASS against installed libobs and D3D11. Four source objects coexist
and produce distinct GPU readbacks. CAM 2 name changes leave CAM 1/3/4 unchanged;
CAM 3 resolution/FPS/pattern changes leave CAM 1/2/4 unchanged.
Deleting CAM 2 leaves CAM 1/3/4 rendering correctly; recreation succeeds.
Duplicate/edit isolation, source rename, enable cycle, save/load of dimensions,
friendly names, FPS/pattern, and shutdown passed. All 10 created diagnostic
instances have matching destruction entries in the saved probe log.

Evidence: evidence/2026-09-26-lifecycle.log.
Manual four-source frontend/restart result: PENDING, not inferred from the probe.
The probe does not validate scene switching, disk persistence, animation timing,
human-readable visual layout, or long-running memory behavior.

**Observed issues**

- Sandbox denied CMake-generated writes/downloads; approved command execution
  completed configuration, build and staging.
- Probe initially supplied a bare D3D11 module name; an absolute installed DLL
  path fixed loading.
- Runtime probe logs HAGS/GPU-priority notices and failure to load the optional
  libobs-winrt module. D3D11 diagnostics pass despite those runtime notices.
- Template SDK configuration emitted Detours-version and empty virtual-camera
  GUID warnings. CamSure neither implements a virtual webcam nor uses those features.
- Minimal diagnostic font uses uppercase ASCII, substitutes unsupported glyphs,
  and clips the displayed name at 32 characters.
- No leak soak or effective-FPS measurement has been performed.

**Regressions**

No pre-existing implementation existed. No cross-instance regression was observed
in the native probe. Frontend regressions remain unassessed.

**Architecture/decision impacts**

D-017 fixes branding; D-018 records the diagnostic source boundary; D-019 records
the official scaffold/SDK pins and GPL-2.0-or-later plugin licensing direction.
Future Android and decoder licensing remain open. The future session architecture
is preserved without speculative session/transport/decoder classes.

**Next recommended slice**

Install the staged plugin and complete the Phase 1 frontend four-source lifecycle,
scene-switching and save/restart gate. Only after that passes proceed to Phase 2,
Android Camera Capability Profiler.

## 2026-09-27 — Installation and Primary Manual Acceptance

**Status:** PASS WITH LIMITATIONS. Phase 0 and the requested Phase 1 primary
four-source acceptance test pass. Extended frontend checklist items remain
separately unconfirmed; this is not a claim that every TESTING.md step was run.

**Goal:** Confirm independent diagnostic sources in the actual OBS frontend,
including persistence across an OBS restart.

**Changes:** Corrected installation from the undiscovered nested folder under
Program Files/obs-studio/obs-plugins to ProgramData/obs-studio/plugins/obs-camsure.
Verified installed DLL SHA256 matches the tested artifact in
evidence/2026-09-26-build.md. No runtime code changes or rebuild were needed.

**Build environment:** Existing 0.1.0 x64 RelWithDebInfo artifact; OBS 32.2.2,
Windows 11, Ryzen 5 3600 / RTX 3060. Build evidence remains unchanged.

**Manual tests / four-source result:** User first confirmed "now working" after
installation correction, then reported "all passed" in response to this checklist:

- Create four separate CamSure Camera sources with Create new.
- Change CAM 2's Friendly Name; the other sources remain unchanged.
- Change CAM 3's resolution; the other sources remain unchanged.
- Delete and recreate CAM 2; the other sources continue.
- Save the scene collection, restart OBS, and verify all settings restore.

This is user-reported frontend evidence, distinct from the earlier automated
libobs/D3D11 test. No post-test log or screenshot was supplied or inferred.

**Observed issues:** Plugin discovery issue resolved by correct installation.
Extended frontend duplication, hide/show, rename, scene-switching and memory-soak
results were not separately reported. Related API-level tests remain recorded
in the native probe; they do not imply frontend coverage or leak-free operation.

**Regressions:** None reported in the primary manual test.

**Architecture/decision impacts:** No changes. Independent ownership and OBS
settings persistence are supported by both native and user-reported evidence.

**Next recommended slice:** Close the remaining extended frontend checklist,
then Phase 2 — Android Camera Capability Profiler. No Phase 2 work is authorized
or started by this acceptance update.

## 2026-09-27 — Phase 1 Final Acceptance

**Status:** PASS. Supersedes the earlier partial manual-acceptance status above.

**Goal:** Close the remaining diagnostic-source lifecycle validation.

**Changes:** Documentation only; no runtime changes or rebuild.

**Build environment:** Same tested and installed CamSure 0.1.0 artifact and OBS
32.2.2 environment recorded above.

**Manual tests:** After the primary test passed, the user confirmed "already done"
in response to the remaining checks: duplicate/edit isolation, rename, hide/show,
scene switching, and a 10-minute four-source run with repeated deletion/recreation
while watching for crashes or steadily increasing memory. Recorded as user-confirmed
acceptance, not an agent-observed test or an instrumented memory measurement.

**Four-source test result:** PASS, combining the native libobs/D3D11 probe with
the user's primary and extended frontend validation.

**Observed issues / regressions:** None reported in these checks. No numerical
memory/FPS measurements were provided; no general leak-free or performance claim
is made beyond this diagnostic-source acceptance.

**Architecture/decision impacts:** None. Per-instance ownership remains the
foundation for future independent sessions.

**Next recommended slice:** Phase 2 — Android Camera Capability Profiler.
At the time of this Phase 1 acceptance entry, Phase 2 had not started. Phase 1
tests need not be repeated unless a relevant change or regression warrants it.

## 2026-09-27 — Phase 2 Android Profiler Implementation

**Date:** 2026-09-27 (Asia/Manila).

**Slice:** Phase 2 — Android Camera Capability Profiler.

**Status:** PASS WITH LIMITATIONS for implementation/build/lint and one tablet
scan/export. The Phase 2 exit gate is still OPEN pending a second physical
device report and cross-device comparison.

**Goal:** Implement a local Android app that reports Camera2 and H.264/HEVC
encoder metadata without opening a camera or running an encoder.

**Changes:** Added a Kotlin Android app with permission-on-scan UI, bounded
Camera2 and MediaCodec metadata collectors, typed status-bearing capability
fields, a single background scan session, rotation-safe Activity attachment,
versioned JSON export through the system document picker, and a minimal report
display. Added the Phase 2 architecture/decision/testing documentation, app
build guide, and a schema guide in the Android project. The camera and encoder
inventories remain separate and the report explicitly says capture/encoding
were not runtime-tested. The native OBS diagnostic source was not changed.

**Build environment:** Windows PowerShell; Microsoft OpenJDK 17.0.16.8; Android
SDK Platform 36; Build Tools 36.0.0; Android Gradle Plugin 8.13.2; Kotlin
Gradle plugin 2.2.20; Gradle Wrapper 8.14.5 with verified distribution SHA-256
`6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854`; min SDK
26, compile/target SDK 36.

**Devices tested:** One user-tested Android tablet, Samsung SM-X115, Android 16
(API 36), app 1.0.0. This evidence is user-supplied via the exported report;
the agent did not operate the tablet directly.

**Automated checks:** `:app:assembleDebug` PASS and `:app:lintDebug` PASS with
`No issues found.` on Gradle 8.14.5. The resulting debug APK is
`apps/android/app/build/outputs/apk/debug/app-debug.apk` (1,018,537 bytes),
SHA-256 `154E0BE93BC42B6053C230EA41B1C1990A40C7BA4125A6EEDEABC3653C87AC6A`.
No unit/instrumentation test task was run.

**Manual tests:** The report artifact confirms a metadata scan completed and
JSON export succeeded on the tablet. No separate observations were supplied for
launch permission timing, denied/retry behavior, rescan, or rotation during a
scan; those checks remain unverified.

**Exported report results:** Parsed `docs/evidence/2026-09-27-samsung-sm-x115-capabilities.json`
(schema 1.0.0, collected `2026-09-27T05:15:18.338324Z`, 184,968 bytes,
SHA-256 `426FAEF002C27C97A41C135C34E20BF6CC9A974293A9A12DBB6202C27DDA5D43`).
It contains two advertised camera profiles and seven H.264/HEVC encoder entries;
top-level and per-camera/encoder collection issue lists are empty. The report
contains no serial number, account data, or persistent device identifier.

Rear camera `0` and front camera `1` both report hardware level 3 and manual
sensor capability. Neither advertises logical/physical camera IDs. The rear
profile lists 1080p and 720p in PRIVATE and YUV_420_888 outputs; the front lists
720p but no 1080p in those formats. Both advertise AE ranges only up to 30 FPS,
no constrained high-speed modes, and no dynamic-range profile values. Reported
manual sensor ranges are ISO 100–1600 rear / 100–800 front, exposure time
100,000–400,000,000 ns on both, and exposure compensation −2.0 to +2.0 EV in
0.1 EV steps.

The report lists MediaTek hardware H.264 and HEVC encoders (including aliases).
Their metadata point queries report 720p30 and 1080p30 supported and 1080p60
unsupported. Software encoder entries are also present. These are advertised
constraints only; no camera capture, encode, or end-to-end pairing was tested.

**Cross-device comparison:** Not run; only one physical-device report is
available. The Phase 2 exit gate remains open.

**Observed issues:** No build, Android Lint, or collection issues were reported.
The tablet's advertised metadata includes no dynamic-range profiles or
constrained high-speed modes and no 1080p60 point support on the listed hardware
encoders. This is a capability result for this tablet, not a profiler failure.
Runtime capture/encoding behavior and OEM metadata quirks remain unverified.

**Regressions:** None known. Existing Phase 1 OBS files and native validation
were not modified or rerun.

**Architecture/decision impacts:** D-020 records the metadata-only Android
profiler boundary. Q-003 (future capture engine choice) remains open; Q-015
tracks the outstanding physical-device comparison.

**At the time of this implementation entry:** The multi-device comparison was
still open. See the following Phase 2 acceptance update for the later reports.

## 2026-09-27 — Phase 2 Multi-device Metadata Comparison

**Date:** 2026-09-27 (Asia/Manila).

**Slice:** Phase 2 — compare exported profiler reports from two Android devices.

**Status:** PASS WITH LIMITATIONS. The Phase 2 export-and-compare exit gate is
met using two user-supplied device reports. This does not validate runtime
capture/encoding or unreported permission, retry, rescan, and rotation flows.

**Goal:** Confirm the profiler exports useful device-specific capability data
and compare camera/encoder metadata across distinct devices.

**Changes:** Preserved the Xiaomi report under `docs/evidence/` and updated this
record, the test procedure, Phase 2 roadmap acceptance, and Q-015. No application
source changed in this comparison update.

**Build environment:** No build was run for this evidence-only update. The prior
Phase 2 `assembleDebug` and `lintDebug` pass remains the implementation build
evidence.

**Devices tested:** User-supplied reports from Samsung SM-X115 and Xiaomi
2406APNFAG, both reporting Android 16/API 36 and app 1.0.0. The report artifacts
are user-run evidence; the agent did not operate either device directly.

**Automated checks:** Both files parse as schema 1.0.0. Samsung report SHA-256:
`426FAEF002C27C97A41C135C34E20BF6CC9A974293A9A12DBB6202C27DDA5D43`.
Xiaomi report SHA-256:
`12AEF5230BD809FECBA96F6D77C971AC67CFC15E0ABE3B3A7443CA47D6BCEA91`.
Each report has two camera profiles, seven encoder entries, and zero collection
issues at the top level and in its camera/encoder profiles.

**Manual tests:** A completed scan and JSON export are evidenced for each
device. The reports do not establish that launch permission timing,
denial/retry, rescanning, or rotation during a scan was tested.

**Exported report results:** Both devices report back and front level-3 cameras
and manual sensor capability. Samsung reports no logical/physical camera
metadata, no constrained high-speed modes, and no dynamic-range profile values.
Its rear video-relevant outputs include 720p and 1080p; its front outputs include
720p but not 1080p. Xiaomi reports a logical rear camera with physical IDs 3 and
6, two constrained high-speed sizes per camera, and advertised dynamic-range
profiles (including standard, HLG10, HDR10, and HDR10+). Both have 30 FPS as the
maximum listed AE range and use the same API level, so this comparison does not
exercise API-version differences.

Both reports list MediaTek hardware H.264/HEVC encoders and software entries.
The Samsung hardware encoder point checks report 720p30 and 1080p30 supported;
the Xiaomi checks report 720p30 supported but 1080p30 unsupported. Both report
1080p60 unsupported. Xiaomi reports 16x16 encoder alignment, so the exact
1920x1080 point fails the height alignment requirement (1080 is not divisible
by 16); Samsung reports 2x2 alignment. The broad width/height ranges do not
override alignment. Camera stream sizes and encoder point checks remain
independent metadata; no compatible camera/encoder pair was run.

**Cross-device comparison:** PASS for the Phase 2 metadata gate. The distinct
manufacturer/model reports show meaningful differences in logical/physical
cameras, stream formats, high-speed/dynamic-range metadata, and 1080p30 encoder
constraints. API-level comparison remains open because both devices report API
36.

**Observed issues:** No profiler collection issues are present in either report.
Xiaomi's camera profiles advertise 1080p output sizes while its encoder
1080p30 point checks are unsupported. The reported 16x16 encoder alignment
explains the exact-size rejection because 1080 is not aligned to 16; Xiaomi's
camera can still advertise a 1080p output. This confirms that camera and encoder
metadata must not be combined into a runtime support claim. This is an advertised
constraint finding, not a runtime failure.

**Regressions:** None. No application source changed.

**Architecture/decision impacts:** Q-015 is resolved for the two-device
metadata comparison. Q-003 (future capture-engine choice) remains open. Runtime
camera/encoder compatibility remains for Phase 3.

**Next recommended slice:** Follow [the Phase 3 handoff](PHASE_3_HANDOFF.md):
exercise a rear-camera 1080p30 H.264 path on the Samsung SM-X115, whose report
advertises that camera size and encoder point check. Treat it as a candidate
until actual session configuration and encoding are verified; use Xiaomi
720p30 as another metadata-supported baseline.

## 2026-09-27 — Phase 3 Direct Camera2-to-Encoder Experiment

**Date:** 2026-09-27 (Asia/Manila).

**Slice:** Phase 3 — one camera to a hardware H.264 encoder.

**Status:** Direct Camera2-to-hardware-encoder baselines PASS WITH LIMITATIONS.
The Samsung SM-X115 1080p30 baseline has two completed runs, and the Xiaomi
2406APNFAG 720p30 baseline has three. Phase 3 follow-up investigations remain
open for Xiaomi aligned 1080p and independent opens of physical camera IDs.

**Goal:** Create a controlled Camera2 repeating capture path into a hardware
MediaCodec input Surface, with instrumentation that distinguishes advertised
metadata from measured camera/encoder behavior.

**Changes:**

- Added permission-gated Camera2 route discovery for logical and standalone
  camera IDs, plus physical output routes where the platform reports them.
- Added exact direct PRIVATE-output/H.264 hardware encoder mode matching at
  30 fps, selecting the largest exact mode up to 1920×1080 for the chosen route.
  Hardware codec aliases and software-only entries are excluded.
- Added a five-minute Camera2 repeating capture to the encoder input Surface,
  with manual stop, foreground lifecycle stop, and screen-awake behavior.
- Added live telemetry and versioned local JSON export for sensor/encoded
  timestamps, cadence, keyframes, drop estimates, capture failures, codec
  output format/crop/profile/level, bitrate, EOS, CPU, thermal, and battery.
- Preserved the Phase 2 scan/export workflow. The OBS plugin, transport, and
  unrelated app behavior were not changed.

**Build environment:** Windows PowerShell, JDK 17, Android SDK Platform 36,
Build Tools 36.0.0, AGP 8.13.2, Kotlin 2.2.20, and the cached Gradle 8.14.5
distribution. The Gradle wrapper could not download in the restricted shell;
the same checksum-pinned cached Gradle distribution was invoked directly.

**Host checks:** The debug APK assemble and Android Lint tasks passed. No unit
or instrumentation tests were added or run.

**Build artifact:** apps/android/app/build/outputs/apk/debug/app-debug.apk,
1,129,847 bytes, SHA-256
EFC422C622A97769650DE20C832A533E6E2E4D9D495AA4A0427C4CF79CC9FD84. This
build includes `measurements.endOfStreamSeen` in the runtime JSON export.

**Physical-device tests:** The user supplied two Samsung SM-X115 (Samsung A9)
rear 1920×1080@30 H.264 reports, three Xiaomi 2406APNFAG (Xiaomi 14T) logical
rear 1280×720@30 H.264 reports, and one run for each Xiaomi logical-camera
physical output ID 3 and 6 at 1280×720@30. All seven report completed
five-minute Camera2-to-MediaCodec Surface runs on Android 16/API 36, app 1.0.0.
The artifacts are recorded in `docs/evidence/`; these are user-supplied device
reports, not independently operated sessions.

**Runtime results:** Samsung configured hardware `c2.mtk.avc.encoder` at exact
1920×1080 with a full-frame crop in both runs. Capture/encoded rates were
30.0036/30.0036 and 30.0003/30.0003 FPS; measured bitrates were 7.989 and
7.990 Mbps. Run 1 reported zero drop estimates; run 2 reported one capture and
one encoded dropped-frame estimate. Both reported zero capture failures and
zero non-monotonic timestamps. The second run explicitly recorded
`endOfStreamSeen: true`.

The three Xiaomi reports configured the same hardware encoder at exact
1280×720 with a full-frame crop. Capture/encoded rates were 30.0099, 30.0078,
and 30.0044 FPS; measured bitrate stayed near 3.625 Mbps. The first two runs
reported zero drop estimates; run 3 reported one capture and one encoded
dropped-frame estimate. All three reported zero capture failures and zero
non-monotonic timestamps. Run 3 explicitly recorded `endOfStreamSeen: true`.
Thermal state remained `none` in the Xiaomi runs with EOS evidence and `light`
across the Samsung runs. The Xiaomi reports continue to state that exact direct
1080p is rejected by 16×16 encoder alignment.

The Xiaomi physical output 3 and 6 reports each selected `Camera 0 · physical
output N` through `logical_camera_physical_output`; neither opened camera ID 3
or 6 as an independent camera. Both configured hardware `c2.mtk.avc.encoder`
at 1280×720 with a full-frame crop. Output 3 reported 30.0082 capture/encoded
FPS and output 6 reported 30.0068 capture/encoded FPS. Both had 300 keyframes,
zero estimated drops, zero capture failures, monotonic timestamps, and
`endOfStreamSeen: true`. Their measured bitrates were 3.624 and 3.625 Mbps.
Thermal status stayed `none` during both runs.

**Evidence limitation:** The initial three exports did not serialize
`measurements.endOfStreamSeen`. The exporter was fixed, and the follow-up
Samsung, Xiaomi logical-baseline, and Xiaomi physical-output reports all
contain `endOfStreamSeen: true`; the user confirmed the two follow-up baseline
runs used the newly installed APK with this exporter fix. The JSON app version
remains 1.0.0. The original reports are preserved unchanged. The app-reported
path declares direct
Camera2-to-encoder Surface output with app queue depth zero; the experiment
does not save a bitstream, so encoded-file playback and visual quality are not
established. No logcat was supplied.

**Mode-specific evidence boundary:** Repeatable direct 1080p30 operation is
demonstrated on Samsung, and direct 720p30 operation is demonstrated on the
Xiaomi logical rear route and physical output IDs 3 and 6. Independent opens of
IDs 3 and 6 have not been tested. Xiaomi 1920×1088 padding, a visible 1080-line
crop, or another GPU path is not implemented or tested.

**Observed issues:** The latest Samsung and Xiaomi logical-baseline runs each
report one estimated gap in both capture and encoded timelines. The physical
output 3 and 6 runs report zero estimated gaps. All have approximately 30 FPS
cadence and no capture failures. The estimates are reported separately and do
not establish the number of unique lost frames. The public API path does not
expose GPU load or the encoder's internal queue depth. Process CPU is sampled
at one-second intervals. Timestamp streams remain separate because their
clock bases have not been qualified.

**Regressions:** No setup/configuration errors or capture failures appear in
the supplied runtime artifacts. Encoded bitstream playback and visual quality
were not tested.

**Architecture/decision impacts:** Multi-device reports now demonstrate the
direct Camera2 baseline on both devices, but do not compare CameraX or Camera2
interop; Q-003 remains open for that architecture decision. Phase 4 transport
and later slices remain unstarted.

**Next recommended slice:** Begin the Phase 4 LAN transport prototype with one
of the tested direct baseline modes. Preserve encoded timestamps and keyframe
metadata, keep queues bounded, and make loss observable. Xiaomi aligned 1080p
and independent physical-camera opens remain separate follow-ups.

## 2026-09-27 — Phase 4 Fixed-Endpoint RTP/H.264 Prototype (initial report)

**Status:** IN PROGRESS. Android Kotlin compilation and the Windows receiver
project build pass. Physical LAN acceptance remains unverified.

**Goal:** Add a nonblocking compressed access-unit seam to the existing
Camera2-to-MediaCodec session and feed one stream to a fixed IPv4 RTP/H.264
Windows counter receiver.

**Changes:** Added a MediaCodec output sink that honors offset/size, codec
configuration buffers, output-format CSD, partial-frame flags, PTS, and
keyframe flags. It copies compressed bytes into pooled storage, caps a complete
access unit at 2 MiB, and enqueues complete units without socket work on the
camera/encoder handler. The sender queue is capped at 512 KiB, 24 units, and
500 ms; overflow/age drops flush complete queued units and wait for a keyframe.
The RTP worker emits H.264 single-NAL/FU-A packets to a fixed IPv4 address on
UDP 5004, maps source PTS to the 90 kHz RTP clock, carries exact PTS and flags
in documented RTP header extensions, and prepends cached SPS/PPS on keyframes.
Keyframe recovery also recognizes IDR NALs when the codec keyframe flag is
absent. The Android UI leaves transport optional and runs ten minutes when a
receiver address is entered. Added a standalone Windows receiver with a 2 MiB /
500 ms bounded access-unit reassembly buffer and live packet/AU/timestamp
counters. Updated architecture, build/use, and Phase 4 test documentation.
Q-001, Q-002, and Q-007 remain open; this is not a permanent protocol decision.

**Build checks:** `:app:compileDebugKotlin` passed with Gradle 8.14.5, Android
SDK Platform 36, AGP 8.13.2, and Kotlin 2.2.20. The full APK assemble and
Android Lint were not run for this slice. The actual
`CamSure.RtpReceiver.csproj` project built with .NET 10, zero warnings, and zero
errors using an empty NuGet source; it has no external package dependencies.
No automated tests were added or run.

**Physical LAN evidence:** None. No Samsung or Xiaomi run has used the sender,
and no Windows packet was received. Source counters do not establish delivery.
The local discovery step, interruption/recovery observations, measured sender
and receiver high-water marks, and queue-age trend remain open.

**Regressions:** No device runtime check was performed, so runtime regressions
remain unassessed. The capture-only path is still configured without an
access-unit sink when no receiver address is set.

**Next recommended slice:** Run the fixed-address Samsung 1080p30 test with the
Windows receiver and record phone/receiver evidence. Add local discovery only
after fixed-address delivery and counters are verified. Do not close Phase 4
or Q-001 from source inspection/build evidence alone.

## 2026-09-28 — Phase 4 Fixed-Address Evidence and Q-007 Discovery

**Status:** PASS WITH LIMITATIONS for the user-supplied fixed-address run.
Q-007 implementation is present, but its Android LAN acceptance remains open.
The overall Phase 4 exit gate and Q-001 remain open.

**Goal:** Preserve the verified Samsung/Windows RTP run, correct the exported
full-run last encoded PTS summary, and add local Windows DNS-SD advertisement
and Android NSD discovery/resolution without changing the RTP media path.

**Changes:**

- Added a Windows IPv4 mDNS/DNS-SD advertiser for
  `_camsure-rtp._udp.local.`. It advertises the actual receiver port, responds
  to browse/resolve queries, refreshes interface records, and sends a goodbye
  when stopped. Advertisement failure leaves fixed-address RTP available.
- Added Android NSD discovery/resolution and a receiver selector. It shows the
  resolved IPv4 address and port, tracks service loss/reappearance while
  foregrounded, uses the multicast lock where needed, and stops discovery with
  the Activity. Manual IPv4 entry remains as fallback.
- Updated the Phase 3/4 exporter to use full-run capture/encoded timestamp
  counters instead of the bounded 10,000-sample arrays for last-value summaries.
- Added the supplied Android JSON and paired Windows log under `docs/evidence/`.
  The preserved JSON copy changes only `encodedLastPresentationTimeUs` to the
  full-run transport counter value, 66,518,352,971; the capped sample tail
  remains 66,251,818,420.
- Updated `docs/TESTING.md`, Android usage notes, and Windows receiver notes with
  the paired run, discovery workflow, firewall requirements, and open reconnect
  and loss/recovery checks.

**Build and host checks:** `:app:compileDebugKotlin :app:lintDebug` and
`:app:assembleDebug` passed with Android SDK Platform 36. The resulting
`app-debug.apk` is 1,084,867 bytes (SHA-256
`6E64F78EF36B81EFDB1BB1B664F44512B79C07F2175D04127CED28749EE182DB`). The
Windows receiver project built with .NET 10 with zero warnings and errors. The
receiver started on UDP 5006 and logged the DNS-SD service and Ethernet address
`192.168.0.146`. An earlier host-only UDP 5353 capture saw no packet. The user
then supplied a live Android LAN result: the app reported “receiver detected,”
and the receiver logged three browse queries from `192.168.0.233:5353`,
answering each with one record plus three additional records. This confirms live
browse/advertiser response. The full user-supplied [discovery receiver log](evidence/2026-09-28-camsure-dnssd-discovery-receiver.log)
and [live-run receiver log](evidence/2026-09-28-camsure-phase4-receiver-live-run-partial.log)
are preserved, along with a transcribed [console excerpt](evidence/2026-09-28-camsure-dnssd-discovery-console-excerpt.txt).
The discovery-only log stays at zero RTP through 3:54. The later log receives
RTP by t=5:18 and at t=6:40 reports 34,356 packets, 2,463 complete AUs, SSRC
1748780090, zero gaps/incomplete AUs/malformed packets/PTS mismatches, and
234,545 bytes / 34 ms reassembly high-water. It has no final counters and
contains about 1:22 of media. This is enough to demonstrate the core
discovery-to-RTP behavior; the ten-minute soak is a separate acceptance gate.
The user supplied the run after being directed to select the discovered
receiver, though the receiver log itself does not encode the UI selection
source. `adb devices -l` had no device attached to the agent host, so the
Android screen was not independently inspected. No automated tests were added.

**Fixed-address measurements:** Samsung SM-X115, Android 16/API 36, rear camera
0, hardware `c2.mtk.avc.encoder`, 1920×1080@30 H.264, 600.16 seconds, destination
`192.168.0.146:5004`. Android sent 17,996 access units / 299,793 RTP packets
with SSRC 604953840 and zero send failures. Receiver evidence reports the same
SSRC, 17,996 complete AUs, 299,793 packets / 347,882,535 bytes, zero gaps,
incomplete AUs, out-of-order packets, malformed/oversized packets, PTS-map
mismatches, non-monotonic PTS, or key-flag mismatches. Sender queue high-water
was 231,729 bytes / 14 ms; receiver reassembly high-water was 231,762 bytes /
32 ms. The run reported 600 keyframes, 4.50 Mbps average bitrate, one estimated
capture and encoded drop, no capture failures, and EOS.

**Firewall and device acceptance:** Windows reports all firewall profiles on,
default inbound block / outbound allow, and local rules controlled by Group
Policy (`LocalFirewallRules: N/A`). The Android browse query reached the
receiver, proving UDP 5353 passage for this trial, though its effective
firewall profile/rule was not identified. RTP also reached UDP 5004, confirming
the media path in this trial. No firewall policy was changed by the agent.
WAN-disconnected discovery, receiver restart, and network reconnect remain
unverified; the partial counters do not close the separate ten-minute soak.
The clean fixed-address run is not an induced-loss/recovery test and does not
settle the Q-001 protocol trade-off.

**Evidence files:** Raw supplied JSON SHA-256
`AF40D17FD643D226B09A5316741872D6C369F3A104482AA1872EB5EC232FB5B1`; corrected
Android report SHA-256
`463C0164179FC83BA93CE4F9F037E2D482CDDB5AC521209A8C4116B2DC91F970`; paired
Windows console log SHA-256
`4CD3489C209478846066DFBE8E43108AA049EA08CB7A9E7203AA061DE48CF698`; DNS-SD
receiver log SHA-256
`DFCAFE6632F595727D43CCB58F5CBF09AAF1CEECFA703997165AA71E4FD95116`; partial
live-run receiver log SHA-256
`1F2DF64B1EDA1131F36BF06C939E0F0248316216E6DBAA9156852A1958E45B40`.
The original external JSON remains unchanged.

**Next recommended slice:** Test the same discovery flow with the WAN
disconnected, then restart the receiver and restore the phone's network while
discovery is active. Record firewall profile/rule and rediscovery behavior.
The current run already demonstrates the basic discovery-to-RTP path; a longer
soak can supply separate duration evidence. Run the distinct Q-001 protocol
trade-off and loss/recovery test afterward. Do not close Q-001 or the Phase 4
exit gate based on a clean short run.

## 2026-09-30 - USB Network Mode implementation

**Status:** PARTIAL; user explicitly deferred physical testing. Added explicit
Android USB selection/local bind, bounded USB queue and independent link monitor;
Windows adapter/address/peer selection, exact bind, pre-parser peer isolation,
100 ms reassembly and session/retired-SSRC handling. Reuses existing RTP/AU/pipe/
decoder/OBS; native runtime code unchanged. Manual tethering, no ADB/debugging
requirement. LAN defaults preserved. No audio/controls/multicamera added.

**Tests:** Android assemble/lint and focused subnet/queue/stall/abort/LAN-default
unit tests pass; .NET build and USB plus existing RTP self-tests pass. Shared
synthetic native pipeline passes 720p/1080p exact PTS, induced loss/restart,
132 distinct OBS GPU frames, stop-clear and idle/partial-read teardown x10.
These are host/simulated results. USB route/OBS/600 s/latency/ten cable cycles/
consumer stall/frontend lifecycle and physical LAN regression are NOT RUN.
No USB throughput, FPS, recovery or glass-to-glass values are claimed.

**Limitations/next:** Operator must confirm candidate USB hardware; polling/OEM
visibility and route need physical qualification. Complete the prepared A-H
checklist before evaluating Camera Manual Controls Foundation. See
[report](USB_NETWORK_REPORT.md) for limits, telemetry and files.

**2026-10-01 final build handoff:** Prepared APK and isolated Windows helper;
see the report for artifact hashes and final Android build/unit/lint evidence.
Physical acceptance remains deferred by the user.

## 2026-10-01 - USB receive-loop scheduling refinement

User reports drops persist with only one CamSure source. Latest receiver log
still records packet gaps; duplicated source pipes are not the entire cause.
The Xiaomi ten-minute report had zero sender queue/send drops. Receiver console
output (including Tee-Object backpressure) and adapter enumeration ran inline
with UDP reception. These are plausible local receive stalls, not proven USB
packet-loss attribution. Move console reports to one bounded latest snapshot
worker and adapter polling to a separate cancelable task that closes the socket
on link loss. Keep USB socket/reassembly/AU limits and decoder/OBS unchanged.
Build/self-tests pass, including a blocked telemetry sink with 1,000 publications
and one pending snapshot. Physical retest is required; this is not a fixed claim.
Prepared helper: tools/windows-rtp-receiver/bin/usb-refinement/CamSure.RtpReceiver.dll.
No Android APK change is required for this refinement.
