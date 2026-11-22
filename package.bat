@echo off
setlocal
cd /d "%~dp0" || exit /b 1
set "MIAOSHA_PACKAGE_ROOT=%cd%"
set "MIAOSHA_PACKAGE_DEPLOY=%cd%\target\deploy"
set "JAR=%cd%\target\miaosha-1.0-SNAPSHOT.jar"

echo [1/3] Maven build...
if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\java.exe" (
        echo [FAIL] JAVA_HOME does not contain bin\java.exe
        exit /b 1
    )
    set "PATH=%JAVA_HOME%\bin;%PATH%"
) else (
    where java >nul 2>nul
    if errorlevel 1 (
        echo [FAIL] Set JAVA_HOME or add Java to PATH.
        exit /b 1
    )
)

set "MAVEN_COMMAND=mvn.cmd"
if defined MAVEN_HOME (
    if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
        echo [FAIL] MAVEN_HOME does not contain bin\mvn.cmd
        exit /b 1
    )
    set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
) else (
    where mvn.cmd >nul 2>nul
    if errorlevel 1 (
        echo [FAIL] Set MAVEN_HOME or add Maven to PATH.
        exit /b 1
    )
)

call "%MAVEN_COMMAND%" clean package -DskipTests
if errorlevel 1 (
    echo [FAIL] Maven build failed.
    exit /b 1
)
for %%F in ("%JAR%" "Dockerfile" "docker-compose.yml" ".dockerignore" ".env" "application.yml" "sql\miaosha.sql") do (
    if not exist "%%~F" (
        echo [FAIL] Missing deploy input: %%~F
        exit /b 1
    )
)

echo [2/3] Collect deploy files...
REM Keep validation and recursive removal in one PowerShell process.
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; $root=[IO.Path]::GetFullPath($env:MIAOSHA_PACKAGE_ROOT).TrimEnd('\'); $deploy=[IO.Path]::GetFullPath($env:MIAOSHA_PACKAGE_DEPLOY); $expected=Join-Path $root 'target\deploy'; if ($deploy -ine $expected) { throw 'Unexpected deploy path' }; foreach ($path in @((Join-Path $root 'target'), $deploy)) { if (Test-Path -LiteralPath $path) { $item=Get-Item -Force -LiteralPath $path; if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Refusing to clean a linked deploy path' } } }; if (Test-Path -LiteralPath $deploy) { Remove-Item -LiteralPath $deploy -Recurse -Force }"
if errorlevel 1 (
    echo [FAIL] Could not safely clean target\deploy.
    exit /b 1
)
mkdir "%MIAOSHA_PACKAGE_DEPLOY%\logs" "%MIAOSHA_PACKAGE_DEPLOY%\sql"
if errorlevel 1 exit /b 1

call :copyFile "%JAR%" "%MIAOSHA_PACKAGE_DEPLOY%\miaosha.jar"
if errorlevel 1 exit /b 1
for %%F in ("Dockerfile" "docker-compose.yml" ".dockerignore" ".env" "application.yml") do (
    call :copyFile "%%~F" "%MIAOSHA_PACKAGE_DEPLOY%\%%~F"
    if errorlevel 1 exit /b 1
)
call :copyFile "sql\miaosha.sql" "%MIAOSHA_PACKAGE_DEPLOY%\sql\miaosha.sql"
if errorlevel 1 exit /b 1

echo [3/3] Done: target\deploy
echo Upload the whole deploy directory, including .env and .dockerignore.
echo Start on server: docker compose up -d
exit /b 0

:copyFile
copy /y "%~1" "%~2" >nul
if errorlevel 1 (
    echo [FAIL] Could not copy: %~1
    exit /b 1
)
exit /b 0
