# Publish the existing receiver into the plugin's data package before native stage.
# Framework-dependent x64 Windows apphost: .NET 10 runtime required on the PC.
$ErrorActionPreference = 'Stop'
$receiverProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../tools/windows-rtp-receiver/CamSure.RtpReceiver.csproj'))
& dotnet publish $receiverProject -c Release --no-self-contained -o (Join-Path $PSScriptRoot 'data/receiver')
if ($LASTEXITCODE -ne 0) { throw "CamSure receiver publish failed: $LASTEXITCODE" }
