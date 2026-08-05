---
title: 更新日志
last_updated: 2026-08-05
version: 1.0.15
---
# 更新日志

Luban-RDS 是一款轻量级、高性能、完全兼容 RESP 协议的 Java 内存数据库，易于嵌入和扩展。

## [1.0.15] - 2026-08-05

### 🛰️ 3 节点 Raft 强一致集群（luban-rds-mesh）— 13 阶段全闭环

- **阶段 1 项目骨架（`e44e2a2` / `c6f53bf`）**：建立 mesh 模块 pom + MeshBus 传输层（Codec / Client / Server / Frame）
- **阶段 2 状态机与 RPC（`6f6d713`）**：LogEntry / RaftStateMachine / 5 类 RPC（AppendEntries / RequestVote / InstallSnapshot / 投票响应 / 复制响应）
- **阶段 3 选举与 Lease（`4af73b5`）**：ElectionTimer + PreVote 防 term 膨胀 + LeaseManager 心跳租约
- **阶段 4 日志复制（`665fbf9`）**：LogApplier + LogReplicator 多数派 ACK
- **阶段 5 MeshWriteGate（`471afb9`）**：handler 级读写分流门面
- **阶段 6 MOVED / MESHDOWN（`5be6172`）**：MeshClientRedirector + 与 server 集成
- **阶段 7 读路径（`f0638a8`）**：Leader Lease 线性一致读 + read-index 退化路径
- **阶段 8 CLUSTER 命令（`abf550f`）**：`CLUSTER SLOTS/NODES/INFO` 单主视图
- **阶段 9 事务 + BLOCK（`aae4b6a`）**：MULTI 单条目 + `BLPOP/BRPOP/BLMOVE/WAIT` 禁用
- **阶段 10 chunked snapshot（`38a95be` / `e55c93c`）**：RDB 拆分块传输 + dump.rdb 归属 SnapshotManager；RdbDataLoader keysLoaded 计数修复
- **阶段 11 持久化与启动加载（`ecb291f`）**：dump.rdb 启动加载 + Raft log replay
- **阶段 12 装配（`2ef38ab`）**：MeshBootstrap + 与 `NettyRedisServer` 集成
- **阶段 13 文档与集成测试（`643316e` / `078f5ea`）**：3 节点真实选举 + 多数派写 + 一致性集成测试

测试统计：**291 测试全过**（含 3 节点集成测试）。

### 🔧 mesh 模块 13 项 hotfix（v1.0.15 内）

| commit | 修复 |
|--------|------|
| `6e7f60d` | **nodeId 编码补齐 40B**：集群瘫痪根因（Encoder 严格校验 `nodeId==40` 字符，配置接受任意字符串致所有帧丢弃） |
| `99da18b` | **注册总线消息消费者**：MeshBootstrap 未调 `setMessageConsumer`，所有 Raft RPC 到站即丢 |
| `193e153` | **PreVote electionTimer.reset()**：漏调致一次性 timer future 消费后永久静默，选举死锁 |
| `9b4ff4a` | **MOVED 地址带端口**：非 Leader MOVED 错传 nodeId 无端口致 Redisson 崩溃 |
| `286abf8` | **MOVED 自重定向死循环**：`parsePeers` 地址塌缩校验 + 自重定向守卫改发 MESHDOWN |
| `170d35d` | **resolveMeshPeerEndpoints 三段格式解析**：补 mesh-peers `nodeId@host:servicePort:busPort` 三段解析 |
| `d4dc1ad` | **选举退避 + MOVED 兜底**：kill follower 后选举风暴 `term` 飙升至 20+ leaderId 混乱→MOVED 循环→集群不可用 |
| `0dc88ef` | **`CLUSTER SLOTS` 补齐 replicas**：`*4` 头缺 `replicas` 元素挂死严格客户端 |
| `84eb0aa` | **非 Leader MOVED 携带真实 key**：修复 `slot` 恒为 0，部分客户端依赖 slot 更新本地路由缓存 |
| `ee29460` | **非 Leader propose 异常带真实 key + 畸形帧回退 null** |
| `2808410` / `bba857c` / `a557616` | **`CLUSTER NODES` 死节点 disconnected 标记**：重载构造器 + MeshBootstrap 装配 `busClient::isConnected` + 测试 |
| `182ae27` | **myself 行 linkState 恒 connected**：spec 增量（self 正在响应请求） |
| `6d2a9c8` | **日志降级**：调整高频日志级别减少 I/O |

### ⚡ Mesh 全栈基准测试套件（luban-rds-benchmark）

- **`MeshBenchmarkSuite`**（`2554202`）：mesh 模式基准聚合入口
- **`MeshScaleBenchmark`**：节点规模扩展性
- **`MeshFailoverBenchmark`**：failover 收敛时间与可用性影响
- **`MeshVsSingleGetBenchmark`** / **`MeshVsSingleSetBenchmark`**：mesh vs 单节点读写基线
- **`RedisVsMeshBenchmark`**（`59c452b`）：与 Redis 7.0.12 同机对比基线
- **报告输出**：`ReportGenerator` + `HtmlReportBuilder` + `MarkdownReportBuilder` 自动产出可分享报告

### 🛰️ Mesh 部署与运维能力

- **`mesh-*` 配置项**（中划线风格）：`mesh-enabled` / `mesh-peers` / `mesh-self-node-id` / `mesh-bus-port` / `mesh-service-port` / 选举超时 / 心跳 / Lease / 读一致性模式 / snapshot 阈值
- **CLI 参数**：`--mesh-enabled` / `--mesh-peers` / `--mesh-self-node-id` / `--mesh-bus-port`（`java -jar luban-rds-bin.jar --help` 查看全集）
- **运维命令**：`CLUSTER INFO/NODES/SLOTS` + `-MOVED <slot> <leaderAddr>` + `-MESHDOWN The mesh cluster has no leader`
- **关键约束**：`mesh-enabled` 与 `cluster-enabled` 互斥；BLOCK 命令 v1 禁用；Lua/EVAL 当写；mesh 模式不写 AOF；dump.rdb 唯一写者 = SnapshotManager

### 兼容性

- 与 v1.0.10 ~ v1.0.14 完全兼容，cluster / mesh 互斥启动校验
- mesh 模式客户端零侵入（JedisCluster / lettuce / Redisson 经 `CLUSTER SLOTS` + `MOVED` 自动跟随）
- 与 `luban-rds-mesh/README.md` 协同（291 测试用例详见 mesh 模块）

### 文档

- **[luban-rds-mesh/README.md](../luban-rds-mesh/README.md)**：模块快速上手
- **[luban-rds-mesh/docs/DESIGN.md](../luban-rds-mesh/docs/DESIGN.md)**：完整协议设计 v1.2
- **[luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md](../luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md)**：13 阶段实施计划 v1.2

---

## [1.0.14] - 2026-08-03

### 🛡️ Lua 脚本只读性分析器（解决 Redisson 从节点 `READONLY` 报错）

- **`LuaScriptAnalyzer`**（`a602f1f`）：脚本级只读判定，扫描 `redis.call` / `redis.pcall` 调用识别写命令
- **从节点行为修正**：`EVAL` / `EVALSHA` 仅当脚本判定为只读时按读命令处理（可路由从节点）；否则按写命令走 Raft 复制
- **零回归**：仅修改 slave 路径，master / 集群感知客户端零变化

### 兼容性

- 与 v1.0.10 ~ v1.0.13 完全兼容
- Redisson 等集群感知客户端在 slave 节点上不再误抛 `READONLY`

---

## [1.0.13] - 2026-08-03

### 🛡️ 集群 R2 审计修复批 1-6（N-1 ~ N-40，`7f57568`）

#### 协议面修复

- **MYSELF 守卫**：节点自身处理写命令不再被 slot 校验拦截
- **XREAD 键提取**：streams 键正确解析，不依赖 raw key
- **事务路由**：`MULTI/EXEC` 跨节点时按首键槽位稳定路由
- **destDb 透传**：跨库迁移保留目标 db
- **zset+stream 序列化**：RDB 二进制安全
- **位图上限**：`SETBIT/GETBIT/BITCOUNT` 上限校验

#### 协议码与子命令

- **消息码 0x40+**：避免与 Redis 现有消息码冲突
- **vars 段 + 真实时间戳**：携带真实 `currentEpoch` / `currentMyEpoch`
- **迁移方括号**：`MIGRATING` / `IMPORTING` 标记正确输出
- **CLUSTER 8 子命令补齐**：`CLUSTER SET-CONFIG-EPOCH` / `CLUSTER LINKS` / `CLUSTER MYSHARDID` 等
- **错误串英文化**：对齐 Redis 错误信息（兼容严格客户端解析）

#### failover 深化

- **N-11 重试冷却**：选举发起后设置最小冷却窗口
- **N-12 votesCast 清理**：选举结束立即清空已投记录
- **N-9 伪造防护**：投票校验 term + 候选 ID + 候选 log up-to-date
- **N-13 降级收窄**：仅在 `cluster-require-full-coverage=no` 时降级读
- **N-14 投票者持槽 + voted_time**：投票者必须拥有当前 epoch 槽位
- **N-15 候选纪元裁决**：冲突 epoch 时取 lastVoteEpoch 大者

#### 运维可观测

- **N-26 状态单公式**：cluster_state 一处计算
- **N-27/28 save 竞态 + fsync**：双写加锁 + `fsync` 兜底
- **N-37 总线端口**：端口分配审计
- **N-38 帧上限**：Gossip 帧大小限制
- **N-39/40 连接治理**：总连接数 / 阻塞客户端限制
- **INFO·NODES 补全**：含 `cluster_enabled` / `cluster_state` / 节点数 / 槽位状态等

### 兼容性

- 与 v1.0.10 ~ v1.0.12 完全兼容
- cluster 全套件 536 测试全绿

---

## [1.0.12] - 2026-08-03

### 🛡️ 集群审计修复批 1-6（P0×4 + P1×24，`46fdb7d`）

#### P0 数据安全修复

- **MYSELF replOffset 恒 0 致自动 failover 失效**：修复自身节点 `replOffset` 回填，自动 failover 可正确比较候选
- **手动 failover 写冻结自动恢复**：`CLUSTER FAILOVER` 后旧 master 写冻结超过 grace 期自动恢复
- **MIGRATE 复制分叉**：跨节点 `MIGRATE` 后从节点 replication offset 同步

#### 双 master 根因收敛（`fix-slot-epoch-convergence` 分支）

- **onFailoverResult 消除 winner slots 双写路径**（`0a1a23a`）：提权后置于 slot 写入后，避免重复更新
- **processGossipNodes 角色切换时立即对齐 slot 所有权**（`777f0c8`）：Gossip 接收后立即同步本地 slot 表
- **syncSlotsFromNode 相等 epoch 行为回归保护**（`8fde4f8`）：相等 epoch 不重置本地状态

#### P1 闭环

- **N-24 / N-1 / P1-4 / N-25 / N-7** 等 P1 项修复

### 兼容性

- 与 v1.0.10 ~ v1.0.11 完全兼容
- `nodes.conf` 文件结构不变（保持 v1.0.4 起的格式）

---

## [1.0.11] - 2026-08-03

### 🛡️ 集群 FAIL 保护期修复（`e0289ce`，归档 `fix-fail-state-cleared-prematurely`）

- **FAIL 状态保护期**：failover 期间维护 `FAIL` 状态保护窗口，避免 PFAIL 抢先清除导致选举被取消
- **行为变化**：failover 发起后，`FAIL` 标志保留至 `gracePeriod` 结束，期间拒绝新选举

### 兼容性

- 与 v1.0.4 ~ v1.0.10 完全兼容
- `nodes.conf` 文件结构不变

---

## [1.0.10] - 2026-07-30

### 修复

- **修复并发保存 `nodes.conf` 的竞态条件**（`ca3db8c`）：`ClusterConfigPersister` 对拓扑变更与周期 `clusterSaveConfigIfNeeded` 共路径场景下的并发刷盘进行加锁与串行化，避免半写文件与状态丢失

### 兼容性

- 与 v1.0.4 ~ v1.0.9 完全兼容，`nodes.conf` 文件结构与节点恢复行为不变
- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster create` 回归验证通过

## [1.0.9] - 2026-07-30

### 修复

- **P0 数据安全 / Redis 7 兼容性修复补遗（审计 C2/C3/C4/C5/C6/C10 等）**
  - **C2 PSYNC / REPLCONF 链路**（`a529d46` / `39d9a3a` / `1a5288a` / `ec14154`）：激活 PSYNC 响应路由，REPLCONF 顺序等待 + 超时，握手失败原因日志输出，重新启用 `ReplicationIntegrationTest`
  - **C3 AOF 记录接口与 SELECT db 标记**（`ec07bd0` / `bc0ef7f` / `d9a3858`）：补齐 AOF `recordCommand` 接口与 `SELECT db` 落盘标记，`CompositePersistService` 委托实现 `recordCommand`
  - **C4 SLAVEOF 启动复制**（`3acaa07`）：`SLAVEOF` 命令实际触发复制链路
  - **C5 全量同步窗口回放**（`e852780`）：全量同步期间增量写入在同步窗口完成后回放，避免漏写
  - **C6 从节点 offset 与 WAIT 校验**（`3c0604a`）：校验从节点 replication offset，`WAIT N` 行为对齐 Redis
  - **C10 RDB TTL 持久化**（`29d99e5` / `31a604e`）：补齐 RDB TTL 持久化编码（`0xFD`），补充测试用例
- **持久化代码清理**（`c3f3208`）：移除 `parseAndExecuteCommand` / `parseRespArray` 等死代码

### 兼容性

- 与 v1.0.4 ~ v1.0.8 完全兼容，AOF/RDB 文件结构与 Redis 7 协议行为一致
- 与 Jedis / Lettuce / Redisson / `redis-cli` 回归验证通过

## [1.0.8] - 2026-07-27

### 新增功能

- **P0 数据安全 / Redis 7 兼容性修复（审计 C1/C7/C8/C9/C11/C12）**
  - **C1 CROSSSLOT 校验**（`c09903e`）：集群模式下对多键命令执行 CROSSSLOT 槽位归属校验，不在同一槽位的多键命令返回标准 `-CROSSSLOT Keys in request don't hash to the same slot` 错误
  - **C7 MIGRATE 原子性**（`18c834d`）：多键槽位迁移改用一次性 `RESTORE`，保证迁移过程原子化，避免半迁移状态
  - **C8 故障转移选举**（`9fed095`）：选举算法改用真实复制偏移（replication offset）作为选主依据，替代旧的随机/固定值选举
  - **C9 手动故障转移广播**（`278b294`）：`CLUSTER FAILOVER` 完成后通过新增的 `FAILOVER_RESULT` 消息（类型码 0x08）向全集群广播结果
  - **C11 AOF 二进制安全**（`21b8774`）：AOF rewrite 改为按数据类型分别重写；AOF 加载同样二进制安全，避免二进制数据损坏
  - **C12 ZSet 同分数排序**（`252851c`）：ZSet 相同分数成员的排序改为字典序（lexicographic），与 Redis 官方语义一致

### 修复

- 配套修复 P0 审计报告中其余条目（C2/C3/C4/C5/C6/C10 等）已随上述 P0 批次合并

### 兼容性

- 与 v1.0.4 ~ v1.0.7 完全兼容，AOF/RDB 文件结构与 Redis 7 协议行为一致
- 与 Jedis / Lettuce / Redisson / `redis-cli` 回归验证通过

## [1.0.7] - 2026-07-XX

### 新增功能

- **`CLUSTER SET-CONFIG-EPOCH` 命令**：完整实现，支持设置节点 configEpoch
- **`ADDSLOTS` 后自动同步 configEpoch**：`ClusterCommandHandler` 在槽位分配后主动同步 epoch
- **Gossip 协议携带 `masterNodeId`**：`GossipNodeInfo` 新增字段，主从关系随 Gossip 扩散
- **`CLUSTER REPLICATE` 角色传播**：从节点角色经 Gossip 协议正确传递到全集群
- **集群总线通信重构**：提升 Gossip 与 PING/PONG 的可靠性

### 修复

- **修复 `slotManager` / `clusterConfig` / `ClusterNode` 三重槽位归属不一致**：统一以 `slotManager` 为准
- **修复故障转移后 `CLUSTER SLOTS` 返回错误路由**：故障转移后 `ClusterConfig.slotAssignment` 同步更新
- **修复从节点始终返回 `CLUSTERDOWN`**：`checkSlotAndRedirect` 改为从 `slotManager` 读取槽位归属
- **修复 `CLUSTER MEET` 用 127.0.0.1 建连后节点地址未收敛为真实 IP**
- **修复 Gossip 协议 `masterNodeId` 在 MASTER→SLAVE 角色切换时未同步**

### 优化

- **ClusterNode 线程安全**：关键读写方法加 `synchronized`
- **ReplicationCoordinator 装配**：事务传播写入命令到复制链路
- **`ReplicationLifecycleListener`**：将角色变更接入复制路径
- **`ReplicationStreamApplier`**：实现并接入 `SlaveReplicationService`

### 兼容性

- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster` 验证通过

## [1.0.6] - 2026-07-XX

### 修复

- **修复集群 PFAIL 投票未通过 Gossip 传播**：修复 PFAIL 状态无法扩散导致自动故障转移失效
- **`ClusterNode` 状态修正**：PFAIL→FAIL 升级链路恢复，触发后续自动选举

### 新增功能

- **`FAILOVER_RESULT` 消息类型**：新增 0x08 消息类型与 `FailoverResultMessage`
- **`FailoverManager` 状态机**：状态机 + 消息分发接线 + 注入 `NettyRedisServer`
- **`ClusterConfig.getSlavesOfMaster`**：暴露主从关系查询
- **`gracePeriod` 配置**：故障转移宽限期可配置

### 兼容性

- 消息类型码向后兼容（0x07/0x08 不冲突）

## [1.0.5] - 2026-07-XX

### 新增功能

- **`FailoverManager` 骨架**：引入自动选举 / 状态切换的基础组件，为后续 PFAIL→FAIL 投票链路预留接入点
- **Gossip 状态整合**：接入点预留

### 修复

- **累积修复**：本版本以工程内部改进为主，未单独发布外部可见特性

### 兼容性

- 与 v1.0.4 协议层完全兼容

## [1.0.4] - 2026-07-08

### 新增功能

- **集群配置持久化与节点状态恢复**
  - 启动时自动从 `nodes.conf`（`cluster-config-file`）加载已有集群配置，恢复节点列表、槽位分配和配置纪元信息
  - 优先复用已存在的节点 ID，避免重启后节点 ID 漂移导致拓扑分裂
  - 重启后保留节点状态，仅更新可能变化的 IP/端口网络地址信息
  - 优化集群初始化流程，增加配置文件加载和状态恢复步骤
  - 详细日志记录配置加载与状态恢复过程，便于运维排查
  - 保持 `MYSELF` 节点的连接状态和其它重要属性在重启后不变
- **集群配置自动持久化机制**
  - 引入脏标记（dirty flag）追踪集群拓扑变更，避免每次操作都同步刷盘
  - `ClusterConfig` 新增 `markDirty`、`isDirty`、`clearDirty` 方法，供命令处理器在拓扑变更时主动标记
  - 通过 Gossip 协议在节点变更时自动触发配置持久化
  - `ClusterCommandHandler` 在处理 MEET/FORGET/ADDSLOTS 等命令时通知拓扑变更
  - 实现类 Redis 7 `clusterSaveConfigIfNeeded` 的周期性检查机制，定时刷新脏配置
  - 优化启动流程，移除重复的配置保存调用

### 修复

- **集群重启后节点状态和槽位分配恢复问题**
  - 移除 `FAIL`/`PFAIL` 状态的持久化，这些是运行时瞬时状态，不应写入 `nodes.conf`
  - 添加启动时主动连接已知节点功能，避免全集群重启后节点成孤岛无法恢复
  - 实现从恢复的 `ClusterConfig` 重建 `SlotManager` 槽位表，确保重启后能正常服务请求
  - 添加兼容旧版 `nodes.conf` 含 `fail` 标志的处理逻辑，避免升级后启动失败
  - 增加相关单元测试验证重启恢复场景的正确性

### 兼容性

- 与 v1.0.0 ~ v1.0.3 已生成的 `nodes.conf` 保持向后兼容（自动忽略 `fail` 标志）
- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster create` 验证通过

## [1.0.3] - 2026-07-07

### 新增功能

- **Redis 集群创建 CLI 工具**（`RedisCliMain`）
  - 模仿 `redis-cli --cluster create` 子集，支持远程编排集群搭建
  - 通过 `--cluster create <host:port> ... [--cluster-replicas N]` 参数创建 3 主 + N 从的集群
  - 自动完成 `CLUSTER MEET`、主从划分、16384 槽位均分、状态校验
  - 提供 `ClusterSetupCommand` 静态方法 `createCluster(...)` 便于程序化嵌入调用
  - 新增 `verbose` 参数支持静默模式（用于脚本/批量场景）

### 修复

- **客户端半包/粘包问题**（`NettyRedisClient`）
  - 添加 `ByteBuf` 累积缓冲区，正确处理跨 TCP 段的 RESP 响应
  - 实现循环解析机制，处理同一 TCP 段中的多个完整响应
  - 通过 `readerIndex` 标记区分半包与完整响应的解析状态
  - 连接关闭时正确释放累积缓冲区，避免内存泄漏
- **协议解析器半包处理逻辑**（`RedisProtocolParser`）
  - `parseBulkStringBytes` 方法添加 reader index 回退机制处理半包
  - 为所有解析分支（简单字符串、错误、整数、批量字符串、数组、映射、集合等）统一添加 null 检查
  - 解析失败时重置缓冲区读取索引，避免协议解析死循环
  - 完善 CRLF 检测逻辑，确保半包数据能正确等待后续字节
  - 优化 `parseResp` 错误处理流程，提升解析稳定性

### 兼容性

- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster create` 全流程验证通过
- `CLUSTER NODES` / `CLUSTER SLOTS` / `CLUSTER INFO` 输出格式与 Redis 官方一致

## [1.0.2] - 2026-07-07

### 新增功能

- **CLUSTER SLOTS 命令**：完整实现 Redis `CLUSTER SLOTS` 命令，返回当前槽位分布数组
- **集群节点过滤优化**：在节点列表中过滤下线/未握手节点，避免返回陈旧拓扑

### 修复

- **修复 `redis-cli --cluster create` 卡在 "Waiting for the cluster to join"**
  - Gossip 发现新节点后主动建连/`MEET`，保证拓扑收敛
  - `GossipTask` 心跳不再跳过 `HANDSHAKE` 状态节点，握手流程正常推进
  - Gossip 消息携带槽位所有权信息，`cluster_state` 能够正确转为 `ok`
- **修复 CLUSTER NODES 行尾符导致 Redisson 解析异常**
  - `ClusterCommandHandler.clusterNodes()` 每行改用裸 `\n` 结尾（对齐真实 Redis 行为）
  - 解决 Redisson `ClusterNodesDecoder` 因残留 `\r` 而抛 `NumberFormatException` 导致集群初始化失败的问题
- **修复 `CLUSTER MEET` 命令在集群模式下装配缺陷**：在集群模式下正确路由 `CLUSTER MEET` 到集群命令处理器
- **修复集群节点握手协议和临时 ID 解析机制**：`MEET` 消息识别握手阶段返回的临时 ID，避免误判为已知节点
- **补全 `CLUSTER INFO` 与 `INFO` 的 `cluster_enabled` 字段**：使第三方监控/健康检查能正确判定集群模式开关
- **禁用集群模式时跳过 CLUSTER 命令拦截**：避免 `cluster-enabled=no` 时仍拦截 CLUSTER 命令带来的语义混淆

### 优化

- 集群调试日志级别调整为 `TRACE`，降低生产环境日志开销

## [1.0.1] - 2026-03-24

### 新增功能

- **Redis Cluster 集群模式**：完整实现 Redis Cluster 协议兼容
  - 16384 槽位分配与管理（BitSet 优化）
  - MOVED/ASK 重定向机制
  - Gossip 协议心跳检测
  - PFAIL/FAIL 故障检测
  - 槽位迁移（IMPORTING/MIGRATING 状态）
  - 集群总线协议（端口 + 10000）
  - Hash Tag 语法支持 `{tag}`
  - Jedis/Lettuce/Redisson 客户端兼容性测试
- **主从复制**：完整支持 Redis 主从复制协议
  - 全量同步（RDB 传输）
  - 增量同步（基于复制积压缓冲区）
  - 复制状态管理
  - 从节点只读模式
- **哨兵模式（Sentinel）**：实现哨兵模式核心功能

### 变更

- 升级 Spring Boot 版本至 3.4.11

## [1.0.0] - 2026-03-04

### 新增功能

- **协议支持**
  - 完整 RESP 协议解析与编码，支持 RESP2 和 RESP3
  - 完整 RESP3 协议支持，包括新数据类型（Map、Set、Null、Boolean、Double、Big Number）
  - 协议版本自动检测和切换，支持 RESP2 和 RESP3 客户端
- **数据结构**
  - 内存数据结构与过期支持（String/List/Set/Hash/ZSet/Stream）
  - Stream 数据类型支持：完整实现 Stream 相关命令（XADD, XLEN, XRANGE, XREVRANGE, XREAD, XGROUP, XREADGROUP 等）
- **Lua 脚本**
  - Lua 脚本执行（EVAL/EVALSHA/SCRIPT），沙箱与执行统计
  - Lua struct 库增强：支持 Lc0、Ic0、ic0 等组合格式说明符，变长字符串打包和解包
- **持久化**
  - RDB 与 AOF 持久化机制
- **网络服务**
  - 基于 Netty 的高并发 NIO 服务器
  - 多线程 I/O 优化：三层线程模型（Boss → Worker → Business）
  - 内存池集成：Netty PooledByteBufAllocator
- **发布订阅**
  - 发布/订阅：频道订阅、模式订阅和流订阅
- **事务支持**
  - 事务支持：MULTI/EXEC/DISCARD/WATCH
  - 键版本控制机制，支持 WATCH 乐观锁
- **集成与扩展**
  - Spring Boot Starter 自动配置集成
- **监控与管理**
  - 内存统计与 MEMORY 命令族
  - 高性能 MONITOR 命令与事件管线（采用 MPSC 无锁环形缓冲区，<40ns 开销）
  - 慢查询日志功能（SLOWLOG GET/LEN/RESET）
  - 分布式追踪支持：基于 TraceId 的全链路追踪，自动注入日志 MDC
- **命令扩展**
  - 批量命令支持：MSET, MGET, HMSET, HMGET, DEL (多键)
  - 多元素推入：LPUSH/RPUSH/SADD/ZADD 支持多元素
  - 扩展字符串命令：SETNX, GETSET, SETRANGE, GETRANGE, PSETEX
  - 扩展集合命令：SPOP, SRANDMEMBER, SMOVE, SINTER, SUNION, SDIFF, SSCAN
  - 扩展有序集合命令：ZREVRANGE, ZRANGEBYSCORE, ZRANK, ZREVRANK, ZCOUNT, ZINCRBY, ZPOPMAX, ZPOPMIN, ZSCAN
  - 扩展列表命令：LINDEX, LSET, LREM, LTRIM
  - 扩展哈希命令：HSETNX, HINCRBY, HSCAN
  - 客户端管理命令：CLIENT LIST, CLIENT KILL, CLIENT SETNAME, CLIENT GETNAME
  - BLPOP/BRPOP 阻塞命令支持：完整实现 Redis 规范的阻塞列表弹出命令
- **内存管理**
  - 内存碎片整理：自动/手动
- **部署支持**
  - Docker 部署支持
  - Kubernetes 部署支持
- **配置**
  - 配置文件 Lua 支持：新增 lua-timeout、lua-sandbox-enabled、lua-max-script-bytes 等配置项

### 变更

- 升级 Netty 版本至 4.2.10.Final
- 升级 Caffeine 版本至 3.2.3
- 升级 Guava 版本至 33.5.0-jre
- 升级 Kryo 版本至 5.6.0
- RDB 持久化改用 Kryo 序列化框架
- MONITOR 命令支持 DB 和 MATCH 过滤参数

### 修复

- 修复事务执行时的响应序列化问题
- 修复 WATCH 机制在多数据库场景下的键版本检查
- 修复 String.intern() 导致的内存泄漏问题，改用分段锁机制
- 修复过期键竞态条件问题，使用双重检查锁定机制
- 修复 STRLEN 命令返回字符长度而非字节长度的问题
- 修复 MSET 命令缺少原子性保证的问题
- 修复 LRU 淘汰策略性能问题，优化采样算法
- 修复 AOF 持久化命令解析不完整问题，支持 20+ 种命令类型
- 修复 RDB 持久化 ZSet 分数丢失问题，完整保存和恢复分数
- 修复 RDB 长度编码错误，添加边界检查
- 添加过期键主动清理机制，避免过期键长期占用内存
- 修复 Lua 脚本中 HSCAN/SSCAN/ZSCAN 嵌套数组解析问题
- 修复 BLPOP/BRPOP 命令未注册问题

### 安全

- 增强 Lua 脚本沙箱安全性
