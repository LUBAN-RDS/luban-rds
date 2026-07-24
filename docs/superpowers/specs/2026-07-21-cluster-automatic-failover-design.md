---
comet_change: add-cluster-automatic-failover
role: technical-design
canonical_spec: openspec
archived-with: 2026-07-21-add-cluster-automatic-failover
status: final
---

# Cluster Automatic Failover — 技术设计

> **Canonical spec**: `openspec/changes/add-cluster-automatic-failover/specs/cluster-automatic-failover/spec.md`
> 本文档是 HOW（实现层），需求层（WHAT）以 OpenSpec delta spec 为准。

## 1. 背景与目标

Luban-RDS 集群已实现 PFAIL → FAIL 的故障检测共识，但 FAIL 之后无任何 slave 自动提升。线上 3 主 3 从集群在单 master 宕机时整个不可用（日志证据：master 反复 PFAIL/FAIL 但无 "slave promoted to master"）。

本设计新增 `FailoverManager` 选举状态机，对齐 Redis Cluster 的 slave election：slave 检测 master FAIL → 广播 AUTH_REQUEST → master 多数派授权 → 胜选提升 → 广播 FailoverResult 全网收敛。

详细背景、Goals/Non-Goals、高层决策 D1-D6 见 OpenSpec `design.md`。本文档聚焦实现层决策 I1-I4 与详细设计。

## 2. 实现层决策（Implementation Decisions）

### I1. `performFailover` 归属：FailoverManager 内部方法

`performFailover(slaveNode, masterNode)` 从 `ClusterCommandHandler` 抽取到 `FailoverManager` 作为私有方法。`FailoverManager` 持有 `ClusterConfig / SlotManager / ClusterStateManager / ClusterBusClient` 引用。

- 手动 `CLUSTER FAILOVER [FORCE|TAKEOVER]` 改为 `gossipProtocol.getFailoverManager().performManualFailover(slave, master)`，该方法内部调用私有 `performFailover` + `notifyTopologyChanged`，**不**经过选举状态机、**不**广播 RESULT（保持 TAKEOVER 直接接管语义）。
- 自动选举胜选后调用 `performFailoverAndBroadcast`，它内部也调用私有 `performFailover`，额外自增 epoch + 广播 RESULT。

**理由**：逻辑集中、签名稳定、手动/自动共用核心提升逻辑避免漂移。

### I2. 线程安全：FailoverManager 内部锁

`FailoverManager` 所有状态读写方法用 `synchronized`（或 `ReentrantLock`）保护。理由：
- `GossipTask.tick()` 跑在 `gossip-protocol` 单线程调度器。
- `onAuthRequest / onAuthAck / onFailoverResult` 跑在 Netty `nioEventLoopGroup-*` 线程。
- 调度频率 1Hz，锁竞争可忽略；`synchronized` 简单直接。

**不用 lock-free**：多字段组合读写（如 "check state==REQUESTING && now > electionStartTime + 2*nodeTimeout"）需 CAS 循环，易错且无性能收益。

### I3. FailoverResult 传播：立即广播 + gossip 兜底

胜选后立即 `busClient.broadcast(FailoverResultMessage)` 给所有连接节点（与现有 `broadcastFail` 同模式）。同时 winner 在后续 PING gossip 段携带 MASTER 标志 + 新槽位 + 新 configEpoch，通过现有 `syncSlotsFromNode` 纪元裁决做兜底：错过广播的节点会被动同步。

**理由**：收敛快（秒级），与现有 FAIL 广播一致；gossip 兜底保证最终一致。

### I4. 测试策略：纯单测 + 模拟消息传递

- **单测** `FailoverManagerTest`：直接 `new FailoverManager(config, slotManager, ...)`，mock `ClusterBusClient`，手动调 `tick()` / `onAuthRequest()` / `onAuthAck()` / `onFailoverResult()`，`verify(busClient).broadcast(any())`。
- **集成测试** 扩展 `ClusterFailoverTest`：构造 `TestCluster` 模拟器，每个逻辑节点一个 `FailoverManager`，`deliver(msg, fromNode)` 把广播消息投递给其他节点的 handler，驱动各节点 `tick()`，验证 3 master + 多 slave 场景下的唯一胜选。

**不启真网络**：端口/时序/CI 环境脆弱，且单线程模型下单测能确定性地覆盖竞态。

## 3. FailoverManager 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      FailoverManager                        │
├─────────────────────────────────────────────────────────────┤
│  依赖（构造注入）:                                           │
│    ClusterConfig clusterConfig                              │
│    SlotManager slotManager                                  │
│    ClusterStateManager stateManager                         │
│    ClusterBusClient busClient                               │
│    Runnable onTopologyChanged                               │
│    long nodeTimeout                                         │
│    long gracePeriod           // cluster-failover-grace-period │
├─────────────────────────────────────────────────────────────┤
│  候选侧状态（slave 发起选举用）:                              │
│    FailoverState state        // IDLE/REQUESTING/ELECTED     │
│    long electionEpoch         // 本轮选举的 currentEpoch     │
│    long requestDeadline       // 退避到期时间                │
│    long electionStartTime                                     │
│    Set<String> authVotes      // 已授权的 master nodeId 集合 │
│    String failedMasterId      // 触发选举的 master           │
│                                                              │
│  投票侧状态（master 授权用，与本节点状态共存）:               │
│    Map<String, Long> votesCast  // slaveId → currentEpoch    │
│    long lastVoteEpoch           // 本节点已投票的最高 epoch  │
├─────────────────────────────────────────────────────────────┤
│  公共方法（全部 synchronized）:                              │
│    void tick()                                   // GossipTask 每轮调 │
│    void onAuthRequest(FailoverAuthRequestMessage)  // master 侧 │
│    void onAuthAck(FailoverAuthAckMessage)          // slave 侧  │
│    void onFailoverResult(FailoverResultMessage)    // 全节点   │
│    void performManualFailover(slaveNode, masterNode)         │
├─────────────────────────────────────────────────────────────┤
│  私有方法:                                                   │
│    void tryStartElection()                                   │
│    void broadcastAuthRequest()                               │
│    void checkElectionTimeout()                               │
│    void performFailoverAndBroadcast()                        │
│    void performFailover(slaveNode, masterNode)  // I1 抽取    │
│    void resetElectionState()                                 │
│    void sendAuthAck(candidateId, epoch)                      │
│    void notifyTopologyChanged()                              │
└─────────────────────────────────────────────────────────────┘
```

候选侧与投票侧状态在同一实例共存：一个节点既可能是 master（用 votesCast）也可能是 slave（用 authVotes），但不会同时处于两种角色，字段互不干扰。

## 4. 状态机

```
                         ┌──────────────────────────────┐
                         │            IDLE              │
                         └──────────────┬───────────────┘
                                        │ tick() 检测到:
                                        │ isSlave() && master.isFail() && state==IDLE
                                        ▼
                        ┌───────────────────────────────┐
                        │  tryStartElection()           │
                        │  electionStartTime = now      │
                        │  requestDeadline = now +      │
                        │      gracePeriod +            │
                        │      hash(nodeId) % 500ms     │
                        │  authVotes.clear()            │
                        │  failedMasterId = master      │
                        └───────────────┬───────────────┘
                                        ▼
                         ┌──────────────────────────────┐
                         │       REQUESTING             │
   tick() & now ≥        │  (等待退避 + 收集 ACK)        │
   requestDeadline:      └──────┬───────────┬───────────┘
   - currentEpoch++             │           │ tick() & now ≥
   - broadcast AUTH_REQUEST     │           │ electionStartTime + 2*nodeTimeout:
                                │           │ 清 authVotes → 回 IDLE
                                │           ▼
                  onAuthAck() & authVotes.size() ≥ masterCount/2+1:
                                ▼
                         ┌──────────────────────────────┐
                         │        ELECTED               │
                         │  performFailoverAndBroadcast │
                         │  回 IDLE (已是 master)       │
                         └──────────────────────────────┘
```

**退避抖动公式**（D3 具体化）：
```java
long jitter = Math.abs(nodeId.hashCode()) % 500L;  // 0..499ms
long deadline = electionStartTime + gracePeriod + jitter;
```
nodeId 是 40 字符十六进制，不同 slave hashCode 不同 → 错峰广播。

**不变量**：
- IDLE → REQUESTING 只在 `master.isFail()` 时触发；master 恢复则回 IDLE。
- ELECTED 是瞬态，performFailover 后本节点已是 master，下轮 tick 自然留 IDLE。

## 5. 投票授权逻辑（master 侧 onAuthRequest）

```java
synchronized void onAuthRequest(FailoverAuthRequestMessage req) {
    ClusterNode me = clusterConfig.getMyNode();
    if (me == null || !me.isMaster()) return;           // 仅 master 投票

    long reqEpoch = req.getCurrentEpoch();
    long myEpoch = clusterConfig.getCurrentEpoch();

    // (1) 过期纪元拒绝
    if (reqEpoch < myEpoch) return;

    // (2) 落后则追平，新纪元清旧票
    if (reqEpoch > myEpoch) {
        clusterConfig.setCurrentEpoch(reqEpoch);
        lastVoteEpoch = reqEpoch;
        votesCast.clear();
    }

    String candidateId = req.getSenderNodeId();

    // (3) 本纪元已投该 slave → 幂等重发 ACK
    Long votedAtEpoch = votesCast.get(candidateId);
    if (votedAtEpoch != null && votedAtEpoch == reqEpoch) {
        sendAuthAck(candidateId, reqEpoch);
        return;
    }

    // (4) 本纪元已投他 slave → 拒绝
    if (!votesCast.isEmpty()) return;

    // (5) 首投
    votesCast.put(candidateId, reqEpoch);
    sendAuthAck(candidateId, reqEpoch);
}
```

**Spec 对应**：过期拒绝→(1)；首投→(2)+(5)；幂等→(3)；拒他→(4)。

**简化说明**：Redis 真实实现里 master 缓存多候选请求择优（configEpoch/offset/nodeId 比较）。本项目 replicationOffset 恒为 0（Non-Goals），择优退化为 nodeId 字典序；D3 退避抖动让 nodeId 较小的 slave 先广播，首投即投 ≈ 择优。严格择优留作后续 change。

## 6. 胜选提升与 FailoverResultMessage

### performFailover（从 ClusterCommandHandler 抽取，签名不变）

```java
private void performFailover(ClusterNode slaveNode, ClusterNode masterNode) {
    slaveNode.removeState(ClusterNodeState.SLAVE);
    slaveNode.addState(ClusterNodeState.MASTER);
    slaveNode.setMasterNodeId(null);

    BitSet masterSlots = masterNode.getSlots();
    for (int i = masterSlots.nextSetBit(0); i >= 0; i = masterSlots.nextSetBit(i + 1)) {
        slaveNode.addSlot(i);
        slotManager.setSlotOwner(i, slaveNode.getNodeId());
    }

    masterNode.clearSlots();
    masterNode.removeState(ClusterNodeState.MASTER);
    masterNode.addState(ClusterNodeState.SLAVE);
    masterNode.setMasterNodeId(slaveNode.getNodeId());

    stateManager.updateClusterState();
}
```

### performFailoverAndBroadcast（自动选举专用）

```java
synchronized void performFailoverAndBroadcast() {
    ClusterNode me = clusterConfig.getMyNode();
    ClusterNode oldMaster = clusterConfig.getNode(failedMasterId);

    performFailover(me, oldMaster);

    clusterConfig.incrementEpoch();              // currentEpoch++
    me.setConfigEpoch(clusterConfig.getCurrentEpoch());
    state = FailoverState.ELECTED;

    FailoverResultMessage result = new FailoverResultMessage(
            me.getNodeId(),
            clusterConfig.getCurrentEpoch(),
            me.getSlots());
    busClient.broadcast(result);

    notifyTopologyChanged();
    logger.warn("slave 自动提升为 master: nodeId={}, epoch={}",
            me.getNodeId(), clusterConfig.getCurrentEpoch());

    resetElectionState();   // 回 IDLE
}
```

### FailoverResultMessage 字段

| 字段 | 类型 | 编码 |
|------|------|------|
| senderNodeId | String (40B) | 继承自 GossipMessage |
| winnerNodeId | String (40B) | 40 字节 ASCII |
| newConfigEpoch | long | 8 字节大端 |
| inheritedSlots | BitSet (16384b = 2048B) | 2048 字节位图 |

总消息体 ≈ 2096 字节，单包可控。

### onFailoverResult（收端收敛）

```java
synchronized void onFailoverResult(FailoverResultMessage msg) {
    long myEpoch = clusterConfig.getCurrentEpoch();

    // 纪元裁决：旧纪元忽略（防回放）
    if (msg.getNewConfigEpoch() < myEpoch) return;

    ClusterNode winner = clusterConfig.getNode(msg.getWinnerNodeId());
    if (winner == null) return;

    // winner 提权
    winner.removeState(ClusterNodeState.SLAVE);
    winner.addState(ClusterNodeState.MASTER);
    winner.removeState(ClusterNodeState.FAIL);
    winner.removeState(ClusterNodeState.PFAIL);
    winner.setMasterNodeId(null);
    winner.setConfigEpoch(msg.getNewConfigEpoch());

    // 槽位转移
    BitSet inherited = msg.getInheritedSlots();
    winner.setSlots(inherited);
    for (int i = inherited.nextSetBit(0); i >= 0; i = inherited.nextSetBit(i + 1)) {
        slotManager.setSlotOwner(i, winner.getNodeId());
    }

    // 原持有这些槽位的旧 master 降级为 winner 的 slave
    for (ClusterNode node : clusterConfig.getAllNodes()) {
        if (node.isMaster() && !node.getNodeId().equals(winner.getNodeId())
                && sharesAnySlot(node, inherited)) {
            node.clearSlots();
            node.removeState(ClusterNodeState.MASTER);
            node.addState(ClusterNodeState.SLAVE);
            node.setMasterNodeId(winner.getNodeId());
        }
    }

    clusterConfig.setCurrentEpoch(msg.getNewConfigEpoch());
    notifyTopologyChanged();
}
```

## 7. 集成点

### GossipTask.run 改动

```java
@Override
public void run() {
    try {
        sendHeartbeats();
        checkNodeTimeouts();
        checkAndBroadcastFail();
        updateClusterState();
        // 新增：驱动选举状态机（FAIL 状态已更新，此时检查最准确）
        gossipProtocol.getFailoverManager().tick();
        gossipProtocol.saveClusterConfigIfNeeded();
    } catch (Exception e) {
        logger.error("Gossip 任务执行失败", e);
    }
}
```

### ClusterBusHandler.handleMessage 改动

```java
case FAILOVER_AUTH_REQUEST:
    if (gossipProtocol != null) {
        gossipProtocol.handleFailoverAuthRequest((FailoverAuthRequestMessage) message);
    }
    return null;
case FAILOVER_AUTH_ACK:
    if (gossipProtocol != null) {
        gossipProtocol.handleFailoverAuthAck((FailoverAuthAckMessage) message);
    }
    return null;
case FAILOVER_RESULT:
    if (gossipProtocol != null) {
        gossipProtocol.handleFailoverResult((FailoverResultMessage) message);
    }
    return null;
```

### GossipProtocol 委托方法（薄封装）

```java
public void handleFailoverAuthRequest(FailoverAuthRequestMessage msg) {
    failoverManager.onAuthRequest(msg);
}
public void handleFailoverAuthAck(FailoverAuthAckMessage msg) {
    failoverManager.onAuthAck(msg);
}
public void handleFailoverResult(FailoverResultMessage msg) {
    failoverManager.onFailoverResult(msg);
}
public FailoverManager getFailoverManager() { return failoverManager; }
```

### ClusterCommandHandler.clusterFailover 改动

```java
// 替换原 performFailover(myNode, masterNode) 直接调用
gossipProtocol.getFailoverManager().performManualFailover(myNode, masterNode);
```
performManualFailover 内部调私有 performFailover + notifyTopologyChanged，**不**经选举状态机、**不**广播 RESULT（TAKEOVER 直接接管语义）。

### ClusterConfig.getSlavesOfMaster 新增

```java
public List<ClusterNode> getSlavesOfMaster(String masterNodeId) {
    List<ClusterNode> slaves = new ArrayList<>();
    for (ClusterNode node : nodes.values()) {
        if (node.isSlave() && masterNodeId.equals(node.getMasterNodeId())) {
            slaves.add(node);
        }
    }
    return slaves;
}
```

### GossipMessageType.FAILOVER_RESULT 新增

```java
FAILOVER_RESULT((byte) 0x08);   // 0x00-0x07 已占用，0x08 为下一个空闲码
```
并在 `GossipMessage.createMessage` 工厂注册。

### 配置项 cluster-failover-grace-period

新增可选配置（默认 0），在 `NettyRedisServer` 初始化 `GossipProtocol` / `FailoverManager` 时传入。

## 8. 测试策略

| 测试类 | 覆盖范围 | 手法 |
|--------|---------|------|
| FailoverManagerTest (单测) | IDLE→REQUESTING→ELECTED 流转、退避抖动计算、超时回退 | 直接 new FailoverManager，手动调 tick()，verify(busClient).broadcast(any()) |
| FailoverManagerTest | 投票授权：首投/幂等/拒他/拒过期 | 构造 master 节点，调 onAuthRequest，verify ACK |
| FailoverManagerTest | performFailover 槽位转移、epoch 自增、RESULT 广播 | slave + master，调 onAuthAck 凑票，assert 槽位 + RESULT |
| FailoverManagerTest | FailoverResult 收敛：winner 提权、原 master 降级、旧纪元忽略 | onFailoverResult，assert 节点状态 |
| ClusterFailoverTest (扩展) | 3 master + 1 slave of M1，M1 FAIL → slave 自动提升 | TestCluster 模拟器，驱动 tick，assert 唯一胜选 |
| ClusterFailoverTest | 3 master + 2 slaves of M1，同时 REQUESTING → 仅一个胜选 | 两 slave 各 FailoverManager，模拟 AUTH_REQUEST 传递 + master 投票 |
| ClusterFailoverTest | 手动 CLUSTER FAILOVER TAKEOVER 不触发选举状态机 | 调 clusterFailover，assert FailoverManager.state == IDLE |

### TestCluster 多节点模拟模式

```java
class TestCluster {
    Map<String, FailoverManager> nodes = new HashMap<>();
    Map<String, ClusterConfig> configs = new HashMap<>();

    // 模拟 busClient.broadcast：把消息投递给所有其他节点的对应 handler
    void deliver(GossipMessage msg, String fromNode) {
        for (var e : nodes.entrySet()) {
            if (e.getKey().equals(fromNode)) continue;
            if (msg instanceof FailoverAuthRequestMessage r) e.getValue().onAuthRequest(r);
            else if (msg instanceof FailoverAuthAckMessage a) e.getValue().onAuthAck(a);
            else if (msg instanceof FailoverResultMessage res) e.getValue().onFailoverResult(res);
        }
    }
}
```

覆盖 spec 全部 19 个 Scenario。

## 9. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 多 slave 同时胜选脑裂 | D1 "每纪元每 master 一票" + D3 退避抖动 + D4 结果广播共同保证唯一胜选 |
| 选举窗口槽位无主，客户端 MOVED | Redis Cluster 固有秒级窗口，客户端按 -MOVED 重试收敛 |
| 原 master 回归冲突 | D4 结果消息强制降级；configEpoch 较小在 syncSlotsFromNode 败北 |
| replicationOffset 缺失数据不一致 | Non-Goals 明确接受；运维确保 slave 复制延迟可控 |
| FAILOVER_RESULT 新消息类型对老节点不兼容 | 老节点 default 分支丢弃；滚动升级约束 |

## 10. 验收标准（对应 spec Scenario）

实现完成后，以下 spec Scenario 必须全部有对应测试通过：

- slave 检测 master FAIL 启动选举 / 退避后广播 AUTH_REQUEST / 非 slave 不触发
- master 首投 / 幂等重发 / 拒他 / 拒过期
- 候选过半授权胜选 / 选举超时回退
- FailoverResult 更新拓扑 / 旧纪元忽略
- ClusterBusHandler 分发三种新消息
- 手动 CLUSTER FAILOVER TAKEOVER 行为不变
- cluster-failover-grace-period 默认值与自定义值

最终 `mvn test -pl luban-rds-cluster` 全量通过。
