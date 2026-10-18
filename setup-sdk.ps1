# setup-sdk.ps1 -- install the Android SDK components this project needs.
#
# ASCII-only on purpose: PowerShell 5.1 decodes a BOM-less UTF-8 file as ANSI,
# which corrupts non-ASCII characters and produces bogus parser errors.
#
# NOTE ON THE JDK:
#   The bundled JBR in Android Studio 2026.2 is JDK 25, but this project uses
#   Gradle 8.9 + AGP 8.7.3 which only support JDK 17..22. sdkmanager itself
#   works fine on JBR 25, so it is used here to install the SDK packages.
#   Gradle builds, however, MUST run on JDK 17 -- that is pinned separately in
#   the project's gradle.properties via org.gradle.java.home.

param(
    [string]$SdkRoot = "D:\Android\Sdk",
    [string]$StudioRoot = "C:\Program Files\Android\Android Studio"
)

$ErrorActionPreference = "Stop"

Write-Host "=== Android SDK component setup ===" -ForegroundColor Cyan
Write-Host "SDK root    : $SdkRoot"
Write-Host "Studio root : $StudioRoot"
Write-Host ""

# ---------------------------------------------------------------- locate JBR
$JbrHome = Join-Path $StudioRoot "jbr"
if (-not (Test-Path $JbrHome)) {
    Write-Host "[X] JetBrains Runtime not found at $JbrHome" -ForegroundColor Red
    Write-Host "    Android Studio does not appear to be installed there." -ForegroundColor Red
    Write-Host "    Re-run with -StudioRoot pointing at the real install dir." -ForegroundColor Red
    exit 1
}
$JavaExe = Join-Path $JbrHome "bin\java.exe"
if (-not (Test-Path $JavaExe)) {
    Write-Host "[X] java.exe not found under $JbrHome" -ForegroundColor Red
    exit 1
}
Write-Host "[1/4] Found JBR: $JbrHome" -ForegroundColor Green
& $JavaExe -version 2>&1 | ForEach-Object { Write-Host "      $_" }

# ------------------------------------------------------- locate sdkmanager
if (-not (Test-Path $SdkRoot)) {
    New-Item -ItemType Directory -Path $SdkRoot -Force | Out-Null
    Write-Host "[2/4] Created SDK root: $SdkRoot" -ForegroundColor Green
} else {
    Write-Host "[2/4] SDK root already exists: $SdkRoot" -ForegroundColor Green
}

$SdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"

if (-not (Test-Path $SdkManager)) {
    Write-Host ""
    Write-Host "      sdkmanager not found -- installing command-line tools first." -ForegroundColor Yellow

    $cltVersion = "13114758"
    $cltZip = Join-Path $env:TEMP "cmdline-tools.zip"
    $cltUrl = "https://dl.google.com/android/repository/commandlinetools-win-$cltVersion" + "_latest.zip"

    Write-Host "      downloading commandlinetools-win-$cltVersion" + "_latest.zip ..."
    try {
        $ProgressPreference = "SilentlyContinue"
        Invoke-WebRequest -Uri $cltUrl -OutFile $cltZip -UseBasicParsing
    } catch {
        Write-Host "[X] Download failed: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "    URL: $cltUrl" -ForegroundColor Red
        exit 1
    }

    $extractTo = Join-Path $env:TEMP "clt-extract"
    if (Test-Path $extractTo) { Remove-Item $extractTo -Recurse -Force }
    Expand-Archive -Path $cltZip -DestinationPath $extractTo -Force

    $target = Join-Path $SdkRoot "cmdline-tools\latest"
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    # The zip unpacks to "cmdline-tools\"; move its contents up a level
    $inner = Join-Path $extractTo "cmdline-tools"
    Copy-Item -Path (Join-Path $inner "*") -Destination $target -Recurse -Force
    Remove-Item $cltZip -Force -ErrorAction SilentlyContinue
    Remove-Item $extractTo -Recurse -Force -ErrorAction SilentlyContinue

    Write-Host "      command-line tools installed." -ForegroundColor Green
}

if (-not (Test-Path $SdkManager)) {
    Write-Host "[X] sdkmanager still missing at $SdkManager" -ForegroundColor Red
    exit 1
}
Write-Host "[3/4] sdkmanager ready" -ForegroundColor Green

# ------------------------------------------------------------ install packages
# Minimal set for DocScanPro:
#   platform-tools  -> adb (install APKs to a connected phone)
#   platforms;35    -> compileSdk = 35
#   build-tools;35  -> matches compileSdk, required by AGP
#   ndk + cmake     -> OpenCV native bits / any externalNativeBuild
$packages = @(
    "platform-tools",
    "platforms;android-35",
    "build-tools;35.0.0",
    "ndk;28.2.13676358",
    "cmake;3.31.6"
)

Write-Host ""
Write-Host "[4/4] Installing SDK packages (accepting licenses automatically):"
foreach ($p in $packages) { Write-Host "      - $p" }
Write-Host ""

$env:JAVA_HOME = $JbrHome

# Pipe "y" repeatedly to auto-accept the license prompts.
$yes = ("y`r`n" * 50)
$yes | & $SdkManager --sdk_root="$SdkRoot" --licenses 2>&1 |
    ForEach-Object { if ($_ -match "All SDK package licenses accepted") { Write-Host "      licenses accepted" -ForegroundColor Green } }

foreach ($p in $packages) {
    Write-Host "      installing $p ..."
    & $SdkManager --sdk_root="$SdkRoot" $p 2>&1 |
        Where-Object { $_ -notmatch "^\s*$" } |
        ForEach-Object { Write-Host "        $_" }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "      [!] $p reported a non-zero exit code ($LASTEXITCODE)" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== Installed SDK contents ===" -ForegroundColor Cyan
foreach ($d in @("platform-tools", "platforms", "build-tools", "ndk", "cmake")) {
    $full = Join-Path $SdkRoot $d
    if (Test-Path $full) {
        $items = Get-ChildItem $full -Name -ErrorAction SilentlyContinue
        Write-Host ("  {0,-16} {1}" -f $d, ($items -join ", ")) -ForegroundColor Green
    } else {
        Write-Host ("  {0,-16} (missing)" -f $d) -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "Done. adb is at: $SdkRoot\platform-tools\adb.exe" -ForegroundColor Cyan
Write-Host "The project's local.properties already points at $SdkRoot" -ForegroundColor Cyan
