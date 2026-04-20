    @echo off
    setlocal

    set APP_NAME=ServiceProcess
    set APP_VERSION=1.0.0
    set MAIN_CLASS=com.activepulse.agent.ActivePulseApplication
    set JAR_NAME=active-pulse-0.0.1-SNAPSHOT.jar
    set JAR_FILE=target\%JAR_NAME%
    set BUILD_DIR=C:\ap-build
    set INPUT_DIR=%BUILD_DIR%\input
    set OUT_DIR=%BUILD_DIR%\output

    :: ── Force short TEMP so paths stay short ─────────────────────────────
    set TEMP=C:\ap-tmp
    set TMP=C:\ap-tmp
    mkdir "C:\ap-tmp" 2>nul

    echo.
    echo ╔══════════════════════════════════════════════════╗
    echo ║   ServiceProcess — Windows MSI Installer          ║
    echo ╚══════════════════════════════════════════════════╝
    echo.

    :: ── Step 0: Kill any process locking the JAR ─────────────────────────
    echo [INFO] Stopping any running ServiceProcess or Java instances...
    taskkill /f /im ServiceProcess.exe >nul 2>&1
    taskkill /f /im javaw.exe       >nul 2>&1
    taskkill /f /im java.exe        >nul 2>&1
    timeout /t 2 /nobreak >nul
    echo [OK] Processes cleared.

    :: ── Step 1: Checks ────────────────────────────────────────────────────
    if not exist "%JAR_FILE%" (
        echo [ERROR] JAR not found. Run: mvn clean package -q
        pause & exit /b 1
    )
    echo [OK] JAR found.

    where jpackage >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo [ERROR] jpackage not found. Need JDK 17+.
        pause & exit /b 1
    )
    echo [OK] jpackage found.

    :: ── Step 2: Clean short build dirs ───────────────────────────────────
    if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
    mkdir "%INPUT_DIR%" & mkdir "%OUT_DIR%"

    :: ── Step 3: Copy JAR to short path ───────────────────────────────────
    copy /Y "%JAR_FILE%" "%INPUT_DIR%\app.jar" >nul
    if %ERRORLEVEL% neq 0 (
        echo [ERROR] Cannot copy JAR — still locked. Close IntelliJ/IDE and retry.
        pause & exit /b 1
    )
    echo [OK] JAR copied to short path.

    :: ── Step 4: Icon ──────────────────────────────────────────────────────
    set ICON_ARG=
    if exist "src\main\resources\tray-icon.ico" (
        copy /Y "src\main\resources\tray-icon.ico" "%BUILD_DIR%\tray-icon.ico" >nul
        set ICON_ARG=--icon %BUILD_DIR%\tray-icon.ico
        echo [OK] tray-icon.ico found.
    ) else (
        echo [WARN] No tray-icon.ico found — using default Java icon.
    )

    :: ── Step 5: Build MSI with jpackage ──────────────────────────────────
    echo.
    echo [INFO] Running jpackage --type msi (5-10 min, please wait)...
    echo.

    if exist "target\setup" rmdir /s /q "target\setup"
    mkdir "target\setup"

    :: Find Java home from JAVA_HOME environment variable
    if "%JAVA_HOME%"=="" (
        echo [ERROR] JAVA_HOME environment variable not set
        echo Please set JAVA_HOME to your JDK installation path
        pause & exit /b 1
    )

    echo [INFO] Using JDK from: %JAVA_HOME%

    jpackage ^
    --type msi ^
    --name "%APP_NAME%" ^
    --input "%INPUT_DIR%" ^
    --main-jar app.jar ^
    --dest "target\setup" ^
    --temp "%BUILD_DIR%\jpackage-tmp" ^
    --runtime "%JAVA_HOME%" ^
    %ICON_ARG% ^
    --win-console ^
    --win-shortcut ^
    --win-menu ^
    --win-menu-group "ServiceProcess" ^
    --win-dir-chooser

    if %ERRORLEVEL% neq 0 (
        echo.
        echo [ERROR] jpackage failed. Check output above.
        rmdir /s /q "%BUILD_DIR%" 2>nul
        rmdir /s /q "C:\ap-tmp"   2>nul
        pause & exit /b 1
    )
    echo [OK] MSI built successfully.

    :: ── Step 6: Create auto-start configuration script ──────────────────────
    echo [INFO] Creating auto-start configuration script...

    (
        echo # ServiceProcess Auto-Start Configuration Script
        echo # Run this after installation to add machine-wide auto-start
        echo # Uses both HKLM Run key AND Startup folder for redundancy
        echo.
        echo $ErrorActionPreference = "Stop"
        echo.
        echo $installPath = "C:\Program Files\ServiceProcess"
        echo $exePath = "$installPath\ServiceProcess.exe"
        echo $startupFolder = "C:\ProgramData\Microsoft\Windows\Start Menu\Programs\StartUp"
        echo $shortcutPath = "$startupFolder\ServiceProcess.lnk"
        echo.
        echo Write-Host "Configuring ServiceProcess auto-start for all users..."
        echo Write-Host "Install Path: $installPath"
        echo Write-Host "Executable: $exePath"
        echo.
        echo # Check if installed
        echo if ^(-not ^(Test-Path $exePath^)^) {
        echo     Write-Host "Error: ServiceProcess not found at $exePath"
        echo     Write-Host "Please install ServiceProcess first"
        echo     exit 1
        echo }
        echo.
        echo # Method 1: Add to HKLM Run key
        echo Write-Host "Method 1: Adding to HKLM Run key..."
        echo $regPath = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"
        echo $regName = "ServiceProcessAgent"
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
        echo     $Shortcut.Description = "ServiceProcess Productivity Tracking"
        echo     $Shortcut.Save(^)
        echo     Write-Host "✅ Startup folder shortcut created"
        echo } catch {
        echo     Write-Host "⚠️  Failed to create startup shortcut: $_"
        echo }
        echo.
        echo Write-Host ""
        echo Write-Host "✅ Auto-start configuration completed"
        echo Write-Host "ServiceProcess will now start automatically for all users on this machine"
        echo Write-Host ""
        echo Write-Host "Configuration applied:"
        echo Write-Host "  - HKLM Run key: $regPath\$regName"
        echo Write-Host "  - Startup folder: $shortcutPath"
    ) > "target\setup\configure-autostart.ps1"

    echo [OK] Auto-start script created.

    :: ── Step 7: Cleanup ───────────────────────────────────────────────────
    rmdir /s /q "%BUILD_DIR%" 2>nul
    rmdir /s /q "C:\ap-tmp"   2>nul

    echo.
    echo ╔══════════════════════════════════════════════════╗
    echo ║  Build Complete!                                 ║
    echo ╚══════════════════════════════════════════════════╝
    echo.
    echo   Installer: target\setup\ServiceProcess-*.msi
    echo   Auto-start script: target\setup\configure-autostart.ps1
    echo.
    echo   Installs with:
    echo     - Machine-wide installation (all users)
    echo     - Setup wizard + directory chooser
    echo     - Start Menu under 'ServiceProcess'
    echo     - Add/Remove Programs entry
    echo.
    echo   Auto-start configuration (run after installation):
    echo     - Method 1: HKLM Run key (registry)
    echo     - Method 2: Common startup folder shortcut
    echo.
    echo   To configure auto-start:
    echo     powershell -ExecutionPolicy Bypass -File configure-autostart.ps1
    echo.

    set /p OPEN="Open output folder? (y/n): "
    if /i "%OPEN%"=="y" explorer "target\setup"

    endlocal
    pause