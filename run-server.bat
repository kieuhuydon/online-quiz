@echo off
echo =============================================
echo   Online Quiz - Khoi dong Server (Port 5555)
echo =============================================
cd /d "%~dp0"

echo Dang bien dich quiz-server...
call .\mvnw.cmd install -Dmaven.test.skip=true -pl quiz-server -am
if errorlevel 1 goto build_error

echo Dang khoi dong Server...
call .\mvnw.cmd exec:java -pl quiz-server

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [LOI] Khong the khoi dong Server!
    echo Vui long kiem tra:
    echo 1. PostgreSQL da duoc bat va dang chay tren cong 5432.
    echo 2. Thong tin tai khoan db.user / db.password trong quiz-server\src\main\resources\server.properties da chinh xac.
    echo 3. Cong 5555 khong bi chiem dung boi tien trinh khac.
    pause
    exit /b 1
)
exit /b %ERRORLEVEL%

:build_error
echo.
echo [LOI] Khong the bien dich quiz-server va quiz-common.
pause
exit /b 1
