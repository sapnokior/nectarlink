# SPDX-License-Identifier: GPL-3.0-or-later
<#
.SYNOPSIS
    Downloads and installs the latest NectarLink release for Windows and/or Android.

.DESCRIPTION
    1. Queries the GitHub Releases API to find the latest version.
    2. Compares it against the currently installed Windows version (if any).
    3. If a newer version is found (or -Force is used):
       - Downloads the Windows installer (.exe) and Android APK from GitHub.
       - Verifies each download against SHA256SUMS.txt.
       - Silently installs the Windows app.
       - Installs the Android APK to a connected device via ADB (if -Android).
    4. Sends a Windows notification with the result.

.PARAMETER Force
    Install even if the current version matches the latest release.

.PARAMETER WindowsOnly
    Skip Android APK download and install.

.PARAMETER Android
    Also download and install the Android APK via ADB.

.PARAMETER Device
    ADB device serial to install the APK on. If omitted and multiple devices
    are connected, you will be prompted to choose one.

.PARAMETER DownloadDir
    Where to save downloaded files. Defaults to a temp folder under %TEMP%.

.EXAMPLE
    .\install-update.ps1
    .\install-update.ps1 -Android
    .\install-update.ps1 -Android -Device "ZN5227FQWX"
    .\install-update.ps1 -Force -Android
#>
param(
    [switch]$Force,
    [switch]$WindowsOnly,
    [switch]$Android,
    [string]$Device,
    [string]$DownloadDir = (Join-Path $env:TEMP 'NectarLinkUpdate')
)

$ErrorActionPreference = 'Stop'
$repo = 'thtbee/nectarlink'
$apiUrl = "https://api.github.com/repos/$repo/releases/latest"
$notifyScript = "C:\Users\adars\.agy-hooks\notify-ready.ps1"

# ── Helpers ──────────────────────────────────────────────────────────────────

function Write-Step([string]$msg) {
    Write-Host "`n==> $msg" -ForegroundColor Cyan
}

function Write-Ok([string]$msg) {
    Write-Host "  ✓ $msg" -ForegroundColor Green
}

function Write-Warn([string]$msg) {
    Write-Host "  ⚠ $msg" -ForegroundColor Yellow
}

function Send-Notification([string]$title, [string]$body) {
    if (Test-Path $notifyScript) {
        powershell.exe -ExecutionPolicy Bypass -NoProfile -File $notifyScript `
            -Title $title -Message $body | Out-Null
    }
}

function Get-InstalledWindowsVersion {
    # NectarLink installs into HKCU (per-user NSIS installer)
    $regPaths = @(
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    foreach ($base in $regPaths) {
        if (-not (Test-Path $base)) { continue }
        $entry = Get-ChildItem $base -ErrorAction SilentlyContinue |
            Get-ItemProperty -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -match 'NectarLink' } |
            Select-Object -First 1
        if ($entry) { return $entry.DisplayVersion }
    }
    return $null
}

function Compare-Versions([string]$a, [string]$b) {
    # Returns 1 if $a > $b, -1 if $a < $b, 0 if equal (semver numeric parts only)
    $pa = ($a -replace '[^0-9.]', '') -split '\.' | ForEach-Object { [int]$_ }
    $pb = ($b -replace '[^0-9.]', '') -split '\.' | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt [Math]::Max($pa.Count, $pb.Count); $i++) {
        $x = if ($i -lt $pa.Count) { $pa[$i] } else { 0 }
        $y = if ($i -lt $pb.Count) { $pb[$i] } else { 0 }
        if ($x -gt $y) { return 1 }
        if ($x -lt $y) { return -1 }
    }
    return 0
}

function Verify-Sha256([string]$file, [string]$sumsFile, [string]$expectedName) {
    $lines = Get-Content $sumsFile
    $match = $lines | Where-Object { $_ -match $expectedName } | Select-Object -First 1
    if (-not $match) { throw "SHA256 entry not found for '$expectedName' in SHA256SUMS.txt" }
    $expected = ($match -split '\s+')[0].ToUpper()
    $actual   = (Get-FileHash $file -Algorithm SHA256).Hash.ToUpper()
    if ($actual -ne $expected) {
        throw "SHA256 mismatch for $expectedName!`n  Expected: $expected`n  Actual:   $actual"
    }
    Write-Ok "SHA256 verified: $expectedName"
}

function Select-AdbDevice {
    $raw = & adb devices 2>&1 | Where-Object { $_ -match '\bdevice\b' -and $_ -notmatch 'List of' }
    $serials = $raw | ForEach-Object { ($_ -split '\s+')[0] }
    if ($serials.Count -eq 0) { throw "No ADB devices connected. Connect a device and retry." }
    if ($serials.Count -eq 1) { return $serials[0] }

    Write-Host "`nMultiple ADB devices found:" -ForegroundColor Yellow
    for ($i = 0; $i -lt $serials.Count; $i++) {
        $model = (& adb -s $serials[$i] shell getprop ro.product.model 2>$null).Trim()
        Write-Host "  [$($i+1)] $($serials[$i])  ($model)"
    }
    $choice = Read-Host "Select device number"
    $idx = [int]$choice - 1
    if ($idx -lt 0 -or $idx -ge $serials.Count) { throw "Invalid selection." }
    return $serials[$idx]
}

# ── Step 1: Fetch release info ────────────────────────────────────────────────

Write-Step "Checking GitHub for latest NectarLink release"
$headers = @{ 'User-Agent' = 'NectarLink-Updater/1.0' }
try {
    $release = Invoke-RestMethod -Uri $apiUrl -Headers $headers
} catch {
    throw "Failed to reach GitHub API: $_"
}

$latestTag     = $release.tag_name          # e.g. "v0.1.5"
$latestVersion = $latestTag.TrimStart('v')  # e.g. "0.1.5"
Write-Ok "Latest release: $latestTag"

# ── Step 2: Compare with installed version ────────────────────────────────────

Write-Step "Checking installed Windows version"
$installed = Get-InstalledWindowsVersion
if ($installed) {
    Write-Host "  Installed: v$installed"
    $cmp = Compare-Versions $latestVersion $installed
    if ($cmp -le 0 -and -not $Force) {
        Write-Ok "Already up to date (v$installed). Use -Force to reinstall."
        Send-Notification "✅ NectarLink Up To Date" "Version v$installed is already the latest. No update needed."
        exit 0
    }
    if ($cmp -gt 0) {
        Write-Host "  ↑ Update available: v$installed → v$latestVersion" -ForegroundColor Green
    } else {
        Write-Warn "Forcing reinstall of v$latestVersion"
    }
} else {
    Write-Host "  NectarLink not detected — fresh install of v$latestVersion"
}

# ── Step 3: Download assets ───────────────────────────────────────────────────

Write-Step "Downloading release assets for v$latestVersion"
$null = New-Item -ItemType Directory -Force -Path $DownloadDir

# Locate expected asset names
$arch              = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x64' }
$exeName           = "Nectarlink-$latestVersion-$arch-setup.exe"
$apkName           = "Nectarlink-$latestVersion-android.apk"
$sumsName          = 'SHA256SUMS.txt'

$assets    = $release.assets
$exeAsset  = $assets | Where-Object { $_.name -eq $exeName }  | Select-Object -First 1
$apkAsset  = $assets | Where-Object { $_.name -eq $apkName }  | Select-Object -First 1
$sumsAsset = $assets | Where-Object { $_.name -eq $sumsName } | Select-Object -First 1

if (-not $sumsAsset) { throw "SHA256SUMS.txt not found in release assets." }
if (-not $exeAsset)  { throw "$exeName not found in release assets." }

# Download SHA256SUMS.txt first
$sumsPath = Join-Path $DownloadDir $sumsName
Write-Host "  Downloading $sumsName ..."
Invoke-WebRequest -Uri $sumsAsset.browser_download_url -OutFile $sumsPath -Headers $headers

# Download Windows installer
$exePath = Join-Path $DownloadDir $exeName
if (Test-Path $exePath) {
    Write-Warn "$exeName already in download dir — skipping re-download"
} else {
    Write-Host "  Downloading $exeName ..."
    Invoke-WebRequest -Uri $exeAsset.browser_download_url -OutFile $exePath -Headers $headers
}
Verify-Sha256 $exePath $sumsPath $exeName

# Download Android APK (only if requested)
$apkPath = $null
if ($Android -and -not $WindowsOnly) {
    if (-not $apkAsset) { throw "$apkName not found in release assets." }
    $apkPath = Join-Path $DownloadDir $apkName
    if (Test-Path $apkPath) {
        Write-Warn "$apkName already in download dir — skipping re-download"
    } else {
        Write-Host "  Downloading $apkName ..."
        Invoke-WebRequest -Uri $apkAsset.browser_download_url -OutFile $apkPath -Headers $headers
    }
    Verify-Sha256 $apkPath $sumsPath $apkName
}

# ── Step 4: Install Windows app ───────────────────────────────────────────────

Write-Step "Installing Windows app: $exeName"
# NSIS silent install: /S = silent, /CURRENTUSER = no UAC if user-scoped
$proc = Start-Process -FilePath $exePath -ArgumentList '/S' -Wait -PassThru
if ($proc.ExitCode -ne 0) {
    throw "Installer exited with code $($proc.ExitCode)"
}
Write-Ok "Windows app installed (v$latestVersion)"

# ── Step 5: Install Android APK ───────────────────────────────────────────────

if ($apkPath -and (Test-Path $apkPath)) {
    Write-Step "Installing Android APK via ADB"

    $serial = if ($Device) { $Device } else { Select-AdbDevice }
    Write-Host "  Device: $serial"

    $model = (& adb -s $serial shell getprop ro.product.model 2>$null).Trim()
    Write-Host "  Model : $model"

    # -r = replace existing, -d = allow version downgrade only if forced
    $adbArgs = @('-s', $serial, 'install', '-r', $apkPath)
    & adb @adbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)" }
    Write-Ok "Android APK installed on $model ($serial)"
}

# ── Done ──────────────────────────────────────────────────────────────────────

Write-Step "Done!"
$summary = if ($installed) { "Updated v$installed → v$latestVersion" } else { "Installed v$latestVersion fresh" }
Write-Host "`n  $summary" -ForegroundColor Green
Write-Host "  Downloads kept in: $DownloadDir`n"

$notifBody = "🎉 $summary on Windows"
if ($apkPath) { $notifBody += " + Android 📱" }
$notifBody += " | SHA256 verified ✅"
Send-Notification "🚀 NectarLink Updated to v$latestVersion!" $notifBody
