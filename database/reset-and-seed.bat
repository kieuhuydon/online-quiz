@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0reset-and-seed.ps1" %*
exit /b %ERRORLEVEL%
