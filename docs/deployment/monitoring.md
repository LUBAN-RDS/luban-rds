---
title: 监控维护
---

# 监控维护

本部分详细介绍了如何监控和维护 Luban-RDS 服务，包括监控指标、工具集成、日常维护和最佳实践。

## 1. 监控指标

### 1.1 核心指标

| 指标类别 | 指标名称 | 说明 | 单位 | 参考值 |
|----------|----------|------|------|--------|
| **内存** | used_memory | 已使用内存 | bytes | < maxmemory |
| **内存** | used_memory_rss | 物理内存使用 | bytes | < 系统内存 |
| **内存** | mem_fragmentation_ratio | 内存碎片率 | ratio | 1.0-1.5 |
| **内存** | expired_keys | 过期键数量 | count | - |
| **内存** | evicted_keys | 淘汰键数量 | count | - |
| **客户端** | connected_clients | 活跃连接数 | count | < maxclients |
| **客户端** | blocked_clients | 阻塞客户端数 | count | 0 |
| **命令** | total_commands_processed | 处理命令总数 | count | - |
| **命令** | instantaneous_ops_per_sec | 每秒执行命令数 | ops/s | - |
| **网络** | total_net_input_bytes | 网络输入字节数 | bytes | - |
| **网络** | total_net_output_bytes | 网络输出字节数 | bytes | - |
| **网络** | instantaneous_input_kbps | 每秒网络输入 | kbps | - |
| **网络** | instantaneous_output_kbps | 每秒网络输出 | kbps | - |
| **持久化** | rdb_changes_since_last_save | 上次保存后修改数 | count | - |
| **持久化** | rdb_last_save_time | 上次 RDB 保存时间戳 | timestamp | - |
| **持久化** | aof_current_size | AOF 文件当前大小 | bytes | - |
| **持久化** | aof_rewrite_in_progress | AOF 重写是否进行中 | 0/1 | 0 |
| **键空间** | db0:keys | 数据库 0 的键数 | count | - |
| **键空间** | db0:expires | 数据库 0 的过期键数 | count | - |
| **Lua** | used_memory_lua | Lua 脚本使用内存 | bytes | - |
| **Lua** | lua_scripts | Lua 脚本数量 | count | - |
| **CPU** | os_cpu_load_average | 系统 CPU 负载 | load | < CPU 核心数 |

### 1.2 关键指标说明

**内存使用**：
- `used_memory`：服务实际使用的内存，包括数据和开销
- `used_memory_rss`：操作系统分配给服务的物理内存
- `mem_fragmentation_ratio`：内存碎片率，计算公式为 `used_memory_rss / used_memory`
  - 1.0-1.5：正常范围
  - > 1.5：内存碎片较多，可能需要重启服务
  - < 1.0：可能存在内存交换，性能会下降

**客户端连接**：
- `connected_clients`：当前活跃的客户端连接数
- `blocked_clients`：被阻塞的客户端数，如执行 `BLPOP` 等命令的客户端

**命令执行**：
- `instantaneous_ops_per_sec`：当前每秒执行的命令数，反映服务的负载情况
- `total_commands_processed`：服务启动以来处理的命令总数

**网络流量**：
- `instantaneous_input_kbps` 和 `instantaneous_output_kbps`：当前网络输入输出速率
- `total_net_input_bytes` 和 `total_net_output_bytes`：服务启动以来的网络流量总量

**持久化状态**：
- `rdb_changes_since_last_save`：上次 RDB 保存后数据库的修改次数
- `aof_current_size`：当前 AOF 文件大小
- `aof_rewrite_in_progress`：AOF 重写是否正在进行中

**键空间**：
- `db0:keys`：数据库 0 中的键数量
- `db0:expires`：数据库 0 中设置了过期时间的键数量

## 2. 监控工具

### 2.1 内置监控命令

**INFO 命令**：返回服务器的详细信息和统计数据
```bash
# 查看所有信息
redis-cli -h localhost -p 9736 INFO

# 查看特定部分信息
redis-cli -h localhost -p 9736 INFO memory
redis-cli -h localhost -p 9736 INFO clients
redis-cli -h localhost -p 9736 INFO stats
redis-cli -h localhost -p 9736 INFO persistence
redis-cli -h localhost -p 9736 INFO keyspace
```

**CONFIG 命令**：查看和修改服务器配置
```bash
# 查看所有配置
redis-cli -h localhost -p 9736 CONFIG GET *

# 查看特定配置
redis-cli -h localhost -p 9736 CONFIG GET maxmemory
redis-cli -h localhost -p 9736 CONFIG GET maxmemory-policy
```

**STATS 命令**：查看统计信息
```bash
redis-cli -h localhost -p 9736 STATS
```

### 2.2 外部监控工具

**Prometheus + Grafana**：
- **Prometheus**：收集和存储监控数据
- **Grafana**：可视化监控数据，创建仪表盘

**配置步骤**：
1. **安装 Prometheus**：
   ```bash
   wget https://github.com/prometheus/prometheus/releases/download/v2.30.0/prometheus-2.30.0.linux-amd64.tar.gz
   tar -xzf prometheus-2.30.0.linux-amd64.tar.gz
   cd prometheus-2.30.0.linux-amd64
   ```

2. **配置 Prometheus**：
   ```yaml
   # prometheus.yml
   global:
     scrape_interval: 15s

   scrape_configs:
     - job_name: 'luban-rds'
       static_configs:
         - targets: ['localhost:9121']  # Redis Exporter 端口
   ```

3. **安装 Redis Exporter**：
   ```bash
   wget https://github.com/oliver006/redis_exporter/releases/download/v1.33.0/redis_exporter-v1.33.0.linux-amd64.tar.gz
   tar -xzf redis_exporter-v1.33.0.linux-amd64.tar.gz
   cd redis_exporter-v1.33.0.linux-amd64
   ```

4. **启动 Redis Exporter**：
   ```bash
   ./redis_exporter --redis.addr=redis://localhost:9736 --redis.password=your-password
   ```

5. **启动 Prometheus**：
   ```bash
   ./prometheus --config.file=prometheus.yml
   ```

6. **安装 Grafana**：
   ```bash
   wget https://dl.grafana.com/oss/release/grafana_8.1.0_amd64.deb
   sudo dpkg -i grafana_8.1.0_amd64.deb
   sudo systemctl start grafana-server
   ```

7. **配置 Grafana**：
   - 访问 `http://localhost:3000`（默认用户名/密码：admin/admin）
   - 添加 Prometheus 数据源
   - 导入 Redis 仪表盘模板（推荐模板 ID：763）

**Zabbix**：
- 企业级监控解决方案，支持分布式监控
- 提供 Redis 监控模板
- 支持告警和自动发现

**Datadog**：
- 云原生监控平台
- 提供 Redis 集成
- 支持实时监控和告警

**New Relic**：
- 应用性能监控平台
- 提供 Redis 监控集成
- 支持分布式追踪

### 2.3 自定义监控脚本

**内存使用监控**：
```bash
#!/bin/bash

HOST="localhost"
PORT="9736"
PASSWORD="your-password"

# 获取内存使用情况
MEMORY_INFO=$(redis-cli -h $HOST -p $PORT -a $PASSWORD INFO memory)

# 提取关键指标
USED_MEMORY=$(echo "$MEMORY_INFO" | grep "used_memory_human" | awk -F":" '{print $2}' | tr -d '\r')
USED_MEMORY_RSS=$(echo "$MEMORY_INFO" | grep "used_memory_rss_human" | awk -F":" '{print $2}' | tr -d '\r')
MEM_FRAG_RATIO=$(echo "$MEMORY_INFO" | grep "mem_fragmentation_ratio" | awk -F":" '{print $2}' | tr -d '\r')

# 输出结果
echo "Memory Usage:"
echo "  Used Memory: $USED_MEMORY"
echo "  Used Memory RSS: $USED_MEMORY_RSS"
echo "  Memory Fragmentation Ratio: $MEM_FRAG_RATIO"

# 检查内存碎片率
FRAG_RATIO=$(echo "$MEM_FRAG_RATIO" | awk -F" " '{print $1}')
if (( $(echo "$FRAG_RATIO > 1.5" | bc -l) )); then
  echo "WARNING: Memory fragmentation ratio is high ($FRAG_RATIO > 1.5)"
fi
```

**客户端连接监控**：
```bash
#!/bin/bash

HOST="localhost"
PORT="9736"
PASSWORD="your-password"

# 获取客户端信息
CLIENTS_INFO=$(redis-cli -h $HOST -p $PORT -a $PASSWORD INFO clients)

# 提取关键指标
CONNECTED_CLIENTS=$(echo "$CLIENTS_INFO" | grep "connected_clients" | awk -F":" '{print $2}' | tr -d '\r')
BLOCKED_CLIENTS=$(echo "$CLIENTS_INFO" | grep "blocked_clients" | awk -F":" '{print $2}' | tr -d '\r')

# 输出结果
echo "Client Connections:"
echo "  Connected Clients: $CONNECTED_CLIENTS"
echo "  Blocked Clients: $BLOCKED_CLIENTS"

# 检查连接数
if (( $CONNECTED_CLIENTS > 5000 )); then
  echo "WARNING: Connected clients count is high ($CONNECTED_CLIENTS > 5000)"
fi
```

**命令执行监控**：
```bash
#!/bin/bash

HOST="localhost"
PORT="9736"
PASSWORD="your-password"

# 获取统计信息
STATS_INFO=$(redis-cli -h $HOST -p $PORT -a $PASSWORD INFO stats)

# 提取关键指标
INSTANTANEOUS_OPS=$(echo "$STATS_INFO" | grep "instantaneous_ops_per_sec" | awk -F":" '{print $2}' | tr -d '\r')
TOTAL_COMMANDS=$(echo "$STATS_INFO" | grep "total_commands_processed" | awk -F":" '{print $2}' | tr -d '\r')

# 输出结果
echo "Command Execution:"
echo "  Instantaneous Ops/sec: $INSTANTANEOUS_OPS"
echo "  Total Commands Processed: $TOTAL_COMMANDS"
```

## 3. 日常维护

### 3.1 定期备份

**持久化文件备份**：
- **RDB 文件**：定期备份 `dump.rdb` 文件
- **AOF 文件**：定期备份 `appendonly.aof` 文件

**备份策略**：
1. **每日备份**：每天执行一次完整备份
2. **每周备份**：每周执行一次完整备份，并保留一周
3. **每月备份**：每月执行一次完整备份，并保留一个月

**备份脚本**：
```bash
#!/bin/bash

# 备份目录
BACKUP_DIR="/path/to/backup"
DATE=$(date +"%Y%m%d_%H%M%S")

# 创建备份目录
mkdir -p "$BACKUP_DIR/$DATE"

# 执行 BGSAVE 生成 RDB 文件
redis-cli -h localhost -p 9736 -a your-password BGSAVE

# 等待 BGSAVE 完成
while true; do
  BGSAVE_STATUS=$(redis-cli -h localhost -p 9736 -a your-password INFO persistence | grep "rdb_bgsave_in_progress" | awk -F":" '{print $2}' | tr -d '\r')
  if [ "$BGSAVE_STATUS" = "0" ]; then
    break
  fi
  sleep 1
 done

# 复制持久化文件
cp /data/dump.rdb "$BACKUP_DIR/$DATE/"
if [ -f /data/appendonly.aof ]; then
  cp /data/appendonly.aof "$BACKUP_DIR/$DATE/"
fi

# 压缩备份文件
tar -czf "$BACKUP_DIR/luban-rds-backup-$DATE.tar.gz" -C "$BACKUP_DIR" "$DATE"

# 删除临时目录
rm -rf "$BACKUP_DIR/$DATE"

# 清理过期备份（保留 30 天）
find "$BACKUP_DIR" -name "luban-rds-backup-*.tar.gz" -mtime +30 -delete

echo "Backup completed: $BACKUP_DIR/luban-rds-backup-$DATE.tar.gz"
```

### 3.2 内存碎片整理

**问题**：内存碎片率过高（> 1.5）

**解决方案**：
1. **重启服务**：最简单有效的方法，重启后内存碎片会被清理
2. **内存淘汰**：如果无法重启，可以通过设置更激进的内存淘汰策略，让系统主动淘汰一些键，从而触发内存整理

**预防措施**：
- 启用 `lazyfree-lazy-eviction` 和 `lazyfree-lazy-expire`
- 合理设置 `maxmemory`，避免内存使用接近系统上限
- 定期监控内存碎片率，及时发现问题

### 3.3 持久化文件管理

**RDB 文件管理**：
- 定期检查 RDB 文件大小，确保文件正常生成
- 验证 RDB 文件的完整性，确保可以用于恢复
- 合理设置 `save` 策略，平衡数据安全性和性能

**AOF 文件管理**：
- 定期检查 AOF 文件大小，避免文件过大
- 监控 AOF 重写过程，确保重写正常完成
- 合理设置 `aof-rewrite-percentage` 和 `aof-rewrite-min-size`

**文件验证**：
```bash
# 检查 RDB 文件是否存在
if [ -f /data/dump.rdb ]; then
  echo "RDB file exists: $(du -h /data/dump.rdb)"
else
  echo "ERROR: RDB file not found"
fi

# 检查 AOF 文件是否存在
if [ -f /data/appendonly.aof ]; then
  echo "AOF file exists: $(du -h /data/appendonly.aof)"
else
  echo "AOF file not found"
fi
```

### 3.4 日志管理

**日志轮转**：
- 配置日志文件轮转，避免日志文件过大
- 保留适当的日志历史，便于问题排查

**日志清理**：
- 定期清理过期日志文件
- 压缩归档重要的日志文件

**日志分析**：
- 定期分析日志文件，发现潜在问题
- 关注错误和警告信息，及时处理

### 3.5 性能优化

**定期检查**：
- 检查慢查询日志，优化频繁执行的慢命令
- 检查热点键，优化数据结构和访问模式
- 检查内存使用情况，优化内存配置

**优化措施**：
1. **命令优化**：
   - 使用批量命令，如 `MSET`、`MGET`
   - 避免使用 `KEYS` 命令，改用 `SCAN` 命令
   - 对于复杂逻辑，使用 Lua 脚本在服务器端执行

2. **数据结构优化**：
   - 选择合适的数据结构，如使用 `Hash` 存储对象
   - 合理设置键的过期时间，避免内存泄漏
   - 避免使用过大的键，如超大的 `List` 或 `Hash`

3. **配置优化**：
   - 根据业务需求调整 `maxmemory` 和 `maxmemory-policy`
   - 调整持久化策略，平衡数据安全性和性能
   - 根据硬件配置调整线程数和网络参数

## 4. 健康检查

### 4.1 基本健康检查

**PING 命令**：
```bash
redis-cli -h localhost -p 9736 -a your-password PING

# 正常输出
PONG
```

**INFO 命令**：
```bash
redis-cli -h localhost -p 9736 -a your-password INFO server

# 正常输出应包含服务器信息
```

**简单命令**：
```bash
redis-cli -h localhost -p 9736 -a your-password SET health_check "OK"
redis-cli -h localhost -p 9736 -a your-password GET health_check

# 正常输出
"OK"
```

### 4.2 深度健康检查

**内存检查**：
```bash
redis-cli -h localhost -p 9736 -a your-password INFO memory | grep -E "used_memory|mem_fragmentation_ratio"
```

**持久化检查**：
```bash
redis-cli -h localhost -p 9736 -a your-password INFO persistence | grep -E "rdb_|aof_"
```

**客户端检查**：
```bash
redis-cli -h localhost -p 9736 -a your-password INFO clients
```

**键空间检查**：
```bash
redis-cli -h localhost -p 9736 -a your-password INFO keyspace
```

**命令统计检查**：
```bash
redis-cli -h localhost -p 9736 -a your-password INFO commandstats | head -20
```

### 4.3 健康检查脚本

```bash
#!/bin/bash

HOST="localhost"
PORT="9736"
PASSWORD="your-password"

# 检查连接
CONNECTION_CHECK=$(redis-cli -h $HOST -p $PORT -a $PASSWORD PING 2>/dev/null)
if [ "$CONNECTION_CHECK" != "PONG" ]; then
  echo "ERROR: Cannot connect to Luban-RDS"
  exit 1
fi

# 检查内存使用
MEMORY_INFO=$(redis-cli -h $HOST -p $PORT -a $PASSWORD INFO memory)
USED_MEMORY=$(echo "$MEMORY_INFO" | grep "used_memory_human" | awk -F":" '{print $2}' | tr -d '\r')
MEM_FRAG_RATIO=$(echo "$MEMORY_INFO" | grep "mem_fragmentation_ratio" | awk -F":" '{print $2}' | tr -d '\r')

# 检查客户端连接
CLIENTS_INFO=$(redis-cli -h $HOST -p $PORT -a $PASSWORD INFO clients)
CONNECTED_CLIENTS=$(echo "$CLIENTS_INFO" | grep "connected_clients" | awk -F":" '{print $2}' | tr -d '\r')
BLOCKED_CLIENTS=$(echo "$CLIENTS_INFO" | grep "blocked_clients" | awk -F":" '{print $2}' | tr -d '\r')

# 检查持久化状态
PERSISTENCE_INFO=$(redis-cli -h $HOST -p $PORT -a $PASSWORD INFO persistence)
RDB_SAVE_IN_PROGRESS=$(echo "$PERSISTENCE_INFO" | grep "rdb_bgsave_in_progress" | awk -F":" '{print $2}' | tr -d '\r')
AOF_REWRITE_IN_PROGRESS=$(echo "$PERSISTENCE_INFO" | grep "aof_rewrite_in_progress" | awk -F":" '{print $2}' | tr -d '\r')

# 输出健康状态
echo "Luban-RDS Health Check"
echo "====================="
echo "Connection: OK"
echo "Memory Usage: $USED_MEMORY"
echo "Memory Fragmentation Ratio: $MEM_FRAG_RATIO"
echo "Connected Clients: $CONNECTED_CLIENTS"
echo "Blocked Clients: $BLOCKED_CLIENTS"
echo "RDB Save In Progress: $RDB_SAVE_IN_PROGRESS"
echo "AOF Rewrite In Progress: $AOF_REWRITE_IN_PROGRESS"

# 检查内存碎片率
FRAG_RATIO=$(echo "$MEM_FRAG_RATIO" | awk -F" " '{print $1}')
if (( $(echo "$FRAG_RATIO > 1.5" | bc -l) )); then
  echo "WARNING: Memory fragmentation ratio is high ($FRAG_RATIO > 1.5)"
fi

# 检查连接数
if (( $CONNECTED_CLIENTS > 5000 )); then
  echo "WARNING: Connected clients count is high ($CONNECTED_CLIENTS > 5000)"
fi

echo "====================="
echo "Health check completed"
```

## 5. 告警系统

### 5.1 告警指标

**关键告警指标**：
| 指标 | 阈值 | 告警级别 | 说明 |
|------|------|----------|------|
| connected_clients | > 80% of maxclients | 警告 | 连接数接近上限 |
| used_memory | > 80% of maxmemory | 警告 | 内存使用接近上限 |
| used_memory | > 90% of maxmemory | 严重 | 内存使用超过警告阈值 |
| mem_fragmentation_ratio | > 1.5 | 警告 | 内存碎片率过高 |
| mem_fragmentation_ratio | > 2.0 | 严重 | 内存碎片率严重过高 |
| blocked_clients | > 100 | 警告 | 阻塞客户端数过多 |
| instantaneous_ops_per_sec | > 10000 | 警告 | 命令执行速率过高 |
| rdb_bgsave_in_progress | > 300s | 警告 | RDB 保存时间过长 |
| aof_rewrite_in_progress | > 600s | 警告 | AOF 重写时间过长 |
| expired_keys | > 10000/s | 警告 | 每秒过期键数过多 |
| evicted_keys | > 1000/s | 警告 | 每秒淘汰键数过多 |

### 5.2 告警方式

**告警渠道**：
1. **邮件**：发送告警邮件到运维团队
2. **短信**：发送告警短信到相关人员
3. **即时通讯**：通过 Slack、Discord、企业微信等发送告警
4. **监控平台**：在监控平台上显示告警

**告警分级**：
- **信息**：一般信息，不需要立即处理
- **警告**：需要关注，可能会影响服务
- **严重**：需要立即处理，已经影响服务
- **紧急**：需要紧急处理，服务可能会中断

### 5.3 告警配置

**Prometheus Alertmanager**：
```yaml
# alertmanager.yml
global:
  resolve_timeout: 5m
  smtp_smarthost: 'smtp.example.com:587'
  smtp_from: 'alerts@example.com'
  smtp_auth_username: 'alerts@example.com'
  smtp_auth_password: 'your-password'

route:
  group_by: ['alertname']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 1h
  receiver: 'email'

receivers:
- name: 'email'
  email_configs:
  - to: 'ops@example.com'
    send_resolved: true

inhibit_rules:
  - source_match:
      severity: 'critical'
    target_match:
      severity: 'warning'
    equal: ['alertname', 'instance']
```

**Prometheus 告警规则**：
```yaml
# redis_alerts.yml
groups:
- name: redis_alerts
  rules:
  - alert: RedisMemoryUsageHigh
    expr: redis_memory_used_bytes / redis_memory_max_bytes * 100 > 80
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Redis memory usage high"
      description: "Redis instance {{ $labels.instance }} memory usage is {{ $value }}%"

  - alert: RedisMemoryUsageCritical
    expr: redis_memory_used_bytes / redis_memory_max_bytes * 100 > 90
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "Redis memory usage critical"
      description: "Redis instance {{ $labels.instance }} memory usage is {{ $value }}%"

  - alert: RedisConnectionCountHigh
    expr: redis_connected_clients > redis_config_maxclients * 0.8
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Redis connection count high"
      description: "Redis instance {{ $labels.instance }} has {{ $value }} connections"

  - alert: RedisMemoryFragmentationHigh
    expr: redis_memory_fragmentation_ratio > 1.5
    for: 10m
    labels:
      severity: warning
    annotations:
      summary: "Redis memory fragmentation high"
      description: "Redis instance {{ $labels.instance }} memory fragmentation ratio is {{ $value }}"
```

## 6. 最佳实践

### 6.1 监控最佳实践

**1. 全面监控**：
- 监控所有核心指标，包括内存、客户端、命令执行、网络等
- 建立完整的监控体系，覆盖服务的各个方面

**2. 合理阈值**：
- 根据实际业务场景设置合理的告警阈值
- 避免设置过严的阈值导致误告警
- 定期调整阈值，适应业务变化

**3. 多维度监控**：
- 从不同维度监控服务，如实例、集群、数据中心等
- 建立全局视图，了解整个系统的运行状态

**4. 历史数据分析**：
- 存储和分析历史监控数据
- 识别性能趋势和潜在问题
- 为容量规划提供依据

### 6.2 维护最佳实践

**1. 定期维护**：
- 制定定期维护计划，包括备份、检查、优化等
- 严格按照计划执行维护任务
- 记录维护过程和结果

**2. 变更管理**：
- 对配置变更、代码变更等进行严格的变更管理
- 执行变更前进行充分的测试
- 制定回滚计划，确保变更可以回滚

**3. 灾难恢复**：
- 制定详细的灾难恢复计划
- 定期测试灾难恢复流程
- 确保在发生灾难时能够快速恢复服务

**4. 文档管理**：
- 维护详细的系统文档，包括架构、配置、维护流程等
- 及时更新文档，反映系统的最新状态
- 确保文档的准确性和完整性

### 6.3 性能最佳实践

**1. 资源规划**：
- 根据业务需求合理规划硬件资源
- 预留足够的资源余量，应对业务增长
- 定期进行容量规划，避免资源不足

**2. 配置优化**：
- 根据实际业务场景优化配置
- 定期 review 配置，确保配置合理
- 学习和应用最新的最佳实践

**3. 负载测试**：
- 定期进行负载测试，了解系统的性能极限
- 根据测试结果优化系统配置和架构
- 为容量规划提供数据支持

**4. 持续优化**：
- 建立持续优化的文化和流程
- 定期分析性能瓶颈，进行优化
- 跟踪优化效果，持续改进

## 7. 常见问题与解决方案

### 7.1 内存使用过高

**问题**：内存使用持续增长，接近或超过 maxmemory 限制

**解决方案**：
- 检查是否存在内存泄漏
- 检查是否有大键未设置过期时间
- 调整 maxmemory-policy，使用更激进的淘汰策略
- 增加系统内存或减少数据量
- 重启服务（作为最后手段）

### 7.2 连接数过多

**问题**：客户端连接数持续增长，接近或超过 maxclients 限制

**解决方案**：
- 检查客户端是否正确关闭连接
- 调整 timeout 设置，及时释放空闲连接
- 增加 maxclients 设置（如果系统资源允许）
- 检查是否存在连接泄漏

### 7.3 命令执行缓慢

**问题**：命令执行时间变长，响应变慢

**解决方案**：
- 检查是否存在大键操作
- 检查是否存在复杂的 Lua 脚本
- 检查是否存在网络延迟
- 检查是否存在磁盘 I/O 瓶颈（如持久化操作）
- 优化命令执行，使用批量命令减少网络往返

### 7.4 持久化失败

**问题**：RDB 保存或 AOF 重写失败

**解决方案**：
- 检查磁盘空间是否充足
- 检查磁盘 I/O 是否正常
- 检查目录权限是否正确
- 调整持久化策略，减少持久化频率
- 检查是否存在硬件故障

### 7.5 服务崩溃

**问题**：服务突然崩溃，无法正常启动

**解决方案**：
- 检查日志文件，查找崩溃原因
- 检查是否存在内存溢出
- 检查是否存在硬件故障
- 尝试使用备份文件恢复数据
- 重启服务并监控运行状态

## 8. 下一步

- **[故障排查](./troubleshooting.md)**：掌握常见问题的排查和解决方法
- **[配置指南](./configuration.md)**：了解详细的配置选项和优化建议
- **[使用指南](../guide/)**：学习如何使用 Luban-RDS 的各项功能
