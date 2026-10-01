# OBS connection setup — 2026-10-01

Status: source/build and host tests pass; OBS frontend and physical LAN/USB
acceptance OPEN. Existing device-specific 720p/1080p evidence is not a pass for
this new process-management workflow. 4K remains experimental.

## Package and install

The installable package is `plugins/obs-camsure/stage/obs-camsure/`.
It contains the native DLL/decoder dependencies plus `data/receiver/` with
CamSure.RtpReceiver.exe, DLL, deps.json and runtimeconfig.json. This package is
framework-dependent and requires the Windows x64 .NET 10 runtime (available on
the development PC). No SDK, PowerShell, Android debugging or internet is needed
for normal operation. A self-contained public installer is future release work.

Close OBS normally before replacing its plugin. Copy the complete staged folder
to `C:/ProgramData/obs-studio/plugins/obs-camsure/`, preserving existing OBS scenes,
profiles and OBS core DLLs. Stop any old manually launched receiver in its own
console; a busy media port or pipe is an explicit failure, not a takeover.

## Operator steps

1. Add/edit a **CamSure Camera** source. Enable **Manage receiver in OBS (no
   PowerShell)**. This opt-in preserves existing external receiver scenes.
2. Choose Wireless/local network or USB Network, then explicitly choose the PC
   adapter/IPv4. Names alone do not identify a USB adapter. Refresh after enabling
   tethering or changing adapters. An absent saved adapter is never substituted.
3. Keep media port 5004 for the first source. In USB mode enter the phone's
   selected tethering IPv4, shown by Android's USB address selector. USB enforces
   exact local bind, peer filtering and subnet validation; discovery is disabled.
4. Click **Start receiver**. It enables real camera video and shows Connecting,
   then Waiting for phone with the selected PC endpoint. Wireless advertises only
   this address/port; use Find PCs on Android or enter this PC IPv4 manually.
   USB: enter this PC IPv4 and matching media port in Android Settings. Enable USB
   tethering manually; USB debugging is not required.
5. Select the phone camera/resolution and tap Start. OBS reports Streaming only
   after recent decoded video reaches its source. Camera resolution is selected
   on the phone; Diagnostics image settings do not control phone capture.
6. Stop the receiver using its source properties. Stop cancels preview output,
   requests graceful receiver teardown and releases the process/transport.
   Start again explicitly after reconnect or OBS restart. Saved selections remain.

Each independent source requires its own media port and AU pipe (Diagnostics).
Existing defaults/external receiver commands remain compatible. This slice does
not qualify multiple physical phones or add remote camera controls/audio.
Receiver stays active across properties navigation and scene visibility changes;
Stop, source destruction after its last reference, or OBS shutdown releases it.
Firewall passage for the packaged executable/mDNS is setup-specific; allow it on
the appropriate Windows profile when needed. No firewall rules are changed here.

## Physical/frontend acceptance

- Verify fields/layout, selecting mode, refresh, saved adapter/peer/port, and
  startup messages. While running, connection and pipe fields are locked; Stop
  unlocks them. Changing friendly name or navigating properties preserves video.
- Wireless: discover the selected PC offline, start 720p/1080p, compare framing,
  smoothness and latency against the previously accepted workflow. Repeat Stop,
  phone stop/start, and receiver restart. Retain Android JSON and OBS current log.
- USB: confirm the physical tethering adapter and both endpoints, disable alternate
  phone routes, verify real 720p/1080p OBS video and a ten-minute paired-counter run.
  The managed receiver retains the tested 256 KiB USB receive-buffer setting.
- Unplug cable / disable selected adapter: receiver terminates without fallback.
  Restore link, refresh/reselect if changed, and restart both endpoints explicitly.
- Stop phone while receiver remains up: status changes to Disconnected/no recent
  video after two seconds; unchanged AU/keyframe recovery can resume reception.
- Try unavailable adapter, off-subnet USB peer, occupied port and duplicate pipe:
  each must fail clearly and preserve existing receiver/source ownership.
- Close OBS and verify no owned receiver remains. Reopen: selections restored,
  receiver stopped until Start. Remove a running source's last reference and check
  cleanup. Scene hide/show does not represent source destruction.

## Host evidence (separate from physical acceptance)

- Native MSVC build and .NET Release publish pass.
- Real receiver owner probe: readiness, port collision isolation, graceful Stop,
  five destructor/restart cycles, unavailable adapter, invalid USB peer, missing
  package failures. Output is bounded to a latest diagnostic line, not a growing log.
- Real libobs/D3D11 managed source probe: adapter inventory, three Start/Stop
  cycles, active-field locks, USB/LAN visibility, source settings save/load,
  stopped state after load, explicit restart, and source-removal cleanup.
- Existing receiver self-tests and four-source diagnostic lifecycle pass.
- Shared decoder regression: 720p/1080p exact PTS and owned I420; synthetic 4K also
  passes without changing its experimental classification. Shared RTP-to-native
  OBS recovery probe rendered 142 distinct frames, induced loss/restart and
  idle/partial-read removal x10 pass. No real phone was operated by these probes.

Logs: `docs/evidence/2026-10-01-obs-*`. Installation and actual frontend/phone results
must be recorded separately from these host results.

## Installed package proof — 2026-10-01

Installed with OBS closed at C:/ProgramData/obs-studio/plugins/obs-camsure/.
All installed files match stage; evidence/2026-10-01-obs-connection-install-hashes.txt.
Native DLL SHA256: 68E06847E6940D137B2CFBE3719BE719F96126648063540C5A04744F7AC3DEF3.
Receiver DLL SHA256: 12AB2D1A1C8FFD19A6ACE5CC8CBB842C7C40999F82EA30ABDC936F7CDB1C7409.
Prior package backup: plugins/obs-camsure/build_vs2026/install-backups/20261001-connection-setup/.
The actual installed DLL/data package passed the managed libobs source probe:
evidence/2026-10-01-obs-installed-managed-source-tests.log. No receiver process
remained after tests. .NET 10.0.5 x64 runtime is installed. OBS frontend/physical
phone acceptance remains OPEN; no scene collection/profile was changed.

## Mixed Wi-Fi / USB two-phone trial (not yet physically qualified)

Create two independent CamSure sources:

| Source | Phone connection | Media port | AU pipe in Diagnostics |
|---|---|---|---|
| Device A | Wireless / local LAN | 5004 | camsure-camera-1 |
| Device B | USB Network | 5006 | camsure-camera-2 |

Select each PC adapter/address explicitly. B requires its phone tethering peer;
set Android B's USB media port to 5006. A selects its discovered PC endpoint, or
uses manual LAN address with default 5004. Start each receiver separately, then
each phone. Stop/disconnect/restart B and verify A remains smooth; repeat with A.
Record both phone exports, OBS log, latency/load and physical USB route evidence.
Independent ownership exists, but mixed simultaneous-phone acceptance is OPEN.

## 2026-10-01 — Second managed OBS source startup fix

Confirmed current OBS log (2026-10-01 19-05-58.txt, errors from 19:50:18 onward):
"AU pipe unavailable (another source may own this name)". Saved CamSure Camera 2
has a separate media port (5010), but both sources lack an explicit au_pipe setting
and inherit camsure-camera-1. The decoder pipe conflict blocks the second source.

Managed Start now assigns and saves camsure-auto-<OBS source UUID> when the pipe
is inherited or already automatic. A duplicated automatic source derives its own
UUID-based pipe; a saved/restored source retains its identity. Explicit custom pipe
settings and external receiver defaults remain intact. Media ports must still be
unique on the same PC adapter; no automatic retargeting or scene-file editing.

Native build/stage and real concurrent-receiver libobs test pass: two independent
owners, separate automatic pipes, Stop/restart isolation, duplicate isolation,
save/load and cleanup. Existing explicit-pipe managed-source test passes. See
2026-10-01-obs-managed-pair-regression.log and related fix logs in docs/evidence.
This is host ownership proof; simultaneous real-phone quality/latency remains OPEN.
The corrected package is staged pending replacement with OBS closed. No commit/push.

Installed second-source fix with OBS verified closed (2026-10-01).
All package hashes match stage; native DLL SHA256:
C8268C6A1A7F95CD4F2BC5C7F0D1EED6381B82F81C270E7A558802A77DB37B81.
Proof: evidence/2026-10-01-obs-default-pipe-fix-install-hashes.txt.
Backup: plugins/obs-camsure/build_vs2026/install-backups/20261001-default-pipe-fix/.
Shared synthetic video regression passes with 157 distinct frames; explicit-pipe
managed regression remains passing. No OBS scene/profile edits. Physical retest:
start both saved sources (ports 5004 and 5010), select the matching discovered
endpoint on each phone, verify independent images and Stop/restart isolation.

2026-10-01 user follow-up: "now working!" after installing the managed pipe fix.
Record this as user-reported functional success for the second-source startup.
Phone/tablet rear resolution selections differ (phone offers 720p, tablet 1080p);
current metadata filtering and opt-in exact-1080p path remain in place. No paired
multi-phone counters, duration, latency or sustained independence measurements
were supplied; broader multicamera qualification remains open.
