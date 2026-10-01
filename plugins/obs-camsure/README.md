# CamSure native OBS plugin

## Connection setup inside OBS

Enable **Manage receiver in OBS (no PowerShell)** in CamSure Camera properties.
Select Wireless or USB Network, choose the explicit PC adapter/IPv4 and port,
and in USB mode enter the phone tethering IPv4. Click Start receiver, then Start
on Android. Stop and status are available in the same source properties; connection
edits are locked while active. Saved settings restore, but reception starts explicitly.
Existing external receiver scenes remain compatible. Detailed setup and separate
physical acceptance: ../../docs/OBS_CONNECTION_TESTING.md.

Before native build/stage, run `./package-receiver.ps1` from this directory to
publish the existing receiver into `data/receiver`. Normal users need Windows x64
.NET 10 runtime, but no SDK or PowerShell. Install the entire staged folder with
OBS closed. This local framework-dependent package is not a public installer.

Registers **CamSure Camera**, with independent per-instance diagnostic output.
The optional **Real camera video** mode receives transport-neutral H.264 access
units through a local named pipe, decodes with FFmpeg, and submits owned I420
frames through native OBS async video. Host synthetic tests and the user-observed
Samsung ten-minute real-video/recovery baseline pass with limitations. See
../../docs/DECODE_OBS_REPORT.md and ../../docs/TESTING.md, section 19.

## Windows prerequisites

- Windows x64 and OBS Studio x64. Tested runtime: OBS 32.2.2.
- Visual Studio 2022 with Desktop development with C++, MSVC, and Windows SDK
  10.0.20348 or newer; or the tested Visual Studio 2026 preset.
- CMake 3.30.5 or newer for VS 2022. VS 2026 requires a CMake version
  supporting that generator; tested with 4.3.2.
- Internet for the first configure. Runtime diagnostics operate offline.

The official template bootstraps hash-pinned OBS 31.1.1 sources and obs-deps
2025-07-11 into .deps, then builds/installs SDK development artifacts locally.
It also downloads the template's Qt dependency bundle; CamSure itself does not
link Qt or the frontend API. Do not install the SDK's obs.dll into your OBS runtime.

## Configure / build / stage

From this directory in PowerShell:

~~~powershell
cmake --preset windows-vs2026-x64 -DCAMSURE_BUILD_TESTS=ON
cmake --build --preset windows-vs2026-x64
cmake --install build_vs2026 --config RelWithDebInfo --prefix stage
~~~

For Visual Studio 2022 use windows-x64 and build_x64 instead.
Do not reuse a build directory across different generators.

Outputs:

~~~text
build_vs2026/RelWithDebInfo/obs-camsure.dll
build_vs2026/RelWithDebInfo/obs-camsure.pdb
stage/obs-camsure/bin/64bit/obs-camsure.dll
stage/obs-camsure/bin/64bit/obs-camsure.pdb
stage/obs-camsure/data/locale/en-US.ini
~~~

The stage tree is the installable package, not an OBS installation. Generated
builds, SDK downloads, stage files, and local build numbers are ignored by Git.
No commit or remote is created by the build.

## Install for manual testing

Close OBS normally first. Copy the staged **obs-camsure** folder into:

~~~text
C:/ProgramData/obs-studio/plugins/obs-camsure/
~~~

The final DLL path must be:

~~~text
C:/ProgramData/obs-studio/plugins/obs-camsure/bin/64bit/obs-camsure.dll
~~~

And the locale must be:

~~~text
C:/ProgramData/obs-studio/plugins/obs-camsure/data/locale/en-US.ini
~~~

A copy to ProgramData may require elevated permission. Keep the PDB for diagnosis.
Do not replace OBS core DLLs. Start OBS and check Help > Log Files > View Current Log
for "[CamSure] Plugin loaded (0.1.0)". Add a source using Sources > + > CamSure Camera.
Use **Create new** four times; Add Existing intentionally shares an existing source.

Use a dedicated test scene collection and follow ../../docs/TESTING.md, section 15.
To uninstall, close OBS and remove only the installed obs-camsure folder.
Installed at this path on 2026-09-27; the user confirmed plugin loading and the primary four-source/save/restart test passed. See ../../docs/PROGRESS.md.

## Native lifecycle probe

This console test uses real installed libobs and D3D11 GPU output/readback.
It does not automate the OBS frontend or touch profiles/scene collections.
Run from this plugin directory:

~~~powershell
$pluginRoot = (Get-Location).Path
$obsBin = 'C:/Program Files/obs-studio/bin/64bit'
$oldPath = $env:PATH
$env:PATH = "$obsBin;$oldPath"
Push-Location $obsBin
try {
    & "$pluginRoot/build_vs2026/RelWithDebInfo/camsure-lifecycle-test.exe" "$pluginRoot/build_vs2026/RelWithDebInfo/obs-camsure.dll" "$pluginRoot/data"
    if ($LASTEXITCODE -ne 0) { throw "CamSure lifecycle probe failed: $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:PATH = $oldPath
}
~~~

Checks: four distinct GPU-rendered diagnostics, name/resolution isolation,
FPS/pattern save/load, independent duplication, rename, enable cycle, deletion with
surviving sources, recreation, and clean shutdown. The probe excludes the animated
marker from image comparisons. It does not measure effective FPS, validate visual
layout, perform a memory soak, switch frontend scenes, or restart OBS.

## Ownership and limitations

Each source owns its settings, mutex, bounded animation clock, diagnostic image,
and optional pipe/decoder session. Decoded output keeps at most one latest frame.
Only the diagnostic ID allocator is shared. OBS owns source lifetime and settings
persistence. See ../../docs/ARCHITECTURE.md for callback execution paths.

Test resolutions describe source dimensions; the diagnostic image is generated
at 640x360 and scaled. FPS sets marker cadence, capped by OBS FPS.
Names persist exactly in OBS; diagnostics display the first 32 ASCII characters
in uppercase, substituting unsupported glyphs. Process-local instance IDs are
not persistent device identities.

## Upstream provenance

CMake foundation and .clang-format come from the official
[OBS Plugin Template](https://github.com/obsproject/obs-plugintemplate/tree/3e7d7ac3b5342cd7d9b88890b9c70b472d1520fc).
Platform helper files are retained unchanged; this does not establish Linux/macOS
support. Changes are limited to metadata, Windows presets, source targets and tests.

Plugin and tests use GPL-2.0-or-later; see LICENSE and D-019. The bundled FFmpeg
runtime is GPL-3.0-or-later; see data/licenses/README.md and D-021 for distribution
requirements. Install the entire staged folder, including its five runtime DLLs
and license notices. Runtime code and
bitmap glyphs are original CamSure implementation. No camera-project code was copied.
Official API references:
[Source API](https://docs.obsproject.com/reference-sources),
[Graphics](https://docs.obsproject.com/graphics).

## Real video and host regression

In OBS, enable **Real camera video** and set **Local AU pipe** to
`camsure-camera-1`. From the repository root, build the receiver and run:

```powershell
dotnet run --project tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj -- --port 5004 --obs-pipe camsure-camera-1
```

Start the Android LAN camera experiment against this PC. Follow TESTING section
19 for visual, timing, stop/restart, and ten-minute acceptance. The installed
build was refreshed on 2026-09-30; the user subsequently confirmed the Samsung
real-video, ten-minute and stream/source/OBS recovery tests passed.

For the synthetic encoder/RTP/pipe/decoder/native OBS GPU regression, run from
the repository root after building both projects with native tests enabled:

```powershell
./plugins/obs-camsure/tests/run-video-probe.ps1
```

This probe includes induced loss, sender restart, pipe teardown, and source
removal. Its synthetic result does not close physical camera acceptance.
