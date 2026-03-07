---
title: 安装部署
---

# 安装部署

本部分详细介绍了如何在不同环境下安装和部署 Luban-RDS 服务。

## 1. 环境准备

### 1.1 系统要求

| 环境 | 最低要求 | 推荐配置 |
|------|----------|----------|
| **操作系统** | Windows 7+, Linux, macOS | Linux (CentOS 7+, Ubuntu 18.04+) |
| **Java 版本** | Java 17+ | Java 17 |
| **内存** | 512MB | 2GB+ |
| **CPU** | 1 核 | 2 核+ |
| **网络** | 100Mbps | 1Gbps |

### 1.2 Java 安装

Luban-RDS 需要 Java 17 或更高版本。请确保系统已安装正确版本的 Java：

```bash
# 检查 Java 版本
java -version

# 输出示例
java version "17.0.10"
Java(TM) SE Runtime Environment 17.0.10 (build 17.0.10+7-LTS)
Java HotSpot(TM) 64-Bit Server VM 17.0.10 (build 17.0.10+7-LTS, mixed mode)
```

如果未安装 Java，请根据操作系统安装相应版本：

**Ubuntu/Debian：**
```bash
sudo apt update
sudo apt install openjdk-17-jdk
```

**CentOS/RHEL：**
```bash
sudo yum install java-17-openjdk-devel
```

**Windows：**
下载并安装 [Oracle JDK 17](https://www.oracle.com/java/technologies/downloads/) 或 [Temurin JDK 17](https://adoptium.net/)

**macOS：**
```bash
# 使用 Homebrew
brew install openjdk@17

# 或下载安装包
# https://adoptium.net/
```

## 2. 安装方式

### 2.1 从源码构建

**步骤 1：克隆代码库**
```bash
# 从 GitHub 克隆
git clone https://github.com/LUBAN-RDS/luban-rds.git

# 或从 Gitee 克隆
git clone https://gitee.com/luban-rds/luban-rds.git

cd luban-rds
```

**步骤 2：构建项目**
```bash
# 使用 Maven 构建
mvn clean package -DskipTests

# 构建结果将在 luban-rds-bin/target/ 目录中
```

**步骤 3：启动服务**
```bash
# 进入构建输出目录
cd luban-rds-bin/target/

# 启动服务
java -jar luban-rds-bin-1.0.0.jar

# 或使用启动脚本
chmod +x start.sh
./start.sh
```

### 2.2 使用预编译包

**步骤 1：下载预编译包**
从 GitHub Releases 页面下载最新的预编译包：
```bash
wget https://github.com/LUBAN-RDS/luban-rds/releases/download/v1.0.0/luban-rds-bin-1.0.0.jar
```

**步骤 2：启动服务**
```bash
java -jar luban-rds-bin-1.0.0.jar
```

### 2.3 Docker 部署

**步骤 1：拉取镜像**
```bash
docker pull your-org/luban-rds:1.0.0
```

**步骤 2：运行容器**
```bash
# 基本运行
docker run -d --name luban-rds -p 9736:9736 your-org/luban-rds:1.0.0

# 带持久化
docker run -d --name luban-rds \
  -p 9736:9736 \
  -v /path/to/data:/data \
  your-org/luban-rds:1.0.0

# 带自定义配置
docker run -d --name luban-rds \
  -p 9736:9736 \
  -v /path/to/conf/luban-rds.conf:/etc/luban-rds.conf \
  your-org/luban-rds:1.0.0
```

**步骤 3：构建自定义镜像（可选）**
```bash
# 在项目根目录创建 Dockerfile
cat > Dockerfile << EOF
FROM openjdk:11-jre-slim

WORKDIR /app

COPY luban-rds-bin/target/luban-rds-bin-1.0.0.jar /app/

EXPOSE 9736

CMD ["java", "-jar", "luban-rds-bin-1.0.0.jar"]
EOF

# 构建镜像
docker build -t your-org/luban-rds:1.0.0 .
```

### 2.4 Kubernetes 部署

**步骤 1：创建部署配置**
```yaml
# luban-rds-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: luban-rds
  labels:
    app: luban-rds
spec:
  replicas: 1
  selector:
    matchLabels:
      app: luban-rds
  template:
    metadata:
      labels:
        app: luban-rds
    spec:
      containers:
      - name: luban-rds
        image: your-org/luban-rds:1.0.0
        ports:
        - containerPort: 9736
        resources:
          limits:
            memory: "2Gi"
            cpu: "1"
          requests:
            memory: "1Gi"
            cpu: "500m"
        volumeMounts:
        - name: data
          mountPath: /data
      volumes:
      - name: data
        emptyDir: {}
```

**步骤 2：创建服务配置**
```yaml
# luban-rds-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: luban-rds
spec:
  selector:
    app: luban-rds
  ports:
  - port: 9736
    targetPort: 9736
  type: ClusterIP
```

**步骤 3：应用配置**
```bash
# 部署应用
kubectl apply -f luban-rds-deployment.yaml

# 创建服务
kubectl apply -f luban-rds-service.yaml

# 查看状态
kubectl get pods
kubectl get services
```

**步骤 4：使用持久卷（可选）**
```yaml
# luban-rds-pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: luban-rds-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
```

更新 deployment.yaml，使用 PVC：
```yaml
volumes:
- name: data
  persistentVolumeClaim:
    claimName: luban-rds-pvc
```

### 2.5 Spring Boot 集成

**步骤 1：添加依赖**
在 Spring Boot 项目的 pom.xml 中添加依赖：

```xml

<dependency>
    <groupId>com.janeluo</groupId>
    <artifactId>luban-rds-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**步骤 2：配置属性**
在 application.properties 或 application.yml 中添加配置：

**application.properties：**
```properties
# 服务器配置
spring.luban.rds.port=9736
spring.luban.rds.host=localhost

# 认证配置
spring.luban.rds.password=your-secure-password

# 持久化配置
spring.luban.rds.persistence.enabled=true
spring.luban.rds.persistence.rdb-enabled=true
spring.luban.rds.persistence.aof-enabled=false

# 其他配置
spring.luban.rds.databases=16
```

**application.yml：**
```yaml
spring:
  luban:
    rds:
      port: 9736
      host: localhost
      password: your-secure-password
      persistence:
        enabled: true
        rdb-enabled: true
        aof-enabled: false
      databases: 16
```

**步骤 3：使用示例**
```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    public void setValue(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }
    
    public String getValue(String key) {
        return redisTemplate.opsForValue().get(key);
    }
}
```

## 3. 嵌入式部署

Luban-RDS 支持嵌入到应用中使用，无需单独部署服务：

**步骤 1：添加依赖**

```xml

<dependency>
    <groupId>com.janeluo</groupId>
    <artifactId>luban-rds-server</artifactId>
    <version>1.0.0</version>
</dependency>
```

**步骤 2：初始化服务**

```java
import com.janeluo.luban.rds.server.EmbeddedRedisServer;

public class EmbeddedRedisExample {
    public static void main(String[] args) {
        // 创建嵌入式服务器
        EmbeddedRedisServer server = new EmbeddedRedisServer();

        // 配置服务器
        server.setPort(9736);
        server.setHost("localhost");
        server.setPassword("your-password");

        // 启动服务器
        server.start();
        System.out.println("Embedded Redis server started on port 9736");

        // 应用逻辑...

        // 停止服务器
        // server.stop();
    }
}
```

**步骤 3：使用客户端连接**

```java
import com.janeluo.luban.rds.client.RedisClient;
import com.janeluo.luban.rds.client.NettyRedisClient;

public class RedisClientExample {
    public static void main(String[] args) {
        // 创建客户端
        RedisClient client = new NettyRedisClient("localhost", 9736, "your-password");

        // 连接服务器
        client.connect();

        // 执行命令
        client.set("key", "value");
        String result = client.get("key");
        System.out.println("Get result: " + result);

        // 关闭连接
        client.close();
    }
}
```

## 4. 启动配置

### 4.1 命令行参数

Luban-RDS 支持通过命令行参数配置服务：

```bash
# 基本启动
java -jar luban-rds-bin-1.0.0.jar

# 带端口和主机
java -jar luban-rds-bin-1.0.0.jar --port 9736 --host 0.0.0.0

# 带密码
java -jar luban-rds-bin-1.0.0.jar --password your-secure-password

# 带持久化配置
java -jar luban-rds-bin-1.0.0.jar \
  --rdb-enabled true \
  --rdb-filename dump.rdb \
  --rdb-save 900 1 300 10 60 10000

# 带 AOF 配置
java -jar luban-rds-bin-1.0.0.jar \
  --aof-enabled true \
  --aof-filename appendonly.aof \
  --aof-sync always
```

### 4.2 配置文件

Luban-RDS 支持通过配置文件配置服务：

**步骤 1：创建配置文件**
创建 `luban-rds.conf` 文件：

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
```

**步骤 2：使用配置文件启动**
```bash
java -jar luban-rds-bin-1.0.0.jar --config /path/to/luban-rds.conf
```

### 4.3 环境变量

Luban-RDS 支持通过环境变量配置服务：

```bash
# 设置环境变量
export LUBAN_RDS_PORT=9736
export LUBAN_RDS_HOST=0.0.0.0
export LUBAN_RDS_PASSWORD=your-secure-password
export LUBAN_RDS_RDB_ENABLED=true

# 启动服务
java -jar luban-rds-bin-1.0.0.jar
```

## 5. 服务验证

### 5.1 连接测试

使用 Redis 客户端连接测试服务：

**使用 redis-cli：**
```bash
# 基本连接
redis-cli -h localhost -p 9736

# 带密码连接
redis-cli -h localhost -p 9736 -a your-secure-password

# 执行命令
127.0.0.1:9736> SET test key "Hello Luban-RDS"
OK
127.0.0.1:9736> GET test key
"Hello Luban-RDS"
127.0.0.1:9736> PING
PONG
```

**使用 telnet：**
```bash
telnet localhost 9736
Trying 127.0.0.1...
Connected to localhost.
Escape character is '^]'.
*1
$4
PING

+PONG
```

### 5.2 服务状态检查

**使用 INFO 命令：**
```bash
redis-cli -h localhost -p 9736 INFO

# 输出示例
# Server
redis_version:1.0.0
redis_git_sha1:00000000
redis_git_dirty:0
redis_build_id:abcdef123456
redis_mode:standalone
os:Linux 4.15.0-100-generic x86_64
arch_bits:64
multiplexing_api:epoll
atomicvar_api:c11-builtin
gcc_version:7.5.0
process_id:12345
process_supervised:no
run_id:1234567890abcdef1234567890abcdef12345678
tcp_port:9736
server_time_usec:1618947600000000
uptime_in_seconds:3600
uptime_in_days:0
hz:10
default_config_file:/path/to/luban-rds.conf

# Clients
connected_clients:1
client_recent_max_input_buffer:16
client_recent_max_output_buffer:0
blocked_clients:0

# Memory
used_memory:1048576
used_memory_human:1.00M
used_memory_rss:2097152
used_memory_rss_human:2.00M
used_memory_peak:1048576
used_memory_peak_human:1.00M
used_memory_peak_perc:100.00%
used_memory_overhead:0
used_memory_startup:0
used_memory_dataset:1048576
used_memory_dataset_perc:100.00%
allocator_allocated:1048576
allocator_active:1048576
allocator_resident:1048576
allocator_frag_ratio:1.00
allocator_frag_bytes:0
allocator_rss_ratio:1.00
allocator_rss_bytes:0
total_system_memory:16777216000
total_system_memory_human:15.63G
used_memory_lua:37888
used_memory_lua_human:37.00K
used_memory_scripts:0
used_memory_scripts_human:0B
number_of_cached_scripts:0
maxmemory:2147483648
maxmemory_human:2.00G
maxmemory_policy:allkeys-lru
mem_fragmentation_ratio:2.00
mem_fragmentation_bytes:1048576
mem_not_counted_for_evict:0
mem_replication_backlog:0
mem_clients_slaves:0
mem_clients_normal:49696
mem_aof_buffer:0
mem_allocator:jemalloc-5.1.0
active_defrag_running:0
lazyfree_pending_objects:0

# Persistence
loading:0
rdb_changes_since_last_save:0
rdb_bgsave_in_progress:0
rdb_last_save_time:1618947600
rdb_last_bgsave_status:ok
rdb_last_bgsave_time_sec:-1
rdb_current_bgsave_time_sec:-1
rdb_last_cow_size:0
aof_enabled:0
aof_rewrite_in_progress:0
aof_rewrite_scheduled:0
aof_last_rewrite_time_sec:-1
aof_current_rewrite_time_sec:-1
aof_last_bgrewrite_status:ok
aof_last_write_status:ok
aof_last_cow_size:0

# Stats
total_connections_received:1
total_commands_processed:3
instantaneous_ops_per_sec:0
total_net_input_bytes:100
total_net_output_bytes:50
instantaneous_input_kbps:0.00
instantaneous_output_kbps:0.00
rejected_connections:0
sync_full:0
sync_partial_ok:0
sync_partial_err:0
expired_keys:0
evicted_keys:0
keyspace_hits:1
keyspace_misses:0
pubsub_channels:0
pubsub_patterns:0
latest_fork_usec:0
migrate_cached_sockets:0
slave_expires_tracked_keys:0
active_defrag_hits:0
active_defrag_misses:0
active_defrag_key_hits:0
active_defrag_key_misses:0

# Replication
role:master
connected_slaves:0
master_replid:0000000000000000000000000000000000000000
master_replid2:0000000000000000000000000000000000000000
master_repl_offset:0
second_repl_offset:-1
repl_backlog_active:0
repl_backlog_size:1048576
repl_backlog_first_byte_offset:0
repl_backlog_histlen:0

# CPU
used_cpu_sys:0.10
used_cpu_user:0.20
used_cpu_sys_children:0.00
used_cpu_user_children:0.00

# Cluster
cluster_enabled:0

# Keyspace
db0:keys=1,expires=0,avg_ttl=0
```

### 5.3 健康检查

**使用 PING 命令：**
```bash
redis-cli -h localhost -p 9736 PING

# 输出示例
PONG
```

**使用 EXISTS 命令：**
```bash
redis-cli -h localhost -p 9736 EXISTS test

# 输出示例
(integer) 0  # 键不存在
(integer) 1  # 键存在
```

## 5. 停止服务

### 5.1 优雅停止

**使用 SHUTDOWN 命令：**
```bash
redis-cli -h localhost -p 9736 SHUTDOWN

# 带持久化
redis-cli -h localhost -p 9736 SHUTDOWN SAVE

# 不带持久化
redis-cli -h localhost -p 9736 SHUTDOWN NOSAVE
```

### 5.2 强制停止

**使用 Ctrl+C：**
在运行服务的终端中按下 Ctrl+C。

**使用 kill 命令：**
```bash
# 查找进程 ID
ps aux | grep luban-rds

# 停止进程
kill -SIGTERM <pid>

# 强制停止（仅在必要时使用）
kill -SIGKILL <pid>
```

### 5.3 Docker 容器停止

```bash
# 停止容器
docker stop luban-rds

# 删除容器
docker rm luban-rds
```

### 5.4 Kubernetes 停止

```bash
# 停止部署
kubectl delete deployment luban-rds

# 删除服务
kubectl delete service luban-rds

# 删除 PVC（如果使用）
kubectl delete pvc luban-rds-pvc
```

## 6. 常见问题

### 6.1 端口被占用

**问题**：启动时提示端口被占用

**解决方案**：
- 检查是否有其他服务占用了 9736 端口
- 使用不同的端口启动服务

```bash
# 检查端口占用
netstat -tulpn | grep 9736

# 使用不同端口启动
java -jar luban-rds-bin-1.0.0.jar --port 6380
```

### 6.2 内存不足

**问题**：启动时提示内存不足

**解决方案**：
- 增加系统内存
- 调整 JVM 内存参数
- 调整 Luban-RDS 最大内存限制

```bash
# 调整 JVM 内存
java -Xms512m -Xmx2g -jar luban-rds-bin-1.0.0.jar

# 调整最大内存限制
java -jar luban-rds-bin-1.0.0.jar --maxmemory 1g
```

### 6.3 认证失败

**问题**：连接时提示认证失败

**解决方案**：
- 检查密码是否正确
- 检查配置文件中的密码设置
- 确保客户端使用正确的认证命令

```bash
# 带密码连接
redis-cli -h localhost -p 9736 -a your-secure-password

# 或先连接再认证
redis-cli -h localhost -p 9736
> AUTH your-secure-password
```

### 6.4 持久化失败

**问题**：持久化文件创建失败

**解决方案**：
- 检查目录权限
- 确保磁盘空间充足
- 检查配置文件中的路径设置

```bash
# 检查目录权限
mkdir -p /data
chmod 755 /data

# 检查磁盘空间
df -h
```

## 7. 下一步

- **[配置指南](./configuration.md)**：了解详细的配置选项和优化建议
- **[监控维护](./monitoring.md)**：学习如何监控和维护 Luban-RDS 服务
- **[故障排查](./troubleshooting.md)**：掌握常见问题的排查和解决方法
