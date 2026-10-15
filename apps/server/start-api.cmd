@echo off
REM 单独启动服务端（Maven spring-boot:run），用于开发调试。
REM 端口与数据库等配置来自仓库根目录 .env 的 APP_PORT / DATABASE_URL（当前 3001）。
REM 需要 JDK 17 与 Maven，下面的路径按本机环境调整。

set "JAVA_HOME=C:\Users\15003\.workbuddy\binaries\java\jdk\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MAVEN=C:\Users\15003\.workbuddy\binaries\java\maven\apache-maven-3.9.11\bin\mvn.cmd"

cd /d "%~dp0"
echo Starting Travel OS API on http://localhost:3001 ...
call "%MAVEN%" spring-boot:run "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8"
pause
