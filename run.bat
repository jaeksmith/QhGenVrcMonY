@echo off
setlocal

echo ======================================
echo VRC Monitor - Starting Application
echo ======================================

:: Get the directory where this script is located
set "ScriptDir=%~dp0"
:: Remove trailing backslash if it exists
if "%ScriptDir:~-1%"=="\" set "ScriptDir=%ScriptDir:~0,-1%"

:: Change to the script directory
cd /d "%ScriptDir%"

:: Define the JAR file path - looks for JAR in the same directory as the batch file
set "JarFile=%ScriptDir%\vrc-monitor-1.0-SNAPSHOT.jar"

:: Check if the JAR file exists
if not exist "%JarFile%" (
    echo ERROR: JAR file not found at %JarFile%
    echo.
    echo Please ensure the JAR file is in the same directory as this batch file.
    echo If you need to build the JAR, run: mvn clean package
    echo.
    pause
    goto :eof
)

:: Check Java installation
echo Checking Java installation...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Java is not installed or not in the PATH.
    echo Please install Java 17 or higher.
    echo.
    pause
    goto :eof
)

:: Check for config.json
if not exist "config.json" (
    echo WARNING: config.json not found in the current directory.
    echo The application will use default settings or fail if the config is required.
    echo.
)

:: Default port
set "PORT=8080"

:: Parse command line arguments for custom port
:parse_args
if "%~1"=="" goto run_app
if /i "%~1"=="--port" (
    set "PORT=%~2"
    shift
    shift
    goto parse_args
)
shift
goto parse_args

:run_app
echo Starting VRC Monitor on port %PORT%...
echo.
echo You can access the application at http://localhost:%PORT%
echo Current working directory: %CD%
echo Config file should be located at: %CD%\config.json
echo Press Ctrl+C to stop the application
echo.

:: Run the application
java -Dserver.port=%PORT% -jar "%JarFile%"

echo.
if %errorlevel% neq 0 (
    echo Application terminated with an error. Error code: %errorlevel%
) else (
    echo Application stopped.
)

pause
:eof
endlocal 