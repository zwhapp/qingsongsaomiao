@echo off
REM ============================================================
REM  publish_gitee.bat - 一键发布合规分发版 APK 到 Gitee Release
REM  前置: 设置用户级环境变量 GITEE_TOKEN
REM       (https://gitee.com/profile/personal_access_tokens, 勾选 projects)
REM  用法: .\publish_gitee.bat                自动取 outputs/ 最新分发版
REM        .\publish_gitee.bat --dry-run      只检查不落地
REM        .\publish_gitee.bat --list         查看线上附件占用
REM        .\publish_gitee.bat --prune 10     只保留最近 10 个 release
REM  注意: PowerShell 下必须加 .\ 前缀(当前目录不在命令搜索路径)
REM ============================================================
setlocal
if "%GITEE_TOKEN%"=="" goto notoken

call :pickpy
if not defined PY goto nopython

%PY% "%~dp0publish_gitee.py" %*
exit /b %errorlevel%

:pickpy
REM 逐个候选必须实测 --version 能真正运行:
REM WindowsApps\python.exe 是微软商店占位程序, where python 能找到但一跑就
REM 退出码 9009, 不能信 PATH, 必须逐个验证。
if exist "C:\Users\zwhco\.workbuddy\binaries\python\versions\3.13.12\python.exe" (
    "C:\Users\zwhco\.workbuddy\binaries\python\versions\3.13.12\python.exe" --version >nul 2>&1
    if not errorlevel 1 set "PY=C:\Users\zwhco\.workbuddy\binaries\python\versions\3.13.12\python.exe"
)
if not defined PY (
    py --version >nul 2>&1
    if not errorlevel 1 set "PY=py"
)
if not defined PY (
    python --version >nul 2>&1
    if not errorlevel 1 set "PY=python"
)
goto :eof

:notoken
echo [ERROR] GITEE_TOKEN is not set.
echo 1. Create a token: https://gitee.com/profile/personal_access_tokens  ^(scope: projects^)
echo 2. Run:  setx GITEE_TOKEN "your-token-here"
echo 3. Reopen the terminal and run this script again.
exit /b 2

:nopython
echo [ERROR] No working Python 3 found.
echo Checked: managed python, py launcher, python on PATH.
echo Note: WindowsApps\python.exe is a Microsoft Store stub ^(exit 9009^) and is skipped.
echo Fix: install Python 3 from python.org, or edit this file to point PY at your python.exe.
exit /b 2
