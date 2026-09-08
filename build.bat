@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
set "MAVEN_CMD=%LOCALAPPDATA%\Programs\Apache Maven\apache-maven-3.9.16\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" set "MAVEN_CMD=mvn"
call "%MAVEN_CMD%" package
if errorlevel 1 exit /b %errorlevel%
echo.
echo Build complete: target\worker-cms-1.0.0.jar
