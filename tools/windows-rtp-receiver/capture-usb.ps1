param([string]$PhoneAddress = '10.208.162.142', [int]$Port = 5004)
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open PowerShell as Administrator, then run this script again. Windows Packet Monitor requires elevation.'
}
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$captureBase = Join-Path $repoRoot ('docs\evidence\usb-capture-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$filterName = 'CamSure-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
function Invoke-PacketMonitor([string[]]$Arguments) {
    & pktmon @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Packet Monitor failed: $($Arguments -join ' ')" }
}
$filterAdded = $false
$captureStarted = $false
try {
    # Preserve other filters/sessions; never reset or stop an existing capture.
    Invoke-PacketMonitor -Arguments @('filter','add',$filterName,'-t','UDP','-i',$PhoneAddress,'-p',"$Port")
    $filterAdded = $true
    Invoke-PacketMonitor -Arguments @('start','--capture','--comp','nics','--pkt-size','160','--file-size','128','--file-name',"$captureBase.etl")
    $captureStarted = $true
    Write-Host 'Capture started. Keep CamSure streaming and move the camera for 60 seconds.'
    Start-Sleep -Seconds 60
} finally {
    if ($captureStarted) {
        & pktmon counters | Out-File "$captureBase-counters.txt" -Encoding utf8
        & pktmon stop
    }
    if ($filterAdded) { & pktmon filter remove $filterName }
}
Invoke-PacketMonitor -Arguments @('etl2pcap',"$captureBase.etl",'--out',"$captureBase.pcapng")
Write-Host "Capture saved: $captureBase.pcapng"
