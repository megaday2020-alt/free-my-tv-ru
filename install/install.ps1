# Installs Free My TV on an Android TV / Google TV from Windows.
# Usage (PowerShell):  .\install.ps1 -Ip 192.168.1.50 [-Apk ..\FreeMyTV-generic.apk]
# It downloads Google's platform-tools (adb) next to this script if adb is not installed, connects to the TV,
# installs the app and tells you what to tap on the TV.
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [string]$Apk = (Join-Path $PSScriptRoot "..\FreeMyTV-generic.apk")
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not (Test-Path $Apk)) { throw "APK not found: $Apk (build it with ./build.sh --generic, or take it from the GitHub release)" }

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $dir = Join-Path $PSScriptRoot "platform-tools"
    $adb = Join-Path $dir "adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Host "Downloading Google's platform-tools (adb), about 8 MB, from dl.google.com ..."
        $zip = Join-Path $PSScriptRoot "platform-tools.zip"
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive $zip -DestinationPath $PSScriptRoot -Force
        Remove-Item $zip
    }
}

Write-Host "Connecting to $Ip ... (on the TV, tick 'Always allow' when it asks 'Allow USB debugging?')"
& $adb connect "${Ip}:5555"
Start-Sleep 3
& $adb devices
Write-Host "Installing $Apk ..."
& $adb -s "${Ip}:5555" install -r $Apk
Write-Host ""
Write-Host "Done. On the TV: open 'Free My TV' -> 'Home Screen' -> 'Free My TV' (accept the debugging prompt once more)."
Write-Host "Afterwards you can turn USB debugging off, except for the quick buttons and the night maintenance, which use it."
