@echo off
cd /d "%~dp0"

echo ============================================================
echo   CDP Demo - connect to Chrome
echo ============================================================
echo.

REM --- Check if Chrome is already listening on 9222 ---
curl -s http://127.0.0.1:9222/json/version >nul 2>&1
if errorlevel 1 goto need_restart

echo ✅ Chrome already running with CDP on port 9222
goto run

:need_restart
echo Chrome not running with CDP. Starting it...
echo.

echo [1/3] Killing Chrome...
taskkill /F /T /IM chrome.exe >nul 2>&1
timeout /t 2 /nobreak >nul
taskkill /F /T /IM chrome.exe >nul 2>&1
timeout /t 2 /nobreak >nul
taskkill /F /T /IM chrome.exe >nul 2>&1
timeout /t 2 /nobreak >nul
echo OK

echo [2/3] Cleaning lock files...
set "UD=%LOCALAPPDATA%\Google\Chrome\User Data"
del /F /Q "%UD%\SingletonLock" >nul 2>&1
del /F /Q "%UD%\SingletonSocket" >nul 2>&1
del /F /Q "%UD%\SingletonCookie" >nul 2>&1
del /F /Q "%UD%\lockfile" >nul 2>&1
echo OK

echo [3/3] Starting Chrome...
start "" "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --disable-background-mode --hide-crash-restore-bubble --no-first-run
echo    Waiting for port 9222...
:wait_port
timeout /t 1 /nobreak >nul
curl -s http://127.0.0.1:9222/json/version >nul 2>&1
if errorlevel 1 goto wait_port
echo    Ready

:run
echo.
echo Compiling ^& running...
call mvn compile -q
if errorlevel 1 (echo Failed & pause & exit /b 1)
set PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
call mvn exec:java -Dexec.mainClass="demo.CdpTestDemo" -q
pause
