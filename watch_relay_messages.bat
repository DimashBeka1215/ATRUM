@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: 1. Пытаемся найти adb в PATH
set ADB_EXE=adb
where !ADB_EXE! >nul 2>nul
if %errorlevel% equ 0 goto START

:: 2. Проверяем переменную ANDROID_HOME
if not "%ANDROID_HOME%"=="" (
    if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
        set ADB_EXE="%ANDROID_HOME%\platform-tools\adb.exe"
        goto START
    )
)

:: 3. Пытаемся вытащить путь из local.properties
if exist local.properties (
    for /f "tokens=2 delims==" %%A in ('findstr /C:"sdk.dir=" local.properties') do (
        set raw_path=%%A
        set clean_path=!raw_path:\:=:!
        set clean_path=!clean_path:\\=\!
        if exist "!clean_path!\platform-tools\adb.exe" (
            set ADB_EXE="!clean_path!\platform-tools\adb.exe"
            goto START
        )
    )
)

:START
title ATRUM Relay Message Interceptor (ULTRA)
echo ====================================================
echo   ATRUM Relay Message Interceptor (Nostr Transport)
echo ====================================================
echo Использую: !ADB_EXE!
echo Ожидание устройства...
!ADB_EXE! wait-for-device
echo Устройство подключено!
echo.
echo [INFO] Очистка логов и запуск перехвата...
!ADB_EXE! logcat -c
echo [INFO] СЛУШАЮ: сообщения Nostr, ошибки и системный вывод.
echo.

:: Слушаем AtrumRelay и System.out, фильтруя всё лишнее через findstr
!ADB_EXE! logcat -v time AtrumRelay:V System.out:I *:S | findstr /i "ATRUM"

pause
