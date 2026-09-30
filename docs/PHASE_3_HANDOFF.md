# Phase 3 Handoff — One Android Camera to Hardware Encoder

## Starting Point

Phase 2 passed with limitations after comparing user-supplied capability reports
from two devices. The reports establish advertised metadata, not a working camera
to encoder pipeline. Phase 3 should preserve each device's highest usable camera
quality instead of reducing support to a common low mode.

| Device | Camera evidence | Encoder evidence | Phase 3 use |
|---|---|---|---|
| Samsung SM-X115, Android 16/API 36 | Rear camera advertises 1920×1080 and 1280×720 video outputs. | MediaTek H.264 hardware encoder reports 2×2 alignment; exact 720p30 and 1080p30 point checks are supported. | First 1080p30 H.264 runtime baseline. |
| Xiaomi 2406APNFAG, Android 16/API 36 | Rear logical camera exposes physical IDs 3 and 6; camera profiles advertise 1080p outputs. | MediaTek H.264/HEVC hardware encoders report 16×16 alignment; 720p30 is supported, exact 1920×1080@30 is not. | Test 720p30 baseline, then investigate an aligned 1080p path. Do not discard camera-advertised 1080p. |

Both devices report 1080p60 unsupported. The reports use the same Android API
level, so API-version behavior has not been compared. Hardware codec aliases are
listed separately in the reports; do not count alias entries as separate
physical encoding engines.

Evidence files:

- [`Samsung SM-X115 report`](evidence/2026-09-27-samsung-sm-x115-capabilities.json)
- [`Xiaomi 2406APNFAG report`](evidence/2026-09-27-xiaomi-2406apnfag-capabilities.json)
- [Phase 2 comparison record](PROGRESS.md)

## Phase 3 Objective

Prove a stable 1080p30 H.264 camera-to-hardware-encoder path on one device. Keep
the initial experiment to one camera. Use direct Camera2 session control for the
first controlled prototype, but keep Q-003 (Camera2 vs CameraX) open until
multiple-device runtime evidence supports a lasting choice.

The Samsung is the first baseline candidate because its report advertises both
the rear 1080p camera output and the exact hardware H.264 1080p30 point check.
The Xiaomi is a separate full-potential investigation: its reported 16×16
alignment rejects height 1080 even though the camera advertises 1080p. Evaluate
whether an aligned backing size such as 1920×1088 with a visible 1080-line crop,
or another GPU-side alignment path, can preserve the intended output without an
unnecessary CPU image-copy path. This is a test hypothesis, not an assumed
supported mode.

## First Runtime Experiments

1. On Samsung, open the rear camera, configure a 1920×1080 repeating output to
   a MediaCodec H.264 hardware encoder input surface, and record actual encoder
   selection, configuration result, output dimensions, frame rate, bitrate, and
   profile/level.
2. Measure capture timestamps, encoded-frame presentation timestamps, output
   cadence, keyframe cadence, dropped frames, and queue depth. Record the test
   duration and phone/thermal state. Separate metadata claims from measured
   runtime results.
3. On Xiaomi, run the same type of measurement at 1280×720@30 as its advertised
   baseline. Then test the 1080p alignment path, including the actual visible
   crop and encoded dimensions. A false exact-size metadata query alone does not
   close the 1080p investigation.
4. Use Xiaomi's logical rear camera as the baseline. Where supported, separately
   route outputs through Camera2 for physical IDs 3 and 6, and record whether
   either ID can also be opened independently. Do not assume those physical IDs
   are standalone camera IDs. See Android's [`CameraCharacteristics`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
   and [`OutputConfiguration.setPhysicalCameraId`](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setPhysicalCameraId(java.lang.String))
   documentation.

Prefer a Camera2-to-encoder-surface path that avoids CPU frame copies. If a
device needs conversion or padding, measure and document that stage rather than
silently reducing the selected camera mode. Keep queues bounded and report every
dropped frame or failed configuration.

## Acceptance Evidence

- A real Camera2 capture session and MediaCodec encoder run on a physical device.
- Evidence that the selected encoder is hardware accelerated and the configured
  mode actually starts; codec metadata alone is insufficient.
- Captured and encoded dimensions, visible crop, frame timestamps, output cadence,
  keyframe cadence, dropped frames, and queue behavior.
- A trace of the camera-to-encoder path showing whether CPU copies or image
  conversion occur, plus measured CPU/GPU use when available.
- Repeatable results for the Samsung 1080p30 H.264 baseline and the Xiaomi
  720p30 baseline; record the Xiaomi aligned 1080p experiment separately.
- A Phase 3 progress entry with device/build details, logs, outcomes, regressions,
  and a clear boundary between advertised metadata and runtime proof.

LAN transport, OBS ingest, multi-camera operation, remote controls, and audio are
later slices. Do not use their absence to mask a failed camera/encoder test or
claim they were proven by this handoff.
