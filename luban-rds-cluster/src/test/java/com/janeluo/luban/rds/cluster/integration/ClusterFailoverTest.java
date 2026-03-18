package com.janeluo.luban.rds.cluster.integration;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.gossip.FailureDetector;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障转移测试
 * 测试集群故障检测和故障转移机制
 */
class ClusterFailoverTest {

    private ClusterConfig config;
    private SlotManager slotManager;
    private ClusterStateManager stateManager;
    private ClusterCommandHandler commandHandler;
    private FailureDetector failureDetector;

    // 测试节点ID
    private static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    private static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
    private static final String NODE_ID_4 = "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0";

    // 节点超时时间（毫秒）
    private static final long NODE_TIMEOUT = 15000;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        commandHandler = new ClusterCommandHandler(config, slotManager, stateManager, null);
        failureDetector = new FailureDetector(config, NODE_TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        config.reset();
        failureDetector.reset();
    }

    @Test
    @DisplayName("测试节点超时检测")
    void testNodeTimeoutDetection() {
        // 创建主节点集群
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(NODE_ID_1);

        // 分配槽位
        assignSlotRange(node1, 0, 8191);
        assignSlotRange(node2, 8192, 16383);

        // 初始状态：节点2正常
        node2.updateLastPongTime();
        assertFalse(node2.isPfail(), "节点2初始状态不应为PFAIL");
        assertFalse(node2.isFail(), "节点2初始状态不应为FAIL");

        // 模拟节点超时：设置最后PONG时间为很久之前
        node2.setLastPongTime(System.currentTimeMillis() - NODE_TIMEOUT - 1000);

        // 执行超时检测
        failureDetector.checkNodeTimeout();

        // 验证标记为 PFAIL
        assertTrue(node2.isPfail(), "超时节点应该被标记为PFAIL");
        assertFalse(node2.isFail(), "节点不应直接标记为FAIL");

        // 验证投票记录
        int voteCount = failureDetector.getPfailVoteCount(NODE_ID_2);
        assertTrue(voteCount >= 1, "应该记录PFAIL投票");
    }

    @Test
    @DisplayName("测试节点恢复正常")
    void testNodeRecovery() {
        // 创建节点
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(NODE_ID_1);

        // 设置节点为 PFAIL 状态
        node2.addState(ClusterNodeState.PFAIL);
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_1);

        // 模拟节点恢复
        node2.setLastPongTime(System.currentTimeMillis());
        node2.updateLastPongTime();

        // 执行超时检测
        failureDetector.checkNodeTimeout();

        // 验证 PFAIL 状态已清除
        assertFalse(node2.isPfail(), "恢复的节点应该清除PFAIL状态");
        assertFalse(node2.isFail(), "恢复的节点不应有FAIL状态");
    }

    @Test
    @DisplayName("测试 FAIL 状态传播")
    void testFailStatePropagation() {
        // 创建三主节点集群（需要多数投票）
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        ClusterNode node3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);

        config.addNode(node1);
        config.addNode(node2);
        config.addNode(node3);
        config.setMyNodeId(NODE_ID_1);

        // 分配槽位
        assignSlotRange(node1, 0, 5461);
        assignSlotRange(node2, 5462, 10922);
        assignSlotRange(node3, 10923, 16383);

        // 设置 node2 为 PFAIL
        node2.addState(ClusterNodeState.PFAIL);

        // 模拟多数节点确认 PFAIL（需要2/3以上同意）
        // node1 和 node3 都标记 node2 为 PFAIL
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_1);
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_3);

        // 验证达到多数条件
        assertTrue(failureDetector.isMajorityAgreed(NODE_ID_2), "应该达到多数同意条件");

        // 确认节点 FAIL
        boolean confirmed = failureDetector.confirmNodeFail(NODE_ID_2);

        // 验证标记为 FAIL
        assertTrue(confirmed, "节点应该被确认FAIL");
        assertTrue(node2.isFail(), "节点应该有FAIL状态");
        assertFalse(node2.isPfail(), "节点不应再有PFAIL状态");

        // 验证 FAIL 节点列表
        Set<String> failNodes = failureDetector.getFailNodes();
        assertTrue(failNodes.contains(NODE_ID_2), "FAIL节点列表应包含node2");
    }

    @Test
    @DisplayName("测试单节点集群的 FAIL 确认")
    void testSingleNodeClusterFailConfirm() {
        // 创建单节点集群
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);

        config.addNode(node1);
        config.setMyNodeId(NODE_ID_1);

        // 单节点集群无法进行故障检测（没有其他节点）
        assertEquals(1, config.getMasterCount());
        assertEquals(1, config.getNodeCount());
    }

    @Test
    @DisplayName("测试手动故障转移")
    void testManualFailover() {
        // 创建主从节点
        ClusterNode master = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        ClusterNode slave = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        slave.removeState(ClusterNodeState.MASTER);
        slave.addState(ClusterNodeState.SLAVE);
        slave.addState(ClusterNodeState.MYSELF);
        slave.setMasterNodeId(NODE_ID_1);

        config.addNode(master);
        config.addNode(slave);
        config.setMyNodeId(NODE_ID_2);
        slotManager.setMyNodeId(NODE_ID_2);

        // 分配槽位给主节点
        assignSlotRange(master, 0, 16383);

        // 使用 CLUSTER FAILOVER 命令
        String response = commandHandler.handle(new String[]{"FAILOVER"});
        assertTrue(response.contains("+OK"), "FAILOVER命令应该返回OK");

        // 验证从节点提升为主节点
        assertTrue(slave.isMaster(), "从节点应该被提升为主节点");
        assertFalse(slave.isSlave(), "节点不应再是从节点");
        assertNull(slave.getMasterNodeId(), "新主节点不应有主节点ID");

        // 验证原主节点变为从节点
        assertTrue(master.isSlave(), "原主节点应该变为从节点");
        assertEquals(NODE_ID_2, master.getMasterNodeId(), "原主节点应该指向新主节点");

        // 验证槽位已转移
        assertEquals(16384, slave.getSlotCount(), "新主节点应该拥有所有槽位");
        assertEquals(0, master.getSlotCount(), "原主节点不应有槽位");
    }

    @Test
    @DisplayName("测试强制故障转移")
    void testForceFailover() {
        // 创建主从节点
        ClusterNode master = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        // 主节点标记为 FAIL
        master.addState(ClusterNodeState.FAIL);

        ClusterNode slave = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        slave.removeState(ClusterNodeState.MASTER);
        slave.addState(ClusterNodeState.SLAVE);
        slave.addState(ClusterNodeState.MYSELF);
        slave.setMasterNodeId(NODE_ID_1);

        config.addNode(master);
        config.addNode(slave);
        config.setMyNodeId(NODE_ID_2);
        slotManager.setMyNodeId(NODE_ID_2);

        // 分配槽位给主节点
        assignSlotRange(master, 0, 16383);

        // 使用 CLUSTER FAILOVER FORCE 命令
        String response = commandHandler.handle(new String[]{"FAILOVER", "FORCE"});
        assertTrue(response.contains("+OK"), "FAILOVER FORCE命令应该返回OK");

        // 验证故障转移成功
        assertTrue(slave.isMaster(), "从节点应该被提升为主节点");
    }

    @Test
    @DisplayName("测试 TAKEOVER 故障转移")
    void testTakeoverFailover() {
        // 创建主从节点
        ClusterNode master = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        ClusterNode slave = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        slave.removeState(ClusterNodeState.MASTER);
        slave.addState(ClusterNodeState.SLAVE);
        slave.addState(ClusterNodeState.MYSELF);
        slave.setMasterNodeId(NODE_ID_1);

        config.addNode(master);
        config.addNode(slave);
        config.setMyNodeId(NODE_ID_2);
        slotManager.setMyNodeId(NODE_ID_2);

        // 分配槽位给主节点
        assignSlotRange(master, 0, 16383);

        // 使用 CLUSTER FAILOVER TAKEOVER 命令
        String response = commandHandler.handle(new String[]{"FAILOVER", "TAKEOVER"});
        assertTrue(response.contains("+OK"), "FAILOVER TAKEOVER命令应该返回OK");

        // 验证故障转移成功
        assertTrue(slave.isMaster(), "从节点应该被提升为主节点");
        assertEquals(16384, slave.getSlotCount(), "新主节点应该拥有所有槽位");
    }

    @Test
    @DisplayName("测试主节点不能执行故障转移")
    void testMasterCannotFailover() {
        // 创建主节点
        ClusterNode master = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        master.addState(ClusterNodeState.MYSELF);

        config.addNode(master);
        config.setMyNodeId(NODE_ID_1);

        // 主节点尝试执行故障转移
        String response = commandHandler.handle(new String[]{"FAILOVER"});
        assertTrue(response.contains("-ERR"), "主节点执行FAILOVER应该返回错误");
        assertTrue(response.contains("slave"), "错误信息应提示需要从节点执行");
    }

    @Test
    @DisplayName("测试集群状态更新")
    void testClusterStateUpdate() {
        // 创建三主节点集群
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        ClusterNode node3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);

        config.addNode(node1);
        config.addNode(node2);
        config.addNode(node3);
        config.setMyNodeId(NODE_ID_1);

        // 分配所有槽位
        assignSlotRange(node1, 0, 5461);
        assignSlotRange(node2, 5462, 10922);
        assignSlotRange(node3, 10923, 16383);

        // 集群状态应该为 OK
        stateManager.updateClusterState();
        assertTrue(stateManager.isClusterOk(), "集群状态应该为OK");

        // 标记一个主节点为 FAIL
        node2.addState(ClusterNodeState.FAIL);

        // 集群状态应该变为 FAIL
        stateManager.updateClusterState();
        assertFalse(stateManager.isClusterOk(), "有主节点FAIL时集群状态应该为FAIL");

        // 验证不可用槽位数量
        int unavailableSlots = stateManager.getUnavailableSlotCount();
        assertEquals(5461, unavailableSlots, "应该有5461个不可用槽位");
    }

    @Test
    @DisplayName("测试故障转移条件检查")
    void testFailoverConditionCheck() {
        // 创建三主节点集群
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        ClusterNode node3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);

        config.addNode(node1);
        config.addNode(node2);
        config.addNode(node3);
        config.setMyNodeId(NODE_ID_1);

        // 所有节点可用，可以故障转移
        assertTrue(stateManager.canFailover(), "所有节点可用时应该可以故障转移");

        // 标记两个节点为 FAIL（超过半数）
        node2.addState(ClusterNodeState.FAIL);
        node3.addState(ClusterNodeState.FAIL);

        // 不可进行故障转移
        assertFalse(stateManager.canFailover(), "超过半数节点不可用时不应能故障转移");
    }

    @Test
    @DisplayName("测试 PFAIL 和 FAIL 状态转换")
    void testPfailToFailTransition() {
        // 创建三主节点集群
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        ClusterNode node3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);

        config.addNode(node1);
        config.addNode(node2);
        config.addNode(node3);
        config.setMyNodeId(NODE_ID_1);

        // 初始状态
        assertFalse(node2.isPfail());
        assertFalse(node2.isFail());

        // 设置为 PFAIL
        node2.addState(ClusterNodeState.PFAIL);
        assertTrue(node2.isPfail());
        assertFalse(node2.isFail());

        // 记录投票
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_1);
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_3);

        // 确认为 FAIL
        failureDetector.confirmNodeFail(NODE_ID_2);
        assertFalse(node2.isPfail(), "FAIL状态应该覆盖PFAIL");
        assertTrue(node2.isFail());

        // 验证状态统计
        assertEquals(1, stateManager.getFailNodeCount());
        assertEquals(0, stateManager.getPfailNodeCount());
    }

    @Test
    @DisplayName("测试清除故障状态")
    void testClearFailState() {
        // 创建节点
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(NODE_ID_1);

        // 设置节点为 FAIL 状态
        node2.addState(ClusterNodeState.FAIL);
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_1);

        // 清除故障状态
        failureDetector.clearNodeFailState(NODE_ID_2);

        // 验证状态已清除
        assertFalse(node2.isFail(), "FAIL状态应该被清除");
        assertFalse(node2.isPfail(), "PFAIL状态应该被清除");

        // 验证投票记录已清除
        assertEquals(0, failureDetector.getPfailVoteCount(NODE_ID_2));
    }

    @Test
    @DisplayName("测试获取需要广播 FAIL 的节点")
    void testGetNodesToBroadcastFail() {
        // 创建三主节点集群
        ClusterNode node1 = createMasterNode(NODE_ID_1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(NODE_ID_2, "127.0.0.1", 7001);
        ClusterNode node3 = createMasterNode(NODE_ID_3, "127.0.0.1", 7002);

        config.addNode(node1);
        config.addNode(node2);
        config.addNode(node3);
        config.setMyNodeId(NODE_ID_1);

        // 设置 node2 为 PFAIL
        node2.addState(ClusterNodeState.PFAIL);
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_1);
        failureDetector.recordPfailVote(NODE_ID_2, NODE_ID_3);

        // 获取需要广播 FAIL 的节点
        Set<String> toBroadcast = failureDetector.getNodesToBroadcastFail();
        assertTrue(toBroadcast.contains(NODE_ID_2), "node2应该在广播列表中");

        // 确认 FAIL 后
        failureDetector.confirmNodeFail(NODE_ID_2);

        // 再次获取，应该不在列表中
        Set<String> afterConfirm = failureDetector.getNodesToBroadcastFail();
        assertFalse(afterConfirm.contains(NODE_ID_2), "已确认FAIL的节点不应再在广播列表中");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建主节点
     */
    private ClusterNode createMasterNode(String nodeId, String ip, int port) {
        ClusterNode node = new ClusterNode(nodeId);
        node.setIp(ip);
        node.setPort(port);
        node.setBusPort(port + 10000);
        node.addState(ClusterNodeState.MASTER);
        node.updateLastPongTime();
        return node;
    }

    /**
     * 分配槽位范围给节点
     */
    private void assignSlotRange(ClusterNode node, int start, int end) {
        node.addSlotRange(start, end);
        for (int i = start; i <= end; i++) {
            config.setSlotOwner(i, node.getNodeId());
        }
    }
}
