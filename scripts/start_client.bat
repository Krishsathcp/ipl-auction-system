@echo off
cd /d %~dp0\..

echo ================================
echo     Starting IPL Auction Client
echo ================================

REM Compile AuctionClientGUI.java into bin/
javac -cp "lib\mysql-connector-j-9.1.0.jar" -d bin src\AuctionClientGUI.java

IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    pause
    exit /b
)

REM Run client from bin/
echo [INFO] Running client...
cd bin
java -cp ".;..\lib\mysql-connector-j-9.1.0.jar" AuctionClientGUI

pause
