# Synthetic host integration only. Does not operate OBS frontend/Android.
param([string]$ReceiverDll, [switch]$Uhd)
$ErrorActionPreference = 'Stop'
$testRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = (Resolve-Path "$testRoot/../..").Path
$buildRoot = "$testRoot/build_vs2026"
$obsBin = 'C:/Program Files/obs-studio/bin/64bit'
if (!$ReceiverDll) { $ReceiverDll = "$repoRoot/tools/windows-rtp-receiver/bin/Debug/net10.0/CamSure.RtpReceiver.dll" }
$oldPath = $env:PATH
$videoProbe = $null
$receiverProbe = $null
try {
    & "$buildRoot/RelWithDebInfo/camsure-decoder-test.exe" "$buildRoot/synthetic.au" "$buildRoot/synthetic-4k.au"
    if ($LASTEXITCODE -ne 0) { throw 'Decoder test failed' }
    $env:PATH = "$obsBin;$oldPath"
    $videoMode = if ($Uhd) { '--video-4k' } else { '--video' }
    $fixture = if ($Uhd) { "$buildRoot/synthetic-4k.au" } else { "$buildRoot/synthetic.au" }
    $videoProbe = Start-Process -FilePath "$buildRoot/RelWithDebInfo/camsure-lifecycle-test.exe" -ArgumentList @("`"$buildRoot/RelWithDebInfo/obs-camsure.dll`"", "`"$testRoot/data`"", $videoMode) -WorkingDirectory $obsBin -WindowStyle Hidden -PassThru -RedirectStandardOutput "$buildRoot/video-probe.log" -RedirectStandardError "$buildRoot/video-probe-errors.log"
    $receiverProbe = Start-Process -FilePath (Get-Command dotnet).Source -ArgumentList @("`"$receiverDll`"", '--port', '5018', '--obs-pipe', 'camsure-test-video', '--duration-seconds', '12') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$buildRoot/receiver-probe.log" -RedirectStandardError "$buildRoot/receiver-probe-errors.log"
    Start-Sleep -Milliseconds 600
    & dotnet $receiverDll --replay-test $fixture 5018
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic RTP sender failed' }
    if (!$videoProbe.WaitForExit(20000) -or !$receiverProbe.WaitForExit(20000)) { throw 'Probe worker failed to stop' }
    Get-Content "$buildRoot/video-probe.log" | Select-Object -Last 8
    Get-Content "$buildRoot/video-probe-errors.log"
    if ($videoProbe.ExitCode -ne 0 -or $receiverProbe.ExitCode -ne 0) { throw 'Video integration probe failed' }
} finally {
    foreach ($process in @($videoProbe, $receiverProbe)) {
        if ($null -ne $process -and !$process.HasExited) { Stop-Process -Id $process.Id }
    }
    $env:PATH = $oldPath
}
