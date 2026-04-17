@echo off
setlocal

echo Force cleaning ActivePulse build directories...

REM Kill any running processes
echo [1/3] Stopping running processes...
taskkill /f /im ServiceProcess.exe >nul 2>&1
taskkill /f /im ActivePulse.exe >nul 2>&1
taskkill /f /im javaw.exe >nul 2>&1
taskkill /f /im java.exe >nul 2>&1
timeout /t 2 /nobreak >nul

REM Remove target\setup directory
echo [2/3] Removing target\setup...
if exist "target\setup" (
    rmdir /s /q "target\setup" >nul 2>&1
    if exist "target\setup" (
        echo [WARN] target\setup still exists, trying alternative method...
        rd /s /q "target\setup" >nul 2>&1
    )
)

REM Clean entire target directory
echo [3/3] Cleaning target directory...
if exist "target" (
    rmdir /s /q "target" >nul 2>&1
)

echo Cleanup completed!
echo.
echo Now you can run: mvn clean package -q
pause
