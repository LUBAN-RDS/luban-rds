---
title: 安装指南
---

# 安装指南

本指南详细介绍了如何在不同环境下安装和配置 Luban-RDS。

## 环境要求

| 环境 | 最低要求 | 推荐配置 |
|------|----------|----------|
| **操作系统** | Windows 7+, Linux, macOS | Linux (CentOS 7+, Ubuntu 18.04+) |
| **Java 版本** | Java 17+ | Java 17 |
| **内存** | 512MB | 2GB+ |
| **CPU** | 1 核 | 2 核+ |
| **网络** | 100Mbps | 1Gbps |

## 安装方式

### 1. 从源码构建

#### 步骤 1：克隆仓库

```bash
# 从 GitHub 克隆
git clone https://github.com/LUBAN-RDS/luban-rds.git

# 或从 Gitee 克隆
git clone https://gitee.com/luban-rds/luban-rds.git

cd luban-rds
```

#### 步骤 2：构建项目

```bash
# 构建所有模块
mvn clean package

# 跳过测试构建（更快）
mvn clean package -DskipTests
```

构建完成后，可执行文件会生成在 `luban-rds-bin/target/` 目录中。

### 2. 直接下载

（如果提供预构建的二进制文件）

```bash
# 下载最新版本
wget https://github.com/LUBAN-RDS/luban-rds/releases/download/v1.0.0/luban-rds-bin-1.0.0.jar

# 启动服务器
java -jar luban-rds-bin-1.0.0.jar
```

## 配置选项

### 1. 配置文件

Luban-RDS 使用 `luban-rds.conf` 配置文件，默认位于 `luban-rds-server/src/main/resources/` 目录。

### 2. 核心配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `port` | 9736 | 服务器端口 |
| `host` | 0.0.0.0 | 绑定地址 |
| `databases` | 16 | 数据库数量 |
| `maxmemory` | 0 | 最大内存限制（0 表示无限制） |
| `maxmemory-policy` | volatile-lru | 内存不足时的淘汰策略 |

### 3. 持久化配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `save` | 900 1 300 10 60 10000 | RDB 保存策略 |
| `appendonly` | no | 是否开启 AOF |
| `appendfsync` | everysec | AOF 同步策略 |

### 4. 网络配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `tcp-keepalive` | 300 | TCP 保活时间（秒） |
| `timeout` | 0 | 客户端超时时间（秒，0 表示无限制） |
| `bind` | 0.0.0.0 | 绑定地址（多个地址用空格分隔） |

## 启动选项

### 1. 命令行参数

```bash
# 指定端口
java -jar luban-rds-bin-1.0.0.jar --port 6380

# 指定配置文件
java -jar luban-rds-bin-1.0.0.jar --config /path/to/luban-rds.conf

# 后台运行（Linux/Mac）
nohup java -jar luban-rds-bin-1.0.0.jar > luban-rds.log 2>&1 &
```

### 2. 环境变量

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `LUBAN_RDS_PORT` | 服务器端口 | 9736 |
| `LUBAN_RDS_HOST` | 绑定地址 | 0.0.0.0 |
| `LUBAN_RDS_CONFIG` | 配置文件路径 | - |

## 验证安装

### 1. 检查服务状态

```bash
# 使用 redis-cli 连接
redis-cli ping
# 输出: PONG

# 检查版本
redis-cli info server
```

### 2. 测试基本命令

```bash
redis-cli
> SET test hello
OK
> GET test
"hello"
> DEL test
(integer) 1
```

## 常见安装问题

### 1. 端口被占用

**错误信息**：`Address already in use`

**解决方案**：
- 修改配置文件中的端口号
- 或停止占用端口的其他服务

```bash
# 查看端口占用
# Linux/Mac
lsof -i :9736

# Windows
netstat -ano | findstr :9736
```

### 2. Java 版本不兼容

**错误信息**：`Unsupported major.minor version`

**解决方案**：安装 Java 8 或更高版本

### 3. 内存不足

**错误信息**：`Out of memory`

**解决方案**：
- 增加系统内存
- 或调整 `maxmemory` 设置

## 下一步

- **[基本使用](./basic-usage.md)**：学习常用命令和基本操作
- **[高级功能](./advanced.md)**：探索持久化、Lua 脚本等高级特性
- **[部署运维](../deployment/)**：了解生产环境部署和维护
