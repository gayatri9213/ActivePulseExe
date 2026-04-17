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

REM Create output directory
if not exist "dist" mkdir dist

REM Build MSI with jpackage
echo.
echo Building MSI installer with jpackage...
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
    --win-dir-chooser

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: jpackage build failed!
    exit /b 1
)

REM Create auto-start configuration script
echo.
echo Creating auto-start configuration script...
echo.

(
    echo # ActivePulse Auto-Start Configuration Script
    echo # Run this after installation to add machine-wide auto-start
    echo # Uses both HKLM Run key AND Startup folder for redundancy
    echo.
    echo $ErrorActionPreference = "Stop"
    echo.
    echo $installPath = "C:\Program Files\ServiceProcess"
    echo $exePath = "$installPath\ServiceProcess.exe"
    echo $startupFolder = "C:\ProgramData\Microsoft\Windows\Start Menu\Programs\StartUp"
    echo $shortcutPath = "$startupFolder\ActivePulse.lnk"
    echo.
    echo Write-Host "Configuring ActivePulse auto-start for all users..."
    echo Write-Host "Install Path: $installPath"
    echo Write-Host "Executable: $exePath"
    echo.
    echo # Check if installed
    echo if ^(-not ^(Test-Path $exePath^)^) {
    echo     Write-Host "Error: ActivePulse not found at $exePath"
    echo     Write-Host "Please install ActivePulse first"
    echo     exit 1
    echo }
    echo.
    echo # Method 1: Add to HKLM Run key
    echo Write-Host "Method 1: Adding to HKLM Run key..."
    echo $regPath = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"
    echo $regName = "ActivePulseAgent"
    echo $regValue = "`"`"$exePath`"`""
    echo try {
    echo     Set-ItemProperty -Path $regPath -Name $regName -Value $regValue -Type String -Force
    echo     Write-Host "✅ HKLM Run key configured"
    echo } catch {
    echo     Write-Host "⚠️  Failed to add HKLM Run key: $_"
    echo }
    echo.
    echo # Method 2: Create shortcut in common startup folder
    echo Write-Host "Method 2: Creating shortcut in common startup folder..."
    echo try {
    echo     $WScript = New-Object -ComObject WScript.Shell
    echo     $Shortcut = $WScript.CreateShortcut($shortcutPath^)
    echo     $Shortcut.TargetPath = $exePath
    echo     $Shortcut.WorkingDirectory = $installPath
    echo     $Shortcut.Description = "ActivePulse Productivity Tracking"
    echo     $Shortcut.Save(^)
    echo     Write-Host "✅ Startup folder shortcut created"
    echo } catch {
    echo     Write-Host "⚠️  Failed to create startup shortcut: $_"
    echo }
    echo.
    echo Write-Host ""
    echo Write-Host "✅ Auto-start configuration completed"
    echo Write-Host "ActivePulse will now start automatically for all users on this machine"
    echo Write-Host ""
    echo Write-Host "Configuration applied:"
    echo Write-Host "  - HKLM Run key: $regPath\$regName"
    echo Write-Host "  - Startup folder: $shortcutPath"
) > dist\configure-autostart.ps1

echo ✅ Auto-start script created: dist\configure-autostart.ps1

echo.
echo ============================================================
echo SUCCESS! Installer created in dist\ directory
echo ============================================================
echo.
echo Installer location: dist\ServiceProcess-*.msi
echo Auto-start script: dist\configure-autostart.ps1
echo.
echo Auto-start configuration (NO WiX files needed):
echo - Method 1: HKLM Run key (registry)
echo - Method 2: Common startup folder shortcut
echo.
echo To deploy machine-wide in AD environment:
echo 1. Install the MSI using admin credentials
echo 2. Run: powershell -ExecutionPolicy Bypass -File configure-autostart.ps1
echo    (This adds BOTH registry entry AND startup folder shortcut)
echo.
echo Alternative: Deploy via Group Policy
echo - Registry: HKLM\Software\Microsoft\Windows\CurrentVersion\Run
echo - Startup: C:\ProgramData\Microsoft\Windows\Start Menu\Programs\StartUp
echo.
