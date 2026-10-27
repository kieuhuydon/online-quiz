@echo off
echo =============================================
echo   Online Quiz - Khoi dong Client (JavaFX)
echo =============================================
cd /d "%~dp0"

echo Dang bien dich quiz-client...
call .\mvnw.cmd install -DskipTests -pl quiz-client -am

echo Dang khoi dong ung dung Client...
call .\mvnw.cmd javafx:run -pl quiz-client

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ========================================================
    echo [LOI] Khong the khoi dong ung dung Client!
    echo Vui long kiem tra:
    echo 1. Server da duoc khoi dong truoc (chay run-server.bat).
    echo 2. Server dang lang nghe tren cong 5555.
    echo ========================================================
    pause
)
