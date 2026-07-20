## Why

当前 3 主 3 从的集群模式下，任一 master 宕机会导致整个集群对外不可用：故障检测链路（PFAIL → FAIL 共识）能正常工作，但 FAIL 之后没有任何 slave 被自动提升为新 master。线上日志（172.16.83.11/12/19）显示 master 节点被反复标记为 PFAIL/FAIL，却始终没有"slave promoted to master"的记录，应用层因槽位无主而抛出 `WriteRedisConnectionException`。根因是 `GossipProtocol` 只实现了手动 `CLUSTER FAILOVER`，缺少对齐 Redis Cluster 的 slave election 自动故障转移机制，使集群失去了 master 容错这一核心高可用能力。

## What Changes

- 新增**从节点选举触发**：slave 在其 master 被集群多数派确认为 FAIL 后，启动选举（带 AUTH_REQUEST 广播 + 退避抖动，避免多 slave 同时发起）。
- 新增**多数派授权投票**：其余 master 收到 AUTH_REQUEST 后，依据 `configEpoch` 竞争规则与"每纪元每 master 仅投一票"约束，回送 AUTH_ACK；候选 slave 收到过半数授权后胜选。
- 新增**胜选提升流程**：胜选 slave 复用现有 `performFailover` 逻辑提升为 master、继承槽位、自增 `currentEpoch/configEpoch`，并通过 gossip 广播拓扑变更，使全网收敛。
- 启用**已存在但未被使用的死代码**：`FailoverAuthRequestMessage` / `FailoverAuthAckMessage`（含编解码）正式接入 `ClusterBusHandler.handleMessage` 的分发分支。
- 新增 **`FailoverManager`** 作为选举状态机持有者，由 `GossipTask` 定时驱动，避免把选举状态散落到 `GossipProtocol`。
- 增补测试：单 master 故障自动提升、多 slave 竞争唯一胜选、原 master 回归后降级为 slave、重复投票去重、纪元裁决。

## Capabilities

### New Capabilities
- `cluster-automatic-failover`: master 节点被判定 FAIL 后，其 slave 自动发起选举、获得多数派授权、提升为新 master 并广播拓扑收敛的能力。

### Modified Capabilities
（无现有 spec 需修改 —— 当前 `openspec/specs/` 为空，本变更新建首个 capability spec）

## Impact

- **代码**：
  - `luban-rds-cluster`：
    - 新增 `gossip/FailoverManager.java`（选举状态机、退避、投票收集、胜选裁决）。
    - 修改 `gossip/GossipProtocol.java`（增 `handleFailoverAuthRequest` / `handleFailoverAuthAck` / `broadcastFailoverResult`，注入 FailoverManager）。
    - 修改 `gossip/GossipTask.java`（每轮调用 `failoverManager.tick()` 触发候选/超时检查）。
    - 修改 `bus/ClusterBusHandler.java`（`handleMessage` 增加 `FAILOVER_AUTH_REQUEST` / `FAILOVER_AUTH_ACK` / `FAILOVER_RESULT` 分支）。
    - 修改 `config/ClusterConfig.java`（新增 `getSlavesOfMaster(masterNodeId)` 辅助方法）。
    - 复用 `handler/ClusterCommandHandler.performFailover` 抽取为可被 FailoverManager 调用的公共方法。
    - 新增 `gossip/FailoverResultMessage.java`（胜选广播，触发全网拓扑更新）。
  - 测试：新增 `FailoverManagerTest`，扩展 `integration/ClusterFailoverTest` 覆盖自动场景。
- **协议**：集群总线新增 3 种已规划消息类型的实际使用（FAILOVER_AUTH_REQUEST/ACK 已有，新增 FAILOVER_RESULT），**对老版本节点不向后兼容**（老节点收到未知类型会走 default 分支告警丢弃，不影响其自身运行，但无法参与新选举 —— 视为可接受的滚动升级约束）。
- **配置**：新增可选配置项 `cluster-failover-grace-period`（slave 退避窗口，默认 `0`，对齐 Redis 的 `CLUSTER_MFAIL_TIMEOUT` 简化）。
- **运维**：修复后集群具备单 master 容错，`CLUSTER FAILOVER` 手动命令语义保持不变。
