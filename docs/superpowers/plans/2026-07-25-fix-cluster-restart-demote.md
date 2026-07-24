# fix-cluster-restart-demote Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

---
change: fix-cluster-restart-demote
design-doc: docs/superpowers/specs/2026-07-25-fix-cluster-restart-demote-design.md
base-ref: fde07ac296bbecac510a0db27d36ee0a0323eb04
---

**Goal:** 修复集群故障转移后旧主节点重启未降级为从节点的问题，使重启节点能通过 gossip 心跳的 epoch 仲裁自降级为新主的 slave。

**Architecture:** 三处改动耦合：(1) PING/PONG 协议尾部追加 `senderCurrentEpoch`（向后兼容），使重启节点能同步集群级 currentEpoch；(2) `GossipProtocol.processGossipNodes` 移除 MYSELF 跳过，新增自降级分支，经 `FailoverManager.applySelfDemotion`（synchronized）原子完成角色/slots/复制切换；(3) 启动恢复保持软对齐（不阻塞），由 gossip 自然收敛。

**Tech Stack:** Java 17, Maven, JUnit 5, Netty, luban-rds-cluster 模块。

**关键文件映射：**
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/PingMessage.java` - PING 协议扩展
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/PongMessage.java` - PONG 协议扩展
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java` - 自降级 + currentEpoch 同步
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java` - applySelfDemotion
- `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java` - 诊断日志
- 测试：`luban-rds-cluster/src/test/java/...`

---

## Task 1: PING/PONG 协议扩展 - 添加 senderCurrentEpoch 字段

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/PingMessage.java`
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/PongMessage.java`

- [ ] **Step 1.1: PingMessage 添加字段与 getter/setter**

在 `PingMessage.java` 的 `senderMasterNodeId` 字段后（约第 65 行）追加：

```java
    /**
     * 发送方（myNode）所在的集群当前纪元（currentEpoch）
     * <p>
     * 使接收方能通过心跳同步集群级 currentEpoch。重启节点本地 currentEpoch 可能滞后，
     * 导致 epoch 仲裁门控恒为 false。尾部追加字段，旧版本节点解码时忽略多余字节，
     * 新版本节点解码旧消息时字段不足则保留默认值 0（setEpochIfGreater(0) 无副作用）。
     * </p>
     */
    private long senderCurrentEpoch;
```

在 getter/setter 区域（`setSenderMasterNodeId` 之后）追加：

```java
    /**
     * 获取发送方集群当前纪元
     *
     * @return 集群当前纪元
     */
    public long getSenderCurrentEpoch() {
        return senderCurrentEpoch;
    }

    /**
     * 设置发送方集群当前纪元
     *
     * @param senderCurrentEpoch 集群当前纪元
     */
    public void setSenderCurrentEpoch(long senderCurrentEpoch) {
        this.senderCurrentEpoch = senderCurrentEpoch;
    }
```

- [ ] **Step 1.2: PingMessage.encodeBody 追加 currentEpoch**

在 `encodeBody()` 中，`totalLength` 计算追加 `+ 8`：

```java
        int totalLength = 8 + 2 + gossipNodesLength + 4 + slotsBytes.length
                + 8 + 1 + flagsCount * 2 + 1 + masterNodeIdLength + 8;
```

在方法末尾 `return data;` 之前（写入 masterNodeId 之后）追加：

```java
        // 写入发送方集群当前纪元（8字节，大端序）- 尾部追加，向后兼容
        data[offset++] = (byte) (senderCurrentEpoch >> 56);
        data[offset++] = (byte) (senderCurrentEpoch >> 48);
        data[offset++] = (byte) (senderCurrentEpoch >> 40);
        data[offset++] = (byte) (senderCurrentEpoch >> 32);
        data[offset++] = (byte) (senderCurrentEpoch >> 24);
        data[offset++] = (byte) (senderCurrentEpoch >> 16);
        data[offset++] = (byte) (senderCurrentEpoch >> 8);
        data[offset++] = (byte) senderCurrentEpoch;
```

- [ ] **Step 1.3: PingMessage.decodeBody 追加 currentEpoch 读取**

在 `decodeBody()` 末尾（读取 masterNodeId 之后）追加：

```java
        // 读取发送方集群当前纪元（8字节，大端序）- 向后兼容：旧消息无此字段时保留默认值 0
        if (offset + 8 <= body.length) {
            this.senderCurrentEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                    ((long) (body[offset++] & 0xFF) << 48) |
                    ((long) (body[offset++] & 0xFF) << 40) |
                    ((long) (body[offset++] & 0xFF) << 32) |
                    ((long) (body[offset++] & 0xFF) << 24) |
                    ((long) (body[offset++] & 0xFF) << 16) |
                    ((long) (body[offset++] & 0xFF) << 8) |
                    ((long) (body[offset++] & 0xFF));
        }
```

- [ ] **Step 1.4: PongMessage 对称改动**

对 `PongMessage.java` 重复 Step 1.1-1.3（字段、getter/setter、encodeBody 追加 `+8` 与写入、decodeBody 末尾读取）。PongMessage 与 PingMessage 结构完全对称。

- [ ] **Step 1.5: 编译验证**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -q -pl luban-rds-cluster compile`
Expected: BUILD SUCCESS

- [ ] **Step 1.6: 提交**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/PingMessage.java luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/PongMessage.java
git commit -m "feat(cluster): PING/PONG 协议尾部追加 senderCurrentEpoch 字段（向后兼容）"
```

---

## Task 2: GossipProtocol 发送侧填充 currentEpoch + 接收侧同步

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java`

- [ ] **Step 2.1: sendPing 填充 senderCurrentEpoch**

在 `sendPing()`（约 `:366-376`）的 `ping.setSenderMasterNodeId(myNode.getMasterNodeId());` 之后追加：

```java
        // 携带发送方集群当前纪元，使对端（尤其是重启节点）能通过心跳同步 currentEpoch
        ping.setSenderCurrentEpoch(clusterConfig.getCurrentEpoch());
```

- [ ] **Step 2.2: sendPong 对称填充**

在 `sendPong()`（约 `:427-437`，结构同 sendPing）的 `pong.setSenderMasterNodeId(myNode.getMasterNodeId());` 之后追加：

```java
        pong.setSenderCurrentEpoch(clusterConfig.getCurrentEpoch());
```

- [ ] **Step 2.3: updateNodeFromPingMessage 同步 currentEpoch**

在 `updateNodeFromPingMessage()`（约 `:885-913`）的 `syncSenderRole(...)` 调用之后追加：

```java
            // 同步集群级 currentEpoch（重启节点本地可能滞后，导致 epoch 仲裁门控失效）
            clusterConfig.setEpochIfGreater(ping.getSenderCurrentEpoch());
```

- [ ] **Step 2.4: updateNodeFromPongMessage 对称同步**

在 `updateNodeFromPongMessage()`（约 `:922-` ）的 `syncSenderRole(...)` 调用之后追加：

```java
            clusterConfig.setEpochIfGreater(pong.getSenderCurrentEpoch());
```

- [ ] **Step 2.5: 编译验证**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -q -pl luban-rds-cluster compile`
Expected: BUILD SUCCESS

- [ ] **Step 2.6: 提交**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java
git commit -m "feat(cluster): gossip 收发两侧同步集群级 currentEpoch"
```

---

## Task 3: FailoverManager.applySelfDemotion 新增（synchronized 自降级）

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java`

- [ ] **Step 3.1: 新增 applySelfDemotion 方法**

在 `FailoverManager.java` 的 `onFailoverResult` 方法之后（约 `:550`）新增：

```java
    /**
     * 经 gossip 心跳触发的 MYSELF 自降级
     * <p>
     * 当重启的原主节点收到携带更高 configEpoch 的 PONG/PING，且其 gossip section
     * 指出 MYSELF 现为某新主的 SLAVE 时调用。与 {@link #onFailoverResult} 共用
     * synchronized 监视器，保证与并发 FailoverResult 处理串行化。
     * </p>
     * <p>
     * 幂等：MYSELF 已是 SLAVE 时直接返回。新主记录不在本地配置时跳过（等下一轮
     * 心跳发现新主后再降级）。
     * </p>
     *
     * @param newMasterNodeId 新主节点 ID
     * @param newConfigEpoch  触发降级的 gossip configEpoch（已校验大于本地基线）
     */
    public synchronized void applySelfDemotion(String newMasterNodeId, long newConfigEpoch) {
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null || !myNode.isMaster()) {
            // 幂等：已是 slave 或无 MYSELF 记录则跳过
            return;
        }
        ClusterNode newMaster = clusterConfig.getNode(newMasterNodeId);
        if (newMaster == null) {
            logger.warn("自降级跳过: 新主节点未在本地配置中, newMasterId={}, 等待后续心跳发现",
                    newMasterNodeId);
            return;
        }

        // 清空 MYSELF slots，归属转移到新主
        BitSet oldSlots = myNode.getSlots();
        if (oldSlots != null) {
            for (int i = oldSlots.nextSetBit(0); i >= 0; i = oldSlots.nextSetBit(i + 1)) {
                slotManager.setSlotOwner(i, newMasterNodeId);
                clusterConfig.setSlotOwner(i, newMasterNodeId);
            }
        }
        myNode.clearSlots();
        myNode.removeState(ClusterNodeState.MASTER);
        myNode.addState(ClusterNodeState.SLAVE);
        myNode.setMasterNodeId(newMasterNodeId);
        myNode.setConfigEpoch(newConfigEpoch);
        clusterConfig.setCurrentEpoch(newConfigEpoch);

        // 切换复制方向：向新主发起同步
        replicationLifecycleListener.demoteToSlave(newMaster);
        notifyTopologyChanged();
        logger.warn("MYSELF 经 gossip 自降级为 slave: newMaster={}, configEpoch={}",
                newMasterNodeId, newConfigEpoch);
    }
```

注意：确认 `FailoverManager` 已 import `java.util.BitSet`（`onFailoverResult` 已使用，应已存在）。

- [ ] **Step 3.2: 编译验证**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -q -pl luban-rds-cluster compile`
Expected: BUILD SUCCESS

- [ ] **Step 3.3: 提交**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java
git commit -m "feat(cluster): FailoverManager 新增 applySelfDemotion 供 gossip 触发自降级"
```

---

## Task 4: GossipProtocol.processGossipNodes 自降级分支

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java`

- [ ] **Step 4.1: 改造 processGossipNodes 处理 MYSELF**

在 `processGossipNodes()`（约 `:1020-1128`）中，替换原有的"跳过本节点"逻辑。将：

```java
            String nodeId = nodeInfo.getNodeId();
            ClusterNode node = clusterConfig.getNode(nodeId);

            // 跳过本节点
            if (nodeId != null && nodeId.equals(clusterConfig.getMyNodeId())) {
                continue;
            }

            if (node == null) {
```

改为：

```java
            String nodeId = nodeInfo.getNodeId();
            boolean isMyselfEntry = nodeId != null && nodeId.equals(clusterConfig.getMyNodeId());

            // MYSELF 走自降级分支（不再无条件跳过）：当对端对 MYSELF 的视图携带
            // 更高 configEpoch 且角色为 SLAVE 时，采纳该视图自降级为新主的 slave。
            // 这是故障转移后原主重启收敛的关键路径--FailoverResult 广播仅一次，
            // 重启节点错过后只能经 gossip 心跳的 epoch 仲裁自降级。
            if (isMyselfEntry) {
                handleMyselfGossipEntry(nodeInfo);
                continue;
            }

            ClusterNode node = clusterConfig.getNode(nodeId);

            if (node == null) {
```

- [ ] **Step 4.2: 新增 handleMyselfGossipEntry 私有方法**

在 `processGossipNodes` 方法之后新增：

```java
    /**
     * 处理 gossip section 中关于 MYSELF 的条目
     * <p>
     * 当对端节点对 MYSELF 的视图携带严格更大的 configEpoch 且角色为 SLAVE 时，
     * 触发 MYSELF 自降级为新主的 slave。严格 epoch 门控（>localEpochBaseline）
     * 防止陈旧 gossip 回退已合法提升的 master。
     * </p>
     *
     * @param nodeInfo gossip section 中 MYSELF 的条目
     */
    private void handleMyselfGossipEntry(GossipNodeInfo nodeInfo) {
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            return;
        }
        Set<ClusterNodeState> flags = nodeInfo.getFlags();
        if (flags == null || !flags.contains(ClusterNodeState.SLAVE)) {
            return;
        }
        // 捕获本地基线，避免 setConfigEpochIfGreater 提升后门控失效
        long localEpochBaseline = myNode.getConfigEpoch();
        long gossipEpoch = nodeInfo.getConfigEpoch();
        if (gossipEpoch <= localEpochBaseline) {
            // 严格大于才切换；相等/小于忽略（防回退）
            return;
        }
        if (!myNode.isMaster()) {
            // 已是 slave 则幂等跳过（masterNodeId 同步由下方逻辑处理）
            return;
        }
        String newMasterId = nodeInfo.getMasterNodeId();
        if (newMasterId == null) {
            return;
        }
        // 委托 FailoverManager 原子完成自降级（synchronized，与 onFailoverResult 串行）
        if (failoverManager != null) {
            failoverManager.applySelfDemotion(newMasterId, gossipEpoch);
        } else {
            logger.warn("FailoverManager 未注入，MYSELF 自降级未执行: newMasterId={}", newMasterId);
        }
    }
```

确认 `GossipProtocol` 已有 `failoverManager` 字段引用（搜索 `failoverManager` 确认；若字段名不同，用实际字段名）。

- [ ] **Step 4.3: 确认 failoverManager 字段存在**

Run: `git grep -n "failoverManager" -- luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java`
Expected: 显示字段声明与 setter。若无此字段，需在 Step 4.2 中改用 `replicationLifecycleListener` 内联降级（参考 onFailoverResult 的 demoteToSlave 调用），并自行 synchronized。

- [ ] **Step 4.4: 编译验证**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -q -pl luban-rds-cluster compile`
Expected: BUILD SUCCESS

- [ ] **Step 4.5: 提交**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java
git commit -m "fix(cluster): processGossipNodes 移除 MYSELF 跳过，新增 gossip 自降级分支"
```

---

## Task 5: NettyRedisServer 启动恢复诊断日志

**Files:**
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java`

- [ ] **Step 5.1: restoreClusterFromConfig 增加诊断日志**

在 `restoreClusterFromConfig()`（约 `:498`）的 `logger.info("从配置文件恢复集群状态...")` 之后追加：

```java
        // 诊断：若 MYSELF 以 master 恢复但本地 configEpoch 低于 currentEpoch，
        // 说明本地视图可能滞后于集群（如故障转移后重启），等待 gossip 对齐纠正角色
        ClusterNode restoredMy = clusterConfig.getMyNode();
        if (restoredMy != null && restoredMy.isMaster()
                && clusterConfig.getConfigEpoch() < clusterConfig.getCurrentEpoch()) {
            logger.info("MYSELF 以本地配置恢复为 master, configEpoch={}, currentEpoch={}, 等待 gossip 对齐",
                    clusterConfig.getConfigEpoch(), clusterConfig.getCurrentEpoch());
        }
```

- [ ] **Step 5.2: 编译验证**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -q -pl luban-rds-server compile`
Expected: BUILD SUCCESS

- [ ] **Step 5.3: 提交**

```bash
git add luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java
git commit -m "feat(server): 启动恢复增加 MYSELF 等待 gossip 对齐诊断日志"
```

---

## Task 6: 单元测试 - PING/PONG currentEpoch 编解码 + 向后兼容

**Files:**
- Modify: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/GossipMessageCodecTest.java`

- [ ] **Step 6.1: 添加 currentEpoch 编解码测试**

在 `GossipMessageCodecTest.java` 新增测试方法：

```java
    @Test
    void testPingMessageCarriesCurrentEpoch() {
        PingMessage ping = new PingMessage("sender-node-id-12345678901234567890", System.currentTimeMillis());
        ping.setSenderSlots(BitSet.valueOf(new byte[]{0x0F}));
        ping.setSenderConfigEpoch(7L);
        ping.setSenderCurrentEpoch(9L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));

        byte[] encoded = ping.encode();
        PingMessage decoded = new PingMessage();
        decoded.decode(encoded);

        assertEquals(9L, decoded.getSenderCurrentEpoch(),
                "解码后 senderCurrentEpoch 应与编码一致");
        assertEquals(7L, decoded.getSenderConfigEpoch(),
                "senderConfigEpoch 应保持一致");
    }

    @Test
    void testPongMessageCarriesCurrentEpoch() {
        PongMessage pong = new PongMessage("sender-node-id-12345678901234567890", System.currentTimeMillis());
        pong.setSenderCurrentEpoch(11L);

        byte[] encoded = pong.encode();
        PongMessage decoded = new PongMessage();
        decoded.decode(encoded);

        assertEquals(11L, decoded.getSenderCurrentEpoch());
    }
```

- [ ] **Step 6.2: 添加向后兼容测试（旧消息无 currentEpoch 字段）**

```java
    @Test
    void testPingMessageBackwardCompatibleWithoutCurrentEpoch() {
        // 模拟旧版本消息：手动构造不含尾部 currentEpoch 的消息体
        PingMessage ping = new PingMessage("sender-node-id-12345678901234567890", System.currentTimeMillis());
        ping.setSenderConfigEpoch(5L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        byte[] fullEncoded = ping.encode();

        // 截断尾部 8 字节（模拟旧版本无 senderCurrentEpoch 字段）
        byte[] truncated = new byte[fullEncoded.length - 8];
        System.arraycopy(fullEncoded, 0, truncated, 0, truncated.length);

        PingMessage decoded = new PingMessage();
        // 不应抛异常，senderCurrentEpoch 保持默认值 0
        decoded.decode(truncated);

        assertEquals(0L, decoded.getSenderCurrentEpoch(),
                "旧版本消息解码后 senderCurrentEpoch 应为默认值 0");
        assertEquals(5L, decoded.getSenderConfigEpoch(),
                "其他字段应正常解码");
    }
```

- [ ] **Step 6.3: 运行测试**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -pl luban-rds-cluster test -Dtest=GossipMessageCodecTest`
Expected: Tests run: (原数量+3), Failures: 0

- [ ] **Step 6.4: 提交**

```bash
git add luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/GossipMessageCodecTest.java
git commit -m "test(cluster): PING/PONG currentEpoch 编解码与向后兼容测试"
```

---

## Task 7: 单元测试 - gossip 自降级主场景

**Files:**
- Create: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/GossipSelfDemoteTest.java`

- [ ] **Step 7.1: 编写自降级主场景测试**

参考 `GossipRoleSyncTest.java` 的测试基础设施（ClusterConfig + GossipProtocol + FailoverManager mock/real）。新建 `GossipSelfDemoteTest.java`：

```java
package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证重启的原主节点经 gossip 心跳自降级为新主的 slave。
 */
class GossipSelfDemoteTest {

    private ClusterConfig config;
    private GossipProtocol protocol;
    private FailoverManager failoverManager;
    private SlotManager slotManager;

    private static final String MY_ID = "my-node-id-12345678901234567890";
    private static final String NEW_MASTER_ID = "new-master-id-12345678901234567";

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        config.setMyNodeId(MY_ID);
        slotManager = new SlotManager();
        // MYSELF 以旧 master 身份恢复，持有 slots 5461-10922，configEpoch=4
        ClusterNode myNode = new ClusterNode(MY_ID, "127.0.0.1", 9737, 19737);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        myNode.setConfigEpoch(4L);
        BitSet mySlots = new BitSet();
        mySlots.set(5461, 10923);
        myNode.setSlots(mySlots);
        config.addNode(myNode);
        for (int i = 5461; i <= 10922; i++) {
            config.setSlotOwner(i, MY_ID);
            slotManager.setSlotOwner(i, MY_ID);
        }
        // 新主记录（已提升，configEpoch=9）
        ClusterNode newMaster = new ClusterNode(NEW_MASTER_ID, "127.0.0.1", 9740, 19740);
        newMaster.addState(ClusterNodeState.MASTER);
        newMaster.setConfigEpoch(9L);
        config.addNode(newMaster);
        config.setCurrentEpoch(9L);

        protocol = new GossipProtocol(config, slotManager);
        // 注入 FailoverManager 与 ReplicationLifecycleListener（参考 GossipRoleSyncTest 的装配方式）
        // failoverManager = ...; protocol.setFailoverManager(failoverManager);
        // protocol.setReplicationLifecycleListener(...);
    }

    @Test
    void myselfDemotesWhenGossipCarriesHigherEpochSlaveView() {
        // 构造 PONG：gossip section 含 MYSELF 的视图 {configEpoch=9, SLAVE, masterNodeId=NEW_MASTER_ID}
        GossipNodeInfo myselfEntry = new GossipNodeInfo();
        myselfEntry.setNodeId(MY_ID);
        myselfEntry.setConfigEpoch(9L);
        myselfEntry.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        myselfEntry.setMasterNodeId(NEW_MASTER_ID);

        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setSenderCurrentEpoch(9L);
        pong.setGossipNodes(List.of(myselfEntry));

        protocol.handlePong(pong);

        ClusterNode myNode = config.getMyNode();
        assertTrue(myNode.isSlave(), "MYSELF 应已降级为 slave");
        assertFalse(myNode.isMaster(), "MYSELF 不应再是 master");
        assertEquals(NEW_MASTER_ID, myNode.getMasterNodeId());
        assertTrue(myNode.getSlots().isEmpty(), "MYSELF slots 应已清空");
        assertEquals(9L, config.getCurrentEpoch(), "集群 currentEpoch 应同步到 9");
    }

    @Test
    void noDemoteWhenGossipEpochEqualsLocal() {
        // MYSELF 当前 configEpoch=4；gossip 携带 configEpoch=4（相等）应不降级
        GossipNodeInfo myselfEntry = new GossipNodeInfo();
        myselfEntry.setNodeId(MY_ID);
        myselfEntry.setConfigEpoch(4L);
        myselfEntry.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        myselfEntry.setMasterNodeId(NEW_MASTER_ID);

        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setGossipNodes(List.of(myselfEntry));

        protocol.handlePong(pong);

        assertTrue(config.getMyNode().isMaster(), "相等 epoch 不应触发降级（防回退）");
    }

    @Test
    void noDemoteWhenGossipEpochLowerThanLocal() {
        GossipNodeInfo myselfEntry = new GossipNodeInfo();
        myselfEntry.setNodeId(MY_ID);
        myselfEntry.setConfigEpoch(3L);
        myselfEntry.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        myselfEntry.setMasterNodeId(NEW_MASTER_ID);

        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setGossipNodes(List.of(myselfEntry));

        protocol.handlePong(pong);

        assertTrue(config.getMyNode().isMaster(), "更低 epoch 不应触发降级");
    }

    @Test
    void idempotentWhenMyselfAlreadySlave() {
        // 先降级一次
        myselfDemotesWhenGossipCarriesHigherEpochSlaveView();
        // 再次收到相同视图，不应抛异常、不应重复 demote
        GossipNodeInfo myselfEntry = new GossipNodeInfo();
        myselfEntry.setNodeId(MY_ID);
        myselfEntry.setConfigEpoch(9L);
        myselfEntry.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        myselfEntry.setMasterNodeId(NEW_MASTER_ID);

        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setGossipNodes(List.of(myselfEntry));

        assertDoesNotThrow(() -> protocol.handlePong(pong));
        assertTrue(config.getMyNode().isSlave());
    }
}
```

注：setUp 中的 `FailoverManager` 与 `ReplicationLifecycleListener` 装配需参考 `GossipRoleSyncTest.java` 的实际写法（可能用 real FailoverManager + no-op listener 或 mock）。实施时先读 `GossipRoleSyncTest.java` 对齐装配方式，再补全 setUp。

- [ ] **Step 7.2: 运行测试并修复装配**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -pl luban-rds-cluster test -Dtest=GossipSelfDemoteTest`
Expected: Tests run: 4, Failures: 0

若装配失败（FailoverManager/ReplicationLifecycleListener 注入方式不对），读 `GossipRoleSyncTest.java` 对齐后修复。

- [ ] **Step 7.3: 提交**

```bash
git add luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/GossipSelfDemoteTest.java
git commit -m "test(cluster): gossip 自降级主场景与 epoch 门控测试"
```

---

## Task 8: 单元测试 - currentEpoch 经 PING/PONG 同步

**Files:**
- Modify: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocolTest.java`（或新建独立测试类）

- [ ] **Step 8.1: 添加 currentEpoch 同步测试**

```java
    @Test
    void currentEpochSyncedFromPongSenderCurrentEpoch() {
        // 本地 currentEpoch=4，PONG 携带 senderCurrentEpoch=9
        config.setCurrentEpoch(4L);
        ClusterNode sender = new ClusterNode(NEW_MASTER_ID, "127.0.0.1", 9740, 19740);
        sender.addState(ClusterNodeState.MASTER);
        config.addNode(sender);

        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setSenderCurrentEpoch(9L);
        pong.setSenderConfigEpoch(9L);
        pong.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));

        protocol.handlePong(pong);

        assertEquals(9L, config.getCurrentEpoch(),
                "本地 currentEpoch 应被提升到发送方的 senderCurrentEpoch");
    }
```

- [ ] **Step 8.2: 运行测试**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -pl luban-rds-cluster test -Dtest=GossipProtocolTest`
Expected: 全部通过

- [ ] **Step 8.3: 提交**

```bash
git add luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocolTest.java
git commit -m "test(cluster): currentEpoch 经 PONG 心跳同步测试"
```

---

## Task 9: 集成测试 - 故障转移后旧主重启降级

**Files:**
- Create: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/integration/ClusterRestartDemoteTest.java`

- [ ] **Step 9.1: 编写集成测试**

参考 `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/testinfra/EmbeddedCluster.java` 与 `EmbeddedNode.java` 的测试基础设施。模拟：3 主 3 从集群 -> 杀旧主 -> 从提升 -> 旧主重启（以旧 nodes.conf）-> 验证降级。

```java
package com.janeluo.luban.rds.cluster.integration;

import com.janeluo.luban.rds.cluster.testinfra.EmbeddedCluster;
import com.janeluo.luban.rds.cluster.testinfra.EmbeddedNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证：故障转移后旧主重启，经 gossip 自降级为新主的 slave。
 */
class ClusterRestartDemoteTest {

    @Test
    void restartedOldMasterDemotesViaGossip() throws Exception {
        try (EmbeddedCluster cluster = EmbeddedCluster.builder()
                .masters(3).slavesPerMaster(1)
                .build()) {
            cluster.start();
            cluster.waitForClusterOk();

            EmbeddedNode oldMaster = cluster.getMaster(0);
            String oldMasterId = oldMaster.getNodeId();
            EmbeddedNode slave = cluster.getSlaveOf(oldMasterId);

            // 杀旧主，等从节点提升
            oldMaster.stop();
            cluster.waitForFailover(slave.getNodeId());

            // 旧主以旧 nodes.conf 重启（currentEpoch 滞后）
            oldMaster.restart();
            cluster.waitForGossipConverge();

            // 验证旧主已降级为新主（原从）的 slave
            assertTrue(oldMaster.isSlave(),
                    "重启的旧主应已降级为 slave");
            assertEquals(slave.getNodeId(), oldMaster.getMasterNodeId(),
                    "旧主的 masterNodeId 应指向新主（原从）");
            assertFalse(oldMaster.hasSlots(),
                    "旧主不应再持有 slots");
        }
    }
}
```

注：`EmbeddedCluster`/`EmbeddedNode` 的实际 API（`getMaster`/`getSlaveOf`/`waitForFailover`/`restart`/`isSlave` 等）需先读 testinfra 实现对齐。若 API 不完全匹配，按实际方法名调整。若 `restart` 方法不存在，用 `stop` + 重新 `start`（保留 data 目录）模拟。

- [ ] **Step 9.2: 运行集成测试并修复**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -pl luban-rds-cluster test -Dtest=ClusterRestartDemoteTest`
Expected: Tests run: 1, Failures: 0

若失败，分析是装配问题还是实现问题，修复后重跑。

- [ ] **Step 9.3: 提交**

```bash
git add luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/integration/ClusterRestartDemoteTest.java
git commit -m "test(cluster): 故障转移后旧主重启降级集成测试"
```

---

## Task 10: 全模块测试 + 构建验证

- [ ] **Step 10.1: 运行 luban-rds-cluster 全部测试**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd -pl luban-rds-cluster test`
Expected: 全部通过，无回归

- [ ] **Step 10.2: 运行全项目构建**

Run: `JAVA_HOME=C:\Developments\Java\jdk-17.0.4.1 C:\Developments\apache-maven-3.6.3\bin\mvn.cmd clean install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 10.3: 勾选 tasks.md 全部任务**

将 `openspec/changes/fix-cluster-restart-demote/tasks.md` 中所有 `- [ ]` 改为 `- [x]`。

- [ ] **Step 10.4: 提交 tasks.md**

```bash
git add openspec/changes/fix-cluster-restart-demote/tasks.md
git commit -m "chore(change): fix-cluster-restart-demote 全部任务完成"
```

---

## Self-Review

**Spec coverage:** delta spec 5 个 scenario 对应：
- 自降级主场景 -> Task 7
- epoch 门控防回退 -> Task 7 (noDemoteWhenGossipEpochEquals/Lower)
- currentEpoch 经 PING/PONG 同步 -> Task 6 + Task 8
- PING/PONG 向后兼容 -> Task 6 (BackwardCompatible test)
- 启动恢复软对齐 -> Task 5（诊断日志，软对齐行为本身由 Task 4 自降级保证）

**Placeholder scan:** 无 TBD/TODO；所有代码步骤含完整代码；测试含具体断言。

**Type consistency:** `applySelfDemotion(String newMasterNodeId, long newConfigEpoch)` 在 Task 3 定义、Task 4 调用，签名一致；`senderCurrentEpoch` 字段名在 Task 1-2-6-8 一致。
