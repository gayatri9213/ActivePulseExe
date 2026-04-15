@echo off
echo Building WindowsUserDetector native library...

REM Create directories if they don't exist
if not exist "src\main\resources\native" mkdir "src\main\resources\native"

REM Check if Visual Studio Build Tools are available
where cl >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo Visual Studio Build Tools not found in PATH.
    echo Please run this from a Developer Command Prompt for VS.
    echo Or run: "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
    pause
    exit /b 1
)

REM Create build directory
if not exist "build-native" mkdir "build-native"
cd build-native

REM Configure with CMake
echo Configuring with CMake...
cmake -G "NMake Makefiles" ../src/main/cpp
if %ERRORLEVEL% neq 0 (
    echo CMake configuration failed.
    cd ..
    pause
    exit /b 1
)

REM Build the library
echo Building library...
nmake
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    cd ..
    pause
    exit /b 1
)

REM Copy to resources directory
echo Copying library to resources...
copy WindowsUserDetector.dll ..\src\main\resources\native\

cd ..

echo.
echo Build completed successfully!
echo Library copied to: src\main\resources\native\WindowsUserDetector.dll
echo.
pause
