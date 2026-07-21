## Context

Luban-RDS 集群模式已实现 Redis Cluster 兼容的故障检测：`FailureDetector` 做超时判定（PFAIL），`GossipTask` 通过 PING gossip 段交换投票，达到多数派后 `broadcastFail` 把节点标记为 FAIL。但**整个链路在 FAIL 之后戛然而止** —— 没有任何代码把 fail 的 master 的某个 slave 提升为新 master。

证据：
- `GossipProtocol` 没有 `handleFailoverAuth*` 方法，`ClusterBusHandler.handleMessage` 没有 `FAILOVER_AUTH_*` 分支。
- `FailoverAuthRequestMessage` / `FailoverAuthAckMessage` 类已存在并含完整编解码，但从未被构造、发送或分发（死代码）。
- 唯一的提升逻辑 `ClusterCommandHandler.performFailover` 只能由客户端发送 `CLUSTER FAILOVER [FORCE|TAKEOVER]` 触发，master 意外宕机时无人调用。

线上 3 主 3 从（172.16.83.11/12/19，每机 3 实例共 9 节点）在单机宕机时，对应 master 的槽位永久无主，应用 Redisson 抛 `WriteRedisConnectionException`，整个服务不可用。

约束：
- 必须与现有手动 `CLUSTER FAILOVER` 语义共存，不破坏。
- 必须复用已有的 `currentEpoch` / `configEpoch` 纪元机制裁决冲突，避免脑裂。
- 多 slave 场景下必须保证**仅有一个** slave 胜选。
- 嵌入式部署（IgRdsCacheContextInitializer 启动多实例），不能依赖外部协调者。

## Goals / Non-Goals

**Goals:**
- master 被集群判定 FAIL 后，其 slave 在秒级内自动发起选举、获得多数派授权、提升为新 master 并接管槽位。
- 多 slave 竞争时，通过 `configEpoch` + 复制偏移量 + nodeId 确定性排序保证唯一胜选。
- 胜选后通过 gossip 广播拓扑变更，全网收敛槽位归属。
- 原 master 回归后自动降级为新 master 的 slave，不触发二次选举。

**Non-Goals:**
- 不实现完整的复制偏移量同步（当前复制链路 `luban-rds-replication` 与集群解耦，本变更以 `nodeId` 字典序作为偏移量的确定性替代，后续可扩展）。
- 不实现手动 `CLUSTER FAILOVER` 的"请求主节点授权"正常流程（保持现有简化）。
- 不处理 split-brain 后的数据合并（依赖现有 RDB/AOF 持久化与人工介入）。
- 不引入新外部依赖。

## Decisions

### D1. 选举算法：复用 Redis Cluster 的 `currentEpoch` 投票模型

**选择**：候选 slave 自增 `currentEpoch`，广播携带 `(currentEpoch, configEpoch, slaveNodeId)` 的 `FailoverAuthRequestMessage`；每个 master 在 `(currentEpoch, 被选 slave)` 维度上"每纪元仅投一票"，依据 `(configEpoch 大者, replicationOffset 大者, nodeId 小者)` 顺序择优投票；候选 slave 收到过半数 master 授权即胜选。

**理由**：Redis Cluster 经过多年生产验证的算法；本项目的 `FailoverAuthRequestMessage` 字段（configEpoch/currentEpoch/replicationOffset）正是为此设计，直接对齐降低实现风险。

**备选**：Paxos/Raft —— 引入完整共识算法过重，且与现有 Gossip 单线程调度器架构不匹配，否决。

### D2. 选举状态机独立为 `FailoverManager`，不污染 `GossipProtocol`

**选择**：新增 `gossip/FailoverManager.java`，持有：选举状态（IDLE/REQUESTING/ELECTED/FAILED）、本节点收到的授权票数、本轮 `electionEpoch`、退避截止时间。由 `GossipTask.run()` 每轮调用 `tick()` 驱动；`GossipProtocol` 仅作为消息收发门面（`handleFailoverAuthRequest`/`handleFailoverAuthAck`/`broadcastFailoverResult` 委托给它）。

**理由**：`GossipProtocol` 已 995 行，再塞选举状态机会破坏单一职责；独立类便于单测（参考 `FailureDetector` 的拆分模式）。

**备选**：把状态机塞进 `GossipProtocol` —— 否决，可测试性差。

### D3. 退避抖动避免多 slave 同时发起

**选择**：slave 检测到 master FAIL 后，等待 `clusterFailoverGracePeriod`（默认 0，可配）+ 随机抖动 `(0, 500ms]` 再广播 AUTH_REQUEST。抖动种子 = `(masterSlotCount, replicationOffset, nodeId)` 的哈希，使不同 slave 抖动值不同但确定。

**理由**：Redis 用复制偏移量决定优先级，本项目复制偏移量尚未接入集群层（见 Non-Goals），退避抖动是轻量替代，保证多 slave 不同时广播、降低冲突概率；即便同时广播，D1 的"每纪元每 master 一票"仍能保证唯一胜选。

### D4. 胜选广播：新增 `FailoverResultMessage`

**选择**：胜选 slave 自增 `currentEpoch` 与自身 `configEpoch`，调用 `performFailover` 提升后，广播 `FailoverResultMessage(winnerNodeId, newConfigEpoch, inheritedSlots)`。收到此消息的节点：更新 winner 为 master、继承槽位、把原 master 标记为该 winner 的 slave（若原 master 回归）、清除 winner 的 FAIL。消息类型码使用 `GossipMessageType.FAILOVER_RESULT (0x08)`（0x00-0x07 已被 PING/PONG/MEET/FAIL/PUBLISH/FAILOVER_AUTH_REQUEST/FAILOVER_AUTH_ACK/UPDATE 占用）。

**理由**：现有 `broadcastFail` 只能传播"谁挂了"，无法传播"谁接班了"；拓扑收敛必须有一条显式结果消息。

### D5. `performFailover` 抽取为公共方法

**选择**：把 `ClusterCommandHandler.performFailover(slaveNode, masterNode)` 提取到 `FailoverManager`（或 `ClusterConfig` 的工具方法），供手动命令和自动选举共用，避免逻辑重复。

### D6. 投票去重与纪元竞争

**选择**：master 节点维护 `Map<String, Long> votesCast`（key = 被投 slaveId, value = 投票时的 currentEpoch）；收到 AUTH_REQUEST 时：
1. 若 `request.currentEpoch < myCurrentEpoch` → 拒绝（旧纪元请求）。
2. 若已对该 slave 投过票且 `recordedEpoch == request.currentEpoch` → 重发 ACK（幂等）。
3. 若已对该 slave 投过票且 `recordedEpoch < request.currentEpoch` → 视为新纪元，允许重新评估。
4. 若本纪元已投给其他 slave → 拒绝。
5. 否则按 D1 择优规则投票，记录 `votesCast[slaveId] = currentEpoch`。

## Risks / Trade-offs

- **[风险] 多 slave 同时胜选导致脑裂** → D1 的"每纪元每 master 一票" + D3 退避抖动 + D4 结果广播共同保证：同一 currentEpoch 内任何 master 最多投一张票，故最多一个 slave 能拿到过半票。
- **[风险] 选举期间槽位短暂无主，客户端收到 MOVED 到旧 master** → 这是 Redis Cluster 本身的固有窗口（秒级），客户端按 `-MOVED` 重试即可收敛到新 master；不额外处理。
- **[风险] 老 master 回归后仍认为自己是 master，与新 master 冲突** → D4 的结果消息会把老 master 强制降级为 slave；若老 master 在结果消息到达前已恢复并广播，其 `configEpoch` 较小会在现有 `syncSlotsFromNode` 纪元裁决中败北（已有机制）。
- **[风险] 复制偏移量缺失导致数据不一致** → 本变更 Non-Goals 明确接受此风险，胜选 slave 可能不是数据最新的；运维应确保 slave 复制延迟可控。
- **[权衡] FAILOVER_RESULT 是新消息类型** → 老版本节点收到会走 default 分支丢弃，无法参与新拓扑收敛；滚动升级时需先升级所有节点再依赖自动故障转移。

## Migration Plan

1. **阶段 1（本变更）**：实现自动故障转移，新增 `GossipMessageType.FAILOVER_RESULT`（0x07）。所有节点升级到新版本后，能力自动生效。
2. **部署**：无需数据迁移；升级时建议逐节点滚动重启（项目已有 `集群配置自动持久化` 机制，重启后能恢复拓扑）。
3. **回滚**：若新版本引入问题，可回退到旧版本 jar —— 自动故障转移不生效，但手动 `CLUSTER FAILOVER TAKEOVER` 仍可用作兜底。
4. **验证**：灰度后人工 kill 一个 master 进程，观察日志出现 `slave promoted to master` + `广播 FailoverResult` + `集群状态变更: fail -> ok`。

## Open Questions

- `replicationOffset` 当前无来源：暂时硬编码为 `0`，退化为纯 nodeId 字典序裁决。是否在后续 change 接入 `luban-rds-replication` 的真实偏移量？→ 留作后续 change，本变更 Non-Goals。
- 是否需要 `CLUSTER FAILOVER RESET` 命令清选举状态？→ 否，状态机在胜选/超时后自动回 IDLE。
