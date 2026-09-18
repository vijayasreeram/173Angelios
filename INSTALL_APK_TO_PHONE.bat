@echo off
echo ===================================================
echo   Installing Updated Angelios.apk to Connected Phone
echo ===================================================
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

if not exist %ADB% (
    echo Error: ADB not found at %ADB%
    pause
    exit /b 1
)

echo Checking for connected devices...
%ADB% devices

echo.
echo Installing Angelios.apk...
%ADB% install -r "%~dp0Angelios.apk"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===================================================
    echo [SUCCESS] Angelios.apk successfully updated on phone!
    echo ===================================================
) else (
    echo.
    echo [NOTE] If installation failed, make sure:
    echo   1. Phone is connected via USB.
    echo   2. "USB Debugging" is enabled in Developer Options.
    echo   3. Phone screen is unlocked and "Allow USB Debugging" accepted.
)
echo.
pause
