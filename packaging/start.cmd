@echo off
rem VPS Autopilot : double-clic pour démarrer la console (http://127.0.0.1:8088).
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
pause
