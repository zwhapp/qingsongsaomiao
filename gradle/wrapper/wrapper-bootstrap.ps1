#
# 引导脚本：下载 Gradle 发行版并从中提取 gradle-wrapper.jar。
#
# 之所以要这么绕：gradle-wrapper.jar 是二进制，部分网络环境（企业代理、
# 安全策略）会拦截直接从 GitHub raw 拉取 .jar 的请求，但允许下载
# Gradle 官方发行版 zip。这里利用后者间接取得 wrapper jar。
#
# 由 gradlew.bat 在检测到 wrapper jar 缺失时自动调用；也可手动执行。
#

$ErrorActionPreference = 'Stop'

$appHome     = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$wrapperJar  = Join-Path $PSScriptRoot 'gradle-wrapper.jar'
$propsFile   = Join-Path $PSScriptRoot 'gradle-wrapper.properties'

if (Test-Path $wrapperJar) {
    Write-Host "gradle-wrapper.jar 已存在，跳过。"
    exit 0
}

if (-not (Test-Path $propsFile)) {
    Write-Host "错误：找不到 $propsFile" -ForegroundColor Red
    exit 1
}

# 从 properties 里解析 distributionUrl
$distUrl = $null
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*distributionUrl\s*=\s*(.+)$') {
        $distUrl = $Matches[1].Trim() -replace '\\', ''
    }
}

if (-not $distUrl) {
    Write-Host "错误：无法从 gradle-wrapper.properties 解析 distributionUrl" -ForegroundColor Red
    exit 1
}

$distName = Split-Path $distUrl -Leaf
$tempDir  = Join-Path $env:TEMP 'docscan-gradle-bootstrap'
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
$tempZip  = Join-Path $tempDir $distName

Write-Host "Gradle 发行版：$distUrl"
Write-Host "下载中（首次约 130MB，请耐心等待）…"

try {
    # 优先用 curl（Windows 10 1803+ 自带），回退到 WebClient
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & curl.exe -L --fail --progress-bar -o $tempZip $distUrl
        if ($LASTEXITCODE -ne 0) { throw "curl 下载失败，退出码 $LASTEXITCODE" }
    } else {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $distUrl -OutFile $tempZip -UseBasicParsing
    }
} catch {
    Write-Host "下载失败：$_" -ForegroundColor Red
    Write-Host "请检查网络，或改用 Android Studio 打开本工程。" -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $tempZip) -or (Get-Item $tempZip).Length -lt 1MB) {
    Write-Host "下载结果异常，文件过小或不存在。" -ForegroundColor Red
    exit 1
}

Write-Host "解压以提取 wrapper jar…"
$extractDir = Join-Path $tempDir 'extract'
if (Test-Path $extractDir) { Remove-Item -Recurse -Force $extractDir }
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($tempZip)
try {
    $entry = $zip.Entries | Where-Object {
        $_.FullName -match '^gradle-[^/]+/lib/plugins/gradle-wrapper-main-.*\.jar$'
    } | Select-Object -First 1

    if (-not $entry) {
        Write-Host "未能在发行版中找到 gradle-wrapper-main-*.jar" -ForegroundColor Red
        exit 1
    }

    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $wrapperJar, $true)
} finally {
    $zip.Dispose()
}

Write-Host "wrapper jar 已就绪：$wrapperJar" -ForegroundColor Green
exit 0
