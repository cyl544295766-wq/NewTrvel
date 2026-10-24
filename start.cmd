@echo off
setlocal
title Travel OS Launcher

echo ============================================
echo   Travel OS - One Click Launcher
echo   (Static frontend served by Spring, port 3001)
echo ============================================

REM ---------- [1/4] Docker engine + database containers ----------
docker ps >nul 2>&1
if errorlevel 1 (
  echo [1/4] Docker engine not running, starting Docker Desktop...
  start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
  echo       Waiting for Docker engine ^(about 30s^)...
  ping -n 31 127.0.0.1 >nul
) else (
  echo [1/4] Docker engine is running.
)
docker start travel-os-postgres >nul 2>&1

REM ---------- [2/4] Backend (Spring, port 3001) ----------
netstat -ano | findstr ":3001 " | findstr "LISTENING" >nul 2>&1
if errorlevel 1 (
  echo [2/4] Starting backend on port 3001 ...
  start "TravelOS-Backend" /min cmd /c ""C:\Users\15003\.workbuddy\binaries\java\jdk\jdk-17.0.20.1+1\bin\java.exe" -Dfile.encoding=UTF-8 -Dtravelos.static.dir=D:\NewTrevl\apps\web -jar "D:\NewTrevl\apps\server\target\travel-os-api-0.1.0.jar""
) else (
  echo [2/4] Backend already running on port 3001.
)

REM ---------- [3/4] Static frontend (served by Spring on port 3001) ----------
echo     [3/4] Static frontend is served by Spring at /home/, /trips/, ...
echo           Visit http://127.0.0.1:3001/ for the entry redirect.

REM ---------- [4/4] Wait for backend then open browser ----------
echo [4/4] Waiting for backend to be ready...
set /a tries=0
:waitloop
ping -n 4 127.0.0.1 >nul
curl -s -o nul -m 3 http://127.0.0.1:3001/api/health
if not errorlevel 1 goto ready
set /a tries+=1
if %tries% lss 20 goto waitloop
echo WARNING: Backend did not respond in time. Check the backend window.
pause
exit /b 1

:ready
start "" http://127.0.0.1:3001/
echo.
echo ============================================
echo   Travel OS is ready!  http://127.0.0.1:3001
echo   Login: admin / (password from SEED_ADMIN_PASSWORD in .env)
echo ============================================
ping -n 6 127.0.0.1 >nul
endlocal
