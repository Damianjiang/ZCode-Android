@echo off
echo ==========================================
echo  Zemote 局域网下载服务
echo ==========================================
echo.
echo 正在查找本机 IP...
for /f "tokens=2*" %%a in ('ipconfig ^| findstr "IPv4"') do (
    echo   %%b
)
echo.
echo 请访问以下地址下载 APK:
echo.
for /f "tokens=2*" %%a in ('ipconfig ^| findstr "IPv4"') do echo   http://%%b:9000/app-debug.apk
echo.
echo   http://192.168.31.19:9000/app-debug.apk
echo   http://172.30.233.114:9000/app-debug.apk
echo.
echo 正在启动 HTTP 服务器（端口 9000）...
echo 按 Ctrl+C 停止服务
echo.

cd /d "C:\Users\orang\Desktop\zodemobile\app\build\outputs\apk\debug"
python -m http.server 9000
