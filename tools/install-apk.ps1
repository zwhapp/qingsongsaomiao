# 连接手机后，一键安装最新 APK 并启动
#
# 用法：.\install-apk.ps1
#       或指定 APK：.\install-apk.ps1 -Apk "D:\path\to\app.apk"

param(
    [string]$Apk = ""
)

$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# 默认取最新构建的 release 包。
# 产物文件名形如 DocScanPro-v1.1.0-2-release.apk，所以按时间排序取最新那个，
# 不再硬编码版本号 —— 免得上线新版本时脚本失效。
if ([string]::IsNullOrWhiteSpace($Apk)) {
    $releaseDir = Join-Path $projectRoot "app\build\outputs\apk\release"
    $latest = Get-ChildItem -Path $releaseDir -Filter "*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $latest) {
        Write-Host "[X] 找不到 APK，请先构建：.\gradlew assembleRelease" -ForegroundColor Red
        exit 1
    }
    $Apk = $latest.FullName
}

Write-Host "APK: $(Split-Path -Leaf $Apk)" -ForegroundColor Cyan
if (-not (Test-Path $Apk)) {
    Write-Host "[X] APK 不存在：$Apk" -ForegroundColor Red
    exit 1
}
$sizeMb = [math]::Round((Get-Item $Apk).Length / 1MB, 1)
Write-Host "大小: ${sizeMb} MB" -ForegroundColor Cyan
Write-Host ""

# 检查设备
$raw = & $adb devices -l 2>&1
$dev = $raw | Where-Object { $_ -match "^\S+\s+device(\s|$)" }

if (-not $dev) {
    Write-Host "[X] 没有已授权设备。先跑 .\check-usb.ps1 排查连接。" -ForegroundColor Red
    foreach ($l in $raw) { Write-Host "    $l" }
    exit 1
}

$serial = ($dev -split "\s+")[0]
Write-Host "[OK] 设备: $serial" -ForegroundColor Green
Write-Host ""

# 安装（-r 覆盖安装，保留数据）
Write-Host "正在安装..." -ForegroundColor Yellow
& $adb -s $serial install -r $Apk

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[!] 安装失败。常见原因：" -ForegroundColor Red
    Write-Host "    · 签名不一致 —— 需先卸载旧版：adb uninstall com.openscan.docscan"
    Write-Host "    · 版本号未升级 —— 已用 -r 覆盖，若仍失败请卸载重装"
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "[OK] 安装完成，正在启动..." -ForegroundColor Green
& $adb -s $serial shell monkey -p com.openscan.docscan -c android.intent.category.LAUNCHER 1 | Out-Null

Write-Host ""
Write-Host "提示：可直接看实时日志：" -ForegroundColor Cyan
Write-Host "  & `"$adb`" -s $serial logcat -s DocSegmenter:V ScanPipeline:V SelfCheck:V"
