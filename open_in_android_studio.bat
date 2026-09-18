@echo off
title iTANTRA - Launch in Android Studio
echo =========================================================
echo   Launching iTANTRA in Official Android Studio...
echo =========================================================

set "STUDIO_EXE=C:\Program Files\Android\Android Studio\bin\studio64.exe"
if not exist "%STUDIO_EXE%" (
    set "STUDIO_EXE=C:\Users\CMRMuthuthiyagarajan\android-studio\bin\studio64.exe"
)

echo Using Android Studio at: %STUDIO_EXE%
start "" "%STUDIO_EXE%" "%~dp0."
exit
