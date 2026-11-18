@echo off
REM Starts the mock RMA server with the DEMO dataset (fake data) so a phone/tablet can connect.
REM To run the plain test dataset instead, run:  set MOCK_DATASET=default  before this file, or edit the line below.
cd /d "%~dp0"
if "%MOCK_DATASET%"=="" set MOCK_DATASET=demo
echo Installing/updating requirements...
python -m pip install --quiet -r requirements.txt
echo.
echo Dataset: %MOCK_DATASET%   (demo = 8 curated RMAs + 1 finished example; POST /mock/reset restores it)
echo.
echo This PC's addresses - in the app's Settings use  http://IPv4-address:8000/api/v1
ipconfig | findstr /i "IPv4"
echo.
echo REMINDER: put the phone on its own hotspot and connect this PC to that hotspot (or use the same
echo Wi-Fi). Allow Python through Windows Firewall (Private networks) when asked.
echo Sign in with the fake user  demo / demo.
echo.
echo Mock server running on port 8000. Close this window to stop it.
python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
pause
