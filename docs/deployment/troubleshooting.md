---
title: 故障排查
---

# 故障排查

本部分详细介绍了 Luban-RDS 的常见问题、排查方法和解决方案，帮助您快速定位和解决问题。

## 1. 连接问题

### 1.1 无法连接到服务

**症状**：客户端无法连接到 Luban-RDS 服务，报错 "Connection refused" 或 "Connection timed out"

**可能原因**：
- 服务未启动
- 端口配置错误
- 防火墙阻止连接
- 网络问题
- 绑定地址配置错误

**排查步骤**：
1. **检查服务状态**：
   ```bash
   # 检查服务是否运行
   ps aux | grep luban-rds
   
   # 检查服务端口是否监听
   netstat -tulpn | grep 9736
   ```

2. **检查配置文件**：
   ```bash
   # 检查端口配置
   grep "port" /path/to/luban-rds.conf
   
   # 检查绑定地址配置
   grep "host" /path/to/luban-rds.conf
   ```

3. **检查防火墙**：
   ```bash
   # 检查防火墙规则
   iptables -L
   
   # 临时关闭防火墙测试
   systemctl stop firewalld
   ```

4. **测试网络连接**：
   ```bash
   # 测试本地连接
   telnet localhost 9736
   
   # 测试远程连接
   telnet <remote-host> 9736
   ```

**解决方案**：
- 启动服务：`java -jar luban-rds-bin-1.0.0.jar`
- 修正端口配置：确保端口未被占用
- 修正绑定地址：设置为 `0.0.0.0` 允许所有地址访问
- 配置防火墙：开放 9736 端口
- 检查网络连接：确保网络畅通

### 1.2 连接数过多

**症状**：客户端连接报错 "max number of clients reached"

**可能原因**：
- 客户端连接数超过 `maxclients` 限制
- 客户端未正确关闭连接
- 连接泄漏

**排查步骤**：
1. **检查当前连接数**：
   ```bash
   redis-cli -h localhost -p 9736 INFO clients | grep connected_clients
   ```

2. **检查连接限制**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET maxclients
   ```

3. **查看客户端连接详情**：
   ```bash
   redis-cli -h localhost -p 9736 CLIENT LIST
   ```

**解决方案**：
- 增加 `maxclients` 限制：`CONFIG SET maxclients 10000`
- 检查客户端代码：确保正确关闭连接
- 设置合理的 `timeout`：`CONFIG SET timeout 300`
- 重启服务（作为最后手段）

### 1.3 认证失败

**症状**：客户端连接报错 "NOAUTH Authentication required" 或 "ERR invalid password"

**可能原因**：
- 客户端未提供密码
- 密码错误
- 服务端未配置密码

**排查步骤**：
1. **检查认证配置**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET requirepass
   ```

2. **测试认证**：
   ```bash
   # 带密码连接
   redis-cli -h localhost -p 9736 -a your-password PING
   ```

**解决方案**：
- 修正密码：确保客户端使用正确的密码
- 配置密码：`CONFIG SET requirepass your-secure-password`
- 移除密码：`CONFIG SET requirepass ""`（仅开发环境）

## 2. 内存问题

### 2.1 内存使用过高

**症状**：内存使用持续增长，接近或超过 `maxmemory` 限制

**可能原因**：
- 数据量过大
- 内存泄漏
- 大键未设置过期时间
- 内存碎片过多

**排查步骤**：
1. **检查内存使用情况**：
   ```bash
   redis-cli -h localhost -p 9736 INFO memory
   ```

2. **查找大键**：
   ```bash
   # 使用 SCAN 命令查找大键
   redis-cli -h localhost -p 9736 --bigkeys
   ```

3. **检查过期键**：
   ```bash
   redis-cli -h localhost -p 9736 INFO keyspace
   ```

**解决方案**：
- 设置键的过期时间：`EXPIRE key seconds`
- 删除不必要的大键：`DEL key`
- 调整 `maxmemory-policy`：`CONFIG SET maxmemory-policy allkeys-lru`
- 增加 `maxmemory` 限制：`CONFIG SET maxmemory 16gb`
- 重启服务（作为最后手段）

### 2.2 内存碎片率过高

**症状**：`mem_fragmentation_ratio` 大于 1.5

**可能原因**：
- 频繁的键删除和修改
- 内存分配器的特性
- 长时间运行未重启

**排查步骤**：
1. **检查内存碎片率**：
   ```bash
   redis-cli -h localhost -p 9736 INFO memory | grep mem_fragmentation_ratio
   ```

**解决方案**：
- 重启服务：内存碎片会被清理
- 启用 `lazyfree-lazy-eviction` 和 `lazyfree-lazy-expire`
- 优化内存使用：减少大键的使用

### 2.3 内存溢出

**症状**：服务崩溃，日志中显示 "Out of Memory" 错误

**可能原因**：
- 内存使用超过系统限制
- JVM 堆内存设置过小
- 数据量过大

**排查步骤**：
1. **检查系统内存**：
   ```bash
   free -m
   ```

2. **检查 JVM 配置**：
   ```bash
   # 检查 JVM 堆内存设置
   java -XX:+PrintFlagsFinal -version | grep HeapSize
   ```

**解决方案**：
- 增加系统内存
- 调整 JVM 堆内存：`java -Xms4g -Xmx8g -jar luban-rds-bin-1.0.0.jar`
- 减少数据量：删除不必要的数据
- 调整 `maxmemory` 限制：设置为系统内存的 70-80%

## 3. 命令执行问题

### 3.1 命令执行缓慢

**症状**：命令执行时间变长，响应变慢

**可能原因**：
- 大键操作
- 复杂的 Lua 脚本
- 网络延迟
- 磁盘 I/O 瓶颈（如持久化操作）
- CPU 资源不足

**排查步骤**：
1. **检查命令执行统计**：
   ```bash
   redis-cli -h localhost -p 9736 INFO commandstats
   ```

2. **检查大键**：
   ```bash
   redis-cli -h localhost -p 9736 --bigkeys
   ```

3. **检查持久化状态**：
   ```bash
   redis-cli -h localhost -p 9736 INFO persistence
   ```

4. **检查系统资源**：
   ```bash
   # 检查 CPU 使用
   top
   
   # 检查磁盘 I/O
   iostat -x
   ```

**解决方案**：
- 优化大键操作：避免对大键执行全量操作
- 优化 Lua 脚本：减少脚本复杂度和执行时间
- 调整持久化策略：减少持久化频率
- 增加系统资源：升级 CPU、内存或使用 SSD
- 使用批量命令：减少网络往返

### 3.2 命令被拒绝

**症状**：客户端执行命令报错 "ERR unknown command" 或 "ERR command not allowed"

**可能原因**：
- 命令不存在
- 命令被禁用或重命名
- 未认证

**排查步骤**：
1. **检查命令是否存在**：
   ```bash
   redis-cli -h localhost -p 9736 COMMAND | grep "command_name"
   ```

2. **检查命令重命名配置**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET "rename-command"
   ```

3. **检查认证状态**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET requirepass
   ```

**解决方案**：
- 使用正确的命令：参考命令文档
- 修正命令重命名：删除或修改重命名配置
- 执行认证：`AUTH your-password`

### 3.3 Lua 脚本执行错误

**症状**：执行 Lua 脚本报错 "ERR Error running script"

**可能原因**：
- 脚本语法错误
- 脚本执行超时
- 脚本尝试执行危险操作
- 脚本内存使用超限

**排查步骤**：
1. **检查脚本内容**：确保脚本语法正确
2. **检查脚本执行时间**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET lua-time-limit
   ```

3. **检查脚本内存使用**：
   ```bash
   redis-cli -h localhost -p 9736 INFO memory | grep used_memory_lua
   ```

**解决方案**：
- 修正脚本语法错误
- 增加脚本执行时间限制：`CONFIG SET lua-time-limit 10000`
- 优化脚本：减少复杂度和执行时间
- 检查沙箱配置：确保脚本有权限执行所需操作

## 4. 持久化问题

### 4.1 RDB 保存失败

**症状**：RDB 保存失败，日志中显示错误信息

**可能原因**：
- 磁盘空间不足
- 目录权限错误
- 磁盘 I/O 错误
- 内存不足

**排查步骤**：
1. **检查 RDB 状态**：
   ```bash
   redis-cli -h localhost -p 9736 INFO persistence | grep -E "rdb_"
   ```

2. **检查磁盘空间**：
   ```bash
   df -h
   ```

3. **检查目录权限**：
   ```bash
   ls -la /data
   ```

**解决方案**：
- 清理磁盘空间：删除不必要的文件
- 修正目录权限：`chmod 755 /data`
- 检查磁盘健康状态：使用 `smartctl` 工具
- 调整 RDB 保存策略：减少保存频率

### 4.2 AOF 重写失败

**症状**：AOF 重写失败，日志中显示错误信息

**可能原因**：
- 磁盘空间不足
- 目录权限错误
- 磁盘 I/O 错误
- 内存不足

**排查步骤**：
1. **检查 AOF 状态**：
   ```bash
   redis-cli -h localhost -p 9736 INFO persistence | grep -E "aof_"
   ```

2. **检查磁盘空间**：
   ```bash
   df -h
   ```

3. **检查目录权限**：
   ```bash
   ls -la /data
   ```

**解决方案**：
- 清理磁盘空间：删除不必要的文件
- 修正目录权限：`chmod 755 /data`
- 检查磁盘健康状态：使用 `smartctl` 工具
- 调整 AOF 重写配置：`CONFIG SET aof-rewrite-min-size 128mb`

### 4.3 数据恢复失败

**症状**：服务启动时无法从持久化文件恢复数据

**可能原因**：
- 持久化文件损坏
- 目录权限错误
- 版本不兼容

**排查步骤**：
1. **检查持久化文件**：
   ```bash
   ls -la /data/dump.rdb /data/appendonly.aof
   ```

2. **检查日志文件**：查找恢复失败的错误信息

**解决方案**：
- 使用备份文件：替换损坏的持久化文件
- 修正目录权限：`chmod 755 /data`
- 重新构建数据：清空持久化文件，重新导入数据

## 5. 服务问题

### 5.1 服务崩溃

**症状**：服务突然崩溃，无法正常运行

**可能原因**：
- 内存溢出
- 线程死锁
- 硬件故障
- 软件 bug

**排查步骤**：
1. **检查日志文件**：查找崩溃原因
2. **检查系统日志**：
   ```bash
   dmesg | grep luban-rds
   ```

3. **检查 JVM 崩溃日志**：查找 `hs_err_pid*.log` 文件

**解决方案**：
- 增加内存：避免内存溢出
- 升级版本：修复已知 bug
- 检查硬件：确保硬件正常
- 重启服务：`java -jar luban-rds-bin-1.0.0.jar`

### 5.2 服务启动失败

**症状**：服务启动时报错，无法正常启动

**可能原因**：
- 端口被占用
- 配置文件错误
- 持久化文件损坏
- 内存不足

**排查步骤**：
1. **检查端口占用**：
   ```bash
   netstat -tulpn | grep 9736
   ```

2. **检查配置文件**：
   ```bash
   redis-cli -h localhost -p 9736 --test-config --config /path/to/luban-rds.conf
   ```

3. **检查持久化文件**：
   ```bash
   ls -la /data/dump.rdb /data/appendonly.aof
   ```

**解决方案**：
- 释放端口：关闭占用端口的进程
- 修正配置文件：修复语法错误
- 移除损坏的持久化文件：`rm /data/dump.rdb /data/appendonly.aof`
- 增加内存：避免内存不足

### 5.3 服务性能下降

**症状**：服务性能逐渐下降，响应变慢

**可能原因**：
- 内存碎片过多
- 连接数增加
- 数据量增长
- 系统资源不足
- 持久化操作频繁

**排查步骤**：
1. **检查内存使用**：
   ```bash
   redis-cli -h localhost -p 9736 INFO memory
   ```

2. **检查连接数**：
   ```bash
   redis-cli -h localhost -p 9736 INFO clients
   ```

3. **检查命令执行**：
   ```bash
   redis-cli -h localhost -p 9736 INFO stats
   ```

4. **检查系统资源**：
   ```bash
   top
   free -m
   iostat -x
   ```

**解决方案**：
- 重启服务：清理内存碎片
- 调整配置：优化内存、连接数和持久化设置
- 增加系统资源：升级硬件
- 优化数据结构：减少大键的使用
- 调整持久化策略：减少持久化频率

## 6. 网络问题

### 6.1 网络延迟高

**症状**：命令执行延迟高，响应时间长

**可能原因**：
- 网络拥塞
- 带宽不足
- 网络设备故障
- 客户端与服务端距离远

**排查步骤**：
1. **测试网络延迟**：
   ```bash
   ping <redis-server>
   ```

2. **测试网络带宽**：
   ```bash
   iperf3 -c <redis-server>
   ```

3. **检查网络连接**：
   ```bash
   netstat -tnp | grep redis-cli
   ```

**解决方案**：
- 优化网络：增加带宽，减少网络拥塞
- 部署 closer：将客户端和服务端部署在同一网络
- 使用连接池：减少连接建立开销
- 使用批量命令：减少网络往返

### 6.2 网络断连

**症状**：客户端与服务端连接频繁断开

**可能原因**：
- 网络不稳定
- 防火墙设置
- 超时配置不合理
- TCP 保活设置不当

**排查步骤**：
1. **检查超时配置**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET timeout
   ```

2. **检查 TCP 保活设置**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET tcp-keepalive
   ```

3. **检查网络稳定性**：
   ```bash
   ping -t <redis-server>
   ```

**解决方案**：
- 优化网络：确保网络稳定
- 调整超时设置：`CONFIG SET timeout 600`
- 调整 TCP 保活：`CONFIG SET tcp-keepalive 300`
- 配置防火墙：确保不会主动断开连接

## 7. 安全问题

### 7.1 未授权访问

**症状**：服务被未授权访问，数据可能被篡改

**可能原因**：
- 未设置密码
- 密码过于简单
- 绑定地址为 0.0.0.0

**排查步骤**：
1. **检查认证配置**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET requirepass
   ```

2. **检查绑定地址**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET host
   ```

**解决方案**：
- 设置强密码：`CONFIG SET requirepass YourStrongPassword123!`
- 限制绑定地址：`CONFIG SET host 127.0.0.1`
- 禁用危险命令：`CONFIG SET rename-command FLUSHALL ""`

### 7.2 命令注入

**症状**：服务执行了未预期的命令

**可能原因**：
- Lua 脚本沙箱配置不当
- 命令重命名配置不当
- 未授权访问

**排查步骤**：
1. **检查 Lua 沙箱配置**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET lua-sandbox-enabled
   ```

2. **检查命令重命名**：
   ```bash
   redis-cli -h localhost -p 9736 CONFIG GET "rename-command"
   ```

**解决方案**：
- 启用 Lua 沙箱：`CONFIG SET lua-sandbox-enabled true`
- 禁用危险命令：`CONFIG SET rename-command FLUSHALL ""`
- 设置强密码：防止未授权访问

## 8. 排查工具

### 8.1 内置工具

**redis-cli**：
- **INFO**：获取服务器信息和统计数据
- **CONFIG GET**：获取配置信息
- **CLIENT LIST**：查看客户端连接详情
- **SCAN**：迭代遍历键
- **--bigkeys**：查找大键
- **--latency**：测试延迟

**使用示例**：
```bash
# 查看内存使用
redis-cli -h localhost -p 9736 INFO memory

# 查找大键
redis-cli -h localhost -p 9736 --bigkeys

# 测试延迟
redis-cli -h localhost -p 9736 --latency
```

### 8.2 系统工具

**top**：监控 CPU 和内存使用
**free**：查看系统内存使用
**iostat**：监控磁盘 I/O
**netstat**：查看网络连接
**tcpdump**：网络抓包分析
**dmesg**：查看系统日志

**使用示例**：
```bash
# 监控系统资源
top

# 查看内存使用
free -m

# 监控磁盘 I/O
iostat -x 1

# 查看网络连接
netstat -tnp

# 网络抓包
tcpdump -i eth0 port 9736
```

### 8.3 日志分析

**查看服务日志**：
- 标准输出：服务启动时的控制台输出
- 日志文件：如果配置了 `logfile`，查看对应文件

**分析崩溃日志**：
- JVM 崩溃日志：`hs_err_pid*.log` 文件
- 系统日志：`/var/log/syslog` 或 `/var/log/messages`

**使用示例**：
```bash
# 查看服务日志
tail -f /var/log/luban-rds.log

# 查看 JVM 崩溃日志
cat hs_err_pid*.log

# 查看系统日志
tail -f /var/log/syslog | grep luban-rds
```

## 9. 故障恢复

### 9.1 数据恢复

**从 RDB 文件恢复**：
1. **停止服务**：`redis-cli -h localhost -p 9736 SHUTDOWN`
2. **复制 RDB 文件**：`cp /path/to/backup/dump.rdb /data/`
3. **启动服务**：`java -jar luban-rds-bin-1.0.0.jar`

**从 AOF 文件恢复**：
1. **停止服务**：`redis-cli -h localhost -p 9736 SHUTDOWN`
2. **复制 AOF 文件**：`cp /path/to/backup/appendonly.aof /data/`
3. **启动服务**：`java -jar luban-rds-bin-1.0.0.jar`

### 9.2 服务恢复

**紧急恢复**：
1. **停止服务**：`kill -SIGTERM <pid>`
2. **清理环境**：
   ```bash
   rm -f /data/dump.rdb /data/appendonly.aof
   ```
3. **启动服务**：`java -jar luban-rds-bin-1.0.0.jar`
4. **导入数据**：从备份或其他来源导入数据

**常规恢复**：
1. **停止服务**：`redis-cli -h localhost -p 9736 SHUTDOWN`
2. **检查配置**：确保配置正确
3. **检查持久化文件**：确保文件完整
4. **启动服务**：`java -jar luban-rds-bin-1.0.0.jar`
5. **验证服务**：`redis-cli -h localhost -p 9736 PING`

## 10. 预防措施

### 10.1 定期备份

**建立备份策略**：
- 每日备份：每天执行一次完整备份
- 每周备份：每周执行一次完整备份，并保留一周
- 每月备份：每月执行一次完整备份，并保留一个月

**备份脚本**：
```bash
#!/bin/bash

# 备份目录
BACKUP_DIR="/path/to/backup"
DATE=$(date +"%Y%m%d_%H%M%S")

# 创建备份目录
mkdir -p "$BACKUP_DIR/$DATE"

# 执行 BGSAVE
redis-cli -h localhost -p 9736 -a your-password BGSAVE

# 等待完成
while true; do
  BGSAVE_STATUS=$(redis-cli -h localhost -p 9736 -a your-password INFO persistence | grep "rdb_bgsave_in_progress" | awk -F":" '{print $2}' | tr -d '\r')
  if [ "$BGSAVE_STATUS" = "0" ]; then
    break
  fi
  sleep 1
 done

# 复制文件
cp /data/dump.rdb "$BACKUP_DIR/$DATE/"
if [ -f /data/appendonly.aof ]; then
  cp /data/appendonly.aof "$BACKUP_DIR/$DATE/"
fi

# 压缩
 tar -czf "$BACKUP_DIR/luban-rds-backup-$DATE.tar.gz" -C "$BACKUP_DIR" "$DATE"

# 清理
rm -rf "$BACKUP_DIR/$DATE"
find "$BACKUP_DIR" -name "luban-rds-backup-*.tar.gz" -mtime +30 -delete

echo "Backup completed: $BACKUP_DIR/luban-rds-backup-$DATE.tar.gz"
```

### 10.2 监控告警

**设置监控**：
- 使用 Prometheus + Grafana 监控服务
- 设置关键指标的告警阈值
- 配置多渠道告警（邮件、短信、即时通讯）

**关键监控指标**：
- 内存使用
- 连接数
- 命令执行速率
- 持久化状态
- 网络延迟

### 10.3 安全加固

**安全配置**：
- 设置强密码
- 限制绑定地址
- 禁用危险命令
- 启用 Lua 沙箱
- 配置防火墙

**定期安全检查**：
- 检查未授权访问
- 检查密码强度
- 检查危险命令配置
- 检查网络暴露情况

### 10.4 性能优化

**定期优化**：
- 检查大键
- 检查慢查询
- 优化配置
- 升级版本

**最佳实践**：
- 使用连接池
- 使用批量命令
- 合理设置过期时间
- 选择合适的数据结构

## 11. 常见问题速查

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| 无法连接 | 服务未启动 | 启动服务 |
| 连接数过多 | 超过 maxclients 限制 | 增加 maxclients 或关闭空闲连接 |
| 内存使用过高 | 数据量过大 | 设置过期时间或增加内存 |
| 命令执行缓慢 | 大键操作 | 优化大键或使用批量命令 |
| 持久化失败 | 磁盘空间不足 | 清理磁盘空间 |
| 服务崩溃 | 内存溢出 | 增加内存或优化配置 |
| 网络断连 | 超时设置不合理 | 调整 timeout 和 tcp-keepalive |
| 未授权访问 | 未设置密码 | 设置强密码 |
| 数据恢复失败 | 持久化文件损坏 | 使用备份文件 |
| 性能下降 | 内存碎片过多 | 重启服务 |

## 12. 下一步

- **[安装部署](./installation.md)**：学习如何在不同环境下安装和部署 Luban-RDS
- **[配置指南](./configuration.md)**：了解详细的配置选项和优化建议
- **[使用指南](../guide/)**：学习如何使用 Luban-RDS 的各项功能
