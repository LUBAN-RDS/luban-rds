#!/bin/bash

# Luban-RDS Linux启动脚本

# 检查Java是否安装
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java运行环境，请先安装Java 8或更高版本"
    exit 1
fi

# 创建logs目录（如果不存在）
if [ ! -d "logs" ]; then
    mkdir -p "logs"
    echo "已创建logs目录"
fi

# 启动服务器
JAR_FILE="luban-rds-jar-with-dependencies.jar"

if [ -f "$JAR_FILE" ]; then
    echo "启动Luban-RDS服务器..."
    echo "日志输出将保存在logs目录中"
    echo "按Ctrl+C停止服务器"
    echo
    
    # 支持命令行参数指定端口
    if [ "$1" != "" ]; then
        java -jar "$JAR_FILE" "$1"
    else
        java -jar "$JAR_FILE"
    fi
else
    echo "错误: 未找到 $JAR_FILE"
    echo "请先运行 'mvn package' 构建项目"
    exit 1
fi
