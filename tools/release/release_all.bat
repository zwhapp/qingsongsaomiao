@echo off
REM ============================================================
REM  release_all.bat — 轻松扫描一键全自动发布
REM  打包 -> GitHub Release -> Gitee Release -> 网页自动同步
REM
REM  用法:
REM    release_all.bat 1.4.23
REM    release_all.bat 1.4.23 --notes "连拍修复"
REM    release_all.bat 1.4.23 --dry-run     只检查不落地
REM    release_all.bat 1.4.23 --no-build    跳过打包
REM
REM  首次准备(只需一次):
REM    1) winget install GitHub.cli
REM    2) gh auth login --hostname github.com --git-protocol https --web
REM       (浏览器授权, 不需要 PAT; PowerShell 里跑本 bat 记得加 .\ 前缀)
REM    3) setx GITEE_TOKEN "你的Gitee令牌"   然后重开本窗口
REM ============================================================
setlocal
cd /d %~dp0..\..\.

REM 刷新 PATH (gh 是新装的, 当前窗口可能读不到)
set "PATH=%PATH%;C:\Program Files\GitHub CLI"

REM 逐个候选实测可用 python (排除微软商店占位程序)
set "PYEXE="
for %%P in (
  "C:\Users\zwhco\.workbuddy\binaries\python\versions\3.13.12\python.exe"
) do if not defined PYEXE if exist %%P (%%P --version >nul 2>&1 && set "PYEXE=%%P")
if not defined PYEXE (
  py --version >nul 2>&1 && set "PYEXE=py"
)
if not defined PYEXE (
  python --version >nul 2>&1 && set "PYEXE=python"
)
if not defined PYEXE (
  echo [ERROR] 找不到可用的 Python。请安装 Python 3 或检查本机路径。
  exit /b 1
)

"%PYEXE%" tools\release\release_all.py %*
exit /b %ERRORLEVEL%
