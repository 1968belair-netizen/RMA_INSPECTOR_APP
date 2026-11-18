@echo off
REM Starts the mock RMA server (fake data) so a phone/tablet on the same Wi-Fi can connect.
cd /d "%~dp0"
REM App builds made before the sign-in screen send no token; keep this until the sign-in issue is merged.
set MOCK_AUTH_OPTIONAL=1
echo Installing/updating requirements...
python -m pip install --quiet -r requirements.txt
echo.
echo This PC's addresses (use the IPv4 one in the app's Settings, with :8000):
ipconfig | findstr /i "IPv4"
echo.
echo Mock server running on port 8000. Close this window to stop it.
python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
pause
