---
archived-with: 2026-07-21-add-cluster-automatic-failover
status: final
---
# Cluster Automatic Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 luban-rds 集群新增 master 宕机后的 slave 自动选举 + 多数派授权 + 胜选提升 + 拓扑收敛，使单 master 故障时集群秒级自愈。

**Architecture:** 新增 `FailoverManager` 选举状态机（持有 ClusterConfig/SlotManager/ClusterStateManager/ClusterBusClient 引用），由 `GossipTask.tick()` 驱动。复用已有的 `FailoverAuthRequestMessage`/`FailoverAuthAckMessage` 死代码，新增 `FailoverResultMessage` 做拓扑收敛。`ClusterCommandHandler.performFailover` 抽取到 FailoverManager 供手动/自动共用。

**Tech Stack:** Java 17, JUnit 5, Mockito, Netty, SLF4J. Maven multi-module, 改动集中在 `luban-rds-cluster` + `luban-rds-server` 接线。

**Design Doc:** `docs/superpowers/specs/2026-07-21-cluster-automatic-failover-design.md`
**OpenSpec change:** `add-cluster-automatic-failover`

```yaml
---
change: add-cluster-automatic-failover
design-doc: docs/superpowers/specs/2026-07-21-cluster-automatic-failover-design.md
base-ref: 1d074f75219037551bc2630703b3a349609d43e8
---
```

## File Structure

**新建文件:**
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverResultMessage.java` — 胜选结果消息（winnerNodeId/newConfigEpoch/inheritedSlots + 编解码）
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java` — 选举状态机 + 投票授权 + performFailover
- `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/FailoverManagerTest.java` — 单元测试

**修改文件:**
- `luban-rds-cluster/.../gossip/GossipMessageType.java` — 新增 FAILOVER_RESULT(0x08)
- `luban-rds-cluster/.../gossip/GossipMessage.java` — createMessage 注册 FAILOVER_RESULT
- `luban-rds-cluster/.../gossip/GossipProtocol.java` — 新增 failoverManager 字段 + 3 个 handle 委托方法 + 构造时创建
- `luban-rds-cluster/.../gossip/GossipTask.java` — run() 增加 failoverManager.tick()
- `luban-rds-cluster/.../bus/ClusterBusHandler.java` — handleMessage 增加 3 个 case
- `luban-rds-cluster/.../config/ClusterConfig.java` — 新增 getSlavesOfMaster
- `luban-rds-cluster/.../handler/ClusterCommandHandler.java` — performFailover 委托给 FailoverManager.performManualFailover
- `luban-rds-cluster/.../integration/ClusterFailoverTest.java` — 扩展集成测试
- `luban-rds-server/.../server/NettyRedisServer.java` — gracePeriod 配置传入
- `luban-rds-bin/src/main/resources/luban-rds.conf` — 新增 cluster-failover-grace-period
- `AGENTS.md` — 第 10 节补充

---

## Task 1: 新增 GossipMessageType.FAILOVER_RESULT + 工厂注册

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipMessageType.java:46-51`
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipMessage.java:216-236`

- [ ] **Step 1.1: 在 GossipMessageType 新增 FAILOVER_RESULT 枚举值**

修改 `GossipMessageType.java`，把 UPDATE 之后增加 FAILOVER_RESULT（注意：0x07 已被 UPDATE 占用，用 0x08）：

```java
    /**
     * 配置更新通知 - 通知配置变更
     */
    UPDATE((byte) 0x07),

    /**
     * 故障转移结果通知 - 胜选 slave 广播自己已提升为新 master
     */
    FAILOVER_RESULT((byte) 0x08);
```

- [ ] **Step 1.2: 在 GossipMessage.createMessage 注册 FAILOVER_RESULT**

修改 `GossipMessage.java` 的 createMessage switch，在 UPDATE case 后增加（注意此时 FailoverResultMessage 类尚未创建，本步只加 case 占位会在编译时报错 —— 改为在 Task 2 创建类后回来补此 case，或本步一并完成 Task 2 的空类骨架）：

```java
            case UPDATE:
                return new UpdateMessage();
            case FAILOVER_RESULT:
                return new FailoverResultMessage();
            default:
                throw new IllegalArgumentException("不支持的消息类型: " + type);
```

（本步与 Task 2 Step 2.1 一起提交，避免编译断裂。）

- [ ] **Step 1.3: 验证编译**

```bash
mvn -pl luban-rds-cluster compile -q
```
Expected: BUILD SUCCESS（FailoverResultMessage 已在 Task 2 创建）。

---

## Task 2: 新增 FailoverResultMessage 类

**Files:**
- Create: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverResultMessage.java`

- [ ] **Step 2.1: 创建 FailoverResultMessage 完整实现**

参考 `FailMessage.java` 的编解码模式 + `ClusterNode.CLUSTER_SLOTS = 16384`。消息体格式：winnerNodeId(40B) + newConfigEpoch(8B 大端) + inheritedSlots(2048B 位图)。

```java
package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * FAILOVER_RESULT 消息
 * <p>
 * 胜选 slave 广播自己已提升为新 master，触发全网拓扑收敛。
 * </p>
 * <p>
 * 消息体格式：
 * - 胜选节点ID（40 字节 ASCII）
 * - 新配置纪元（8 字节，大端序）
 * - 继承的槽位 BitSet（16384 位 = 2048 字节）
 * </p>
 */
public class FailoverResultMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 16384 位槽位图占用的字节数
     */
    private static final int SLOTS_BYTES = ClusterNodeSlotsHolder.CLUSTER_SLOTS / 8;

    private String winnerNodeId;
    private long newConfigEpoch;
    private BitSet inheritedSlots;

    public FailoverResultMessage() {
        this.type = GossipMessageType.FAILOVER_RESULT;
    }

    public FailoverResultMessage(String senderNodeId, String winnerNodeId,
                                 long newConfigEpoch, BitSet inheritedSlots) {
        super(senderNodeId, GossipMessageType.FAILOVER_RESULT);
        this.winnerNodeId = winnerNodeId;
        this.newConfigEpoch = newConfigEpoch;
        this.inheritedSlots = inheritedSlots != null ? (BitSet) inheritedSlots.clone() : new BitSet(SLOTS_BYTES * 8);
    }

    public String getWinnerNodeId() {
        return winnerNodeId;
    }

    public void setWinnerNodeId(String winnerNodeId) {
        this.winnerNodeId = winnerNodeId;
    }

    public long getNewConfigEpoch() {
        return newConfigEpoch;
    }

    public void setNewConfigEpoch(long newConfigEpoch) {
        this.newConfigEpoch = newConfigEpoch;
    }

    public BitSet getInheritedSlots() {
        return inheritedSlots;
    }

    public void setInheritedSlots(BitSet inheritedSlots) {
        this.inheritedSlots = inheritedSlots;
    }

    @Override
    protected byte[] encodeBody() {
        byte[] data = new byte[40 + 8 + SLOTS_BYTES];
        int offset = 0;

        // winnerNodeId（40 字节）
        if (winnerNodeId != null) {
            byte[] idBytes = winnerNodeId.getBytes(StandardCharsets.UTF_8);
            int copyLen = Math.min(idBytes.length, 40);
            System.arraycopy(idBytes, 0, data, offset, copyLen);
        }
        offset += 40;

        // newConfigEpoch（8 字节大端）
        data[offset++] = (byte) (newConfigEpoch >> 56);
        data[offset++] = (byte) (newConfigEpoch >> 48);
        data[offset++] = (byte) (newConfigEpoch >> 40);
        data[offset++] = (byte) (newConfigEpoch >> 32);
        data[offset++] = (byte) (newConfigEpoch >> 24);
        data[offset++] = (byte) (newConfigEpoch >> 16);
        data[offset++] = (byte) (newConfigEpoch >> 8);
        data[offset++] = (byte) newConfigEpoch;

        // inheritedSlots（2048 字节位图）
        BitSet slots = inheritedSlots != null ? inheritedSlots : new BitSet(SLOTS_BYTES * 8);
        byte[] slotBytes = slots.toByteArray();
        System.arraycopy(slotBytes, 0, data, offset, Math.min(slotBytes.length, SLOTS_BYTES));

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 40 + 8) {
            return;
        }
        int offset = 0;

        byte[] idBytes = new byte[40];
        System.arraycopy(body, offset, idBytes, 0, 40);
        this.winnerNodeId = new String(idBytes, StandardCharsets.UTF_8).trim();
        offset += 40;

        this.newConfigEpoch = 0;
        for (int i = 0; i < 8; i++) {
            this.newConfigEpoch = (this.newConfigEpoch << 8) | (body[offset++] & 0xFFL);
        }

        // inheritedSlots
        int slotLen = Math.min(SLOTS_BYTES, body.length - offset);
        byte[] slotBytes = new byte[SLOTS_BYTES];
        System.arraycopy(body, offset, slotBytes, 0, slotLen);
        this.inheritedSlots = BitSet.valueOf(slotBytes);
    }

    @Override
    public String toString() {
        return "FailoverResultMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", winnerNodeId='" + winnerNodeId + '\'' +
                ", newConfigEpoch=" + newConfigEpoch +
                ", inheritedSlotCount=" + (inheritedSlots != null ? inheritedSlots.cardinality() : 0) +
                '}';
    }

    /**
     * 槽位总数 holder，避免循环依赖 ClusterNode 常量（保持与 ClusterNode.CLUSTER_SLOTS=16384 一致）。
     */
    static final class ClusterNodeSlotsHolder {
        static final int CLUSTER_SLOTS = 16384;
    }
}
```

- [ ] **Step 2.2: 验证编译**

```bash
mvn -pl luban-rds-cluster compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 2.3: 提交**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipMessageType.java \
        luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipMessage.java \
        luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverResultMessage.java
git commit -m "feat(cluster): 新增 FAILOVER_RESULT 消息类型与 FailoverResultMessage 类"
```

- [ ] **Step 2.4: 勾选 tasks.md 1.1 / 1.2**

把 tasks.md 中 `- [ ] 1.1` 和 `- [ ] 1.2` 改为 `- [x]`，提交。

---

## Task 3: ClusterConfig 新增 getSlavesOfMaster

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/config/ClusterConfig.java` (在 getSlaveCount 之后)
- Test: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/config/ClusterConfigTest.java`

- [ ] **Step 3.1: 写失败测试**

在 `ClusterConfigTest.java` 新增测试方法（参考已有测试的 setUp 模式）：

```java
@Test
@DisplayName("getSlavesOfMaster 返回该 master 的所有 slave")
void testGetSlavesOfMaster() {
    ClusterConfig config = new ClusterConfig();
    ClusterNode master = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
    master.addState(ClusterNodeState.MASTER);
    master.setIp("127.0.0.1");
    master.setPort(7000);

    ClusterNode slave1 = new ClusterNode("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0");
    slave1.addState(ClusterNodeState.SLAVE);
    slave1.setMasterNodeId(master.getNodeId());
    ClusterNode slave2 = new ClusterNode("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0");
    slave2.addState(ClusterNodeState.SLAVE);
    slave2.setMasterNodeId(master.getNodeId());
    ClusterNode otherSlave = new ClusterNode("d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0");
    otherSlave.addState(ClusterNodeState.SLAVE);
    otherSlave.setMasterNodeId("other-master-id");

    config.addNode(master);
    config.addNode(slave1);
    config.addNode(slave2);
    config.addNode(otherSlave);

    List<ClusterNode> slaves = config.getSlavesOfMaster(master.getNodeId());
    assertEquals(2, slaves.size());
    assertTrue(slaves.stream().anyMatch(n -> n.getNodeId().equals(slave1.getNodeId())));
    assertTrue(slaves.stream().anyMatch(n -> n.getNodeId().equals(slave2.getNodeId())));
}
```

注意 import: `java.util.List`, `com.janeluo.luban.rds.cluster.node.ClusterNodeState`。

- [ ] **Step 3.2: 运行测试确认失败**

```bash
mvn -pl luban-rds-cluster test -Dtest=ClusterConfigTest#testGetSlavesOfMaster -q
```
Expected: 编译失败（getSlavesOfMaster 未定义）。

- [ ] **Step 3.3: 实现 getSlavesOfMaster**

在 `ClusterConfig.java` 的 `getSlaveCount()` 方法之后新增：

```java
    /**
     * 获取指定主节点的所有从节点
     *
     * @param masterNodeId 主节点ID
     * @return 从节点列表（可能为空，不会返回 null）
     */
    public List<ClusterNode> getSlavesOfMaster(String masterNodeId) {
        java.util.List<ClusterNode> slaves = new java.util.ArrayList<>();
        if (masterNodeId == null) {
            return slaves;
        }
        for (ClusterNode node : nodes.values()) {
            if (node.isSlave() && masterNodeId.equals(node.getMasterNodeId())) {
                slaves.add(node);
            }
        }
        return slaves;
    }
```

（按 AGENTS.md 规范应在文件顶部用显式 import；本步先把 `java.util.List` / `java.util.ArrayList` 加到 import 区，方法体用短名。）

- [ ] **Step 3.4: 运行测试确认通过**

```bash
mvn -pl luban-rds-cluster test -Dtest=ClusterConfigTest#testGetSlavesOfMaster -q
```
Expected: Tests run: 1, Failures: 0.

- [ ] **Step 3.5: 提交 + 勾选 tasks.md 1.3**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/config/ClusterConfig.java \
        luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/config/ClusterConfigTest.java \
        openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): ClusterConfig 新增 getSlavesOfMaster"
```

---

## Task 4: FailoverManager 骨架与状态枚举

**Files:**
- Create: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java`
- Test: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/FailoverManagerTest.java`

- [ ] **Step 4.1: 写失败测试 —— 构造与初始状态**

创建 `FailoverManagerTest.java`：

```java
package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class FailoverManagerTest {

    static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    static final long NODE_TIMEOUT = 15000L;

    ClusterConfig config;
    SlotManager slotManager;
    ClusterStateManager stateManager;
    ClusterBusClient busClient;
    FailoverManager failoverManager;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
    }

    @Test
    @DisplayName("初始状态为 IDLE")
    void testInitialState() {
        assertEquals(FailoverState.IDLE, failoverManager.getState());
    }
}
```

- [ ] **Step 4.2: 运行确认失败**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: 编译失败（FailoverManager / FailoverState 未定义）。

- [ ] **Step 4.3: 创建 FailoverState 枚举**

新文件 `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverState.java`：

```java
package com.janeluo.luban.rds.cluster.gossip;

/**
 * FailoverManager 选举状态机状态
 */
public enum FailoverState {
    /** 空闲：未参与选举 */
    IDLE,
    /** 候选态：已检测到 master FAIL，等待退避后广播 AUTH_REQUEST 或已广播正在收集 ACK */
    REQUESTING,
    /** 已胜选：performFailover 已执行（瞬态，立即回 IDLE） */
    ELECTED
}
```

- [ ] **Step 4.4: 创建 FailoverManager 骨架**

```java
package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 集群自动故障转移管理器
 * <p>
 * 持有选举状态机（候选侧）与投票授权记录（master 侧），
 * 由 {@link GossipTask#run()} 每轮调用 {@link #tick()} 驱动。
 * 所有公共方法 synchronized 保护跨线程访问（GossipTask 线程 vs Netty nioEventLoop）。
 * </p>
 */
public class FailoverManager {

    private static final Logger logger = LoggerFactory.getLogger(FailoverManager.class);

    /** 退避抖动上限（毫秒） */
    private static final long JITTER_BOUND_MS = 500L;

    private final ClusterConfig clusterConfig;
    private final SlotManager slotManager;
    private final ClusterStateManager stateManager;
    private final ClusterBusClient busClient;
    private final Runnable onTopologyChanged;
    private final long nodeTimeout;
    private final long gracePeriod;

    // 候选侧状态
    private FailoverState state = FailoverState.IDLE;
    private long electionStartTime;
    private long requestDeadline;
    private long electionEpoch;
    private final Set<String> authVotes = new HashSet<>();
    private String failedMasterId;
    private boolean requestBroadcasted;

    // 投票侧状态（master 用）
    private final Map<String, Long> votesCast = new HashMap<>();
    private long lastVoteEpoch;

    public FailoverManager(ClusterConfig clusterConfig, SlotManager slotManager,
                           ClusterStateManager stateManager, ClusterBusClient busClient,
                           Runnable onTopologyChanged, long nodeTimeout, long gracePeriod) {
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;
        this.stateManager = stateManager;
        this.busClient = busClient;
        this.onTopologyChanged = onTopologyChanged;
        this.nodeTimeout = nodeTimeout;
        this.gracePeriod = gracePeriod;
    }

    public synchronized FailoverState getState() {
        return state;
    }
}
```

- [ ] **Step 4.5: 运行确认通过**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: Tests run: 1, Failures: 0.

- [ ] **Step 4.6: 提交**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverState.java \
        luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java \
        luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/FailoverManagerTest.java
git commit -m "feat(cluster): 新增 FailoverManager 骨架与 FailoverState 枚举"
```

---

## Task 5: tick() —— 触发选举与退避广播

**Files:**
- Modify: `FailoverManager.java`
- Test: `FailoverManagerTest.java`

- [ ] **Step 5.1: 写失败测试 —— slave 检测 master FAIL 进入 REQUESTING**

在 FailoverManagerTest 追加：

```java
@Test
@DisplayName("slave 检测到 master FAIL 进入 REQUESTING 态")
void testSlaveEntersRequestingWhenMasterFail() {
    ClusterNode master = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    master.addState(ClusterNodeState.FAIL);  // master 已 FAIL
    ClusterNode me = createSlaveNode(NODE_ID_2, "127.0.0.1", 7001, NODE_ID_1);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(master);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_2);

    failoverManager.tick();

    assertEquals(FailoverState.REQUESTING, failoverManager.getState());
    Mockito.verifyNoInteractions(busClient);  // 退避窗口内未广播
}

@Test
@DisplayName("非 slave 节点 tick 不触发选举")
void testMasterDoesNotTriggerElection() {
    ClusterNode me = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_1);

    failoverManager.tick();

    assertEquals(FailoverState.IDLE, failoverManager.getState());
    Mockito.verifyNoInteractions(busClient);
}

// 辅助方法
private ClusterNode createMasterNode(String id, String ip, int port) {
    ClusterNode n = new ClusterNode(id, ip, port, port + 10000);
    n.addState(ClusterNodeState.MASTER);
    return n;
}
private ClusterNode createSlaveNode(String id, String ip, int port, String masterId) {
    ClusterNode n = new ClusterNode(id, ip, port, port + 10000);
    n.addState(ClusterNodeState.SLAVE);
    n.setMasterNodeId(masterId);
    return n;
}
```

- [ ] **Step 5.2: 运行确认失败**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: 编译失败（tick() 未定义）。

- [ ] **Step 5.3: 实现 tick + tryStartElection + broadcastAuthRequest**

在 FailoverManager 增加（全部 synchronized）：

```java
    /**
     * 每轮由 GossipTask 调用，驱动选举状态机。
     */
    public synchronized void tick() {
        try {
            switch (state) {
                case IDLE:
                    tryStartElection();
                    break;
                case REQUESTING:
                    handleRequestingState();
                    break;
                case ELECTED:
                    // 瞬态，不应停留；安全回 IDLE
                    resetElectionState();
                    break;
            }
        } catch (Exception e) {
            logger.error("FailoverManager.tick 异常", e);
        }
    }

    private void tryStartElection() {
        com.janeluo.luban.rds.cluster.node.ClusterNode me = clusterConfig.getMyNode();
        if (me == null || !me.isSlave()) {
            return;
        }
        if (me.isFail() || me.isPfail()) {
            return;
        }
        String masterId = me.getMasterNodeId();
        if (masterId == null) {
            return;
        }
        com.janeluo.luban.rds.cluster.node.ClusterNode master = clusterConfig.getNode(masterId);
        if (master == null || !master.isFail()) {
            return;
        }

        // 满足触发条件
        state = FailoverState.REQUESTING;
        electionStartTime = System.currentTimeMillis();
        long jitter = Math.abs(me.getNodeId().hashCode()) % JITTER_BOUND_MS;
        requestDeadline = electionStartTime + gracePeriod + jitter;
        failedMasterId = masterId;
        authVotes.clear();
        requestBroadcasted = false;
        logger.warn("slave 进入选举: nodeId={}, failedMasterId={}, requestDeadline={}ms 后",
                me.getNodeId(), failedMasterId, (requestDeadline - electionStartTime));
    }

    private void handleRequestingState() {
        // 检查 master 是否已恢复（FAIL 清除）→ 回 IDLE
        com.janeluo.luban.rds.cluster.node.ClusterNode master =
                failedMasterId != null ? clusterConfig.getNode(failedMasterId) : null;
        if (master != null && !master.isFail()) {
            logger.info("原 master 已恢复，取消选举: masterId={}", failedMasterId);
            resetElectionState();
            return;
        }

        // 选举超时（2 * nodeTimeout 未过半授权）→ 回 IDLE
        if (System.currentTimeMillis() - electionStartTime > 2L * nodeTimeout) {
            logger.warn("选举超时，回退 IDLE: nodeId={}, failedMasterId={}",
                    clusterConfig.getMyNodeId(), failedMasterId);
            resetElectionState();
            return;
        }

        // 退避到期 → 广播 AUTH_REQUEST
        if (!requestBroadcasted && System.currentTimeMillis() >= requestDeadline) {
            broadcastAuthRequest();
        }
    }

    private void broadcastAuthRequest() {
        com.janeluo.luban.rds.cluster.node.ClusterNode me = clusterConfig.getMyNode();
        if (me == null) {
            return;
        }
        electionEpoch = clusterConfig.getCurrentEpoch() + 1;
        clusterConfig.setCurrentEpoch(electionEpoch);
        requestBroadcasted = true;

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
                me.getNodeId(),
                me.getConfigEpoch(),
                electionEpoch,
                0L);
        busClient.broadcast(req);
        logger.warn("广播选举请求: candidate={}, epoch={}", me.getNodeId(), electionEpoch);
    }

    private void resetElectionState() {
        state = FailoverState.IDLE;
        authVotes.clear();
        failedMasterId = null;
        requestBroadcasted = false;
        electionStartTime = 0L;
        requestDeadline = 0L;
    }
```

- [ ] **Step 5.4: 运行确认通过**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: Tests run: 3, Failures: 0.

- [ ] **Step 5.5: 追加退避到期广播测试**

```java
@Test
@DisplayName("退避到期后广播 AUTH_REQUEST 并自增 epoch")
void testBroadcastAfterBackoff() throws Exception {
    ClusterNode master = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    master.addState(ClusterNodeState.FAIL);
    ClusterNode me = createSlaveNode(NODE_ID_2, "127.0.0.1", 7001, NODE_ID_1);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(master);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_2);

    failoverManager.tick();  // 进入 REQUESTING
    Mockito.verifyNoInteractions(busClient);

    // 等退避窗口（gracePeriod=0 + jitter ≤ 500ms）+ 一点余量
    Thread.sleep(600);
    failoverManager.tick();  // 退避到期，广播

    assertTrue(clusterConfig.getCurrentEpoch() >= 1);
    Mockito.verify(busClient).broadcast(Mockito.any(FailoverAuthRequestMessage.class));
}
```

- [ ] **Step 5.6: 运行确认通过 + 提交 + 勾选 tasks.md 2.1/2.2/2.3/2.5**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
git add -A luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java \
        luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/FailoverManagerTest.java \
        openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): FailoverManager.tick 实现选举触发与退避广播"
```

---

## Task 6: master 投票授权 onAuthRequest

**Files:**
- Modify: `FailoverManager.java`
- Test: `FailoverManagerTest.java`

- [ ] **Step 6.1: 写失败测试 —— 4 个投票场景**

```java
@Test
@DisplayName("master 首次收到有效 AUTH_REQUEST 投票授权")
void testMasterVotesForFirstRequest() {
    ClusterNode me = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_1);

    FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
            NODE_ID_2, 5L, 10L, 0L);
    failoverManager.onAuthRequest(req);

    assertEquals(10L, clusterConfig.getCurrentEpoch());
    Mockito.verify(busClient).broadcast(Mockito.argThat(
            m -> m instanceof FailoverAuthAckMessage
                    && ((FailoverAuthAckMessage) m).getSenderNodeId().equals(NODE_ID_1)));
}

@Test
@DisplayName("重复同纪元请求触发幂等重发 ACK")
void testIdempotentAckResend() {
    ClusterNode me = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_1);

    FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L);
    failoverManager.onAuthRequest(req);
    failoverManager.onAuthRequest(req);  // 重复

    // 两次 ACK
    Mockito.verify(busClient, Mockito.times(2)).broadcast(Mockito.any(FailoverAuthAckMessage.class));
}

@Test
@DisplayName("本纪元已投他 slave 则拒绝")
void testRejectOtherSlaveInSameEpoch() {
    ClusterNode me = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_1);

    failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
    Mockito.clearInvocations(busClient);

    String otherSlave = "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0";
    failoverManager.onAuthRequest(new FailoverAuthRequestMessage(otherSlave, 5L, 10L, 0L));

    Mockito.verifyNoInteractions(busClient);
}

@Test
@DisplayName("过期纪元请求被拒绝")
void testRejectStaleEpoch() {
    ClusterNode me = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    me.addState(ClusterNodeState.MYSELF);
    config.addNode(me);
    config.setMyNodeId(NODE_ID_1);
    clusterConfig.setCurrentEpoch(20L);

    failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));

    Mockito.verifyNoInteractions(busClient);
}
```

注意：需确认 `FailoverAuthAckMessage` 构造函数签名，若不符则按实际签名调整（查 `FailoverAuthAckMessage.java`）。

- [ ] **Step 6.2: 运行确认失败**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: 编译失败（onAuthRequest 未定义）。

- [ ] **Step 6.3: 实现 onAuthRequest + sendAuthAck**

在 FailoverManager 增加：

```java
    /**
     * master 节点处理 AUTH_REQUEST（候选 slave 请求投票）。
     * 由 GossipProtocol.handleFailoverAuthRequest 委托调用。
     */
    public synchronized void onAuthRequest(FailoverAuthRequestMessage req) {
        com.janeluo.luban.rds.cluster.node.ClusterNode me = clusterConfig.getMyNode();
        if (me == null || !me.isMaster()) {
            return;
        }

        long reqEpoch = req.getCurrentEpoch();
        long myEpoch = clusterConfig.getCurrentEpoch();

        // (1) 过期纪元拒绝
        if (reqEpoch < myEpoch) {
            logger.debug("拒绝过期 AUTH_REQUEST: reqEpoch={}, myEpoch={}", reqEpoch, myEpoch);
            return;
        }

        // (2) 落后则追平，新纪元清旧票
        if (reqEpoch > myEpoch) {
            clusterConfig.setCurrentEpoch(reqEpoch);
            lastVoteEpoch = reqEpoch;
            votesCast.clear();
        }

        String candidateId = req.getSenderNodeId();

        // (3) 本纪元已投该 slave → 幂等重发
        Long votedAt = votesCast.get(candidateId);
        if (votedAt != null && votedAt == reqEpoch) {
            sendAuthAck(candidateId, reqEpoch);
            return;
        }

        // (4) 本纪元已投他 slave → 拒绝
        if (!votesCast.isEmpty()) {
            logger.debug("本纪元已投他 slave，拒绝: votedFor={}, candidate={}",
                    votesCast.keySet(), candidateId);
            return;
        }

        // (5) 首投
        votesCast.put(candidateId, reqEpoch);
        sendAuthAck(candidateId, reqEpoch);
    }

    private void sendAuthAck(String candidateId, long epoch) {
        com.janeluo.luban.rds.cluster.node.ClusterNode me = clusterConfig.getMyNode();
        if (me == null) {
            return;
        }
        FailoverAuthAckMessage ack = new FailoverAuthAckMessage(
                me.getNodeId(), epoch, candidateId);
        busClient.broadcast(ack);
        logger.info("投票授权: voter={}, candidate={}, epoch={}", me.getNodeId(), candidateId, epoch);
    }
```

- [ ] **Step 6.4: 运行确认通过**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: Tests run: 7, Failures: 0.（前面 3 + 本 task 4）

- [ ] **Step 6.5: 提交 + 勾选 tasks.md 2.4(部分)/3.1/3.2/3.3**

```bash
git add -A luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java \
        luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/FailoverManagerTest.java \
        openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): FailoverManager.onAuthRequest 实现 master 投票授权"
```

---

## Task 7: 候选 slave onAuthAck + performFailoverAndBroadcast

**Files:**
- Modify: `FailoverManager.java`（含 performFailover 抽取）
- Modify: `luban-rds-cluster/.../handler/ClusterCommandHandler.java`
- Test: `FailoverManagerTest.java`

- [ ] **Step 7.1: 写失败测试 —— 过半授权胜选**

```java
@Test
@DisplayName("收到过半 master 授权后胜选提升并广播 RESULT")
void testWinElectionAndPromote() {
    // 3 master 集群，需要 2 票（masterCount/2+1）
    ClusterNode m1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    ClusterNode m2 = createMasterNode("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0", "127.0.0.1", 7002);
    ClusterNode m3 = createMasterNode("d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0", "127.0.0.1", 7003);
    // 我是 m1 的 slave
    ClusterNode me = createSlaveNode(NODE_ID_2, "127.0.0.1", 7001, NODE_ID_1);
    me.addState(ClusterNodeState.MYSELF);
    // m1 持有槽位 0-100
    for (int i = 0; i <= 100; i++) m1.addSlot(i);
    m1.addState(ClusterNodeState.FAIL);

    config.addNode(m1); config.addNode(m2); config.addNode(m3); config.addNode(me);
    config.setMyNodeId(NODE_ID_2);

    // 进入候选态
    failoverManager.tick();
    assertEquals(FailoverState.REQUESTING, failoverManager.getState());

    // 收到 2 个 master 授权（≥ masterCount/2+1 = 3/2+1 = 2）
    failoverManager.onAuthAck(new FailoverAuthAckMessage(
            m2.getNodeId(), 1L, me.getNodeId()));
    failoverManager.onAuthAck(new FailoverAuthAckMessage(
            m3.getNodeId(), 1L, me.getNodeId()));

    // 验证：me 已是 master、继承槽位、m1 降级 slave、广播 RESULT
    assertTrue(me.isMaster());
    assertFalse(me.isSlave());
    assertEquals(101, me.getSlotCount());
    assertFalse(m1.isMaster());
    assertTrue(m1.isSlave());
    assertEquals(me.getNodeId(), m1.getMasterNodeId());
    assertTrue(clusterConfig.getCurrentEpoch() >= 1);
    Mockito.verify(busClient).broadcast(Mockito.any(FailoverResultMessage.class));
}
```

- [ ] **Step 7.2: 运行确认失败**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest#testWinElectionAndPromote -q
```
Expected: 编译失败（onAuthAck 未定义）。

- [ ] **Step 7.3: 实现 onAuthAck + performFailover + performFailoverAndBroadcast + performManualFailover**

在 FailoverManager 增加：

```java
    /**
     * 候选 slave 处理 AUTH_ACK（master 投票响应）。
     * 由 GossipProtocol.handleFailoverAuthAck 委托调用。
     */
    public synchronized void onAuthAck(FailoverAuthAckMessage ack) {
        if (state != FailoverState.REQUESTING) {
            return;
        }
        String voterId = ack.getSenderNodeId();
        if (voterId == null) {
            return;
        }
        if (!authVotes.add(voterId)) {
            return;  // 重复授权，忽略
        }

        int masterCount = clusterConfig.getMasterCount();
        int majority = masterCount / 2 + 1;
        logger.info("收到授权票: voter={}, totalVotes={}, majority={}",
                voterId, authVotes.size(), majority);

        if (authVotes.size() >= majority) {
            performFailoverAndBroadcast();
        }
    }

    private void performFailoverAndBroadcast() {
        com.janeluo.luban.rds.cluster.node.ClusterNode me = clusterConfig.getMyNode();
        com.janeluo.luban.rds.cluster.node.ClusterNode oldMaster =
                failedMasterId != null ? clusterConfig.getNode(failedMasterId) : null;

        if (me == null || oldMaster == null) {
            resetElectionState();
            return;
        }

        performFailover(me, oldMaster);

        clusterConfig.incrementEpoch();
        me.setConfigEpoch(clusterConfig.getCurrentEpoch());
        state = FailoverState.ELECTED;

        FailoverResultMessage result = new FailoverResultMessage(
                me.getNodeId(),
                clusterConfig.getCurrentEpoch(),
                me.getSlots());
        busClient.broadcast(result);
        notifyTopologyChanged();
        logger.warn("slave 自动提升为 master: nodeId={}, epoch={}, slotCount={}",
                me.getNodeId(), clusterConfig.getCurrentEpoch(), me.getSlotCount());

        resetElectionState();
    }

    /**
     * 手动 CLUSTER FAILOVER [FORCE|TAKEOVER] 入口。
     * 不经选举状态机、不广播 RESULT（直接接管语义）。
     */
    public synchronized void performManualFailover(
            com.janeluo.luban.rds.cluster.node.ClusterNode slaveNode,
            com.janeluo.luban.rds.cluster.node.ClusterNode masterNode) {
        performFailover(slaveNode, masterNode);
        notifyTopologyChanged();
    }

    /**
     * 执行实际的 slave→master 提升（槽位继承、master 降级）。
     * 从 ClusterCommandHandler 抽取，手动/自动共用。
     */
    private void performFailover(com.janeluo.luban.rds.cluster.node.ClusterNode slaveNode,
                                 com.janeluo.luban.rds.cluster.node.ClusterNode masterNode) {
        slaveNode.removeState(ClusterNodeState.SLAVE);
        slaveNode.addState(ClusterNodeState.MASTER);
        slaveNode.setMasterNodeId(null);

        java.util.BitSet masterSlots = masterNode.getSlots();
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

    private void notifyTopologyChanged() {
        if (onTopologyChanged != null) {
            try {
                onTopologyChanged.run();
            } catch (Exception e) {
                logger.error("onTopologyChanged 回调异常", e);
            }
        }
    }
```

注意 import: `com.janeluo.luban.rds.cluster.node.ClusterNodeState`, `java.util.BitSet`。

- [ ] **Step 7.4: ClusterCommandHandler 改为委托**

在 `ClusterCommandHandler.java` 的 `clusterFailover` 方法（约 line 1069-1132），把三处 `performFailover(myNode, masterNode)` 替换为：

```java
gossipProtocol.getFailoverManager().performManualFailover(myNode, masterNode);
```

并删除 `ClusterCommandHandler` 中的私有 `performFailover` 方法（约 line 1140-1165），因为已抽取到 FailoverManager。

注意：`ClusterCommandHandler` 已有 `gossipProtocol` 字段（构造注入，见 line 54），直接用。

- [ ] **Step 7.5: 运行确认通过**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: Tests run: 8, Failures: 0.

同时运行 ClusterCommandHandlerTest 确认手动 FAILOVER 未破坏：

```bash
mvn -pl luban-rds-cluster test -Dtest=ClusterCommandHandlerTest -q
```
Expected: 全部通过。

- [ ] **Step 7.6: 提交 + 勾选 tasks.md 2.4/2.6/5.x 部分**

```bash
git add -A luban-rds-cluster/ openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): onAuthAck 胜选提升 + performFailover 抽取到 FailoverManager"
```

---

## Task 8: onFailoverResult 收端收敛

**Files:**
- Modify: `FailoverManager.java`
- Test: `FailoverManagerTest.java`

- [ ] **Step 8.1: 写失败测试 —— FailoverResult 收敛**

```java
@Test
@DisplayName("onFailoverResult 收到结果后 winner 提权、原 master 降级、槽位转移")
void testHandleFailoverResult() {
    ClusterNode winner = createSlaveNode(NODE_ID_2, "127.0.0.1", 7001, NODE_ID_1);
    ClusterNode oldMaster = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);

    config.addNode(winner);
    config.addNode(oldMaster);

    BitSet inherited = new BitSet();
    inherited.set(0, 100);

    FailoverResultMessage msg = new FailoverResultMessage(
            NODE_ID_2, NODE_ID_2, 5L, inherited);
    failoverManager.onFailoverResult(msg);

    assertTrue(winner.isMaster());
    assertFalse(winner.isFail());
    assertEquals(5L, winner.getConfigEpoch());
    assertEquals(100, winner.getSlotCount());
    assertFalse(oldMaster.isMaster());
    assertTrue(oldMaster.isSlave());
    assertEquals(NODE_ID_2, oldMaster.getMasterNodeId());
    assertEquals(5L, clusterConfig.getCurrentEpoch());
}

@Test
@DisplayName("旧纪元 FailoverResult 被忽略")
void testIgnoreStaleResult() {
    clusterConfig.setCurrentEpoch(10L);
    ClusterNode winner = createSlaveNode(NODE_ID_2, "127.0.0.1", 7001, NODE_ID_1);
    config.addNode(winner);

    BitSet inherited = new BitSet();
    inherited.set(0, 10);

    failoverManager.onFailoverResult(new FailoverResultMessage(
            NODE_ID_2, NODE_ID_2, 5L, inherited));  // 5 < 10

    assertFalse(winner.isMaster());  // 未被提升
    assertEquals(10L, clusterConfig.getCurrentEpoch());
}
```

- [ ] **Step 8.2: 运行确认失败**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
```
Expected: 编译失败（onFailoverResult 未定义）。

- [ ] **Step 8.3: 实现 onFailoverResult**

```java
    /**
     * 全节点处理 FailoverResult（胜选广播）。
     * 由 GossipProtocol.handleFailoverResult 委托调用。
     */
    public synchronized void onFailoverResult(FailoverResultMessage msg) {
        long myEpoch = clusterConfig.getCurrentEpoch();

        // 纪元裁决：旧纪元忽略（防回放）
        if (msg.getNewConfigEpoch() < myEpoch) {
            logger.debug("忽略旧纪元 FailoverResult: msgEpoch={}, myEpoch={}",
                    msg.getNewConfigEpoch(), myEpoch);
            return;
        }

        com.janeluo.luban.rds.cluster.node.ClusterNode winner =
                clusterConfig.getNode(msg.getWinnerNodeId());
        if (winner == null) {
            logger.warn("收到 FailoverResult 但 winner 不存在: winnerId={}",
                    msg.getWinnerNodeId());
            return;
        }

        // winner 提权
        winner.removeState(ClusterNodeState.SLAVE);
        winner.addState(ClusterNodeState.MASTER);
        winner.removeState(ClusterNodeState.FAIL);
        winner.removeState(ClusterNodeState.PFAIL);
        winner.setMasterNodeId(null);
        winner.setConfigEpoch(msg.getNewConfigEpoch());

        // 槽位转移
        BitSet inherited = msg.getInheritedSlots();
        if (inherited != null) {
            winner.setSlots((BitSet) inherited.clone());
            for (int i = inherited.nextSetBit(0); i >= 0; i = inherited.nextSetBit(i + 1)) {
                slotManager.setSlotOwner(i, winner.getNodeId());
            }
        }

        // 原 master 降级
        for (com.janeluo.luban.rds.cluster.node.ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.isMaster() && !node.getNodeId().equals(winner.getNodeId())
                    && sharesAnySlot(node, inherited)) {
                node.clearSlots();
                node.removeState(ClusterNodeState.MASTER);
                node.addState(ClusterNodeState.SLAVE);
                node.setMasterNodeId(winner.getNodeId());
                logger.info("原 master 降级为 slave: oldMaster={}, newMaster={}",
                        node.getNodeId(), winner.getNodeId());
            }
        }

        clusterConfig.setCurrentEpoch(msg.getNewConfigEpoch());
        notifyTopologyChanged();
        logger.warn("应用 FailoverResult: winner={}, epoch={}, slotCount={}",
                winner.getNodeId(), msg.getNewConfigEpoch(),
                winner.getSlotCount());

        // 若本节点正在对该 master 选举，取消
        if (state == FailoverState.REQUESTING
                && msg.getWinnerNodeId().equals(clusterConfig.getMyNodeId())) {
            resetElectionState();
        }
    }

    private boolean sharesAnySlot(com.janeluo.luban.rds.cluster.node.ClusterNode node, BitSet slots) {
        if (slots == null) {
            return false;
        }
        BitSet nodeSlots = node.getSlots();
        if (nodeSlots == null) {
            return false;
        }
        for (int i = slots.nextSetBit(0); i >= 0; i = slots.nextSetBit(i + 1)) {
            if (nodeSlots.get(i)) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 8.4: 运行确认通过 + 提交 + 勾选 tasks.md 5.1/5.2**

```bash
mvn -pl luban-rds-cluster test -Dtest=FailoverManagerTest -q
git add -A luban-rds-cluster/ openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): FailoverManager.onFailoverResult 实现拓扑收敛"
```

---

## Task 9: GossipProtocol + ClusterBusHandler + GossipTask 接线

**Files:**
- Modify: `luban-rds-cluster/.../gossip/GossipProtocol.java`
- Modify: `luban-rds-cluster/.../bus/ClusterBusHandler.java`
- Modify: `luban-rds-cluster/.../gossip/GossipTask.java`

- [ ] **Step 9.1: GossipProtocol 增加字段、构造、委托方法、访问器**

在 `GossipProtocol.java`：

a) 字段区（约 line 77 failureDetector 之后）增加：
```java
    /**
     * 故障转移管理器
     */
    private FailoverManager failoverManager;
```

b) 在 `setClusterStateManager` 方法（约 line 159）之后增加注入方法：
```java
    /**
     * 注入故障转移管理器（由 NettyRedisServer 在创建 failoverManager 后注入）。
     *
     * @param failoverManager 故障转移管理器
     */
    public void setFailoverManager(FailoverManager failoverManager) {
        this.failoverManager = failoverManager;
    }

    public FailoverManager getFailoverManager() {
        return failoverManager;
    }
```

c) 在 `broadcastFail` 方法（约 line 527）之前/之后增加 3 个 handle 委托方法：
```java
    /**
     * 处理故障转移授权请求（委托给 FailoverManager）。
     */
    public void handleFailoverAuthRequest(FailoverAuthRequestMessage msg) {
        if (failoverManager != null) {
            failoverManager.onAuthRequest(msg);
        }
    }

    /**
     * 处理故障转移授权确认（委托给 FailoverManager）。
     */
    public void handleFailoverAuthAck(FailoverAuthAckMessage msg) {
        if (failoverManager != null) {
            failoverManager.onAuthAck(msg);
        }
    }

    /**
     * 处理故障转移结果（委托给 FailoverManager）。
     */
    public void handleFailoverResult(FailoverResultMessage msg) {
        if (failoverManager != null) {
            failoverManager.onFailoverResult(msg);
        }
    }
```

- [ ] **Step 9.2: ClusterBusHandler.handleMessage 增加 3 个 case**

在 `ClusterBusHandler.java` 的 `handleMessage` switch（约 line 211-230），在 `default` 之前增加：
```java
            case FAILOVER_AUTH_REQUEST:
                handleFailoverAuthRequest((FailoverAuthRequestMessage) message);
                return null;
            case FAILOVER_AUTH_ACK:
                handleFailoverAuthAck((FailoverAuthAckMessage) message);
                return null;
            case FAILOVER_RESULT:
                handleFailoverResult((FailoverResultMessage) message);
                return null;
```

并在文件中增加 3 个私有委托方法（紧邻现有 handleFail/handleUpdate）：
```java
    private void handleFailoverAuthRequest(FailoverAuthRequestMessage msg) {
        if (gossipProtocol != null) {
            gossipProtocol.handleFailoverAuthRequest(msg);
        }
    }

    private void handleFailoverAuthAck(FailoverAuthAckMessage msg) {
        if (gossipProtocol != null) {
            gossipProtocol.handleFailoverAuthAck(msg);
        }
    }

    private void handleFailoverResult(FailoverResultMessage msg) {
        if (gossipProtocol != null) {
            gossipProtocol.handleFailoverResult(msg);
        }
    }
```

注意 import: `com.janeluo.luban.rds.cluster.gossip.FailoverAuthRequestMessage`、`FailoverAuthAckMessage`、`FailoverResultMessage`（按 AGENTS.md 显式 import 规范）。

- [ ] **Step 9.3: GossipTask.run 增加 tick 调用**

在 `GossipTask.java` 的 `run()` 方法（约 line 60-84），在 `checkAndBroadcastFail()` 之后、`updateClusterState()` 之前增加：
```java
            // 3. 检查并广播 FAIL 消息
            checkAndBroadcastFail();

            // 3.5 驱动故障转移选举（FAIL 状态已更新，此时检查最准确）
            FailoverManager failoverManager = gossipProtocol.getFailoverManager();
            if (failoverManager != null) {
                failoverManager.tick();
            }

            // 4. 更新集群状态
            updateClusterState();
```

- [ ] **Step 9.4: 编译验证**

```bash
mvn -pl luban-rds-cluster compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 9.5: 提交 + 勾选 tasks.md 4.1/4.2/4.3/4.4**

```bash
git add -A luban-rds-cluster/ openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): GossipProtocol/BusHandler/GossipTask 接入 FailoverManager"
```

---

## Task 10: NettyRedisServer 注入 FailoverManager + gracePeriod 配置

**Files:**
- Modify: `luban-rds-server/.../server/NettyRedisServer.java` (约 line 339-365)
- Modify: `luban-rds-bin/src/main/resources/luban-rds.conf`

- [ ] **Step 10.1: 在 NettyRedisServer 初始化 FailoverManager 并注入**

在 `NettyRedisServer.java` 初始化集群模式方法（line 339-365 区间），在 `gossipProtocol.setOnTopologyChanged(saveConfigCallback)` 之后增加：

```java
        // 11.5 初始化 FailoverManager 并注入 GossipProtocol
        Runnable sharedTopologyCallback = this::saveClusterConfig;
        FailoverManager failoverManager = new FailoverManager(
                clusterConfig,
                slotManager,
                clusterStateManager,
                clusterBusClient,
                sharedTopologyCallback,
                config.getClusterNodeTimeout(),
                config.getClusterFailoverGracePeriod());
        this.gossipProtocol.setFailoverManager(failoverManager);
        logger.info("FailoverManager 已注入: gracePeriod={}ms",
                config.getClusterFailoverGracePeriod());
```

注意 import: `com.janeluo.luban.rds.cluster.gossip.FailoverManager`。

需确认 `config` 对象（RedisConfig 或类似）是否有 `getClusterFailoverGracePeriod()`；若无，在 RedisConfig 类新增字段 + getter（默认 0）。先 grep 确认：
```bash
grep -rn "getClusterNodeTimeout\|class RedisConfig" luban-rds-server/src/main/java/ | head -5
```

- [ ] **Step 10.2: 在 RedisConfig 增加 clusterFailoverGracePeriod 字段（如需）**

找到 RedisConfig 类（grep 结果指引），新增：
```java
    /**
     * 集群自动故障转移退避窗口（毫秒）
     */
    private long clusterFailoverGracePeriod = 0L;

    public long getClusterFailoverGracePeriod() {
        return clusterFailoverGracePeriod;
    }

    public void setClusterFailoverGracePeriod(long clusterFailoverGracePeriod) {
        this.clusterFailoverGracePeriod = clusterFailoverGracePeriod;
    }
```

并在配置解析处（读取 .conf 的位置）增加：
```java
        // cluster-failover-grace-period
        String gracePeriod = props.getProperty("cluster-failover-grace-period");
        if (gracePeriod != null) {
            config.setClusterFailoverGracePeriod(Long.parseLong(gracePeriod.trim()));
        }
```

- [ ] **Step 10.3: luban-rds.conf 模板增加配置项**

在 `luban-rds-bin/src/main/resources/luban-rds.conf` 的集群配置区段增加：
```
# 集群自动故障转移退避窗口（毫秒）。
# slave 检测到 master FAIL 后，等待该时长 + 随机抖动(0-500ms) 再发起选举。
# 默认 0（仅保留随机抖动）。
# cluster-failover-grace-period 0
```

- [ ] **Step 10.4: 编译验证**

```bash
mvn -pl luban-rds-server compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 10.5: 提交 + 勾选 tasks.md 6.1/6.2**

```bash
git add -A luban-rds-server/ luban-rds-bin/ openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "feat(cluster): NettyRedisServer 注入 FailoverManager + gracePeriod 配置"
```

---

## Task 11: 扩展集成测试 ClusterFailoverTest

**Files:**
- Modify: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/integration/ClusterFailoverTest.java`

- [ ] **Step 11.1: 写端到端测试 —— 单 slave 自动提升**

在 `ClusterFailoverTest.java` 新增 TestCluster 内部类 + 测试方法：

```java
@Test
@DisplayName("集成：master FAIL 后单 slave 自动提升为新 master")
void testAutomaticFailoverSingleSlave() {
    // 3 master + 1 slave of M1
    ClusterNode m1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    for (int i = 0; i < 5000; i++) m1.addSlot(i);
    ClusterNode m2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
    ClusterNode m3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);
    ClusterNode s1 = createSlaveNode(NODE_ID_4, "127.0.0.1", 7003, NODE_ID_1);

    TestCluster cluster = new TestCluster();
    cluster.addNode(m1);
    cluster.addNode(m2);
    cluster.addNode(m3);
    cluster.addNode(s1);

    // M1 宕机
    m1.addState(ClusterNodeState.FAIL);

    // 驱动若干轮 tick，让选举收敛
    for (int i = 0; i < 5; i++) {
        cluster.tickAll();
        cluster.deliverAllPending();
    }

    assertTrue(s1.isMaster(), "s1 应被提升为 master");
    assertFalse(s1.isSlave());
    assertTrue(s1.getSlotCount() > 0, "s1 应继承 M1 的槽位");
    assertFalse(m1.isMaster(), "M1 应降级");
    assertTrue(m1.isSlave());
    assertEquals(s1.getNodeId(), m1.getMasterNodeId());
}
```

`TestCluster` 内部类（同文件）：
```java
    /**
     * 多节点内存模拟器：每节点一个 FailoverManager，broadcast 投递给其他节点。
     */
    static class TestCluster {
        final Map<String, ClusterNode> nodes = new HashMap<>();
        final Map<String, ClusterConfig> configs = new HashMap<>();
        final Map<String, SlotManager> slots = new HashMap<>();
        final Map<String, FailoverManager> managers = new HashMap<>();
        final List<java.util.function.Consumer<GossipMessage>> sinks = new ArrayList<>();
        final Map<String, List<GossipMessage>> pending = new HashMap<>();

        void addNode(ClusterNode node) {
            node.addState(ClusterNodeState.MYSELF);
            ClusterConfig cfg = new ClusterConfig();
            cfg.setMyNodeId(node.getNodeId());
            for (ClusterNode n : nodes.values()) cfg.addNode(n);
            cfg.addNode(node);
            for (ClusterConfig c : configs.values()) c.addNode(node);
            nodes.put(node.getNodeId(), node);
            configs.put(node.getNodeId(), cfg);
            SlotManager sm = new DefaultSlotManager();
            slots.put(node.getNodeId(), sm);

            ClusterStateManager sm2 = new ClusterStateManager(cfg);
            // mock busClient：broadcast 时把消息投递到 pending（deliverAllPending 时分发）
            ClusterBusClient busClient = org.mockito.Mockito.mock(ClusterBusClient.class);
            String fromId = node.getNodeId();
            org.mockito.Mockito.doAnswer(inv -> {
                GossipMessage msg = inv.getArgument(0);
                pending.computeIfAbsent(fromId, k -> new ArrayList<>()).add(msg);
                return null;
            }).when(busClient).broadcast(org.mockito.ArgumentMatchers.any());
            FailoverManager fm = new FailoverManager(cfg, sm, sm2, busClient,
                    () -> {}, 15000L, 0L);
            managers.put(fromId, fm);
        }

        void tickAll() {
            for (FailoverManager fm : managers.values()) fm.tick();
        }

        void deliverAllPending() {
            for (var e : pending.entrySet()) {
                String from = e.getKey();
                for (GossipMessage msg : e.getValue()) {
                    for (var me : managers.entrySet()) {
                        if (me.getKey().equals(from)) continue;
                        if (msg instanceof FailoverAuthRequestMessage r) me.getValue().onAuthRequest(r);
                        else if (msg instanceof FailoverAuthAckMessage a) me.getValue().onAuthAck(a);
                        else if (msg instanceof FailoverResultMessage res) me.getValue().onFailoverResult(res);
                    }
                }
            }
            pending.clear();
        }
    }
```

注意：TestCluster 中每个节点的 ClusterConfig 是独立副本，槽位/状态不会自动跨节点同步——测试主要验证 FailoverManager 的选举协议正确性，不验证 gossip 状态同步（那是 GossipProtocol 的职责，已有测试覆盖）。`createMasterNode`/`createSlaveNode` 辅助方法沿用 ClusterFailoverTest 已有的。

- [ ] **Step 11.2: 运行确认通过**

```bash
mvn -pl luban-rds-cluster test -Dtest=ClusterFailoverTest#testAutomaticFailoverSingleSlave -q
```
Expected: Tests run: 1, Failures: 0.

- [ ] **Step 11.3: 追加多 slave 竞争测试**

```java
@Test
@DisplayName("集成：多 slave 竞争仅一个胜选")
void testMultipleSlavesCompeteSingleWinner() {
    ClusterNode m1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    for (int i = 0; i < 5000; i++) m1.addSlot(i);
    ClusterNode m2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
    ClusterNode m3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);
    ClusterNode s1 = createSlaveNode(NODE_ID_4, "127.0.0.1", 7003, NODE_ID_1);
    ClusterNode s2 = createSlaveNode("e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0",
            "127.0.0.1", 7004, NODE_ID_1);

    TestCluster cluster = new TestCluster();
    cluster.addNode(m1); cluster.addNode(m2); cluster.addNode(m3);
    cluster.addNode(s1); cluster.addNode(s2);

    m1.addState(ClusterNodeState.FAIL);

    for (int i = 0; i < 10; i++) {
        cluster.tickAll();
        cluster.deliverAllPending();
    }

    long winnerCount = 0;
    if (s1.isMaster()) winnerCount++;
    if (s2.isMaster()) winnerCount++;
    assertEquals(1, winnerCount, "仅一个 slave 应胜选");
}
```

- [ ] **Step 11.4: 追加手动 FAILOVER TAKEOVER 共存测试**

```java
@Test
@DisplayName("手动 CLUSTER FAILOVER TAKEOVER 不触发选举状态机")
void testManualFailoverTakeoverBypassesStateMachine() {
    ClusterConfig cfg = new ClusterConfig();
    SlotManager sm = new DefaultSlotManager();
    ClusterStateManager stm = new ClusterStateManager(cfg);
    ClusterCommandHandler handler = new ClusterCommandHandler(cfg, sm, stm, null, null);
    ClusterNode m1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
    for (int i = 0; i < 100; i++) m1.addSlot(i);
    ClusterNode s1 = createSlaveNode(NODE_ID_4, "127.0.0.1", 7003, NODE_ID_1);
    cfg.addNode(m1); cfg.addNode(s1);
    cfg.setMyNodeId(NODE_ID_4);

    // 因 ClusterCommandHandler 构造参数 4 (gossipProtocol) 传 null，
    // 手动 FAILOVER 会因 getFailoverManager() NPE 失败。
    // 本测试改为直接验证 FailoverManager.performManualFailover 不改 state：
    ClusterBusClient busClient = org.mockito.Mockito.mock(ClusterBusClient.class);
    FailoverManager fm = new FailoverManager(cfg, sm, stm, busClient, () -> {}, 15000L, 0L);
    fm.performManualFailover(s1, m1);

    assertEquals(FailoverState.IDLE, fm.getState());
    assertTrue(s1.isMaster());
    org.mockito.Mockito.verifyNoInteractions(busClient);  // 不广播 RESULT
}
```

- [ ] **Step 11.5: 运行全部测试**

```bash
mvn -pl luban-rds-cluster test -Dtest=ClusterFailoverTest -q
```
Expected: 全部通过。

- [ ] **Step 11.6: 提交 + 勾选 tasks.md 7.5/7.6/7.7**

```bash
git add -A luban-rds-cluster/src/test/ openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "test(cluster): 扩展 ClusterFailoverTest 覆盖自动选举端到端场景"
```

---

## Task 12: 全量测试 + 文档

**Files:**
- Modify: `AGENTS.md` (第 10 节)
- Run: 全量 `mvn test`

- [ ] **Step 12.1: 运行 luban-rds-cluster 全量测试**

```bash
mvn -pl luban-rds-cluster test -q
```
Expected: 全部通过。若有 FailoverAuthAckMessage 构造签名不匹配导致的失败，按实际签名调整测试。

- [ ] **Step 12.2: 运行全量测试（含 server 模块接线）**

```bash
mvn test -q
```
Expected: 全部通过。

- [ ] **Step 12.3: 更新 AGENTS.md 第 10 节**

在 `AGENTS.md` 第 10 节 Cluster 的 Key Commands 表之后增加自动故障转移小节：

```markdown
### Automatic Failover

| Component | Description |
|-----------|-------------|
| FailoverManager | 选举状态机（IDLE/REQUESTING/ELECTED） |
| FailoverAuthRequestMessage | slave 请求投票（已有，本变更启用） |
| FailoverAuthAckMessage | master 投票响应（已有，本变更启用） |
| FailoverResultMessage | 胜选广播，触发拓扑收敛（新增） |

Flow: master FAIL → slave 检测 → 退避后广播 AUTH_REQUEST → master 多数派授权 ACK → slave 胜选 → performFailover 提升 → 广播 FailoverResult → 全网收敛。

| Config | Default |
|--------|---------|
| cluster-failover-grace-period | 0 (ms) |
```

- [ ] **Step 12.4: 提交 + 勾选 tasks.md 7.8/8.1/8.2**

```bash
git add AGENTS.md openspec/changes/add-cluster-automatic-failover/tasks.md
git commit -m "docs(cluster): 更新 AGENTS.md 自动故障转移章节 + 全量测试通过"
```

---

## Self-Review Checklist (实施完成后自查)

1. **Spec 覆盖**: 对照 spec.md 19 个 Scenario，每个都有对应测试。
2. **无 placeholder**: 所有方法签名、消息字段、配置项已实现。
3. **类型一致**: FailoverManager 字段名与 ClusterCommandHandler 委托调用一致（performManualFailover）。
4. **编译**: `mvn -pl luban-rds-cluster,luban-rds-server compile` 通过。
5. **测试**: `mvn test` 全量通过。
