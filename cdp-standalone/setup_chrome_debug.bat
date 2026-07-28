@echo off
echo ============================================================
echo   Setup: Add CDP debug port to Chrome shortcut (Standalone)
echo ============================================================
echo.
echo This will add --remote-debugging-port=9222 to your Chrome
echo desktop shortcut. After this, every time you open Chrome,
echo it will be CDP-controllable. No more kill/restart needed.
echo.

set "SHORTCUT=%USERPROFILE%\Desktop\Google Chrome.lnk"
if not exist "%SHORTCUT%" (
    echo Creating desktop shortcut...
    powershell -Command ^
        "$WshShell = New-Object -ComObject WScript.Shell; " ^
        "$Shortcut = $WshShell.CreateShortcut('%USERPROFILE%\Desktop\Google Chrome.lnk'); " ^
        "$Shortcut.TargetPath = 'C:\Program Files\Google\Chrome\Application\chrome.exe'; " ^
        "$Shortcut.Arguments = '--remote-debugging-port=9222'; " ^
        "$Shortcut.Save()"
    echo Done. A new Chrome shortcut is on your desktop.
) else (
    echo Updating existing shortcut...
    powershell -Command ^
        "$WshShell = New-Object -ComObject WScript.Shell; " ^
        "$Shortcut = $WshShell.CreateShortcut('%SHORTCUT%'); " ^
        "$Shortcut.Arguments = '--remote-debugging-port=9222'; " ^
        "$Shortcut.Save()"
    echo Done. Chrome desktop shortcut updated.
)

echo.
echo From now on, use this shortcut to open Chrome.
echo Then run run.bat — it will connect instantly without killing anything.
echo.
pause
