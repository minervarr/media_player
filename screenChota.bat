@echo off
setlocal enabledelayedexpansion

:: Get list of connected devices
set count=0
for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
    if "%%B"=="device" (
        set /a count+=1
        set "device[!count!]=%%A"
    )
)

if %count%==0 (
    echo No devices connected.
    pause
    exit /b 1
)

if %count%==1 (
    set "selected_device=!device[1]!"
    echo Using only connected device: !selected_device!
)

if %count% gtr 1 (
    echo Multiple devices found. Please select one:
    for /l %%I in (1, 1, %count%) do (
        echo %%I. !device[%%I]!
    )
    
    set /p choice="Enter number (1-%count%): "
    
    if "!choice!"=="" (
        echo Invalid selection.
        pause
        exit /b 1
    )
    if !choice! lss 1 (
        echo Invalid selection.
        pause
        exit /b 1
    )
    if !choice! gtr %count% (
        echo Invalid selection.
        pause
        exit /b 1
    )
    
    for %%C in (!choice!) do set "selected_device=!device[%%C]!"
    echo Using selected device: !selected_device!
)

:: Get timestamp
for /f "usebackq" %%I in (`powershell -NoProfile -Command "Get-Date -Format 'yyyyMMdd_HHmmss'"`) do set timestamp=%%I
set filename=screenshot_!timestamp!.png

echo Taking full resolution screenshot...

adb -s "!selected_device!" shell screencap -p /sdcard/tmp_screen.png
adb -s "!selected_device!" pull /sdcard/tmp_screen.png "!filename!"
adb -s "!selected_device!" shell rm /sdcard/tmp_screen.png

echo Screenshot saved as !filename!
pause
