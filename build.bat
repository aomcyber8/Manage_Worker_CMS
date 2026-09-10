@echo off
setlocal
chcp 65001 >nul
set PYTHONUTF8=1
set "PYTHON_EXE=C:\Python314\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"
if not exist ".venv\Scripts\python.exe" "%PYTHON_EXE%" -m venv .venv
if errorlevel 1 exit /b %errorlevel%
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 exit /b %errorlevel%
".venv\Scripts\python.exe" -m pytest
if errorlevel 1 exit /b %errorlevel%
echo.
echo Build complete: Python virtual environment and tests are ready.