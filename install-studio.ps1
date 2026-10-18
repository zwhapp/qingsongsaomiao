# install-studio.ps1 -- install Android Studio silently to D:\Android
#
# ASCII-only on purpose: PowerShell 5.1 decodes a BOM-less UTF-8 file as ANSI,
# which corrupts non-ASCII characters and produces bogus parser errors.
#
# The Android Studio Windows installer is an NSIS installer, so it accepts
# /S (silent) and /D=<dir> (target directory). /D must be LAST and UNQUOTED.

param(
    [string]$Installer = "$env:USERPROFILE\Downloads\android-studio-2026.2.1.8-windows.exe",
    [string]$TargetDir = "D:\Android\Android Studio",
    [string]$SdkRoot = "D:\Android\Sdk"
)

$ErrorActionPreference = "Stop"

Write-Host "=== Android Studio silent install ===" -ForegroundColor Cyan
Write-Host "Installer : $Installer"
Write-Host "Target    : $TargetDir"
Write-Host ""

if (-not (Test-Path $Installer)) {
    Write-Host "[X] Installer not found: $Installer" -ForegroundColor Red
    exit 1
}

$size = (Get-Item $Installer).Length
Write-Host ("      installer size: {0:N1} MB" -f ($size / 1MB))
if ($size -lt 1400MB) {
    Write-Host "[X] File looks truncated (expected ~1442 MB). Re-download it." -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "D:\Android")) {
    New-Item -ItemType Directory -Path "D:\Android" -Force | Out-Null
}

Write-Host "      launching installer (this takes a few minutes)..." -ForegroundColor Yellow

# Silent install. Note: /D has no quotes and must be the final argument.
$p = Start-Process -FilePath $Installer `
    -ArgumentList "/S", "/D=$TargetDir" `
    -PassThru -Wait

Write-Host "      installer exit code: $($p.ExitCode)"
if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) {
    Write-Host "[!] Non-zero exit code. 3010 means 'reboot required' which is fine." -ForegroundColor Yellow
}

# ------------------------------------------------------------- verify layout
Write-Host ""
Write-Host "=== Verification ===" -ForegroundColor Cyan

$checks = @(
    @{ Name = "studio64.exe";    Path = Join-Path $TargetDir "bin\studio64.exe" },
    @{ Name = "JetBrains Runtime"; Path = Join-Path $TargetDir "jbr\bin\java.exe" },
    @{ Name = "Gradle plugin";   Path = Join-Path $TargetDir "plugins\gradle" }
)
$ok = $true
foreach ($c in $checks) {
    if (Test-Path $c.Path) {
        Write-Host ("  [ok] " + $c.Name) -ForegroundColor Green
    } else {
        Write-Host ("  [!!] " + $c.Name + " -> " + $c.Path) -ForegroundColor Yellow
        $ok = $false
    }
}

if (-not $ok) {
    Write-Host ""
    Write-Host "Some expected files are missing. The install may have gone elsewhere." -ForegroundColor Yellow
    Write-Host "Check: C:\Program Files\Android\Android Studio" -ForegroundColor Yellow
    exit 1
}

# ---------------------------------------------- point the SDK at D drive
Write-Host ""
Write-Host "=== SDK location ===" -ForegroundColor Cyan
$ideaProps = Join-Path $env:APPDATA "Google\AndroidStudio2026.2\options"
if (-not (Test-Path $ideaProps)) {
    New-Item -ItemType Directory -Path $ideaProps -Force | Out-Null
}

# The IDE stores the SDK path in its own config; the project reads
# local.properties. We write both so nothing prompts on first open.
$proj = "C:\Users\zwhco\WorkBuddy\2026-10-14-11-24-59\DocScanPro\local.properties"
if (Test-Path (Split-Path $proj)) {
    $escaped = $SdkRoot -replace '\\', '\\'
    # sdk.dir uses Java properties escaping: D\:\\Android\\Sdk
    $line = "sdk.dir=" + ($SdkRoot -replace ':', '\:' -replace '\\', '\\')
    Set-Content -Path $proj -Value @(
        "## Auto-generated. Not for version control (see .gitignore)."
        "## Android Studio will rewrite this if the path is wrong."
        ""
        $line
    ) -Encoding ASCII
    Write-Host "  wrote $proj" -ForegroundColor Green
    Get-Content $proj | ForEach-Object { Write-Host "      $_" }
}

Write-Host ""
Write-Host "Android Studio installed to: $TargetDir" -ForegroundColor Cyan
Write-Host "Next step: run setup-sdk.ps1 to install the SDK components." -ForegroundColor Cyan
