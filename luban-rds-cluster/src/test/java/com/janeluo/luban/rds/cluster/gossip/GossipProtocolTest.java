package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    @DisplayName("测试更新节点最后通信时间")
    void testUpdateNodeLastInteraction() {
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        clusterConfig.addNode(node);

        gossipProtocol.updateNodeLastInteraction(node.getNodeId());

        long lastInteraction = gossipProtocol.getNodeLastInteraction(node.getNodeId());
        assertTrue(lastInteraction > 0);
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
}
