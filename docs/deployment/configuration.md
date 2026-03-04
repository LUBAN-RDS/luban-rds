---
title: 配置指南
---

# 配置指南

本部分详细介绍了 Luban-RDS 的配置选项、优化建议和最佳实践，帮助您根据具体需求定制配置。

## 1. 配置文件格式

Luban-RDS 支持三种配置方式，优先级从高到低：

1. **命令行参数**：通过启动命令传递的参数
2. **配置文件**：通过 `luban-rds.conf` 文件配置
3. **环境变量**：通过系统环境变量配置

### 1.1 配置文件示例

```conf
# 服务器配置
port 9736
host 0.0.0.0

# 认证配置
requirepass your-secure-password

# 数据库配置
databases 16

# 持久化配置
save 900 1
save 300 10
save 60 10000
rdbfilename dump.rdb
dir /data

# AOF 配置
aof-enabled yes
aof-filename appendonly.aof
aof-sync everysec

# Lua 脚本配置
lua-time-limit 5000

# 内存配置
maxmemory 2gb
maxmemory-policy allkeys-lru

# 客户端配置
timeout 300
tcp-keepalive 300

# 日志配置
loglevel notice
logfile ""

# 安全配置
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""
```

### 1.2 环境变量映射

| 配置项 | 环境变量 | 默认值 |
|--------|----------|--------|
| port | LUBAN_RDS_PORT | 9736 |
| host | LUBAN_RDS_HOST | 0.0.0.0 |
| requirepass | LUBAN_RDS_PASSWORD | "" |
| databases | LUBAN_RDS_DATABASES | 16 |
| dir | LUBAN_RDS_DIR | "." |
| rdb-enabled | LUBAN_RDS_RDB_ENABLED | true |
| rdbfilename | LUBAN_RDS_RDB_FILENAME | "dump.rdb" |
| aof-enabled | LUBAN_RDS_AOF_ENABLED | false |
| aof-filename | LUBAN_RDS_AOF_FILENAME | "appendonly.aof" |
| aof-sync | LUBAN_RDS_AOF_SYNC | "everysec" |
| lua-time-limit | LUBAN_RDS_LUA_TIME_LIMIT | 5000 |
| maxmemory | LUBAN_RDS_MAXMEMORY | 0 (无限制) |
| maxmemory-policy | LUBAN_RDS_MAXMEMORY_POLICY | "noeviction" |
| timeout | LUBAN_RDS_TIMEOUT | 0 (无超时) |
| tcp-keepalive | LUBAN_RDS_TCP_KEEPALIVE | 300 |
| loglevel | LUBAN_RDS_LOGLEVEL | "notice" |
| logfile | LUBAN_RDS_LOGFILE | "" |

### 1.3 慢查询日志配置

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| slowlog-log-slower-than | LUBAN_RDS_SLOWLOG_LOG_SLOWER_THAN | 10000 | 记录慢查询的阈值（微秒）。负数表示禁用，0 表示记录所有命令。 |
| slowlog-max-len | LUBAN_RDS_SLOWLOG_MAX_LEN | 128 | 慢查询日志的最大长度。超过限制时，最旧的日志将被移除。 |

## 2. 服务器配置

### 2.1 基本配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| port | 整数 | 9736 | 服务监听端口 |
| host | 字符串 | 0.0.0.0 | 服务监听地址，0.0.0.0 表示监听所有地址 |
| databases | 整数 | 16 | 数据库数量 |
| requirepass | 字符串 | "" | 认证密码，为空表示不需要认证 |
| dir | 字符串 | "." | 工作目录，用于存放持久化文件 |

### 2.2 网络配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| timeout | 整数 | 0 | 客户端连接超时时间（秒），0 表示无超时 |
| tcp-keepalive | 整数 | 300 | TCP 保活时间（秒），用于检测死连接 |

### 2.3 线程配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| netty.boss-threads | 整数 | CPU 核心数 | Netty Boss 线程数 |
| netty.worker-threads | 整数 | CPU 核心数 * 2 | Netty Worker 线程数 |
| business-threads | 整数 | CPU 核心数 | 业务处理线程数 |

## 3. 持久化配置

### 3.1 RDB 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| rdb-enabled | 布尔值 | true | 是否启用 RDB 持久化 |
| rdbfilename | 字符串 | "dump.rdb" | RDB 文件名 |
| save | 字符串 | "900 1 300 10 60 10000" | RDB 保存策略，格式为 "秒数 键数" |

**保存策略说明**：
- `save 900 1`：900 秒内至少有 1 个键被修改，执行 RDB 保存
- `save 300 10`：300 秒内至少有 10 个键被修改，执行 RDB 保存
- `save 60 10000`：60 秒内至少有 10000 个键被修改，执行 RDB 保存

### 3.2 AOF 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| aof-enabled | 布尔值 | false | 是否启用 AOF 持久化 |
| aof-filename | 字符串 | "appendonly.aof" | AOF 文件名 |
| aof-sync | 字符串 | "everysec" | AOF 同步策略，可选值：always、everysec、no |
| aof-rewrite-percentage | 整数 | 100 | AOF 重写触发百分比 |
| aof-rewrite-min-size | 字符串 | "64mb" | AOF 重写最小文件大小 |

**同步策略说明**：
- `always`：每次写命令都同步到磁盘，最安全但性能最差
- `everysec`：每秒同步一次到磁盘，平衡安全性和性能
- `no`：由操作系统决定同步时机，性能最好但安全性最差

## 4. 内存配置

### 4.1 内存限制

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| maxmemory | 字符串 | 0 | 最大内存使用限制，0 表示无限制 |
| maxmemory-policy | 字符串 | "noeviction" | 内存淘汰策略 |
| maxmemory-samples | 整数 | 5 | 内存淘汰时的采样数量 |

**内存淘汰策略说明**：
- `noeviction`：当内存不足时，拒绝写入操作，返回错误
- `allkeys-lru`：在所有键中，使用 LRU 算法淘汰最近最少使用的键
- `volatile-lru`：在设置了过期时间的键中，使用 LRU 算法淘汰最近最少使用的键
- `allkeys-random`：在所有键中，随机淘汰键
- `volatile-random`：在设置了过期时间的键中，随机淘汰键
- `volatile-ttl`：在设置了过期时间的键中，淘汰剩余 TTL 最短的键

### 4.2 内存优化

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| lazyfree-lazy-eviction | 布尔值 | false | 内存淘汰时是否使用惰性删除 |
| lazyfree-lazy-expire | 布尔值 | false | 键过期时是否使用惰性删除 |
| lazyfree-lazy-server-del | 布尔值 | false | DEL 命令是否使用惰性删除 |

## 5. Lua 脚本配置

### 5.1 脚本执行

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| lua-time-limit | 整数 | 5000 | Lua 脚本执行超时时间（毫秒） |
| lua-sandbox-enabled | 布尔值 | true | 是否启用 Lua 沙箱模式 |
| lua-allowed-modules | 字符串 | "base,string,table,math" | 允许使用的 Lua 模块 |
| lua-blocked-functions | 字符串 | "os.execute,io.open" | 禁止使用的 Lua 函数 |
| lua-max-script-bytes | 整数 | 524288 | Lua 脚本最大大小（字节） |
| lua-max-return-bytes | 整数 | 524288 | Lua 脚本返回值最大大小（字节） |
| lua-max-ops-per-script | 整数 | 0 | 脚本单次执行允许的最大 Redis 命令调用次数。0 表示无限制。 |
| lua-yield-ms | 整数 | 0 | 脚本执行期间让出 CPU 的时间间隔（毫秒），防止长时间阻塞。0 表示不让出。 |

### 5.2 脚本缓存

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| lua-max-cache-entries | 整数 | 1000 | Lua 脚本缓存最大条目数 |
| lua-cache-ttl | 整数 | 3600 | Lua 脚本缓存过期时间（秒） |

## 6. 客户端配置

### 6.1 连接管理

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| maxclients | 整数 | 10000 | 最大客户端连接数 |
| client-output-buffer-limit normal | 字符串 | "0 0 0" | 普通客户端输出缓冲区限制 |
| client-output-buffer-limit slave | 字符串 | "256mb 64mb 60" | 从客户端输出缓冲区限制 |
| client-output-buffer-limit pubsub | 字符串 | "32mb 8mb 60" | 发布订阅客户端输出缓冲区限制 |

**输出缓冲区限制格式**：
- `硬限制 软限制 软限制时间`
- 当缓冲区超过硬限制时，立即关闭连接
- 当缓冲区超过软限制且持续时间超过软限制时间时，关闭连接

### 6.2 网络优化

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| tcp-backlog | 整数 | 511 | TCP 连接队列大小 |
| reuseaddr | 布尔值 | true | 是否启用地址重用 |
| tcp-nodelay | 布尔值 | true | 是否禁用 Nagle 算法 |

## 7. 安全配置

### 7.1 认证

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| requirepass | 字符串 | "" | 认证密码，为空表示不需要认证 |
| rename-command | 字符串 | - | 重命名或禁用危险命令 |

**命令重命名示例**：
```conf
# 禁用危险命令
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""

# 重命名命令
rename-command DEL "DELETE"
rename-command CONFIG "CFG"
```

### 7.2 网络安全

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| bind | 字符串 | "0.0.0.0" | 绑定地址，多个地址用空格分隔 |
| protected-mode | 布尔值 | true | 保护模式，当 bind 为 0.0.0.0 且无密码时启用 |

## 8. 日志配置

### 8.1 日志级别

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| loglevel | 字符串 | "notice" | 日志级别，可选值：debug、verbose、notice、warning |
| logfile | 字符串 | "" | 日志文件路径，为空表示输出到标准输出 |
| syslog-enabled | 布尔值 | false | 是否启用 syslog |
| syslog-ident | 字符串 | "luban-rds" | syslog 标识符 |
| syslog-facility | 字符串 | "local0" | syslog 设备 |

**日志级别说明**：
- `debug`：最详细的日志，包含所有调试信息
- `verbose`：详细的日志，包含更多操作信息
- `notice`：正常的日志，包含重要的操作信息
- `warning`：警告日志，只包含警告和错误信息

## 9. 高级配置

### 9.1 执行器配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| executor-type | 字符串 | "cached" | 执行器类型，可选值：cached、fixed |
| executor-core-pool-size | 整数 | CPU 核心数 | 核心线程池大小 |
| executor-max-pool-size | 整数 | CPU 核心数 * 2 | 最大线程池大小 |
| executor-queue-capacity | 整数 | 10000 | 任务队列容量 |
| executor-keep-alive-time | 整数 | 60 | 线程保持活动时间（秒） |

### 9.2 Netty 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| netty.buffer-high-water-mark | 整数 | 65536 | 高水位线缓冲区大小（字节） |
| netty.buffer-low-water-mark | 整数 | 32768 | 低水位线缓冲区大小（字节） |
| netty.reuse-address | 布尔值 | true | 是否启用地址重用 |
| netty.tcp-no-delay | 布尔值 | true | 是否禁用 Nagle 算法 |
| netty.so-backlog | 整数 | 1024 | TCP 连接队列大小 |

### 9.3 性能优化

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| activerehashing | 布尔值 | true | 是否启用主动重哈希 |
| hash-max-ziplist-entries | 整数 | 512 | Hash 类型使用 ziplist 编码的最大条目数 |
| hash-max-ziplist-value | 整数 | 64 | Hash 类型使用 ziplist 编码的最大值大小（字节） |
| list-max-ziplist-size | 字符串 | "-2" | List 类型使用 ziplist 编码的最大大小 |
| list-compress-depth | 整数 | 0 | List 类型压缩深度 |
| set-max-intset-entries | 整数 | 512 | Set 类型使用 intset 编码的最大条目数 |
| zset-max-ziplist-entries | 整数 | 128 | ZSet 类型使用 ziplist 编码的最大条目数 |
| zset-max-ziplist-value | 整数 | 64 | ZSet 类型使用 ziplist 编码的最大值大小（字节） |

### 9.4 监控配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| monitor-max-clients | 整数 | 100 | 最大允许并发 MONITOR 客户端数量。超过此限制时，新连接将收到错误。 |

## 10. 配置优化建议

### 10.1 生产环境优化

**1. 内存配置**
- 设置合理的 `maxmemory`，建议为系统内存的 70-80%
- 选择合适的 `maxmemory-policy`，推荐 `allkeys-lru`
- 启用 `lazyfree-lazy-eviction` 和 `lazyfree-lazy-expire` 提高性能

**2. 持久化配置**
- 对于数据安全性要求高的场景，启用 AOF 并设置 `aof-sync everysec`
- 对于性能要求高的场景，只使用 RDB 并调整保存策略
- 合理设置 `aof-rewrite-percentage` 和 `aof-rewrite-min-size`

**3. 线程配置**
- 根据 CPU 核心数调整 `netty.boss-threads`、`netty.worker-threads` 和 `business-threads`
- 对于高并发场景，适当增加线程数

**4. 网络配置**
- 设置 `timeout` 为 300-600 秒，避免空闲连接占用资源
- 启用 `tcp-keepalive` 并设置为 300 秒，及时检测死连接
- 对于高并发场景，适当增加 `tcp-backlog`

**5. 安全配置**
- 设置强密码，使用 `requirepass`
- 禁用或重命名危险命令，如 `FLUSHALL`、`FLUSHDB`、`CONFIG`
- 限制绑定地址，避免暴露在公网

### 10.2 开发环境优化

**1. 简化配置**
- 禁用持久化，提高启动速度
- 禁用认证，方便开发调试
- 启用详细日志，便于问题排查

**2. 内存配置**
- 设置较小的 `maxmemory`，避免占用过多系统内存
- 选择 `noeviction` 策略，方便发现内存问题

**3. 网络配置**
- 禁用 `timeout`，避免开发过程中连接被断开
- 启用 `debug` 级别的日志，详细记录操作

### 10.3 高可用配置

**1. 持久化策略**
- 同时启用 RDB 和 AOF，提高数据安全性
- 调整 AOF 同步策略为 `everysec`，平衡安全性和性能
- 定期备份持久化文件，防止数据丢失

**2. 内存管理**
- 设置合理的内存限制，避免内存溢出
- 选择合适的内存淘汰策略，根据业务场景选择
- 监控内存使用情况，及时调整配置

**3. 连接管理**
- 设置合理的 `maxclients`，避免连接数过多导致性能下降
- 调整客户端输出缓冲区限制，避免缓冲区溢出
- 监控连接数，及时发现异常连接

## 11. 最佳实践

### 11.1 配置文件管理

**1. 版本控制**
- 将配置文件纳入版本控制系统，便于追踪变更
- 使用不同的配置文件区分不同环境（开发、测试、生产）

**2. 配置备份**
- 定期备份配置文件，防止配置丢失
- 保存配置变更历史，便于回滚

**3. 配置验证**
- 变更配置后，使用 `CONFIG GET` 命令验证配置是否生效
- 重启服务前，检查配置文件语法是否正确

### 11.2 性能调优

**1. 监控指标**
- 监控内存使用情况，避免内存溢出
- 监控命令执行率，发现性能瓶颈
- 监控网络流量，避免网络拥塞
- 监控持久化操作，避免影响服务性能

**2. 热点键处理**
- 识别热点键，优化数据结构
- 对于频繁访问的键，考虑使用 `EXPIRE` 设置合理的过期时间
- 避免使用过大的键，如超大的 Hash 或 List

**3. 命令优化**
- 使用批量命令，如 `MSET`、`MGET`，减少网络往返
- 避免使用 `KEYS` 命令，改用 `SCAN` 命令
- 对于复杂逻辑，使用 Lua 脚本在服务器端执行，减少网络往返

### 11.3 安全最佳实践

**1. 认证**
- 使用强密码，包含字母、数字和特殊字符
- 定期更换密码，避免密码泄露
- 不同环境使用不同的密码

**2. 网络安全**
- 限制绑定地址，只允许内部网络访问
- 使用防火墙限制访问端口
- 对于公网访问，使用 TLS 加密

**3. 命令安全**
- 禁用或重命名危险命令
- 对于生产环境，限制 `CONFIG` 命令的使用
- 监控异常命令执行，及时发现安全问题

## 12. 配置示例

### 12.1 生产环境配置

```conf
# 服务器配置
port 9736
host 0.0.0.0

databases 16

# 认证配置
requirepass YourStrongPassword123!

# 持久化配置
save 900 1
save 300 10
save 60 10000
rdbfilename dump.rdb
dir /data

aof-enabled yes
aof-filename appendonly.aof
aof-sync everysec

# 内存配置
maxmemory 16gb
maxmemory-policy allkeys-lru
maxmemory-samples 5

# 线程配置
netty.boss-threads 4
netty.worker-threads 16
business-threads 8

# 网络配置
timeout 600
tcp-keepalive 300
tcp-backlog 1024

# 客户端配置
maxclients 5000

# 安全配置
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""

# 日志配置
loglevel notice
logfile "/var/log/luban-rds.log"
```

### 12.2 开发环境配置

```conf
# 服务器配置
port 9736
host 127.0.0.1

databases 16

# 禁用认证
# requirepass 

# 禁用持久化以提高性能
rdb-enabled no
aof-enabled no

# 内存配置
maxmemory 1gb
maxmemory-policy noeviction

# 线程配置
netty.boss-threads 2
netty.worker-threads 4
business-threads 2

# 网络配置
timeout 0

# 客户端配置
maxclients 1000

# 日志配置
loglevel debug
logfile ""
```

### 12.3 高可用配置

```conf
# 服务器配置
port 9736
host 0.0.0.0

databases 16

# 认证配置
requirepass YourStrongPassword123!

# 持久化配置
save 3600 1
save 600 10
save 300 100
rdbfilename dump.rdb
dir /data

aof-enabled yes
aof-filename appendonly.aof
aof-sync everysec
aof-rewrite-percentage 100
aof-rewrite-min-size 64mb

# 内存配置
maxmemory 32gb
maxmemory-policy allkeys-lru
maxmemory-samples 5

# 线程配置
netty.boss-threads 8
netty.worker-threads 32
business-threads 16

# 网络配置
timeout 600
tcp-keepalive 300
tcp-backlog 2048

# 客户端配置
maxclients 10000

# 安全配置
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""

# 日志配置
loglevel notice
logfile "/var/log/luban-rds.log"
```

## 13. 配置验证

### 13.1 使用 CONFIG 命令

**查看所有配置**
```bash
redis-cli -h localhost -p 9736 CONFIG GET *
```

**查看特定配置**
```bash
redis-cli -h localhost -p 9736 CONFIG GET maxmemory
redis-cli -h localhost -p 9736 CONFIG GET maxmemory-policy
redis-cli -h localhost -p 9736 CONFIG GET save
```

**修改配置**
```bash
# 临时修改（重启后失效）
redis-cli -h localhost -p 9736 CONFIG SET maxmemory 1gb
redis-cli -h localhost -p 9736 CONFIG SET maxmemory-policy allkeys-lru

# 永久修改需要编辑配置文件并重启服务
```

### 13.2 检查配置文件语法

**使用 --test-config 参数**
```bash
java -jar luban-rds-bin-1.0.0.jar --test-config --config /path/to/luban-rds.conf

# 输出示例
Config file /path/to/luban-rds.conf parsed successfully
```

### 13.3 监控配置效果

**使用 INFO 命令**
```bash
redis-cli -h localhost -p 9736 INFO memory
redis-cli -h localhost -p 9736 INFO persistence
redis-cli -h localhost -p 9736 INFO stats
```

**监控内存使用**
```bash
redis-cli -h localhost -p 9736 INFO memory | grep used_memory
```

**监控持久化状态**
```bash
redis-cli -h localhost -p 9736 INFO persistence | grep -E "rdb_|aof_"
```

## 14. 常见配置问题

### 14.1 内存不足

**问题**：服务启动时报内存不足错误

**解决方案**：
- 检查 `maxmemory` 设置是否合理
- 检查系统内存是否足够
- 调整 `maxmemory-policy` 为更激进的策略
- 减少数据量或增加系统内存

### 14.2 持久化失败

**问题**：持久化文件创建失败或写入错误

**解决方案**：
- 检查 `dir` 目录权限是否正确
- 检查磁盘空间是否充足
- 检查磁盘 I/O 是否正常
- 调整持久化策略，减少持久化频率

### 14.3 连接数过多

**问题**：服务报连接数过多错误

**解决方案**：
- 检查 `maxclients` 设置是否合理
- 检查客户端是否正确关闭连接
- 调整 `timeout` 设置，及时释放空闲连接
- 增加系统文件描述符限制

### 14.4 性能下降

**问题**：服务性能突然下降

**解决方案**：
- 检查内存使用情况，是否达到 `maxmemory` 限制
- 检查持久化操作是否正在执行
- 检查是否有大键或热点键
- 检查网络连接是否正常
- 调整线程配置和网络配置

## 15. 下一步

- **[监控维护](./monitoring.md)**：学习如何监控和维护 Luban-RDS 服务
- **[故障排查](./troubleshooting.md)**：掌握常见问题的排查和解决方法
- **[使用指南](../guide/)**：学习如何使用 Luban-RDS 的各项功能
