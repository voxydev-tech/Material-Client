@echo off
setlocal
cd /d "%~dp0"
echo === Material Client: сборка бета-версии ===
echo.
echo [1/2] Портативная версия (без установки, не требует WiX)...
call gradlew.bat :composeApp:createDistributable
if errorlevel 1 goto :fail
echo.
echo [2/2] Установщики .exe и .msi (нужен WiX Toolset 3: https://github.com/wixtoolset/wix3/releases)...
call gradlew.bat :composeApp:packageExe :composeApp:packageMsi
if errorlevel 1 (
  echo.
  echo Установщики не собрались. Скорее всего, не установлен WiX Toolset 3. Портативная версия уже готова.
)
echo.
echo Готово. Результат:
echo   Портативная: composeApp\build\compose\binaries\main\app\Material Client\
echo   Установщики: composeApp\build\compose\binaries\main\exe\  и  ...\msi\
pause
exit /b 0
:fail
echo Сборка не удалась.
pause
exit /b 1
