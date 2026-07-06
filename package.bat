@echo off
setlocal
cd /d "%~dp0"

title miaosha-package

echo ============================================================
echo   miaosha deploy package
echo ============================================================
echo.
echo   PWD: %cd%
echo.

REM ==================== Step 1: Maven build ====================
echo [1/3] Maven build...
echo.

set JAVA_HOME=D:\software\jdk-8
set MAVEN_HOME=D:\software\apache-maven-3.6.3

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [FAIL] JDK not found: %JAVA_HOME%
    pause
    exit /b 1
)
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo [FAIL] Maven not found: %MAVEN_HOME%
    pause
    exit /b 1
)

echo JDK:    %JAVA_HOME%
echo Maven:  %MAVEN_HOME%
echo.

call "%MAVEN_HOME%\bin\mvn.cmd" clean package -DskipTests
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAIL] Maven build failed, exit code: %ERRORLEVEL%
    pause
    exit /b 1
)

echo.
echo [OK] Build done
echo.

REM ==================== Step 2: Collect files ====================
echo [2/3] Collect deploy files...
echo.

set DEPLOY=target\deploy
set JAR=target\miaosha-1.0-SNAPSHOT.jar

if not exist "%JAR%" (
    echo [FAIL] Jar not found: %JAR%
    pause
    exit /b 1
)

if exist "%DEPLOY%" (
    echo Clean old deploy dir...
    rmdir /s /q "%DEPLOY%"
)
mkdir "%DEPLOY%"
mkdir "%DEPLOY%\logs"
mkdir "%DEPLOY%\sql"

copy /y "%JAR%"              "%DEPLOY%\miaosha.jar"          >nul && echo   [OK] miaosha.jar || echo   [FAIL] miaosha.jar
copy /y "Dockerfile"         "%DEPLOY%\Dockerfile"           >nul && echo   [OK] Dockerfile || echo   [FAIL] Dockerfile
copy /y "docker-compose.yml" "%DEPLOY%\docker-compose.yml"   >nul && echo   [OK] docker-compose.yml || echo   [FAIL] docker-compose.yml
copy /y ".env"               "%DEPLOY%\.env"                 >nul && echo   [OK] .env || echo   [FAIL] .env
copy /y "application.yml"    "%DEPLOY%\application.yml"      >nul && echo   [OK] application.yml || echo   [FAIL] application.yml

echo.

REM ==================== Step 3: Done ====================
echo [3/3] Done!
echo.
echo ============================================================
echo   Deploy dir: target\deploy\
echo ============================================================
echo.
echo   Files:
dir /b "%DEPLOY%"
echo.
echo ============================================================
echo   Upload:
echo     scp -r target\deploy\* xiong@SERVER:/home/xiong/miaosha/
echo.
echo   Start on server:
echo     cd /home/xiong/miaosha
echo     docker-compose up -d
echo ============================================================
echo.
pause
