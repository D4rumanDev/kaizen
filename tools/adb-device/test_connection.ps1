$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$Device = $env:ADB_DEVICE
$Package = "app.kaizen"

if (Test-Path "$ScriptDir\.env") {
    Get-Content "$ScriptDir\.env" | ForEach-Object {
        if ($_ -match "^\s*ADB_DEVICE\s*=\s*(.+)$") { $Device = $matches[1].Trim() }
        if ($_ -match "^\s*ADB_PACKAGE\s*=\s*(.+)$") { $Package = $matches[1].Trim() }
    }
}

if (-not $Device) {
    Write-Error "Define ADB_DEVICE en tools/adb-device/.env (ver .env.example)"
    exit 1
}

Write-Host "[tools/adb-device] Comprobando binario adb..."
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCmd) {
    Write-Warning "WARN: 'adb' no está disponible en el PATH actual de PowerShell."
    exit 0
}

Write-Host "[tools/adb-device] Conectando a $Device..."
& adb connect $Device

Write-Host "[tools/adb-device] Dispositivos ADB:"
& adb devices -l

