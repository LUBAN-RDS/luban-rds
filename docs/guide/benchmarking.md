---
title: 性能基准测试
last_updated: 2026-08-05
version: 1.0.15
---

# 性能基准测试（luban-rds-benchmark）

> **v1.0.15 起** —— `luban-rds-benchmark` 模块提供 **单节点 / Cluster / Mesh / Redis 对比** 四类基准套件，支持 HTML / Markdown 报告输出。

## 1. 模块概览

```
luban-rds-benchmark/
└── src/main/java/com/janeluo/luban/rds/benchmark/
    ├── LubanBenchmarkMain.java          # CLI 入口（commons-cli）
    ├── api/                             # Benchmark / BenchmarkConfig / BenchmarkResult
    ├── cases/                           # 单节点 12 类基准
    │   ├── GetBenchmark
    │   ├── SetBenchmark
    │   ├── IncrBenchmark
    │   ├── ListPushBenchmark
    │   ├── ListRangeBenchmark
    │   ├── HashSetBenchmark / HashGetBenchmark
    │   ├── SetAddBenchmark
    │   ├── LatencyBenchmark
    │   ├── MultiThreadBenchmark
    │   ├── MemoryFragmentationBenchmark
    │   └── MemoryStabilityBenchmark
    ├── cluster/                         # Cluster 基准套件
    │   ├── ClusterAwareClient.java
    │   ├── ClusterBenchmarkSuite.java
    │   ├── comparison/
    │   │   ├── ClusterScaleBenchmark
    │   │   ├── ClusterVsSingleGetBenchmark
    │   │   ├── ClusterVsSingleSetBenchmark
    │   │   └── RedirectOverheadBenchmark
    │   └── model/                       # BenchmarkResult / LatencyDistribution
    ├── mesh/                            # Mesh 基准套件（v1.0.15 新增）
    │   ├── MeshBenchmarkSuite.java
    │   ├── MeshScaleBenchmark.java
    │   ├── MeshFailoverBenchmark.java
    │   ├── MeshTestCluster.java
    │   └── comparison/
    │       ├── MeshVsSingleGetBenchmark
    │       ├── MeshVsSingleSetBenchmark
    │       └── RedisVsMeshBenchmark    # 与 Redis 7.0.12 对比
    └── report/                          # 报告输出
        ├── ReportGenerator.java
        ├── HtmlReportBuilder.java
        └── MarkdownReportBuilder.java
```

## 2. CLI 参数

`LubanBenchmarkMain` 基于 `commons-cli`，支持以下参数：

| 参数 | 长选项 | 默认值 | 说明 |
|------|--------|--------|------|
| `-h` | `--host` | `127.0.0.1` | 目标 Redis 服务地址 |
| `-p` | `--port` | `9736` | 目标 Redis 服务端口 |
| `-t` | `--threads` | `10` | 并发客户端线程数 |
| `-n` | `--requests` | `100000` | 每线程总请求数 |
| `-d` | `--duration` | `0` | 持续时间（秒），> 0 时覆盖 `--requests` |
| `-s` | `--size` | `100` | value 大小（字节） |
| `-c` | `--cases` | `all` | 逗号分隔用例：`set,get,incr,lpush,lrange,hset,hget,sadd` 或 `all` |
| `-m` | `--memory` | - | 是否启用内存监控（每 5 秒抓 INFO memory） |
|  | `--pipeline` | `1` | pipeline 批大小（> 1 启用） |
|  | `--pool` | `0` | Jedis 连接池大小（0 = 每线程独立连接） |
|  | `--help` | - | 打印帮助 |

### 2.1 常用示例

```bash
# 基本基准：10 线程，每线程 100k 请求，100 字节 value
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -n 100000 -s 100

# 流水线模式：批 32
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -n 100000 --pipeline 32

# 连接池模式：池 50
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -n 100000 --pool 50

# 指定用例：仅 SET + GET
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -n 100000 -c set,get

# 持续时间模式：跑 60 秒
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -d 60
```

## 3. Cluster 基准套件

### 3.1 ClusterBenchmarkSuite

聚合入口，连续运行 Cluster 模式下的多个基准场景并产出统一报告。

```java
import com.janeluo.luban.rds.benchmark.cluster.ClusterBenchmarkSuite;

ClusterBenchmarkSuite.run(
    /* host */ "127.0.0.1",
    /* port */ 9736,
    /* threads */ 10,
    /* requests */ 100000,
    /* valueSize */ 100,
    /* outputDir */ "./bench-reports/cluster"
);
```

### 3.2 单项对比基准

| 类 | 用途 |
|----|------|
| `ClusterVsSingleGetBenchmark` | Cluster GET vs 单节点 GET 基线（含 MOVED 重定向开销） |
| `ClusterVsSingleSetBenchmark` | Cluster SET vs 单节点 SET 基线 |
| `ClusterScaleBenchmark` | Cluster 节点规模扩展性（3 / 6 / 9 主+从） |
| `RedirectOverheadBenchmark` | MOVED / ASK 重定向开销占比 |

## 4. Mesh 基准套件（v1.0.15+）

### 4.1 MeshBenchmarkSuite

聚合入口，连续运行 Mesh 模式下的多个基准场景：

```java
import com.janeluo.luban.rds.benchmark.mesh.MeshBenchmarkSuite;

MeshBenchmarkSuite.run(
    /* host */ "127.0.0.1",
    /* port */ 6379,
    /* threads */ 10,
    /* requests */ 100000,
    /* valueSize */ 100,
    /* outputDir */ "./bench-reports/mesh"
);
```

### 4.2 单项 Mesh 基准

| 类 | 用途 |
|----|------|
| `MeshScaleBenchmark` | Mesh 节点规模（固定 3 节点 + 数据规模扩展） |
| `MeshFailoverBenchmark` | failover 收敛时间（kill Leader / kill Follower 后恢复时长） |
| `MeshVsSingleGetBenchmark` | Mesh GET vs 单节点 GET（含 Leader 路由 + Lease 读） |
| `MeshVsSingleSetBenchmark` | Mesh SET vs 单节点 SET（含多数派 ACK + 落盘） |
| `RedisVsMeshBenchmark` | **与 Redis 7.0.12 同机对比基线**（v1.0.15 `59c452b` 新增） |

### 4.3 RedisVsMeshBenchmark 使用示例

需在同一台机器上同时启动 Redis 7.x 与 Luban-RDS mesh：

```bash
# 1. 启动 Redis 7.0.12（端口 6379）
redis-server --port 6379

# 2. 启动 mesh 三节点（端口 16379/26379/36379，互不冲突）
java -jar luban-rds-bin.jar --port 16379 --mesh-enabled \
  --mesh-peers "a1b2...@127.0.0.1:17379,c3d4...@127.0.0.1:27379,e5f6...@127.0.0.1:37379" \
  --mesh-self-node-id a1b2... --mesh-bus-port 17379
# 另两个节点类推

# 3. 运行对比
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.mesh.comparison.RedisVsMeshBenchmark \
  --redis 127.0.0.1:6379 \
  --mesh 127.0.0.1:16379 \
  --threads 10 --requests 100000 --output ./bench-reports/redis-vs-mesh
```

> **结果说明**：mesh 写路径因多数派 ACK + 落盘天然慢于 Redis 7 单节点（实测本机 D:\dev 基线 SET 约 6.8× 慢），这是**强一致性的代价**，不是 bug。结果仅供方向性参考。

## 5. 报告输出

### 5.1 Markdown 报告

```bash
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -n 100000 \
  --report-dir ./bench-reports/basic \
  --report-format markdown
```

输出 `./bench-reports/basic/report-<timestamp>.md`，包含：

- 总吞吐（ops/s）
- 每用例 P50 / P95 / P99 / P99.9 延迟（μs / ms）
- 错误率与重试次数
- 内存监控采样（如启用 `-m`）

### 5.2 HTML 报告

```bash
java -cp luban-rds-benchmark.jar com.janeluo.luban.rds.benchmark.LubanBenchmarkMain \
  -h 127.0.0.1 -p 9736 -t 10 -n 100000 \
  --report-dir ./bench-reports/basic \
  --report-format html
```

输出 `./bench-reports/basic/report-<timestamp>.html`，包含：

- 摘要卡片（吞吐 / 平均延迟 / 错误率）
- 每用例延迟分布图（直方图）
- 趋势对比（多轮结果）
- 内存使用曲线（如启用 `-m`）

### 5.3 报告格式扩展

如需自定义输出，可继承 `ReportGenerator` 实现自定义 `HtmlReportBuilder` / `MarkdownReportBuilder`，或将原始 `BenchmarkResult` 序列化到 JSON / CSV 后用其他工具可视化。

## 6. 与 Redis 7.x 对比说明

`RedisVsMeshBenchmark` 在本机 `D:\dev` 基线下大致参考数字（仅供方向性参考，**生产请以自己的环境为准**）：

| 用例 | Redis 7.0.12 单节点 | Mesh 3 节点 | 差距 |
|------|----------------------|--------------|------|
| SET | 100k+ ops/s | ~15k ops/s | mesh 约 6.8× 慢 |
| GET（热路径） | 200k+ ops/s | ~80k ops/s | mesh 约 2.5× 慢 |
| 持久化开启后写 | ~80k ops/s | ~80 ops/s | mesh 约 1000× 慢（含 fsync） |

**结论**：mesh 的写吞吐受限于 `raftExecutor` 单线程 + 多数派 ACK + fsync；如需更高写吞吐，建议 Redis Cluster（异步复制最终一致）；如需强一致，mesh 是当前 v1.0.15 最简方案。

## 7. 常见问题

### 7.1 连接被拒

检查目标服务是否启动、端口是否正确、防火墙是否放行；mesh 模式还需确认 `mesh-service-port` 与连接端口一致。

### 7.2 报告输出为空

检查 `--report-dir` 目录是否存在且可写；`-m` 启用时 INFO memory 失败也会导致部分字段缺失（不影响主报告）。

### 7.3 跑 Mesh 基准时集群状态异常

- 检查 3 节点是否都已启动且 `CLUSTER INFO` 返回 `cluster_state:ok`
- 检查 peers 列表是否一致
- 检查是否有节点 OOM 或磁盘满（Raft log 写入失败）

## 8. 下一步

- [Mesh 快速上手](../mesh/setup.md)
- [luban-rds-benchmark 模块源码](https://github.com/LUBAN-RDS/luban-rds/tree/master/luban-rds-benchmark)（详见各 benchmark 类注释）
- [Redis 7 兼容性审计报告](https://github.com/LUBAN-RDS/luban-rds/blob/master/AUDIT-REPORT-vs-Redis7.md)（性能对比参考）