@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-release.ps1" %*
set "exitCode=%ERRORLEVEL%"
echo.
if not "%exitCode%"=="0" (
    echo Upload failed with exit code %exitCode%.
) else (
    echo Upload completed.
)
echo.
pause
exit /b %exitCode%
