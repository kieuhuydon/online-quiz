@echo off
echo =============================================
echo   Online Quiz - Khoi dong Client (JavaFX)
echo =============================================

set JAVA_HOME=
for /f "tokens=*" %%i in ('where java') do set JAVA_CMD=%%i

set CP=quiz-client\target\quiz-client-1.0.0-SNAPSHOT.jar;C:\Users\MSI\.m2\repository\quiz\quiz-common\1.0.0-SNAPSHOT\quiz-common-1.0.0-SNAPSHOT.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-controls\21.0.5\javafx-controls-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-controls\21.0.5\javafx-controls-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-fxml\21.0.5\javafx-fxml-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-fxml\21.0.5\javafx-fxml-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-graphics\21.0.5\javafx-graphics-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-graphics\21.0.5\javafx-graphics-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-base\21.0.5\javafx-base-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-base\21.0.5\javafx-base-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\slf4j\slf4j-api\2.0.16\slf4j-api-2.0.16.jar;C:\Users\MSI\.m2\repository\ch\qos\logback\logback-classic\1.6.5\logback-classic-1.6.5.jar;C:\Users\MSI\.m2\repository\ch\qos\logback\logback-core\1.6.5\logback-core-1.6.5.jar;C:\Users\MSI\.m2\repository\quiz\quiz-server\1.0.0-SNAPSHOT\quiz-server-1.0.0-SNAPSHOT.jar;C:\Users\MSI\.m2\repository\org\postgresql\postgresql\42.7.14\postgresql-42.7.14.jar;C:\Users\MSI\.m2\repository\com\zaxxer\HikariCP\5.1.0\HikariCP-5.1.0.jar;C:\Users\MSI\.m2\repository\org\mindrot\jbcrypt\0.4\jbcrypt-0.4.jar

set JFX_MODS=C:\Users\MSI\.m2\repository\org\openjfx\javafx-controls\21.0.5\javafx-controls-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-fxml\21.0.5\javafx-fxml-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-graphics\21.0.5\javafx-graphics-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-base\21.0.5\javafx-base-21.0.5-win.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-controls\21.0.5\javafx-controls-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-fxml\21.0.5\javafx-fxml-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-graphics\21.0.5\javafx-graphics-21.0.5.jar;C:\Users\MSI\.m2\repository\org\openjfx\javafx-base\21.0.5\javafx-base-21.0.5.jar

java --module-path "%JFX_MODS%" --add-modules javafx.controls,javafx.fxml,javafx.graphics -cp "%CP%" quiz.client.ClientMain

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Loi khoi dong client! Kiem tra server dang chay tren port 5555
    pause
)
