@echo off
setlocal
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"
"%JAVA_EXE%" -jar target\worker-cms-1.0.0.jar --cli --dry-run %*
