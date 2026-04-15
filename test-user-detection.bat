@echo off
echo ActivePulse User Detection Test
echo ================================
echo.

echo Testing Windows user detection functionality...
echo.

echo Java System Property (user.name): %USERNAME%
echo.

echo Running WindowsUserDetector test...
echo.

cd /d "%~dp0"

REM Run the test if Maven is available
where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo Running via Maven...
    mvn test -Dtest=WindowsUserDetectorTest
) else (
    echo Maven not found. Trying to run Java directly...
    
    REM Try to compile and run a simple test
    if exist "target\classes" (
        echo Running compiled test...
        java -cp "target\classes;target\test-classes" com.activepulse.agent.util.WindowsUserDetectorTest
    ) else (
        echo Classes not found. Please run 'mvn compile test-compile' first.
        echo Or run: mvn test -Dtest=WindowsUserDetectorTest
    )
)

echo.
echo Test completed.
pause
