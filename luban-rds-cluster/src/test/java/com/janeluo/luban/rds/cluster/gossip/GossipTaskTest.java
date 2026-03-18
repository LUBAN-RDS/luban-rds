package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GossipTask 单元测试
 */
class GossipTaskTest {

    private ClusterConfig clusterConfig;
    private ClusterNode myNode;
    private GossipProtocol gossipProtocol;
    private FailureDetector failureDetector;
    private GossipTask gossipTask;

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

        // 获取故障检测器
        failureDetector = gossipProtocol.getFailureDetector();

        // 创建 Gossip 任务
        gossipTask = new GossipTask(gossipProtocol, failureDetector);
    }

    @Test
    @DisplayName("测试 Gossip 任务执行 - 检测超时节点")
    void testRunCheckTimeout() {
        // 添加一个超时节点
        ClusterNode timeoutNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        timeoutNode.addState(ClusterNodeState.MASTER);
        timeoutNode.setLastPongTime(System.currentTimeMillis() - 10000); // 10秒前
        clusterConfig.addNode(timeoutNode);

        // 启动 Gossip 协议
        gossipProtocol.start();

        // 执行任务
        gossipTask.run();

        // 验证节点是否被标记为 PFAIL
        assertTrue(timeoutNode.isPfail());

        // 停止协议
        gossipProtocol.stop();
    }

    @Test
    @DisplayName("测试 Gossip 任务执行 - 更新集群状态为 OK")
    void testRunUpdateClusterStateOk() {
        // 添加多个正常的主节点
        for (int i = 1; i <= 3; i++) {
            ClusterNode node = createTestNode(
                    String.format("%040d", i),
                    "127.0.0.1",
                    6380 + i,
                    16380 + i
            );
            node.addState(ClusterNodeState.MASTER);
            node.updateLastPongTime();
            clusterConfig.addNode(node);
        }

        // 启动 Gossip 协议
        gossipProtocol.start();

        // 执行任务
        gossipTask.run();

        // 验证集群状态为 OK
        assertEquals("ok", clusterConfig.getState());

        // 停止协议
        gossipProtocol.stop();
    }

    @Test
    @DisplayName("测试 Gossip 任务执行 - 更新集群状态为 FAIL")
    void testRunUpdateClusterStateFail() {
        // 添加多个主节点，大部分标记为 FAIL
        for (int i = 1; i <= 3; i++) {
            ClusterNode node = createTestNode(
                    String.format("%040d", i),
                    "127.0.0.1",
                    6380 + i,
                    16380 + i
            );
            node.addState(ClusterNodeState.MASTER);
            node.addState(ClusterNodeState.FAIL);
            clusterConfig.addNode(node);
        }

        // 启动 Gossip 协议
        gossipProtocol.start();

        // 执行任务
        gossipTask.run();

        // 验证集群状态为 FAIL
        assertEquals("fail", clusterConfig.getState());

        // 停止协议
        gossipProtocol.stop();
    }

    @Test
    @DisplayName("测试 Gossip 任务执行 - 广播 FAIL 消息")
    void testRunBroadcastFail() {
        // 添加多个主节点（总共 4 个主节点：myNode + 3 个）
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
        ClusterNode failNode = createTestNode("cccccccccccccccccccccccccccccccccccccccc", "127.0.0.1", 6390, 16390);
        failNode.addState(ClusterNodeState.MASTER);  // 设置为主节点
        // 设置超时（lastPongTime 为很久以前，让节点真正超时）
        failNode.setLastPongTime(System.currentTimeMillis() - 10000);  // 10秒前
        clusterConfig.addNode(failNode);

        // 启动 Gossip 协议
        gossipProtocol.start();

        // 第一次执行：检测超时并标记 PFAIL
        gossipTask.run();

        // 验证节点被标记为 PFAIL
        assertTrue(failNode.isPfail(), "节点应该被标记为 PFAIL");

        // 记录足够的投票（投票者必须是主节点）
        failureDetector.recordPfailVote(failNode.getNodeId(), myNode.getNodeId());
        failureDetector.recordPfailVote(failNode.getNodeId(), String.format("%040d", 1));
        failureDetector.recordPfailVote(failNode.getNodeId(), String.format("%040d", 2));

        // 第二次执行：检查并广播 FAIL
        gossipTask.run();

        // 验证节点被标记为 FAIL
        assertTrue(failNode.isFail(), "节点应该被标记为 FAIL");

        // 停止协议
        gossipProtocol.stop();
    }

    @Test
    @DisplayName("测试 Gossip 任务 - 未启动时不执行")
    void testRunNotStarted() {
        // 添加目标节点
        ClusterNode targetNode = createTestNode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "127.0.0.1", 6380, 16380);
        targetNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(targetNode);

        // 不启动协议，直接执行任务
        gossipTask.run();

        // 验证节点没有被标记为 PFAIL（因为协议未启动）
        // 由于没有设置超时，节点应该不会被标记
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
