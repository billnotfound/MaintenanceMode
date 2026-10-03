@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ==================================================
echo   Maintenance Mode - Build Script
echo ==================================================
echo.

set "CHECK_ONLY="
if /i "%~1"=="--check" set "CHECK_ONLY=1"

REM ============================================================
REM  Step 1: find a Java 17 - 21 installation. Gradle 8.5 cannot
REM  run on Java 8, 23 or 25.
REM ============================================================
set "JDK="

call :try_jdk "%JDK_HOME%"
if not defined JDK call :try_jdk "%JAVA_HOME%"
if not defined JDK call :try_jdk "%ProgramFiles%\Android\Android Studio\jbr"

for %%B in (
    "%ProgramFiles%\Eclipse Adoptium"
    "%ProgramFiles%\Java"
    "%ProgramFiles%\Microsoft"
    "%ProgramFiles%\Zulu"
    "%ProgramFiles%\BellSoft"
    "%ProgramFiles%\Amazon Corretto"
    "%LOCALAPPDATA%\Programs\Eclipse Adoptium"
) do (
    if not defined JDK (
        for /d %%D in (
            "%%~B\jdk-21*"
            "%%~B\jdk-17*"
            "%%~B\jdk21*"
            "%%~B\jdk17*"
            "%%~B\zulu-21*"
            "%%~B\zulu-17*"
            "%%~B\LibericaJDK-21*"
            "%%~B\LibericaJDK-17*"
            "%%~B\jbr*"
            "%%~B\*21*"
            "%%~B\*17*"
        ) do (
            if not defined JDK call :try_jdk "%%~fD"
        )
    )
)

if not defined JDK (
    echo [ERROR] Could not find a compatible Java 17 - 21 installation.
    echo.
    echo   This project cannot be built with Java 8, 23 or 25.
    echo   Please install "Eclipse Temurin 21 - JDK" from:
    echo   https://adoptium.net/temurin/releases/?version=21
    echo.
    if not defined CI pause
    exit /b 1
)

echo [INFO] Using Java:
"%JDK%\bin\java" -version 2>&1
echo.

if defined CHECK_ONLY (
    echo [INFO] Check passed, Java detection works.
    if not defined CI pause
    exit /b 0
)

REM ============================================================
REM  Step 2: make sure a local Gradle 8.5 exists. The official
REM  download is often unreachable, so the Tencent mirror is used.
REM ============================================================
set "BUILD_DIR=%LOCALAPPDATA%\mmode-build"
set "GRADLE_DIR=%BUILD_DIR%\gradle-8.5"
set "GRADLE_ZIP=%BUILD_DIR%\gradle-8.5-bin.zip"

if not exist "%GRADLE_DIR%\bin\gradle.bat" (
    if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
    if not exist "%GRADLE_ZIP%" call :download_gradle
    if not exist "%GRADLE_ZIP%" exit /b 1
    call :extract_gradle
    if not exist "%GRADLE_DIR%\bin\gradle.bat" (
        echo [ERROR] Failed to extract Gradle. Delete the file
        echo         "%GRADLE_ZIP%"
        echo         and run this script again.
        if not defined CI pause
        exit /b 1
    )
)

REM ============================================================
REM  Step 3: build the mod
REM ============================================================
set "JAVA_HOME=%JDK%"

echo [INFO] Starting the build. The first run downloads everything,
echo        so it can take a while. Please be patient ...
echo.

call "%GRADLE_DIR%\bin\gradle.bat" --init-script "%~dp0build-mirrors.init.gradle" clean build -Prelease=true --console=plain
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. Scroll up to see the cause.
    echo         If it failed while downloading, just run this script again.
    echo.
    if not defined CI pause
    exit /b 1
)

echo.
echo ==================================================
echo   Build finished! The mod jar is:
echo ==================================================
for %%F in ("%~dp0build\libs\*.jar") do echo   %%~nxF
echo.
echo Full path: %~dp0build\libs
echo.
if not defined CI pause
exit /b 0

REM ============================================================
REM  Helper: use the folder in %1 as JDK when it contains Java 17-21
REM ============================================================
:try_jdk
set "CAND=%~1"
if "%CAND%"=="" goto :eof
if not exist "%CAND%\bin\javac.exe" goto :eof

set "MAJOR="
for /f "tokens=2 delims== " %%V in ('findstr /b /c:"JAVA_VERSION" "%CAND%\release" 2^>nul') do set "MAJOR=%%~V"
for /f "tokens=1 delims=." %%M in ("%MAJOR%") do set "MAJOR=%%M"

if not defined MAJOR goto :eof
if %MAJOR% GEQ 17 if %MAJOR% LEQ 21 set "JDK=%CAND%"
goto :eof

:download_gradle
echo [INFO] Downloading Gradle 8.5, about 130 MB, one time only ...
where curl >nul 2>nul
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip' -OutFile '%GRADLE_ZIP%'"
) else (
    curl -L --fail --output "%GRADLE_ZIP%" "https://mirrors.cloud.tencent.com/gradle/gradle-8.5-bin.zip"
)
if not exist "%GRADLE_ZIP%" (
    echo [ERROR] Downloading Gradle failed. Please check your network.
    if not defined CI pause
    exit /b 1
)
goto :eof

:extract_gradle
echo [INFO] Extracting Gradle ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%BUILD_DIR%' -Force"
goto :eof
