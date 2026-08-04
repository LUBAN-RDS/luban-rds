package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.FailMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.gossip.MeetMessage;
import com.janeluo.luban.rds.cluster.gossip.PingMessage;
import com.janeluo.luban.rds.cluster.gossip.PongMessage;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClusterBusServer 单元测试
 */
class ClusterBusServerTest {

    private static final String NODE_ID_1 = "1234567890123456789012345678901234567890";
    private static final String NODE_ID_2 = "abcdefghijklmnopqrstuvwxyz1234567890abcd";
    private static final int SERVICE_PORT = 19736;
    private static final int BUS_PORT = SERVICE_PORT + ClusterBusServer.BUS_PORT_OFFSET;

    private ClusterConfig clusterConfig;
    private MockGossipProtocol gossipProtocol;
    private ClusterBusServer server;

    @BeforeEach
    void setUp() {
        clusterConfig = new ClusterConfig(NODE_ID_1);
        gossipProtocol = new MockGossipProtocol(clusterConfig);
        server = new ClusterBusServer(SERVICE_PORT, clusterConfig, gossipProtocol);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testServerStart() {
        // 启动服务器
        server.start();

        // 验证服务器状态
        assertTrue(server.isRunning());
        assertEquals(BUS_PORT, server.getPort());

        // 验证绑定地址
        InetSocketAddress address = server.getLocalAddress();
        assertNotNull(address);
        assertEquals(BUS_PORT, address.getPort());
    }

    @Test
    void testServerStop() {
        // 启动服务器
        server.start();
        assertTrue(server.isRunning());

        // 停止服务器
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    void testServerStartTwice() {
        // 第一次启动
        server.start();
        assertTrue(server.isRunning());

        // 第二次启动应该被忽略
        server.start();
        assertTrue(server.isRunning());
    }

    @Test
    void testServerStopTwice() {
        // 启动服务器
        server.start();
        assertTrue(server.isRunning());

        // 第一次停止
        server.stop();
        assertFalse(server.isRunning());

        // 第二次停止应该被忽略
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    void testBusPortOffset() {
        assertEquals(10000, ClusterBusServer.BUS_PORT_OFFSET);
    }

    @Test
    void testServerWithDifferentPorts() {
        // 测试不同服务端口
        ClusterBusServer server1 = new ClusterBusServer(6379, clusterConfig, gossipProtocol);
        assertEquals(16379, server1.getPort());

        ClusterBusServer server2 = new ClusterBusServer(7000, clusterConfig, gossipProtocol);
        assertEquals(17000, server2.getPort());
    }

    @Test
    void testN37CustomBusPortConsumed() {
        // N-37：cluster-announce-bus-port 必须被总线服务端消费——
        // 显式传入总线端口时监听该端口，而非永远 servicePort+10000。
        ClusterBusServer server = new ClusterBusServer(6379, 26379, clusterConfig, gossipProtocol);
        assertEquals(26379, server.getPort(), "自定义总线端口应被监听");
    }

    @Test
    void testN37ResolveBusPortUsesAdvertisedPort() {
        // N-37：出站连接优先使用对端通告的总线端口（@cport），未通告时回退 servicePort+10000。
        ClusterNode withBusPort = new ClusterNode("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
        withBusPort.setBusPort(26379);
        assertEquals(26379, ClusterBusClient.resolveBusPort(withBusPort, 6379),
                "应使用对端通告的总线端口");

        ClusterNode withoutBusPort = new ClusterNode("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
        withoutBusPort.setBusPort(0);
        assertEquals(6379 + ClusterBusServer.BUS_PORT_OFFSET,
                ClusterBusClient.resolveBusPort(withoutBusPort, 6379),
                "未通告总线端口时应回退 servicePort+10000");
        assertEquals(6379 + ClusterBusServer.BUS_PORT_OFFSET,
                ClusterBusClient.resolveBusPort(null, 6379),
                "目标未知时应回退 servicePort+10000");
    }

    /**
     * Mock Gossip 协议实现
     */
    private static class MockGossipProtocol extends GossipProtocol {

        private final List<String> receivedMessages = new ArrayList<>();

        public MockGossipProtocol(ClusterConfig clusterConfig) {
            super(clusterConfig, null, 15000);
        }

        @Override
        public PongMessage handlePing(PingMessage ping) {
            receivedMessages.add("PING from " + ping.getSenderNodeId());
            ClusterNode myNode = getClusterConfig().getMyNode();
            if (myNode != null) {
                return new PongMessage(myNode.getNodeId(), System.currentTimeMillis());
            }
            return null;
        }

        @Override
        public void handlePong(PongMessage pong) {
            receivedMessages.add("PONG from " + pong.getSenderNodeId());
        }

        @Override
        public void handleMeet(MeetMessage meet) {
            receivedMessages.add("MEET from " + meet.getSenderNodeId());
        }

        @Override
        public void handleFail(FailMessage fail) {
            receivedMessages.add("FAIL: " + fail.getFailedNodeId() + " reported by " + fail.getSenderNodeId());
        }

        public List<String> getReceivedMessages() {
            return receivedMessages;
        }
    }
}
