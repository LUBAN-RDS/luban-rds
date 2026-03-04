@echo off

REM Luban-RDS Windows启动脚本

REM 检查Java是否安装
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到Java运行环境，请先安装Java 8或更高版本
    pause
    exit /b 1
)

REM 创建logs目录（如果不存在）
if not exist "logs" (
    mkdir "logs"
    echo 已创建logs目录
)

REM 启动服务器
set "JAR_FILE=luban-rds-jar-with-dependencies.jar"

if exist "%JAR_FILE%" (
    echo 启动Luban-RDS服务器...
    echo 日志输出将保存在logs目录中
    echo 按Ctrl+C停止服务器
    echo.
    
    REM 支持命令行参数指定端口
    if "%1" neq "" (
        java -jar "%JAR_FILE%" %1
    ) else (
        java -jar "%JAR_FILE%"
    )
) else (
    echo 错误: 未找到 %JAR_FILE%
    echo 请先运行 "mvn package" 构建项目
    pause
    exit /b 1
)
