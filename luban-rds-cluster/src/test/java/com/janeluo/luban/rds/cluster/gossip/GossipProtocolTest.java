package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GossipProtocol 单元测试
 */
class GossipProtocolTest {

    private ClusterConfig clusterConfig;
    private ClusterNode myNode;
    private GossipProtocol gossipProtocol;

    @BeforeEach
    void setUp() {
        // 创建集群配置
        clusterConfig = new ClusterConfig();

        // 创建本节点
        myNode = createTestNode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "127.0.0.1", 6379, 16379);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);
        clusterConfig.setMyNodeId(myNode.getNodeId());

        // 创建 Gossip 协议实例（不使用总线客户端）
        gossipProtocol = new GossipProtocol(clusterConfig, null, 5000);
    }

    @Test
    @DisplayName("测试启动和停止 Gossip 协议")
    void testStartAndStop() {
        assertFalse(gossipProtocol.isStarted());

        gossipProtocol.start();
        assertTrue(gossipProtocol.isStarted());

        gossipProtocol.stop();
        assertFalse(gossipProtocol.isStarted());
    }

    @Test
    @DisplayName("测试处理 PING 消息")
    void testHandlePing() {
        PingMessage ping = new PingMessage(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                System.currentTimeMillis()
        );

        PongMessage pong = gossipProtocol.handlePing(ping);

        assertNotNull(pong);
        assertEquals(GossipMessageType.PONG, pong.getType());
        assertEquals(myNode.getNodeId(), pong.getSenderNodeId());
    }

    @Test
    @DisplayName("测试处理 PONG 消息")
    void testHandlePong() {
        // 先添加节点
        ClusterNode senderNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(senderNode);

        PongMessage pong = new PongMessage(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                System.currentTimeMillis()
        );

        gossipProtocol.handlePong(pong);

        // 验证节点状态是否更新
        assertFalse(senderNode.isPfail());
    }

    @Test
    @DisplayName("测试处理 MEET 消息")
    void testHandleMeet() {
        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        meet.setSenderIp("127.0.0.1");
        meet.setSenderPort(6380);
        meet.setSenderBusPort(16380);

        gossipProtocol.handleMeet(meet);

        // 验证新节点是否被添加
        ClusterNode newNode = clusterConfig.getNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertNotNull(newNode);
        // 收到 MEET 后握手完成：HANDSHAKE 被移除，MASTER 被设置
        assertFalse(newNode.hasState(ClusterNodeState.HANDSHAKE));
        assertTrue(newNode.isMaster());
    }

    @Test
    @DisplayName("测试处理 FAIL 消息")
    void testHandleFail() {
        ClusterNode failedNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        failedNode.addState(ClusterNodeState.MASTER);
        failedNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(failedNode);
        // 发送方节点需存在于集群配置中（handleNow 校验发送方身份）
        ClusterNode senderNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6390, 16390);
        senderNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(senderNode);

        FailMessage fail = new FailMessage();
        fail.setSenderNodeId("cccccccccccccccccccccccccccccccccccccccc");
        fail.setFailedNodeId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        gossipProtocol.handleFail(fail);

        // 验证节点是否被标记为 FAIL
        assertTrue(failedNode.isFail());
        assertFalse(failedNode.isPfail());
    }

    @Test
    @DisplayName("测试选择 Gossip 节点")
    void testSelectGossipNodes() {
        // 添加多个节点
        for (int i = 0; i < 5; i++) {
            ClusterNode node = createTestNode(
                    String.format("%040d", i),
                    "127.0.0.1",
                    6380 + i,
                    16380 + i
            );
            node.addState(ClusterNodeState.MASTER);
            clusterConfig.addNode(node);
        }

        List<GossipNodeInfo> gossipNodes = gossipProtocol.selectGossipNodes();

        // 验证返回的节点数量不超过配置值
        assertTrue(gossipNodes.size() <= 3);
        assertTrue(gossipNodes.size() > 0);

        // 验证不包含本节点
        for (GossipNodeInfo info : gossipNodes) {
            assertNotEquals(myNode.getNodeId(), info.getNodeId());
        }
    }

    @Test
    @DisplayName("通过 Gossip 发现新节点后应主动发起 connect 与 MEET")
    void testGossipDiscoveryTriggersMeet() {
        // 使用 mock 的总线客户端
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPromise succeededPromise = new DefaultChannelPromise(channel);
        succeededPromise.trySuccess();
        when(mockBusClient.isConnected(anyString())).thenReturn(false);
        when(mockBusClient.connect(anyString(), anyString(), anyInt())).thenReturn(succeededPromise);

        GossipProtocol protocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);

        // 构造一个已知发送方节点（PONG 的发送方），使 updateNodeFromPongMessage 能找到它
        ClusterNode senderNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(senderNode);

        // 构造 PONG 携带的 Gossip 节点信息：一个本节点未知的第三层节点
        String discoveredNodeId = "cccccccccccccccccccccccccccccccccccccccc";
        GossipNodeInfo discoveredInfo = new GossipNodeInfo(discoveredNodeId);
        discoveredInfo.setIp("127.0.0.1");
        discoveredInfo.setPort(6381);
        discoveredInfo.setBusPort(16381);
        Set<ClusterNodeState> flags = EnumSet.of(ClusterNodeState.MASTER);
        discoveredInfo.setFlags(flags);

        List<GossipNodeInfo> gossipSection = new ArrayList<>();
        gossipSection.add(discoveredInfo);

        PongMessage pong = new PongMessage(senderNode.getNodeId(), System.currentTimeMillis());
        for (GossipNodeInfo info : gossipSection) {
            pong.addGossipNode(info);
        }

        protocol.handlePong(pong);

        // 验证：新节点已加入本地配置（HANDSHAKE 状态）
        ClusterNode discovered = clusterConfig.getNode(discoveredNodeId);
        assertNotNull(discovered, "通过 Gossip 发现的节点应被加入本地配置");
        assertTrue(discovered.hasState(ClusterNodeState.HANDSHAKE), "新发现节点应处于 HANDSHAKE 状态");

        // 验证：已对该节点发起 connect（以真实 nodeId）
        verify(mockBusClient, atLeastOnce()).connect(eq(discoveredNodeId), eq("127.0.0.1"), eq(6381));
        // 验证：连接成功后发送了 MEET（任意 MeetMessage）
        verify(mockBusClient, atLeastOnce()).send(eq(discoveredNodeId), any(MeetMessage.class));
    }

    @Test
    @DisplayName("已连接的 Gossip 发现节点不应重复发起 MEET")
    void testGossipDiscoverySkipsWhenConnected() {
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        when(mockBusClient.isConnected(anyString())).thenReturn(true);

        GossipProtocol protocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);

        ClusterNode senderNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(senderNode);

        String discoveredNodeId = "cccccccccccccccccccccccccccccccccccccccc";
        GossipNodeInfo discoveredInfo = new GossipNodeInfo(discoveredNodeId);
        discoveredInfo.setIp("127.0.0.1");
        discoveredInfo.setPort(6381);
        discoveredInfo.setBusPort(16381);
        discoveredInfo.setFlags(EnumSet.of(ClusterNodeState.MASTER));

        PongMessage pong = new PongMessage(senderNode.getNodeId(), System.currentTimeMillis());
        pong.addGossipNode(discoveredInfo);

        protocol.handlePong(pong);

        // 节点仍被加入配置
        assertNotNull(clusterConfig.getNode(discoveredNodeId));
        // 但因 isConnected 返回 true，不应再次 connect
        verify(mockBusClient, org.mockito.Mockito.never())
                .connect(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("PONG 携带 senderSlots 时本地应同步发送方槽位归属")
    void testSenderSlotsSyncOnPong() {
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        GossipProtocol protocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);

        // 发送方节点 B，拥有 slot 5461-10922
        ClusterNode senderNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        senderNode.setConfigEpoch(2L);
        clusterConfig.addNode(senderNode);

        java.util.BitSet senderSlots = new java.util.BitSet();
        senderSlots.set(5461, 10923); // 5461-10922
        senderNode.setSlots(senderSlots);

        PongMessage pong = new PongMessage(senderNode.getNodeId(), System.currentTimeMillis());
        pong.setSenderSlots(senderSlots);

        protocol.handlePong(pong);

        // 本节点 A 应把 slot 5461-10922 归属设为 B
        assertEquals(senderNode.getNodeId(), clusterConfig.getSlotOwner(5461));
        assertEquals(senderNode.getNodeId(), clusterConfig.getSlotOwner(10922));
        assertNull(clusterConfig.getSlotOwner(0));
    }

    @Test
    @DisplayName("Gossip section 中第三方节点 slots 应被同步")
    void testGossipSectionSlotsSync() {
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        when(mockBusClient.isConnected(anyString())).thenReturn(true);
        GossipProtocol protocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);

        // 发送方节点 B
        ClusterNode senderNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        senderNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(senderNode);

        // 第三方节点 C（gossip 携带），拥有 slot 10923-16383
        String nodeCId = "cccccccccccccccccccccccccccccccccccccccc";
        GossipNodeInfo nodeCInfo = new GossipNodeInfo(nodeCId);
        nodeCInfo.setIp("127.0.0.1");
        nodeCInfo.setPort(6381);
        nodeCInfo.setBusPort(16381);
        nodeCInfo.setConfigEpoch(3L);
        nodeCInfo.setFlags(EnumSet.of(ClusterNodeState.MASTER));
        java.util.BitSet nodeCSlots = new java.util.BitSet();
        nodeCSlots.set(10923, 16384); // 10923-16383
        nodeCInfo.setSlots(nodeCSlots);

        PongMessage pong = new PongMessage(senderNode.getNodeId(), System.currentTimeMillis());
        pong.addGossipNode(nodeCInfo);

        protocol.handlePong(pong);

        // C 被加入配置，且 slot 10923-16383 归属设为 C
        ClusterNode nodeC = clusterConfig.getNode(nodeCId);
        assertNotNull(nodeC);
        assertEquals(nodeCId, clusterConfig.getSlotOwner(10923));
        assertEquals(nodeCId, clusterConfig.getSlotOwner(16383));
    }

    @Test
    @DisplayName("测试更新节点最后通信时间")
    void testUpdateNodeLastInteraction() {
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        clusterConfig.addNode(node);

        gossipProtocol.updateNodeLastInteraction(node.getNodeId());

        long lastInteraction = gossipProtocol.getNodeLastInteraction(node.getNodeId());
        assertTrue(lastInteraction > 0);
    }

    /**
     * 回归测试：集群重启后从 nodes.conf 恢复的已知节点必须在 Gossip 启动时被主动连接。
     * <p>
     * 场景：全集群停止后重启，nodes.conf 里的对端节点以 MASTER 状态（非 HANDSHAKE）
     * 加载，link 为 disconnected。若 start() 不主动 connect，sendPing 只 warn 不连接，
     * PING 永远发不出去，PFAIL 无法清除，集群成孤岛、状态恢复不了。
     * 对齐 Redis clusterConnectAllNodes。
     * </p>
     */
    @Test
    @DisplayName("start() 应主动连接从 nodes.conf 恢复的已知节点")
    void testStartConnectsRecoveredKnownNodes() {
        // 使用 mock 的总线客户端
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPromise succeededPromise = new DefaultChannelPromise(channel);
        succeededPromise.trySuccess();
        when(mockBusClient.isConnected(anyString())).thenReturn(false);
        when(mockBusClient.connect(anyString(), anyString(), anyInt())).thenReturn(succeededPromise);

        GossipProtocol protocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);

        // 添加 2 个已知的 master 节点（模拟从 nodes.conf 恢复，非 HANDSHAKE）
        ClusterNode peer1 = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "192.168.8.161", 9737, 19737);
        peer1.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(peer1);
        ClusterNode peer2 = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "192.168.8.161", 9738, 19738);
        peer2.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(peer2);

        try {
            protocol.start();

            // 验证：对两个非本节点都调用了 connect(nodeId, ip, port)
            verify(mockBusClient, atLeastOnce()).connect(eq(peer1.getNodeId()), eq("192.168.8.161"), eq(9737));
            verify(mockBusClient, atLeastOnce()).connect(eq(peer2.getNodeId()), eq("192.168.8.161"), eq(9738));
            // 验证：没有对本节点调用 connect
            verify(mockBusClient, org.mockito.Mockito.never())
                    .connect(eq(myNode.getNodeId()), anyString(), anyInt());
        } finally {
            protocol.stop();
        }
    }

    /**
     * 回归测试：FAIL 节点在 start() 时不应被主动连接。
     * <p>
     * 已下线节点不应建立总线连接（对齐 Redis：link 节点排除 FAIL）。
     * 注：HANDSHAKE 节点会由 GossipTask 的 initiateMeetForDiscoveredNode 流程处理，
     * 属独立的 MEET 握手路径，不在本断言范围内。
     * </p>
     */
    @Test
    @DisplayName("start() 不应连接 FAIL 节点")
    void testStartSkipsFailNodes() {
        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPromise succeededPromise = new DefaultChannelPromise(channel);
        succeededPromise.trySuccess();
        when(mockBusClient.isConnected(anyString())).thenReturn(false);
        when(mockBusClient.connect(anyString(), anyString(), anyInt())).thenReturn(succeededPromise);

        GossipProtocol protocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);

        // FAIL 节点（持有槽位，模拟一个已下线的主节点）
        ClusterNode failNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6381, 16381);
        failNode.addState(ClusterNodeState.MASTER);
        failNode.addState(ClusterNodeState.FAIL);
        clusterConfig.addNode(failNode);

        try {
            protocol.start();

            // 验证：没有对 FAIL 节点调用 connect（connectKnownNodes 与 GossipTask 都应跳过 FAIL）
            verify(mockBusClient, org.mockito.Mockito.never())
                    .connect(eq(failNode.getNodeId()), anyString(), anyInt());
        } finally {
            protocol.stop();
        }
    }

    /**
     * 创建测试节点
     */
    private ClusterNode createTestNode(String nodeId, String ip, int port, int busPort) {
        ClusterNode node = new ClusterNode(nodeId);
        node.setIp(ip);
        node.setPort(port);
        node.setBusPort(busPort);
        return node;
    }

    @Test
    @DisplayName("N-10：针对本节点的 FAIL 消息被忽略（MYSELF 守卫）")
    void testFailAboutMyselfIgnored() {
        // 发送方须为已知节点（handleFail 的既有发送方校验）
        ClusterNode sender = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        sender.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(sender);

        FailMessage fail = new FailMessage();
        fail.setSenderNodeId(sender.getNodeId());
        fail.setFailedNodeId(myNode.getNodeId()); // 声明本节点 FAIL

        gossipProtocol.handleFail(fail);

        assertFalse(myNode.isFail(), "MYSELF 守卫：本节点不应被 FAIL 消息标记");
        assertTrue(myNode.isMaster(), "本节点角色不应被影响");
    }

    @Test
    @DisplayName("N-2：伪造本节点 ID 的 PING 不删槽位、不降级")
    void testPingWithForgedOwnNodeIdIgnored() {
        // 本节点持有 slot 0
        clusterConfig.setSlotOwner(0, myNode.getNodeId());
        assertTrue(myNode.hasSlot(0), "前置条件：本节点持有 slot 0");

        // 伪造发送方为本节点的 PING：空位图 + SLAVE 角色 + 更高纪元，
        // 旧实现会经 syncSlotsFromNode 删光本节点槽位、经 syncSenderRole 降级
        PingMessage ping = new PingMessage(myNode.getNodeId(), System.currentTimeMillis());
        ping.setSenderSlots(new java.util.BitSet());
        ping.setSenderConfigEpoch(myNode.getConfigEpoch() + 1);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        ping.setSenderMasterNodeId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        PongMessage pong = gossipProtocol.handlePing(ping);

        assertNotNull(pong, "仍应回复 PONG（仅忽略对 MYSELF 的状态更新）");
        assertTrue(myNode.hasSlot(0), "MYSELF 守卫：本节点槽位不应被伪造 PING 删除");
        assertTrue(myNode.isMaster(), "MYSELF 守卫：本节点不应被降级为 slave");
    }

    @Test
    @DisplayName("N-2：伪造本节点 ID 的 PONG 被忽略")
    void testPongWithForgedOwnNodeIdIgnored() {
        // 伪造发送方为本节点的 PONG：空位图 + SLAVE 角色
        PongMessage pong = new PongMessage(myNode.getNodeId(), System.currentTimeMillis());
        pong.setSenderSlots(new java.util.BitSet());
        pong.setSenderConfigEpoch(myNode.getConfigEpoch() + 1);
        pong.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        pong.setSenderMasterNodeId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        gossipProtocol.handlePong(pong);

        assertTrue(myNode.isMaster(), "MYSELF 守卫：本节点不应被降级为 slave");
        assertEquals(0, clusterConfig.getAssignedSlotCount(), "本节点不应持有（空位图也不应产生影响）");
    }

    @Test
    @DisplayName("N-2：伪造本节点 ID 的 MEET 被忽略（不覆盖本节点地址）")
    void testMeetWithForgedOwnNodeIdIgnored() {
        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId(myNode.getNodeId());
        meet.setSenderIp("192.168.99.99");
        meet.setSenderPort(9999);
        meet.setSenderBusPort(19999);

        gossipProtocol.handleMeet(meet);

        assertEquals("127.0.0.1", myNode.getIp(), "本节点通告地址不应被伪造 MEET 覆盖");
        assertEquals(6379, myNode.getPort());
    }
}
