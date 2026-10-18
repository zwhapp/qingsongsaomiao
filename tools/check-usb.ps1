# 三星手机 USB 连接诊断 —— 一键跑，自动判断卡在哪一层
#
# 用法：右键本文件 → 使用 PowerShell 运行
#   或在 PowerShell 里执行：  .\check-usb.ps1
#
# 它会依次检查：
#   1. adb 是否可用
#   2. 系统是否枚举到三星设备（VID_04E8）
#   3. adb 是否看到设备、授权状态如何
#   4. 如果都正常，直接打印可用的设备序列号

$ErrorActionPreference = "Continue"
$adb = "D:\Android\Sdk\platform-tools\adb.exe"

function Line($msg) { Write-Host $msg }

Line "============================================"
Line " 三星 S23U USB 连接诊断"
Line "============================================"
Line ""

# --- 1. adb 可用性 ---
if (-not (Test-Path $adb)) {
    Line "[X] 找不到 adb：$adb"
    Line "    请检查 Android SDK platform-tools 是否已安装。"
    exit 1
}
$ver = & $adb version 2>&1 | Select-Object -First 1
Line "[1] adb 工具：OK"
Line "    $ver"
Line ""

# --- 2. 系统是否枚举到三星 USB 设备 ---
$samsung = Get-PnpDevice -PresentOnly |
    Where-Object { $_.InstanceId -like "*VID_04E8*" } |
    Select-Object Status, Class, FriendlyName, InstanceId

if ($samsung) {
    Line "[2] 系统已识别到三星 USB 设备："
    foreach ($d in $samsung) {
        Line "    [$($d.Status)] $($d.FriendlyName)"
        Line "        $($d.InstanceId)"
    }
} else {
    Line "[2] [X] 系统未枚举到任何三星设备（VID_04E8）"
    Line "    => 电脑在物理层就没感知到手机，问题在「线 / 口 / USB 模式」"
    Line "       请依次尝试：换原装数据线、插主板后置口、手机下拉通知栏改「传输文件」"
}
Line ""

# --- 3. 所有异常状态的 USB 设备（驱动问题会显示在这里）---
$bad = Get-PnpDevice -PresentOnly |
    Where-Object { $_.Status -ne "OK" -and $_.Class -in @("USB","USBDevice","WPD","AndroidUsbDeviceClass","Portable Devices","Other") }
if ($bad) {
    Line "[3] 发现状态异常的 USB 设备（可能是驱动没装好）："
    foreach ($d in $bad) {
        Line "    [$($d.Status)] $($d.FriendlyName)  [$($d.Class)]"
    }
} else {
    Line "[3] 无异常状态的 USB 设备"
}
Line ""

# --- 4. adb 是否看到设备 ---
& $adb kill-server  | Out-Null
Start-Sleep -Milliseconds 800
& $adb start-server | Out-Null
Start-Sleep -Milliseconds 800

$raw = & $adb devices -l 2>&1
Line "[4] adb devices 输出："
foreach ($l in $raw) { Line "    $l" }
Line ""

# --- 结论 ---
$hasDevice = $raw | Where-Object { $_ -match "^\S+\s+device(\s|$)" }
$hasUnauth = $raw | Where-Object { $_ -match "^\S+\s+unauthorized" }
$hasOffline = $raw | Where-Object { $_ -match "^\S+\s+offline" }

Line "============================================"
if ($hasDevice) {
    $serial = ($hasDevice -split "\s+")[0]
    Line " [OK] 连接成功！设备序列号：$serial"
    Line ""
    Line " 可以直接安装新版 APP 了："
    Line "   & `"$adb`" install -r <APK路径>"
} elseif ($hasUnauth) {
    Line " [!!] 手机已连上，但「USB 调试未授权」"
    Line "      请看手机屏幕，点弹窗里的「允许」，勾选「一律允许」"
} elseif ($hasOffline) {
    Line " [!!] 设备处于 offline 状态"
    Line "      请重新插拔数据线，或在开发者选项里关闭再打开 USB 调试"
} elseif ($samsung) {
    Line " [!!] 系统看到三星设备，但 adb 看不到"
    Line "      => 多半是 USB 调试没开，或 USB 模式还停在「仅充电」"
    Line "      请：设置 → 开发者选项 → 打开「USB 调试」；"
    Line "          下拉通知栏 → USB 用途 → 改成「传输文件」"
} else {
    Line " [X] 仍未检测到手机（物理层未连接）"
    Line "     重点排查："
    Line "       1. 换一根【能传数据】的原装线（当前这根可能只能充电）"
    Line "       2. 换插主板【后置】USB 口，不要用前置面板或 Hub"
    Line "       3. 手机下拉通知栏，USB 用途从「仅充电」改成「传输文件」"
    Line "       4. 手机有没有摔过/进过水？充电口接触不良也会这样"
}
Line "============================================"
