# Xiaomi USB 256 KiB receive-buffer trial

Phone report: `D:/Downloads/Mobile Devices/camsure-usb-network-20261001-044403.json`.
Receiver log: `usb-buffer-trial-receiver.log`. Matching SSRC: **2001963800**.
Run: 2026-09-30 20:41:17.866Z to 20:44:00.337Z, 162.4705 seconds.

The receiver includes an earlier SSRC 1946747231. Subtract its last reported
baseline (287 packets, 20 complete AUs, zero gaps/incomplete AUs), rather than
attributing the entire file to the supplied phone report.

| Metric | Phone | Receiver, matched run |
| --- | ---: | ---: |
| Compressed frames/AUs | 4867 encoded and sent | 4855 complete + 12 incomplete |
| RTP packets | 65868 successful sends | 65856 received |
| Sequence gaps | unavailable | 12 missing packets |
| Sender queue drops / send failures / in-flight stale drops | 0 / 0 / 0 | unavailable |

Receiver final cumulative counters: 66143 packets, 4875 complete AUs,
12 incomplete AUs and 12 sequence gaps. No malformed or out-of-order packets,
stale AUs, or oversized AUs. Effective OS receive buffer: 262144 bytes.
Largest reconstructed AU: 92748 bytes; maximum reconstruction age: 15 ms.
Bridge maximum depth: 2 of 4, maximum recorded age: 18 ms, maximum
reconstruction-to-write time: 36.601 ms. Bridge drops cannot all be labeled
queue overflow: connection startup and keyframe recovery also discard AUs.

Phone capture/encoding averages approximately 30 FPS with zero estimated frame
drops. The two capture failure samples are at the end (frames 4867 and 4868),
not evidence of the mid-run interruptions. Sender queue maximum depth was one
and recorded maximum age 10 ms. Android successful UDP sends establish socket
acceptance, not delivery over RNDIS or into the Windows receive socket.

The user reports slightly better movement but two visible drops in under
30 seconds. The 256 KiB trial does not establish a fix. Matched packet counts
confirm loss somewhere after successful Android socket sends and before
Windows application receipt. They do not isolate Android/kernel, USB/RNDIS,
Windows driver/kernel, or receive-loop scheduling. OBS additionally replaces
decoded frames between ticks; this is separate from missing RTP packets.

Next bounded investigation: observe packet burst timing and receive-loop
service stalls, then trial USB-only sender pacing if burst evidence supports
it. Preserve source resolution/FPS, bounded freshness policy, and LAN defaults.
