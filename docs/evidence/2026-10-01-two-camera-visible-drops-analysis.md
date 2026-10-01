# 2026-10-01 Xiaomi continuous LAN720 visible-drop analysis

User observed frame drops. Wi-Fi band is UNCONFIRMED: user guesses 2.4 GHz.
Original JSON: camsure-phase4-lan-20261001-201531.json, SHA256
961CB9445DAF49C099DD00C989A943E46F297B60D5D43FE3105AA41922A4CDB5.
Preserved unchanged: 2026-10-01-xiaomi-two-camera-lan-720p.json.

Xiaomi 2406APNFAG rear logical0, 1280x720/30, continuous with local preview,
837.385s (13m57s), local run 20:01:32.766–20:15:30.152 Asia/Manila.
Capture/encode approximately30.0069fps, encoded25119, capture25118; zero reported
capture/encoded drop estimates, failures and nonmonotonic timestamps. RTP SSRC
4176079723, destination192.168.0.146:5004, sent25119AUs/335621packets; zero sender
failures/queue drops. Queue high-water1unit/167396bytes/11ms. Approx3.581Mbps.
Thermal none->none, battery82->77%, battery temperature37.4->40.5C.

Corresponding OBS scene source CamSure Camera uses port5004 and automatic pipe
camsure-auto-df9ea2d1-bffd-4ef5-ac88-2565a39ef7ba; source2 uses port5010 and
camsure-auto-dc91b346-0c63-409d-a3ec-b6bc0c5503d0. Scene mapping read-only.
OBS log2026-10-01 20-00-51.txt reports OBS30fps. Selected CamSure-only excerpt:
2026-10-01-two-camera-obs-camsure-excerpt.log.

720p pipe at20:15:29.516: input=decoded22669, submitted16212, frame-replaced6456,
frame-stale0, AU-dropped0, resets126, errors0; AU-age-max32.55ms,
submit-to-decoded-max16.70ms, decoded-to-tick-max45.72ms. Final stop20:17:36.658:
input=decoded22692, submitted16227, drops0, resets126, errors0.
At least6456 decoded frames were overwritten in the latest-frame slot before OBS
presentation, matching visible-drop concern. The phone sends25119 frames while
OBS sees22692; this discrepancy is not proof of RTP packet loss: receiver loss,
reassembly, bridge recovery/freshness and start/stop boundaries may contribute.
Receiver SSRC/packet/gap/bridge totals are not retained in this native OBS excerpt,
so exact end-to-end accounting remains UNCONFIRMED.

The simultaneous1080p pipe continues at20:15:59 with input=decoded25783,
submitted23525, replaced2257, AU-dropped0, stale0, resets3, errors0. Its exact phone
report/SSRC is not supplied, so do not claim a matched second-device baseline.

Conclusion: clean Xiaomi capture/encoder/sender counters do not establish smooth
OBS output. Decode errors and native100ms stale-drop gates are not observed here;
upstream delivery/recovery and burst timing relative to OBS ticks need isolation.
No buffer, bitrate, resolution, decoder or transport changes were made from this
single run. Do not infer Wi-Fi band or blame it as a proven root cause.

Next comparison: confirm Xiaomi5GHz, run alone atsame720p30 then add tablet,
retain both exports and receiver SSRC/packet/gap/reassembly/bridge snapshots plus
OBS log. Compare final sent/received/decoded/submitted counts. A verified USB run
can serve as a separate transport comparison. Avoid changing multiple variables.
