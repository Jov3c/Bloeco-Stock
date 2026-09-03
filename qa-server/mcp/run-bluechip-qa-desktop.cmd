@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "bluechip-qa-runner.ps1" > "bluechip-qa-desktop.log" 2>&1
endlocal
