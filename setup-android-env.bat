@echo off
REM Android Development Environment Setup Script
REM This script sets up Android SDK environment variables

echo ========================================
echo Android Development Environment Setup
echo ========================================
echo.

REM Check if running as Administrator
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [WARNING] This script should be run as Administrator to set system environment variables.
    echo [INFO] You can also set user environment variables without Administrator privileges.
    echo.
    pause
)

REM Set Android SDK path
set ANDROID_SDK_PATH=D:\develop\android-sdk

echo [INFO] Setting ANDROID_HOME to %ANDROID_SDK_PATH%
setx ANDROID_HOME "%ANDROID_SDK_PATH%" /M 2>nul
if %errorLevel% neq 0 (
    echo [WARNING] Failed to set system variable. Trying user variable...
    setx ANDROID_HOME "%ANDROID_SDK_PATH%"
)

echo [INFO] Adding Android SDK tools to PATH...

REM Get current PATH
for /f "tokens=2*" %%a in ('reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul') do set "SYSTEM_PATH=%%b"

REM Check if paths already exist
echo %SYSTEM_PATH% | find /i "%ANDROID_SDK_PATH%\platform-tools" >nul
if %errorLevel% neq 0 (
    echo [INFO] Adding platform-tools to PATH...
    setx PATH "%SYSTEM_PATH%;%ANDROID_SDK_PATH%\platform-tools;%ANDROID_SDK_PATH%\cmdline-tools\latest\bin;%ANDROID_SDK_PATH%\emulator" /M 2>nul
    if %errorLevel% neq 0 (
        echo [WARNING] Failed to set system PATH. Please add manually:
        echo   %ANDROID_SDK_PATH%\platform-tools
        echo   %ANDROID_SDK_PATH%\cmdline-tools\latest\bin
        echo   %ANDROID_SDK_PATH%\emulator
    ) else (
        echo [SUCCESS] PATH updated successfully.
    )
) else (
    echo [INFO] Android SDK paths already in PATH.
)

echo.
echo ========================================
echo Setup Complete!
echo ========================================
echo.
echo Please restart your terminal or computer for changes to take effect.
echo.
echo To verify installation, run:
echo   java -version
echo   adb version
echo   sdkmanager --version
echo.

pause
