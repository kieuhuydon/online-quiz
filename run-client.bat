@echo off
echo =============================================
echo   Online Quiz - Khoi dong Client (JavaFX)
echo =============================================
cd /d "%~dp0"

if not exist "quiz-client\target\quiz-client-1.0.0-SNAPSHOT.jar" (
    echo Dang bien dich va dong goi quiz-client...
    call .\mvnw.cmd package -DskipTests
)

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
