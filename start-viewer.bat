@echo off
rem Starts the Webmatica viewer and opens it in the browser.
rem The viewer finds the recording and Minecraft jar itself once Minecraft has run with the mod.
rem Custom folder: start-viewer.bat --recording "D:\...\webmatica_recording"
setlocal
cd /d "%~dp0"

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (where python >nul 2>nul && set "PY=python")
if not defined PY (
  echo Python is missing. Install it from https://www.python.org/downloads/
  echo and tick "Add python.exe to PATH" during setup. Then run this file again.
  pause
  exit /b 1
)

%PY% -c "import numpy, PIL" >nul 2>nul
if errorlevel 1 (
  echo Installing numpy and Pillow ...
  %PY% -m pip install --user -r requirements.txt || (pause & exit /b 1)
)

start "" /min cmd /c "timeout /t 5 >nul & start http://127.0.0.1:8770"
%PY% -m viewer %*
pause
