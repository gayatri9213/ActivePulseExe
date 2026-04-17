@echo off
setlocal

echo.
echo ╔══════════════════════════════════════════════════╗
echo ║   ServiceProcess - Diagnostic Tool                ║
echo ╚══════════════════════════════════════════════════╝
echo.

echo [INFO] Checking ServiceProcess installation and permissions...
echo.

REM Check if ServiceProcess is installed
echo [1/5] Checking ServiceProcess installation...
if exist "C:\Program Files\ServiceProcess\ServiceProcess.exe" (
    echo [OK] Found machine-wide installation: C:\Program Files\ServiceProcess\
    set INSTALL_PATH=C:\Program Files\ServiceProcess
) else if exist "%LOCALAPPDATA%\ServiceProcess\ServiceProcess.exe" (
    echo [OK] Found per-user installation: %LOCALAPPDATA%\ServiceProcess\
    set INSTALL_PATH=%LOCALAPPDATA%\ServiceProcess
) else (
    echo [ERROR] ServiceProcess not found in standard locations
    echo        Expected: C:\Program Files\ServiceProcess\ or %LOCALAPPDATA%\ServiceProcess\
    goto :end
)

REM Check screenshot directory
echo.
echo [2/5] Checking screenshot directory...
set SCREENSHOT_DIR=%USERPROFILE%\.activepulse\screenshots
if exist "%SCREENSHOT_DIR%" (
    echo [OK] Screenshot directory exists: %SCREENSHOT_DIR%
    dir "%SCREENSHOT_DIR%" /b | find /c "." > temp_count.txt
    set /p SCREENSHOT_COUNT=<temp_count.txt
    del temp_count.txt
    echo     Contains %SCREENSHOT_COUNT% screenshot^(s^)
) else (
    echo [WARN] Screenshot directory not found: %SCREENSHOT_DIR%
    echo     This will be created when ServiceProcess takes screenshots
)

REM Check registry entries
echo.
echo [3/5] Checking auto-start registry entries...

REM Check HKLM (all users)
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v "ActivePulseAgent" >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Found HKLM registry entry (all users)
    for /f "tokens=3*" %%a in ('reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" /v "ActivePulseAgent" ^| find "REG_SZ"') do echo     Value: %%a %%b
) else (
    echo [WARN] No HKLM registry entry found (all users)
)

REM Check HKCU (current user)
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "ActivePulseAgent" >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Found HKCU registry entry (current user)
    for /f "tokens=3*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "ActivePulseAgent" ^| find "REG_SZ"') do echo     Value: %%a %%b
) else (
    echo [WARN] No HKCU registry entry found (current user)
)

REM Check permissions
echo.
echo [4/5] Checking directory permissions...
if exist "%USERPROFILE%\.activepulse" (
    echo [OK] Can access .activepulse directory
    attrib "%USERPROFILE%\.activepulse" | find "H" >nul
    if %ERRORLEVEL% equ 0 (
        echo [INFO] .activepulse directory is hidden (normal)
    )
) else (
    echo [INFO] .activepulse directory doesn't exist yet (will be created)
)

REM Test screenshot directory creation
echo.
echo [5/5] Testing screenshot directory creation...
if not exist "%USERPROFILE%\.activepulse" (
    mkdir "%USERPROFILE%\.activepulse" 2>nul
    if %ERRORLEVEL% equ 0 (
        echo [OK] Can create .activepulse directory
        rmdir "%USERPROFILE%\.activepulse" 2>nul
    ) else (
        echo [ERROR] Cannot create .activepulse directory (permission issue)
    )
)

if not exist "%SCREENSHOT_DIR%" (
    mkdir "%SCREENSHOT_DIR%" 2>nul
    if %ERRORLEVEL% equ 0 (
        echo [OK] Can create screenshots directory
        rmdir "%SCREENSHOT_DIR%" 2>nul
        rmdir "%USERPROFILE%\.activepulse" 2>nul
    ) else (
        echo [ERROR] Cannot create screenshots directory (permission issue)
    )
)

:end
echo.
echo ╔══════════════════════════════════════════════════╗
echo ║   Diagnostic Complete                            ║
echo ╚══════════════════════════════════════════════════╝
echo.
echo Summary:
if exist "%INSTALL_PATH%\ServiceProcess.exe" echo ✓ ServiceProcess is installed
if exist "%SCREENSHOT_DIR%" echo ✓ Screenshot directory exists
if %ERRORLEVEL% leq 1 echo ✓ Auto-start registry entries configured
echo.
echo If issues persist:
echo 1. Run ServiceProcess as Administrator once to set up HKLM registry
echo 2. Check Windows Event Viewer for detailed error logs
echo 3. Ensure user has permission to create directories in %USERPROFILE%
echo.
pause
