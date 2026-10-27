@echo off
echo =============================================
echo   Online Quiz - Khoi dong Server (Port 5555)
echo =============================================
cd /d "%~dp0"

echo Dang bien dich quiz-server...
call .\mvnw.cmd install -DskipTests -pl quiz-server -am

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
)
