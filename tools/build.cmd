@echo off
REM Build the APK with JDK 17 (the system JAVA_HOME points at Zulu 25, unsupported by AGP 8.7.3).
REM Usage: tools\build.cmd assembleDebug [assembleRelease ...]
set "JAVA_HOME=D:\java\jdk-17"
set "ANDROID_HOME=D:\Documents\AndroidSDK"
set "ANDROID_SDK_ROOT=D:\Documents\AndroidSDK"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0.."
call gradlew.bat %* --no-daemon
exit /b %ERRORLEVEL%