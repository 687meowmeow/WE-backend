@echo off
REM === WepWawet RE Toolkit - Windows launcher ===
REM
REM Commands:
REM   run.bat analyze C:\path\to\file.mpkg --full
REM   run.bat pack C:\input_dir out.mpkg
REM   run.bat server --pin 1234
REM   run.bat broadcast
REM   run.bat capture --monitor
REM   run.bat decompile
REM   run.bat search C:\path\to\decompiled
REM   run.bat keypair --out key.json
REM   run.bat frida --out hooks.js
REM   run.bat help
REM
REM If Python is not on PATH, set PYTHON env var first:
REM   set PYTHON=C:\Python312\python.exe
REM   run.bat analyze file.mpkg

setlocal

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
