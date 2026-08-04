package com.janeluo.luban.rds.server.cluster;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.server.RedisServerHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群模式事务（WATCH/MULTI/EXEC）与流命令（XREAD/XREADGROUP/XINFO）路由校验测试（N-16/N-17）。
 * <p>
 * 回归保护：
 * <ul>
 *   <li>N-16：WATCH 在错误节点监视不存在键、EXEC 静默执行跨槽事务（写后键"消失"）；</li>
 *   <li>N-17：XREAD/XREADGROUP/XINFO 默认分支取 args[1]（COUNT/GROUP/STREAM 等关键字）
 *       作路由键，已实现命令在集群中被 MOVED 到无关节点静默返回空、多流 CROSSSLOT 不校验。</li>
 * </ul>
 * </p>
 */
class ClusterTransactionAndStreamRoutingTest extends AbstractClusterHandlerTest {

    private static final String LOCAL_TAG = "{local-tag}";
    private static final String REMOTE_TAG = "{remote-tag}";

    /**
     * 创建双节点集群通道：localKey 的槽位归属本节点（NODE_ID_1），
     * remoteKey 的槽位归属远端节点（NODE_ID_2，127.0.0.1:7001）。
     */
    private EmbeddedChannel createChannel(String localKey, String remoteKey) {
        int localSlot = SlotUtils.keyHashSlot(localKey);
        int remoteSlot = SlotUtils.keyHashSlot(remoteKey);
        assertFalse(localSlot == remoteSlot, "前置条件：两个 key 应不同 slot");

        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);

        ClusterNode myNode = new ClusterNode(NODE_ID_1);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        myNode.setBusPort(17000);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);

        ClusterNode remoteNode = new ClusterNode(NODE_ID_2);
        remoteNode.setIp("127.0.0.1");
        remoteNode.setPort(7001);
        remoteNode.setBusPort(17001);
        remoteNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(remoteNode);

        clusterConfig.setSlotOwner(localSlot, NODE_ID_1);
        clusterConfig.setSlotOwner(remoteSlot, NODE_ID_2);
        // P1-13：命令路由门控依赖 cluster_state=ok。
        clusterConfig.setState("ok");

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        ClusterStateManager stateManager = new ClusterStateManager(clusterConfig);
        ClusterCommandHandler clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null, null, null);

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, protocolParser, 0,
                true, clusterConfig, slotManager);
        handler.setClusterCommandHandler(clusterCommandHandler);

        return new EmbeddedChannel(handler);
    }

    // ==================== N-17：XREAD/XREADGROUP/XINFO 键提取 ====================

    @Test
    @DisplayName("N-17：XREAD 本地槽位键不被 MOVED（COUNT 关键字不再被当路由键）")
    void testXReadLocalSlotKeyNotMoved() {
        String localKey = "stream:" + LOCAL_TAG;
        String remoteKey = "other:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        String response = sendCommand(channel, "XREAD", "COUNT", "10", "STREAMS", localKey, "0");

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "本地槽位键不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "单流不应 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("N-17：XREAD 远端槽位键按流键路由返回 -MOVED 到正确节点")
    void testXReadRemoteSlotKeyMovedToCorrectNode() {
        String localKey = "other:" + LOCAL_TAG;
        String remoteKey = "stream:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);
        int remoteSlot = SlotUtils.keyHashSlot(remoteKey);

        String response = sendCommand(channel, "XREAD", "COUNT", "10", "STREAMS", remoteKey, "0");

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-MOVED " + remoteSlot + " 127.0.0.1:7001"),
                "应按流键槽位路由到正确节点，实际: " + response);
    }

    @Test
    @DisplayName("N-17：XREAD 多流跨槽返回 -CROSSSLOT")
    void testXReadCrossSlotStreamsRejected() {
        String localKey = "s1:" + LOCAL_TAG;
        String remoteKey = "s2:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        String response = sendCommand(channel, "XREAD", "COUNT", "10", "STREAMS", localKey, remoteKey, "0", "0");

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT"), "多流跨槽应 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("N-17：XREADGROUP 本地槽位键不被 MOVED（GROUP 关键字不再被当路由键）")
    void testXReadGroupLocalSlotKeyNotMoved() {
        String localKey = "group-stream:" + LOCAL_TAG;
        String remoteKey = "other:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        String response = sendCommand(channel, "XREADGROUP", "GROUP", "g", "consumer",
                "COUNT", "10", "STREAMS", localKey, "0");

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "本地槽位键不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "单流不应 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("N-17：XINFO STREAM 本地槽位键不被 MOVED（子命令名不再被当路由键）")
    void testXInfoStreamLocalSlotKeyNotMoved() {
        String localKey = "info-stream:" + LOCAL_TAG;
        String remoteKey = "other:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        String response = sendCommand(channel, "XINFO", "STREAM", localKey);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "本地槽位键不应 MOVED，实际: " + response);
    }

    // ==================== N-16：WATCH/MULTI/EXEC 集群校验 ====================

    @Test
    @DisplayName("N-16：WATCH 远端槽位键返回 -MOVED（不再在错误节点监视）")
    void testWatchRemoteSlotKeyMoved() {
        String localKey = "watch-local:" + LOCAL_TAG;
        String remoteKey = "watch-remote:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);
        int remoteSlot = SlotUtils.keyHashSlot(remoteKey);

        String response = sendCommand(channel, "WATCH", remoteKey);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-MOVED " + remoteSlot + " 127.0.0.1:7001"),
                "WATCH 远端键应 MOVED，实际: " + response);
    }

    @Test
    @DisplayName("N-16：WATCH 本地槽位键正常 OK")
    void testWatchLocalSlotKeyAccepted() {
        String localKey = "watch-local:" + LOCAL_TAG;
        String remoteKey = "other:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        String response = sendCommand(channel, "WATCH", localKey);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("+OK"), "WATCH 本地键应 OK，实际: " + response);
    }

    @Test
    @DisplayName("N-16：EXEC 含远端槽位命令时整个事务 -MOVED 中止并丢弃")
    void testExecAbortsOnMoved() {
        String localKey = "tx-local:" + LOCAL_TAG;
        String remoteKey = "tx-remote:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);
        int remoteSlot = SlotUtils.keyHashSlot(remoteKey);

        sendCommand(channel, "MULTI");
        String queued = sendCommand(channel, "GET", remoteKey);
        assertTrue(queued != null && queued.contains("QUEUED"), "入队应返回 QUEUED，实际: " + queued);

        String execResp = sendCommand(channel, "EXEC");
        assertNotNull(execResp, "响应不应为 null");
        assertTrue(execResp.startsWith("-MOVED " + remoteSlot + " 127.0.0.1:7001"),
                "事务含远端槽位命令应整体 MOVED 中止，实际: " + execResp);

        // 事务已丢弃：后续命令按普通命令处理
        String ping = sendCommand(channel, "PING");
        assertTrue(ping != null && ping.startsWith("+PONG"), "事务丢弃后应正常处理后续命令");
    }

    @Test
    @DisplayName("N-16：EXEC 含跨槽多键命令时整个事务 -CROSSSLOT 中止")
    void testExecAbortsOnCrossSlot() {
        String localKey = "tx-local:" + LOCAL_TAG;
        String remoteKey = "tx-remote:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        sendCommand(channel, "MULTI");
        String queued = sendCommand(channel, "MGET", localKey, remoteKey);
        assertTrue(queued != null && queued.contains("QUEUED"), "入队应返回 QUEUED，实际: " + queued);

        String execResp = sendCommand(channel, "EXEC");
        assertNotNull(execResp, "响应不应为 null");
        assertTrue(execResp.startsWith("-CROSSSLOT"),
                "事务含跨槽多键命令应 CROSSSLOT 中止，实际: " + execResp);
    }

    @Test
    @DisplayName("N-16：EXEC 全部本地槽位命令正常执行（不误杀）")
    void testExecLocalSlotCommandsExecute() {
        String localKey = "tx-local:" + LOCAL_TAG;
        String remoteKey = "other:" + REMOTE_TAG;
        EmbeddedChannel channel = createChannel(localKey, remoteKey);

        sendCommand(channel, "MULTI");
        sendCommand(channel, "GET", localKey);

        String execResp = sendCommand(channel, "EXEC");
        assertNotNull(execResp, "响应不应为 null");
        assertFalse(execResp.startsWith("-MOVED"), "全部本地槽位不应 MOVED，实际: " + execResp);
        assertFalse(execResp.startsWith("-CROSSSLOT"), "单键不应 CROSSSLOT，实际: " + execResp);
        assertFalse(execResp.startsWith("-EXECABORT"), "不应 EXECABORT，实际: " + execResp);
    }
}
