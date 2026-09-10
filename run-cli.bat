@echo off
setlocal
chcp 65001 >nul
set PYTHONUTF8=1
if not exist ".venv\Scripts\python.exe" call build.bat
if errorlevel 1 exit /b %errorlevel%
".venv\Scripts\python.exe" -m worker_cms --cli %*