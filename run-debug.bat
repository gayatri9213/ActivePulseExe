@echo off
echo Running Windows User Detection Debug...
echo.

cd /d "%~dp0"

echo Compiling debug program...
javac debug-user-detection.java

if %ERRORLEVEL% neq 0 (
    echo Compilation failed.
    pause
    exit /b 1
)

echo.
echo Running debug program...
echo.

java debug-user-detection

echo.
echo Debug completed.
pause
