@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "REPO_ROOT=%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO_ROOT%\tools\android\verify-replay-phase3-acceptance.ps1" %*
exit /b %ERRORLEVEL%
