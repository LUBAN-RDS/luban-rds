package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailureDetector 单元测试
 */
class FailureDetectorTest {

    private ClusterConfig clusterConfig;
    private ClusterNode myNode;
    private FailureDetector failureDetector;

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

        // 创建故障检测器（超时时间 5000ms）
        failureDetector = new FailureDetector(clusterConfig, 5000);
    }

    @Test
    @DisplayName("测试检测节点超时 - 正常节点")
    void testCheckNodeTimeoutNormal() {
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        node.addState(ClusterNodeState.MASTER);
        node.updateLastPongTime(); // 更新最后通信时间
        clusterConfig.addNode(node);

        failureDetector.checkNodeTimeout();

        // 节点不应该被标记为 PFAIL
        assertFalse(node.isPfail());
    }

    @Test
    @DisplayName("测试检测节点超时 - 超时节点")
    void testCheckNodeTimeoutExpired() {
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        node.addState(ClusterNodeState.MASTER);
        // 设置最后 PONG 时间为很久以前
        node.setLastPongTime(System.currentTimeMillis() - 10000);
        clusterConfig.addNode(node);

        failureDetector.checkNodeTimeout();

        // 节点应该被标记为 PFAIL
        assertTrue(node.isPfail());
    }

    @Test
    @DisplayName("测试确认节点 FAIL - 单节点集群")
    void testConfirmNodeFailSingleNode() {
        // 测试只有一个主节点的情况
        // 在这个场景下，masterCount = 1，majority = 1
        // 本节点标记 PFAIL 即可确认 FAIL
        
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        node.addState(ClusterNodeState.SLAVE);  // 设置为从节点，这样只有一个主节点（myNode）
        node.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(node);

        // 只有一个主节点（myNode），需要 1 票即可确认 FAIL
        failureDetector.recordPfailVote(node.getNodeId(), myNode.getNodeId());
        
        boolean result = failureDetector.confirmNodeFail(node.getNodeId());

        assertTrue(result);
        assertTrue(node.isFail());
        assertFalse(node.isPfail());
    }

    @Test
    @DisplayName("测试确认节点 FAIL - 多节点集群需要多数投票")
    void testConfirmNodeFailMultiNode() {
        // 添加多个主节点
        for (int i = 1; i <= 5; i++) {
            ClusterNode node = createTestNode(
                    String.format("%040d", i),
                    "127.0.0.1",
                    6380 + i,
                    16380 + i
            );
            node.addState(ClusterNodeState.MASTER);
            clusterConfig.addNode(node);
        }

        // 创建一个要被标记为 FAIL 的节点
        ClusterNode targetNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6390, 16390);
        targetNode.addState(ClusterNodeState.MASTER);
        targetNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(targetNode);

        // 记录足够的投票（需要多数：7个主节点，需要4票）
        failureDetector.recordPfailVote(targetNode.getNodeId(), myNode.getNodeId());
        failureDetector.recordPfailVote(targetNode.getNodeId(), String.format("%040d", 1));
        failureDetector.recordPfailVote(targetNode.getNodeId(), String.format("%040d", 2));
        failureDetector.recordPfailVote(targetNode.getNodeId(), String.format("%040d", 3));

        boolean result = failureDetector.confirmNodeFail(targetNode.getNodeId());

        assertTrue(result);
        assertTrue(targetNode.isFail());
    }

    @Test
    @DisplayName("测试多数投票条件判断")
    void testIsMajorityAgreed() {
        // 添加多个主节点
        for (int i = 1; i <= 3; i++) {
            ClusterNode node = createTestNode(
                    String.format("%040d", i),
                    "127.0.0.1",
                    6380 + i,
                    16380 + i
            );
            node.addState(ClusterNodeState.MASTER);
            clusterConfig.addNode(node);
        }

        String targetNodeId = "cccccccccccccccccccccccccccccccccccccccc";

        // 4个主节点，需要3票（多数）
        assertFalse(failureDetector.isMajorityAgreed(targetNodeId));

        // 记录2票
        failureDetector.recordPfailVote(targetNodeId, myNode.getNodeId());
        failureDetector.recordPfailVote(targetNodeId, String.format("%040d", 1));
        assertFalse(failureDetector.isMajorityAgreed(targetNodeId));

        // 记录第3票
        failureDetector.recordPfailVote(targetNodeId, String.format("%040d", 2));
        assertTrue(failureDetector.isMajorityAgreed(targetNodeId));
    }

    @Test
    @DisplayName("测试清除节点 FAIL 状态")
    void testClearNodeFailState() {
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        node.addState(ClusterNodeState.MASTER);
        node.addState(ClusterNodeState.FAIL);
        clusterConfig.addNode(node);

        failureDetector.clearNodeFailState(node.getNodeId());

        assertFalse(node.isFail());
        assertFalse(node.isPfail());
    }

    @Test
    @DisplayName("测试清除节点 PFAIL 状态")
    void testClearNodePfailState() {
        ClusterNode node = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        node.addState(ClusterNodeState.MASTER);
        node.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(node);

        failureDetector.clearNodeFailState(node.getNodeId());

        assertFalse(node.isPfail());
    }

    @Test
    @DisplayName("测试获取 PFAIL 节点列表")
    void testGetPfailNodes() {
        // 添加 PFAIL 节点
        ClusterNode pfailNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        pfailNode.addState(ClusterNodeState.MASTER);
        pfailNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(pfailNode);

        // 添加 FAIL 节点
        ClusterNode failNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6381, 16381);
        failNode.addState(ClusterNodeState.MASTER);
        failNode.addState(ClusterNodeState.FAIL);
        clusterConfig.addNode(failNode);

        // 添加正常节点
        ClusterNode normalNode = createTestNode("dddddddddddddddddddddddddddddddddddddddd", "127.0.0.1", 6382, 16382);
        normalNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(normalNode);

        Set<String> pfailNodes = failureDetector.getPfailNodes();

        assertEquals(1, pfailNodes.size());
        assertTrue(pfailNodes.contains(pfailNode.getNodeId()));
    }

    @Test
    @DisplayName("测试获取 FAIL 节点列表")
    void testGetFailNodes() {
        // 添加 PFAIL 节点
        ClusterNode pfailNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        pfailNode.addState(ClusterNodeState.MASTER);
        pfailNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(pfailNode);

        // 添加 FAIL 节点
        ClusterNode failNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6381, 16381);
        failNode.addState(ClusterNodeState.MASTER);
        failNode.addState(ClusterNodeState.FAIL);
        clusterConfig.addNode(failNode);

        Set<String> failNodes = failureDetector.getFailNodes();

        assertEquals(1, failNodes.size());
        assertTrue(failNodes.contains(failNode.getNodeId()));
    }

    @Test
    @DisplayName("测试重置故障检测器")
    void testReset() {
        // 添加投票记录
        failureDetector.recordPfailVote("test-node-id", myNode.getNodeId());

        failureDetector.reset();

        assertEquals(0, failureDetector.getPfailVoteCount("test-node-id"));
    }

    @Test
    @DisplayName("测试获取需要广播 FAIL 的节点")
    void testGetNodesToBroadcastFail() {
        // 添加多个主节点
        for (int i = 1; i <= 3; i++) {
            ClusterNode node = createTestNode(
                    String.format("%040d", i),
                    "127.0.0.1",
                    6380 + i,
                    16380 + i
            );
            node.addState(ClusterNodeState.MASTER);
            clusterConfig.addNode(node);
        }

        // 创建一个满足 FAIL 条件的节点
        ClusterNode targetNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6390, 16390);
        targetNode.addState(ClusterNodeState.MASTER);
        targetNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(targetNode);

        // 记录足够的投票
        failureDetector.recordPfailVote(targetNode.getNodeId(), myNode.getNodeId());
        failureDetector.recordPfailVote(targetNode.getNodeId(), String.format("%040d", 1));
        failureDetector.recordPfailVote(targetNode.getNodeId(), String.format("%040d", 2));

        Set<String> nodesToBroadcast = failureDetector.getNodesToBroadcastFail();

        assertTrue(nodesToBroadcast.contains(targetNode.getNodeId()));
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
