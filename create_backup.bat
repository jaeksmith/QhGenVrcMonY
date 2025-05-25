@echo off
setlocal

echo === Creating Project Backup ===

:: Get the directory where this script is located (project root)
set "ProjectDir=%~dp0"
:: Remove trailing backslash if it exists
if "%ProjectDir:~-1%"=="\" set "ProjectDir=%ProjectDir:~0,-1%"

:: Get the name of the project directory
for %%F in ("%ProjectDir%") do set "DirName=%%~nxF"

:: Get the parent directory path
for %%A in ("%ProjectDir%\..") do set "ParentDir=%%~fA"

:: Get timestamp using PowerShell for reliable formatting YYYYMMDDHHMMSS
echo Getting timestamp...
for /f %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-Date -Format 'yyyyMMddHHmmss'"') do set "Timestamp=%%i"

if not defined Timestamp (
    echo ERROR: Failed to get timestamp from PowerShell.
    goto :eof
)

:: Construct the output zip file path
set "ZipFileName=%DirName%.%Timestamp%.zip"
set "ZipPath=%ParentDir%\%ZipFileName%"

echo Project Name: %DirName%
echo Timestamp: %Timestamp%
echo Output Path: %ZipPath%

:: Create the zip archive using PowerShell Compress-Archive
:: Get all items in the current directory, exclude target and .git, then compress
cd /D "%ProjectDir%"
echo Archiving project contents (excluding target and .git directories)... 
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { Get-ChildItem -Path . -Exclude 'target', '.git' | Compress-Archive -DestinationPath '%ZipPath%' -Force }"

if %errorlevel% neq 0 (
    echo ERROR: Failed to create zip archive using PowerShell Compress-Archive.
) else (
    echo Successfully created backup: %ZipPath%
)

:eof
endlocal
echo.
pause 