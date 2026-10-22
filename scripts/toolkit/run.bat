@echo off
REM === WepWawet RE Toolkit - Windows launcher ===
REM
REM Usage:
REM   run.bat <command> [args...]
REM
REM Commands:
REM   analyze     Analyze a .mpkg file
REM   pack         Pack a wallpaper project into .mpkg
REM   server       Run a WE pairing server (for testing)
REM   broadcast    Broadcast WallpaperEngine/Ping on UDP 7884
REM   capture      Print Wireshark/tshark capture commands
REM   decompile    Download + decompile the WE Android APK with jadx
REM   search       Search decompiled WE Android source
REM   keypair      Generate RSA-2048 keypair
REM   frida        Print a Frida hooks JS script
REM   help         Show this help
REM
REM If you don't have Python on PATH, set PYTHON env var first:
REM   set PYTHON=C:\Python312\python.exe
REM   run.bat analyze file.mpkg

setlocal

REM Pick the Python interpreter
if "%PYTHON%"=="" (
    where python >nul 2>nul
    if errorlevel 1 (
        where py >nul 2>nul
        if errorlevel 1 (
            echo [error] Python not found on PATH. Install Python 3.10+ from https://www.python.org/
            echo        Or set PYTHON env var to your python.exe path.
            exit /b 3
        )
        set PYTHON=py -3
    ) else (
        set PYTHON=python
    )
)

set TOOLKIT_DIR=%~dp0
%PYTHON% "%TOOLKIT_DIR%toolkit.py" %*

endlocal
