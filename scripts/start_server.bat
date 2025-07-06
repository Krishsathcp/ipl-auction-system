@echo off
cd /d %~dp0\..

echo ================================
echo     Starting IPL Auction Server
echo ================================

REM Compile Server2.java into bin/
javac -cp "lib\mysql-connector-j-9.1.0.jar" -d bin src\Server2.java

IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    pause
    exit /b
)

REM Run server from bin/
echo [INFO] Running server...
cd bin
java -cp ".;..\lib\mysql-connector-j-9.1.0.jar" Server2

pause
