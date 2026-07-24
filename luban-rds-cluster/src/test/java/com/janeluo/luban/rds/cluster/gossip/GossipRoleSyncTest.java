package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@code syncSenderRole} 纪元门控修复：MEET/PING/PONG 消息头携带的发送方角色
 * （MASTER/SLAVE）必须能正确同步到接收方本地视图。
 * <p>
 * 回归缺陷：{@code updateNodeFromMeetMessage} 中 {@code setConfigEpochIfGreater} 先于
 * {@code syncSenderRole} 执行，把本地 configEpoch 提升到与消息 senderConfigEpoch 相等，
 * 导致 {@code syncSenderRole} 内 {@code configEpoch > localEpoch} 恒为 false，
 * slave 角色永不切换。本测试覆盖 MEET/PING/PONG 三条路径。
 * </p>
 */
class GossipRoleSyncTest {

    private static final String MY_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SENDER_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String MASTER_ID = "cccccccccccccccccccccccccccccccccccccccc";

    private ClusterConfig clusterConfig;
    private ClusterNode myNode;
    private GossipProtocol gossipProtocol;

    @BeforeEach
    void setUp() {
        clusterConfig = new ClusterConfig();
        myNode = createTestNode(MY_ID, "127.0.0.1", 6379, 16379);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);
        clusterConfig.setMyNodeId(MY_ID);

        // 不使用真实总线客户端
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        gossipProtocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);
    }

    @Test
    @DisplayName("MEET 携带 SLAVE 角色时接收方应把对端从 MASTER 切换为 SLAVE")
    void testMeetSyncsSlaveRole() {
        // 发送方已在本地配置中（模拟握手阶段），初始为 MASTER，configEpoch=0
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.setConfigEpoch(0L);
        clusterConfig.addNode(senderNode);

        // 构造 MEET：发送方执行了 REPLICATE，configEpoch=4，角色为 SLAVE
        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId(SENDER_ID);
        meet.setSenderIp("127.0.0.1");
        meet.setSenderPort(6380);
        meet.setSenderBusPort(16380);
        meet.setSenderConfigEpoch(4L);
        meet.setCurrentEpoch(4L);
        meet.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        meet.setSenderMasterNodeId(MASTER_ID);

        gossipProtocol.handleMeet(meet);

        // 修复前：senderNode 仍为 MASTER（configEpoch 被提前提升到 4，门控失效）
        // 修复后：senderNode 应切换为 SLAVE，masterNodeId 指向 MASTER_ID
        assertTrue(senderNode.isSlave(), "MEET 携带 SLAVE 角色后，对端应切换为 SLAVE");
        assertFalse(senderNode.isMaster(), "MEET 携带 SLAVE 角色后，对端不应再是 MASTER");
        assertEquals(MASTER_ID, senderNode.getMasterNodeId(),
                "对端的 masterNodeId 应指向 MEET 携带的主节点ID");
        assertEquals(4L, senderNode.getConfigEpoch(),
                "对端的 configEpoch 应被同步提升到消息携带的纪元");
    }

    @Test
    @DisplayName("PING 携带 SLAVE 角色时接收方应把对端从 MASTER 切换为 SLAVE")
    void testPingSyncsSlaveRole() {
        // 发送方已在本地配置中，初始为 MASTER，configEpoch=0
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.setConfigEpoch(0L);
        clusterConfig.addNode(senderNode);

        // 构造 PING：发送方 configEpoch=5，角色为 SLAVE
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        ping.setSenderConfigEpoch(5L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        ping.setSenderMasterNodeId(MASTER_ID);
        ping.setSenderSlots(new java.util.BitSet());

        gossipProtocol.handlePing(ping);

        assertTrue(senderNode.isSlave(), "PING 携带 SLAVE 角色后，对端应切换为 SLAVE");
        assertFalse(senderNode.isMaster(), "PING 携带 SLAVE 角色后，对端不应再是 MASTER");
        assertEquals(MASTER_ID, senderNode.getMasterNodeId(),
                "对端的 masterNodeId 应指向 PING 携带的主节点ID");
    }

    @Test
    @DisplayName("PONG 携带 SLAVE 角色时接收方应把对端从 MASTER 切换为 SLAVE")
    void testPongSyncsSlaveRole() {
        // 发送方已在本地配置中，初始为 MASTER，configEpoch=0
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.setConfigEpoch(0L);
        clusterConfig.addNode(senderNode);

        // 构造 PONG：发送方 configEpoch=6，角色为 SLAVE
        PongMessage pong = new PongMessage(SENDER_ID, System.currentTimeMillis());
        pong.setSenderConfigEpoch(6L);
        pong.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        pong.setSenderMasterNodeId(MASTER_ID);
        pong.setSenderSlots(new java.util.BitSet());

        gossipProtocol.handlePong(pong);

        assertTrue(senderNode.isSlave(), "PONG 携带 SLAVE 角色后，对端应切换为 SLAVE");
        assertFalse(senderNode.isMaster(), "PONG 携带 SLAVE 角色后，对端不应再是 MASTER");
        assertEquals(MASTER_ID, senderNode.getMasterNodeId(),
                "对端的 masterNodeId 应指向 PONG 携带的主节点ID");
    }

    @Test
    @DisplayName("陈旧纪元（senderConfigEpoch < 本地基线）不应触发角色切换")
    void testStaleEpochDoesNotRevertRole() {
        // 发送方已在本地配置中，已知为 SLAVE（configEpoch=10，已完成故障转移提升视图）
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.SLAVE);
        senderNode.setMasterNodeId(MASTER_ID);
        senderNode.setConfigEpoch(10L);
        clusterConfig.addNode(senderNode);

        // 构造陈旧 PING：configEpoch=3（< 10），声称 MASTER，试图回退角色
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        ping.setSenderConfigEpoch(3L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        ping.setSenderMasterNodeId(null);
        ping.setSenderSlots(new java.util.BitSet());

        gossipProtocol.handlePing(ping);

        // 陈旧纪元不应把 SLAVE 回退为 MASTER
        assertTrue(senderNode.isSlave(), "陈旧纪元不应把 SLAVE 回退为 MASTER");
        assertFalse(senderNode.isMaster(), "陈旧纪元不应触发角色切换");
        assertEquals(MASTER_ID, senderNode.getMasterNodeId(),
                "masterNodeId 不应被陈旧消息清除");
    }

    @Test
    @DisplayName("MEET 携带 MASTER 角色时接收方应把对端从 SLAVE 切换为 MASTER（故障转移提升）")
    void testMeetSyncsMasterPromotion() {
        // 发送方已在本地配置中，初始为 SLAVE，configEpoch=2
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.SLAVE);
        senderNode.setMasterNodeId(MASTER_ID);
        senderNode.setConfigEpoch(2L);
        clusterConfig.addNode(senderNode);

        // 构造 MEET：发送方已提升为 MASTER，configEpoch=8
        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId(SENDER_ID);
        meet.setSenderIp("127.0.0.1");
        meet.setSenderPort(6380);
        meet.setSenderBusPort(16380);
        meet.setSenderConfigEpoch(8L);
        meet.setCurrentEpoch(8L);
        meet.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        meet.setSenderMasterNodeId(null);

        gossipProtocol.handleMeet(meet);

        assertTrue(senderNode.isMaster(), "MEET 携带 MASTER 角色后，对端应提升为 MASTER");
        assertFalse(senderNode.isSlave(), "对端不应再是 SLAVE");
        assertNull(senderNode.getMasterNodeId(),
                "提升为 MASTER 后 masterNodeId 应被清除");
    }

    @Test
    @DisplayName("相等于基线的纪元不切换角色，但可同步 masterNodeId")
    void testEqualEpochDoesNotSwitchRoleButSyncsMasterNodeId() {
        // 发送方已在本地配置中，初始为 MASTER，configEpoch=5
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.setConfigEpoch(5L);
        clusterConfig.addNode(senderNode);

        // 构造 PING：configEpoch=5（== 基线），声称 SLAVE
        // 相等纪元不应切换角色（防止抖动），但 masterNodeId 同步仅在已是 slave 时生效
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        ping.setSenderConfigEpoch(5L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        ping.setSenderMasterNodeId(MASTER_ID);
        ping.setSenderSlots(new java.util.BitSet());

        gossipProtocol.handlePing(ping);

        // 相等纪元不切换角色：仍为 MASTER
        assertTrue(senderNode.isMaster(), "相等于基线的纪元不应触发 MASTER->SLAVE 切换");
        assertFalse(senderNode.isSlave(), "相等纪元不应切换为 SLAVE");
    }

    @Test
    @DisplayName("Gossip section 中第三方节点 SLAVE 角色应被同步（processGossipNodes 路径）")
    void testGossipSectionSyncsThirdPartySlaveRole() {
        // 发送方节点 B（master）
        ClusterNode senderNode = createTestNode(SENDER_ID, "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.setConfigEpoch(0L);
        clusterConfig.addNode(senderNode);

        // 第三方节点 D，已在本地配置中，初始为 MASTER，configEpoch=0
        String nodeDId = "dddddddddddddddddddddddddddddddddddddddd";
        ClusterNode nodeD = createTestNode(nodeDId, "127.0.0.1", 6382, 16382);
        nodeD.addState(ClusterNodeState.MASTER);
        nodeD.setConfigEpoch(0L);
        clusterConfig.addNode(nodeD);

        // 构造 PING：发送方 B 是 master，gossip section 携带第三方 D 为 SLAVE（configEpoch=7）
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        ping.setSenderConfigEpoch(0L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        ping.setSenderSlots(new java.util.BitSet());

        GossipNodeInfo gossipD = new GossipNodeInfo(nodeDId);
        gossipD.setIp("127.0.0.1");
        gossipD.setPort(6382);
        gossipD.setBusPort(16382);
        gossipD.setConfigEpoch(7L);
        gossipD.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        gossipD.setMasterNodeId(MASTER_ID);
        ping.addGossipNode(gossipD);

        gossipProtocol.handlePing(ping);

        // 修复前：processGossipNodes 中 setConfigEpochIfGreater 先把 D 的本地纪元提升到 7，
        // 随后 gossipEpoch(7)>localEpoch(7) 恒 false，D 不切换为 SLAVE
        // 修复后：基于基线（0）判断，7>0 成立，D 切换为 SLAVE
        assertTrue(nodeD.isSlave(), "Gossip section 携带 SLAVE 角色后，第三方节点 D 应切换为 SLAVE");
        assertFalse(nodeD.isMaster(), "第三方节点 D 不应再是 MASTER");
        assertEquals(MASTER_ID, nodeD.getMasterNodeId(),
                "第三方节点 D 的 masterNodeId 应指向 gossip 携带的主节点ID");
        assertEquals(7L, nodeD.getConfigEpoch(),
                "第三方节点 D 的 configEpoch 应被同步提升");
    }

    // ==================== 测试辅助方法 ====================

    private ClusterNode createTestNode(String nodeId, String ip, int port, int busPort) {
        ClusterNode node = new ClusterNode(nodeId);
        node.setIp(ip);
        node.setPort(port);
        node.setBusPort(busPort);
        return node;
    }
}
