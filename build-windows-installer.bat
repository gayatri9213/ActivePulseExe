@echo off
REM ========================================================================
REM ActivePulse Windows Installer Build Script
REM Builds MSI installer with machine-wide auto-start configuration
REM ========================================================================

echo ============================================================
echo ActivePulse Windows Installer Build Script
echo ============================================================
echo.

REM Check if WiX Toolset is installed
where candle >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: WiX Toolset not found!
    echo Please install WiX Toolset v3.11 from: https://wixtoolset.org/releases/
    echo Or run: choco install wixtoolset
    exit /b 1
)

echo WiX Toolset found successfully
echo.

REM Build the JAR
echo Building JAR with Maven...
call mvn clean package
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Maven build failed!
    exit /b 1
)

REM Prepare the JAR
echo Preparing JAR for packaging...
copy target\active-pulse-0.0.1-SNAPSHOT.jar target\app.jar
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to copy JAR!
    exit /b 1
)

REM Choose auto-start method
echo.
echo ============================================================
echo Choose Auto-Start Method:
echo ============================================================
echo 1. Registry-based (HKLM) - Simple, works for all users
echo 2. Task Scheduler - Enterprise recommended, more robust
echo ============================================================
set /p autostartMethod="Enter choice (1 or 2) [default: 2]: "
if "%autostartMethod%"=="" set autostartMethod=2

if "%autostartMethod%"=="1" (
    set wixFile=wix-overrides.wxs
    echo Using Registry-based auto-start (HKLM)
) else (
    set wixFile=wix-overrides-task-scheduler.wxs
    echo Using Task Scheduler-based auto-start (Enterprise Recommended)
)

REM Copy selected WiX file to main.wxs (jpackage expects main.wxs in resource dir)
copy %wixFile% main.wxs
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to copy WiX file!
    exit /b 1
)

REM Create output directory
if not exist "dist" mkdir dist

REM Build MSI with jpackage
echo.
echo Building MSI installer with jpackage...
echo Auto-start method: %autostartMethod%
echo.
jpackage ^
    --name ServiceProcess ^
    --input target ^
    --main-jar app.jar ^
    --type msi ^
    --dest dist ^
    --icon src\main\resources\tray-icon.ico ^
    --win-console ^
    --win-shortcut ^
    --win-menu ^
    --win-menu-group "ServiceProcess" ^
    --win-dir-chooser ^
    --resource-dir .

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: jpackage build failed!
    exit /b 1
)

REM Clean up temporary main.wxs
del main.wxs

echo.
echo ============================================================
echo SUCCESS! Installer created in dist\ directory
echo ============================================================
echo.
echo Installer location: dist\ServiceProcess-*.msi
echo.
echo To deploy machine-wide in AD environment:
echo 1. Install using admin credentials
echo 2. The auto-start will work for ALL users automatically
echo 3. No runtime registry writes needed
echo.
echo To verify auto-start after installation:
echo - Registry method: Check HKLM\Software\Microsoft\Windows\CurrentVersion\Run
echo - Task Scheduler method: Check Task Scheduler for "ActivePulseAgent" task
echo.
