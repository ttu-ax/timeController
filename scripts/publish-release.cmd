@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-release.ps1" %*
set "exitCode=%ERRORLEVEL%"
echo.
if not "%exitCode%"=="0" (
    echo Release failed with exit code %exitCode%.
    echo If the APK was already built and only the upload failed,
    echo run deploy-release.cmd to retry the upload.
) else (
    echo Release workflow completed.
)
echo.
pause
exit /b %exitCode%
