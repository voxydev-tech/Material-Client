@echo off
setlocal
cd /d "%~dp0"

call gradlew.bat :composeApp:build
if errorlevel 1 exit /b 1

call gradlew.bat :composeApp:run
exit /b %errorlevel%
