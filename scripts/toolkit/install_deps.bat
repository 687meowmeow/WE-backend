@echo off
REM === Install Python dependencies for the WepWawet RE Toolkit (Windows) ===
REM
REM Run this once before using the toolkit for the first time:
REM   install_deps.bat
REM
REM Requires: Python 3.10+ on PATH (or set PYTHON env var to python.exe)

setlocal

if "%PYTHON%"=="" (
    where python >nul 2>nul
    if errorlevel 1 (
        where py >nul 2>nul
        if errorlevel 1 (
            echo [error] Python not found on PATH.
            exit /b 3
        )
        set PYTHON=py -3
    ) else (
        set PYTHON=python
    )
)

echo Installing Python dependencies for the WepWawet RE Toolkit ...
%PYTHON% -m pip install --upgrade pip
%PYTHON% -m pip install -r "%~dp0requirements.txt"
echo.
echo Done. You can now run:
echo   run.bat help
echo.
endlocal
