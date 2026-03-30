@echo off
setlocal

REM Double-click entrypoint for syncing lime.db into the active Rime user dictionary.
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\CODE\weasel\scripts\sync_lime_to_rime.ps1"

if errorlevel 1 (
  echo.
  echo Sync failed.
  pause
  exit /b 1
)

echo.
echo Sync completed.
pause
