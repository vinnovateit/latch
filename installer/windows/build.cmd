@echo off
setlocal

set CSC=%SystemRoot%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" (
    echo Error: csc.exe not found at %CSC%
    exit /b 1
)

set ROOT=%~dp0..\..
set OUT_DIR=%~dp0bin
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

set OUT_EXE=%OUT_DIR%\LatchSetup.exe

echo Building LatchSetup.exe...
"%CSC%" /nologo /target:winexe /optimize+ /out:"%OUT_EXE%" /r:System.dll,System.Drawing.dll,System.Windows.Forms.dll "%~dp0src\*.cs"
if %ERRORLEVEL% neq 0 (
    echo Compilation failed.
    exit /b %ERRORLEVEL%
)

echo Built successfully: %OUT_EXE%
for %%I in ("%OUT_EXE%") do echo Size: %%~zI bytes
