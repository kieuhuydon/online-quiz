@echo off
chcp 65001 > nul
setlocal

cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0database\setup-db.ps1" %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [THONG BAO] Co loi xay ra trong qua trinh thiet lap Database.
    echo Vui long kiem tra lai thong tin va thu lai.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Nhan phim bat ky de thoat...
pause > nul
exit /b 0

