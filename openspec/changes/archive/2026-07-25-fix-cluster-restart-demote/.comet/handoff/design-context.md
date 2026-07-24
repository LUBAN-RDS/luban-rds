# Comet Design Handoff

- Change: fix-cluster-restart-demote
- Phase: design
- Mode: compact
- Context hash: 14ec7ce068623622c3cc2c34087f8f7b05563bd6885adcf495f085b2dd134f8c

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/fix-cluster-restart-demote/proposal.md

- Source: openspec/changes/fix-cluster-restart-demote/proposal.md
- Lines: 1-35
- SHA256: 1ccbade68ea10c578057b03ae85210805205c995182576dcdebf2a5fd9a4a817

```md
## Why

集群模式主节点故障转移后，原主节点重启上线时未降级为从节点，导致双主冲突（split-brain）。

实测现象：3 主 3 从集群中，9737（master，持有 slots 5461-10922）宕机后，其从节点 9740 经选举提升为新主（epoch=9）。9737 重启后，`cluster nodes` 显示它仍以 `master` 身份上线（无 slots，但角色错误），且集群 `currentEpoch` 未对齐——重启节点停留在 epoch=4，而集群已演进到 epoch=9。

根因（5 个耦合缺口，均已通过日志和源码确认）：

1. **`GossipProtocol.processGossipNodes` 显式跳过 MYSELF**（`GossipProtocol.java:1030-1031`）：重启节点收到 PONG 时，gossip section 即便携带了 MYSELF 的正确降级视图（更高 configEpoch + SLAVE 角色 + masterNodeId 指向新主），也被 `continue` 丢弃，MYSELF 永不通过 gossip 自降级。
2. **`syncSenderRole` 只改对端记录**（`GossipProtocol.java:1244-1269`）：epoch 仲裁逻辑只作用于 `sender`（远端节点），从不作用于 MYSELF 自身。
3. **`FailoverResultMessage` 广播仅一次**（`FailoverManager.performFailoverAndBroadcast`）：故障转移时胜选者广播一次，原主此时已死、错过广播；重启后没有任何节点重播，`onFailoverResult` 的降级路径（`FailoverManager.java:510-523`）对重启节点永不触发。
4. **`NettyRedisServer.restoreClusterFromConfig` 盲信本地文件**（`NettyRedisServer.java:478`）：从 `nodes.conf` 恢复 MYSELF 为 MASTER、恢复旧 slots、恢复过时的低 configEpoch，无任何与集群对齐的步骤。
5. **PING/PONG 不携带 `currentEpoch`**：只在 MEET 时通过 `setEpochIfGreater` 同步（`GossipProtocol.java:990`），PING/PONG 仅携带 `senderConfigEpoch`。重启节点连集群级 currentEpoch 都无法通过心跳同步，更难做 epoch 仲裁。

## What Changes

- **gossip 接收侧自降级**：改造 `processGossipNodes`，当 gossip section 携带 MYSELF 的视图且其 `configEpoch` 严格大于本地 MYSELF 的 `configEpoch`、角色为 SLAVE 时，让 MYSELF 采纳该视图——清空自身 slots、切换 MASTER→SLAVE、设置 `masterNodeId` 指向新主，并通过 `ReplicationLifecycleListener.demoteToSlave` 切换复制方向。严格 epoch 门控（>`localEpochBaseline`）防止陈旧 gossip 回退已提升的 master。
- **PING/PONG 携带 `currentEpoch`**：扩展 `PingMessage`/`PongMessage` 协议（向后兼容追加字段），接收侧 `updateNodeFromPingMessage`/`updateNodeFromPongMessage` 调用 `clusterConfig.setEpochIfGreater`，使重启节点能通过心跳同步集群级 currentEpoch。
- **启动恢复对齐**：`restoreClusterFromConfig`/`initCurrentNode` 不再无条件以本地 MYSELF 角色为准恢复 slots；恢复后标记"待对齐"，由首个 PONG 的 epoch 仲裁纠正角色与 slots（与上一条改动配合）。保留本地文件作为节点 ID 与已知节点列表的来源。
- **回归测试**：覆盖 (a) 重启旧主通过 gossip 自降级；(b) currentEpoch 经 PING/PONG 同步；(c) 启动恢复后首个 PONG 触发对齐；(d) epoch 门控防止回退已提升 master。

## Capabilities

### New Capabilities

### Modified Capabilities
- `cluster-automatic-failover`: 故障转移后原主重启必须根据集群最新拓扑（更高 configEpoch）自降级为新主的 slave，并同步 currentEpoch；不得盲信本地 nodes.conf 维持 master 身份。

## Impact

- 影响 `luban-rds-cluster` 的 `GossipProtocol`、`PingMessage`/`PongMessage`（协议向后兼容扩展）、`ClusterConfig`（`setEpochIfGreater` 已有，复用）。
- 影响 `luban-rds-server` 的 `NettyRedisServer.restoreClusterFromConfig`/`initCurrentNode` 启动恢复路径。
- 复用 `ReplicationLifecycleListener.demoteToSlave` 切换复制方向（已有，不新增接口）。
- 不改变 Redis 协议或对外 API；PING/PONG 编码向后兼容（追加字段，旧节点解码时尾部截断安全）。
- 不引入新依赖；仅本地内存与集群总线消息变更。
```

## openspec/changes/fix-cluster-restart-demote/design.md

- Source: openspec/changes/fix-cluster-restart-demote/design.md
- Lines: 1-45
- SHA256: ba390c25b9e3ef49327343c34f08d457473f4eb6e229d2dcd3916728116de599

```md
## Context

集群故障转移后，原主节点重启必须降级为新主的 slave，否则会以旧 master 身份重新上线，与接管其 slots 的新主形成双主冲突（split-brain）。当前实现依赖 `FailoverResultMessage` 广播来传播降级，但该广播仅在故障转移瞬间发送一次，重启节点错过广播后无任何收敛机制。gossip 的 epoch 仲裁本可作为后备收敛路径（对齐 Redis），但 `processGossipNodes` 显式跳过 MYSELF，`syncSenderRole` 只改对端，导致 MYSELF 永不自降级。

## Goals / Non-Goals

**Goals:**
- 重启的原主节点在收到首个携带更高 configEpoch 的 PONG/PING 后，能通过 epoch 仲裁自降级为新主的 slave，清空 slots、切换角色、切换复制方向。
- 重启节点能通过心跳同步集群级 `currentEpoch`，避免 epoch 滞后导致后续仲裁门控恒为 false。
- PING/PONG 协议扩展向后兼容：旧版本节点能解码新消息（尾部追加字段），新版本节点能解码旧消息（缺字段时按默认处理）。
- 启动恢复不再无条件以本地 MYSELF 角色为准恢复 slots；保留本地文件仅作为节点 ID 与已知节点列表来源。
- 严格 epoch 门控，防止陈旧 gossip 把已提升的 master 回退为 slave（撤销合法故障转移）。

**Non-Goals:**
- 不改变故障转移选举算法、投票机制或 `FailoverResultMessage` 的广播时机。
- 不引入新的集群总线消息类型或新的持久化格式。
- 不改变 `nodes.conf` 文件格式或公开命令语义。
- 不处理手动 `CLUSTER FAILOVER TAKEOVER` 的特殊语义（其 configEpoch 已由现有逻辑覆盖）。

## Decisions

1. **自降级走 gossip 接收侧，不走启动阻塞对齐。** 在 `processGossipNodes` 中移除"跳过 MYSELF"的盲区，但仅对"角色为 SLAVE 且 configEpoch 严格大于本地基线"的 MYSELF 视图应用降级。相比启动时阻塞等待 PONG，此方案不增加启动延迟、不引入死锁风险（启动时若集群不可达仍能以本地配置服务），且与 Redis 的"gossip 驱动收敛"模型一致。
2. **PING/PONG 追加 `currentEpoch` 字段，向后兼容。** 在消息体尾部追加 8 字节 `senderCurrentEpoch`；解码时若剩余字节不足则保留默认值 0（`setEpochIfGreater(0)` 无副作用）。不新增消息类型、不改变现有字段顺序，旧节点解码新消息时尾部多出的字节被忽略。
3. **启动恢复"软对齐"而非"硬重置"。** `restoreClusterFromConfig` 仍从本地恢复 MYSELF 与 slots（保证单节点启动可用），但 `initCurrentNode` 后由 gossip 自然纠正。不在启动路径加阻塞等待，避免集群分区时节点无法启动。
4. **复用 `ReplicationLifecycleListener.demoteToSlave`。** 自降级时调用现有 `demoteToSlave(winner)` 切换复制方向，不新增接口。`ClusterNode` 的角色/slots/masterNodeId 变更与 `onFailoverResult` 降级路径保持一致（clearSlots、MASTER->SLAVE、setMasterNodeId）。
5. **epoch 门控统一为"严格大于"。** 自降级仅在 `gossipEpoch > localEpochBaseline` 时触发，与 `syncSenderRole`/`processGossipNodes` 现有第三方节点角色切换门控一致。相等时不切换，防止陈旧视图回退。

## Risks / Trade-offs

- [Risk] gossip section 随机选择 ≤3 个节点，重启节点可能要等几个心跳周期才收到携带 MYSELF 视图的消息 -> 可接受（秒级收敛），且 PING/PONG 头携带的 `currentEpoch` 不受此限制，能更快同步集群级 epoch。
- [Risk] 移除 MYSELF 跳过后，若 gossip 携带错误视图可能误降级 -> 严格 epoch 门控 + 仅 SLAVE 角色触发 + `configEpoch` 单调递增保证只有更新视图才能覆盖。
- [Risk] PING/PONG 协议扩展可能破坏旧版本节点 -> 追加字段在尾部、解码时长度校验、缺字段默认 0，向后兼容；需在编解码测试中覆盖旧消息解码。
- [Risk] 启动恢复仍以本地 slots 服务，在首个 PONG 到达前可能短暂接受写入 -> 与 Redis 行为一致（重启后短暂窗口），且 slots 写入会因 MOVED 重定向到新主而自然失败；可接受。
- [Trade-off] 不做启动阻塞对齐意味着极端情况下（集群完全分区）重启节点仍以 master 上线 -> 这是预期行为（与 Redis 一致），分区恢复后由 gossip 收敛。

## Migration Plan

无需数据迁移。升级后：
- 重启的原主节点会在首个携带更高 configEpoch 的 PONG 后自降级。
- 滚动升级期间，新版本节点与旧版本节点可互通（协议向后兼容）。
- 回滚：回退应用版本即可；`nodes.conf` 格式不变。

## Open Questions

无。设计决策已通过日志与源码验证。
```

## openspec/changes/fix-cluster-restart-demote/tasks.md

- Source: openspec/changes/fix-cluster-restart-demote/tasks.md
- Lines: 1-34
- SHA256: 8f3feda8f680092d9afc57e9aeb8562652c243dacb134f24c364d043fc8f5291

```md
## 1. PING/PONG 协议扩展（携带 currentEpoch）

- [ ] 1.1 `PingMessage`/`PongMessage` 在消息体尾部追加 8 字节 `senderCurrentEpoch` 字段，更新 `encodeBody`/`decodeBody`；解码时剩余字节不足则保留默认值 0（向后兼容）
- [ ] 1.2 `GossipProtocol.sendPing`/`sendPong` 填充 `senderCurrentEpoch = clusterConfig.getCurrentEpoch()`
- [ ] 1.3 `GossipProtocol.updateNodeFromPingMessage`/`updateNodeFromPongMessage` 调用 `clusterConfig.setEpochIfGreater(senderCurrentEpoch)`，使重启节点能通过心跳同步集群级 currentEpoch
- [ ] 1.4 更新 `GossipMessageCodecTest` 覆盖：新消息编解码、旧消息（无 currentEpoch 字段）解码向后兼容

## 2. gossip 接收侧 MYSELF 自降级

- [ ] 2.1 改造 `GossipProtocol.processGossipNodes`：移除对 MYSELF 的无条件 `continue`，改为当 gossip entry 的 `nodeId == myNodeId` 且 `gossipEpoch > localEpochBaseline` 且 flags 含 SLAVE 时，执行自降级（清空 MYSELF slots、MASTER->SLAVE、`setMasterNodeId(nodeInfo.getMasterNodeId())`）
- [ ] 2.2 自降级时调用 `replicationLifecycleListener.demoteToSlave(newMasterNode)` 切换复制方向（复用现有接口，不新增）
- [ ] 2.3 自降级后调用 `notifyTopologyChanged()` 触发 `nodes.conf` 持久化与 SlotManager 同步
- [ ] 2.4 严格 epoch 门控：仅 `gossipEpoch > localEpochBaseline` 触发角色切换，相等时不切换（与第三方节点门控一致，防回退）
- [ ] 2.5 处理 MYSELF 视图中 slots 同步：自降级时清空本地 slots，并按新主视图同步 slots 归属到 SlotManager/ClusterConfig

## 3. 启动恢复软对齐

- [ ] 3.1 `NettyRedisServer.restoreClusterFromConfig`/`initCurrentNode` 保留本地恢复（节点 ID、已知节点列表、slots 作为初始值），不新增阻塞等待；依赖 gossip 自然纠正角色
- [ ] 3.2 增加日志：启动恢复时若 MYSELF 为 master 且本地 configEpoch 低于已知集群视图，输出"等待 gossip 对齐"提示，便于诊断
- [ ] 3.3 确保 `seedSlotManagerFromConfig` 在 gossip 自降级清空 slots 后能正确同步 SlotManager（mySlots 清空、slotOwners 更新为新主）

## 4. 回归测试

- [ ] 4.1 单元测试：`GossipProtocol` 收到携带 MYSELF 降级视图（更高 configEpoch + SLAVE + masterNodeId）的 PONG 时，MYSELF 自降级--角色切换、slots 清空、masterNodeId 设置
- [ ] 4.2 单元测试：epoch 门控--`gossipEpoch == localEpoch` 时不自降级（防回退）；`gossipEpoch < localEpoch` 时忽略
- [ ] 4.3 单元测试：PING/PONG 携带 `currentEpoch` 后，接收侧 `setEpochIfGreater` 同步集群级 currentEpoch
- [ ] 4.4 单元测试：旧版本 PING/PONG 消息（无 currentEpoch 字段）解码向后兼容，不抛异常
- [ ] 4.5 集成测试：模拟故障转移后旧主重启--新主已提升（epoch=9），旧主以 epoch=4 重启，经 gossip 交互后旧主降级为新主 slave，slots 转移，`cluster nodes` 一致

## 5. 验证

- [ ] 5.1 运行 `luban-rds-cluster` 模块全部单元测试
- [ ] 5.2 运行项目构建（Java 17）确认无回归
- [ ] 5.3 在 `D:\tmp\luban-rds` 6 节点集群复现场景，验证重启旧主正确降级
```

## openspec/changes/fix-cluster-restart-demote/specs/cluster-automatic-failover/spec.md

- Source: openspec/changes/fix-cluster-restart-demote/specs/cluster-automatic-failover/spec.md
- Lines: 1-41
- SHA256: cfbad5778083071e6e7e66a507899f0a22eeba548c2d20d1e908c4eea92ae024

```md
## ADDED Requirements

### Requirement: 故障转移后原主重启经 gossip 自降级

当 master 节点被故障转移替换后重启上线，系统 MUST 通过 gossip 心跳的 epoch 仲裁让其自降级为新主的 slave，而不得盲信本地 `nodes.conf` 维持旧 master 身份。重启节点从本地恢复的 `configEpoch` 低于集群已演进的 `configEpoch` 时，首个携带 MYSELF 降级视图（更高 `configEpoch` + SLAVE 角色 + 指向新主的 `masterNodeId`）的 PING/PONG 必须触发自降级。

#### Scenario: 重启旧主通过 gossip 自降级

- **WHEN** 原 master M 重启后从本地 `nodes.conf` 恢复为 MASTER，且本地 `configEpoch` 低于集群当前值
- **AND** M 收到 PONG/PING，其 gossip section 携带 MYSELF 的视图：`configEpoch > M 本地基线` 且 flags 含 SLAVE 且 `masterNodeId` 指向新主 S
- **THEN** M 清空自身 slots
- **AND** M 切换角色 MASTER->SLAVE 并设置 `masterNodeId = S`
- **AND** M 通过 `ReplicationLifecycleListener.demoteToSlave(S)` 切换复制方向，向 S 发起同步
- **AND** M 持久化 `nodes.conf` 并同步 SlotManager

#### Scenario: 严格 epoch 门控防止回退已提升 master

- **WHEN** 节点收到 gossip 携带 MYSELF 视图，但其 `configEpoch` 小于或等于本地 MYSELF 的 `configEpoch` 基线
- **THEN** 不触发自降级
- **AND** 不改变 MYSELF 的角色、slots 或 masterNodeId
- **AND** 防止陈旧 gossip 把已合法提升的 master 回退为 slave

#### Scenario: currentEpoch 经 PING/PONG 心跳同步

- **WHEN** 重启节点收到 PING/PONG，其消息头 `senderCurrentEpoch` 大于本地 `currentEpoch`
- **THEN** 节点通过 `setEpochIfGreater` 将本地 `currentEpoch` 提升至 `senderCurrentEpoch`
- **AND** 使后续 epoch 仲裁门控能正常工作（避免本地 epoch 滞后导致自降级恒不触发）

#### Scenario: PING/PONG 协议向后兼容

- **WHEN** 新版本节点收到旧版本节点发送的 PING/PONG（消息体尾部无 `senderCurrentEpoch` 字段）
- **THEN** 解码不抛异常
- **AND** `senderCurrentEpoch` 按默认值 0 处理，`setEpochIfGreater(0)` 无副作用
- **AND** 旧版本节点收到新版本消息时忽略尾部多余字节

#### Scenario: 启动恢复软对齐不阻塞

- **WHEN** 节点重启并从本地 `nodes.conf` 恢复，本地视图显示 MYSELF 为 master
- **THEN** 节点以本地配置启动服务（不阻塞等待集群对齐）
- **AND** 启动后由 gossip 心跳自然纠正角色与 slots
- **AND** 在首个携带更高 configEpoch 的 PONG 到达前，写入请求因 slots 已属于新主而返回 MOVED 重定向
```

