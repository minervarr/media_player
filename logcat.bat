@echo off
setlocal enabledelayedexpansion

echo Available devices:
set i=0
for /f "skip=1 tokens=1" %%D in ('adb devices') do (
    set /a i+=1
    set dev!i!=%%D
    echo   !i!. %%D
)
echo.
set /p CHOICE=Choose device:

set DEVICE=!dev%CHOICE%!
echo Using: %DEVICE%
echo.

adb -s %DEVICE% logcat -c
adb -s %DEVICE% logcat -v time MatrixPlayer:V MainActivity:V MusicService:V QobuzFragment:V QobuzApi:V QobuzAuth:V QobuzJNI:V HttpMediaDataSource:V AndroidRuntime:E ActivityManager:W "*:S"
