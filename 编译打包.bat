@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ==========================================
echo   Chrono 时钟   编译 + 打包
echo ==========================================
echo.

rem ---- 自动定位 JDK ----
set "JPACKAGE="
for /f "delims=" %%i in ('where jpackage 2^>nul') do if not defined JPACKAGE set "JPACKAGE=%%i"
if not defined JPACKAGE (
  echo [错误] 未找到 jpackage，请安装 JDK 17 或更高版本，并把 bin 目录加入 PATH
  echo.
  pause
  exit /b 1
)
for %%i in ("%JPACKAGE%") do set "JDK_HOME=%%~dpi"
set "JDK_HOME=%JDK_HOME:~0,-1%"
echo 使用 JDK: %JDK_HOME%
echo.

echo [1/4] 编译源码 ...
if not exist build\classes mkdir build\classes
"%JDK_HOME%\javac.exe" --release 17 -encoding UTF-8 -d build\classes src\Main.java
if errorlevel 1 goto fail

echo [2/4] 打包 jar ...
if not exist dist-input mkdir dist-input
"%JDK_HOME%\jar.exe" --create --file dist-input\Chrono.jar --main-class Main -C build\classes .
if errorlevel 1 goto fail

echo [3/4] 生成 exe（jpackage app-image）...
if exist out rmdir /s /q out
"%JDK_HOME%\jpackage.exe" --type app-image --name Chrono --input dist-input --main-jar Chrono.jar --main-class Main --app-version 1.0.0 --description "黑色风格桌面时钟" --icon assets\chrono.ico --dest out --java-options "-Dfile.encoding=UTF-8" --java-options "-Dsun.java2d.uiScale.enabled=true"
if errorlevel 1 goto fail

echo [4/4] 打包完成
echo.
echo   输出目录 : %~dp0out\Chrono
echo   可执行   : %~dp0out\Chrono\Chrono.exe
echo.
echo   说明：整个 Chrono 目录一起拷贝即可运行，目标电脑无需安装 Java。
echo         如需单文件 exe，把 out\Chrono 压缩为 payload.zip，
echo         再用 csc 把 Launcher.cs 和该 zip 一起编译（见 说明.txt）。
echo.
pause
exit /b 0

:fail
echo.
echo [失败] 请查看上面的错误信息
echo.
pause
exit /b 1
