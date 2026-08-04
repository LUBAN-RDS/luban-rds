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
 * 集群模式 CROSSSLOT 校验测试（Task C1）。
 * <p>
 * 对齐 Redis 7 语义：多键命令的所有键必须 hash 到同一 slot，否则返回
 * {@code -CROSSSLOT Keys in request don't hash to the same slot}。
 * </p>
 */
class ClusterCrossSlotTest extends AbstractClusterHandlerTest {

    // ==================== 同 slot 多键：被接受 ====================

    @Test
    @DisplayName("MGET 多键同 slot（hash tag）时正常执行，无 MOVED/CROSSSLOT")
    void testMultiKeySameSlot_MGET_accepted() {
        String key1 = "session:info:{tag-shared}";
        String key2 = "session:attr:{tag-shared}";
        assertTrue(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "MGET", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "同 slot 本地不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "同 slot 不应 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("MGET 单键不应触发 CROSSSLOT 校验")
    void testSingleKeyNotCrossSlot_MGET_accepted() {
        String key = "session:info:{single-key}";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "MGET", key);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-CROSSSLOT"), "单键不应 CROSSSLOT，实际: " + response);
        assertFalse(response.startsWith("-MOVED"), "本地槽位不应 MOVED，实际: " + response);
    }

    // ==================== 跨 slot 多键：被拒绝 ====================

    @Test
    @DisplayName("MGET 跨 slot 多键应返回 -CROSSSLOT")
    void testMultiKeyCrossSlot_MGET_rejected() {
        String key1 = "session:info:{a-tag}";
        String key2 = "session:attr:{b-tag}";
        assertFalse(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应不同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "MGET", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT "),
                "跨 slot MGET 应返回 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("MSET 跨 slot 多键应返回 -CROSSSLOT")
    void testMultiKeyCrossSlot_MSET_rejected() {
        String key1 = "session:info:{a-mset}";
        String key2 = "session:attr:{b-mset}";
        assertFalse(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应不同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "MSET", key1, "v1", key2, "v2");

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT "),
                "跨 slot MSET 应返回 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("DEL 跨 slot 多键应返回 -CROSSSLOT")
    void testMultiKeyCrossSlot_DEL_rejected() {
        String key1 = "session:info:{a-del}";
        String key2 = "session:attr:{b-del}";
        assertFalse(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应不同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "DEL", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT "),
                "跨 slot DEL 应返回 CROSSSLOT，实际: " + response);
    }

    // ==================== RENAME：src + dst 不同 slot 应被拒 ====================

    @Test
    @DisplayName("RENAME 源/目标不同 slot 应返回 -CROSSSLOT")
    void testRenameCrossSlot_rejected() {
        String src = "session:info:{rename-src}";
        String dst = "session:attr:{rename-dst}";
        assertFalse(SlotUtils.keyHashSlot(src) == SlotUtils.keyHashSlot(dst),
                "前置条件：src/dst 应不同 slot");
        int slot = SlotUtils.keyHashSlot(src);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "RENAME", src, dst);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT "),
                "RENAME 源/目标不同 slot 应返回 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("RENAME 源/目标同 slot 应被接受（不返回 CROSSSLOT/MOVED）")
    void testRenameSameSlot_accepted() {
        String src = "session:info:{tag-shared-rename}";
        String dst = "session:attr:{tag-shared-rename}";
        assertTrue(SlotUtils.keyHashSlot(src) == SlotUtils.keyHashSlot(dst),
                "前置条件：src/dst 应同 slot");
        int slot = SlotUtils.keyHashSlot(src);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "RENAME", src, dst);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-CROSSSLOT"),
                "同 slot RENAME 不应 CROSSSLOT，实际: " + response);
        assertFalse(response.startsWith("-MOVED"),
                "本地槽位不应 MOVED，实际: " + response);
    }

    // ==================== 非集群模式：跳过 CROSSSLOT 校验 ====================

    @Test
    @DisplayName("非集群模式不进行 CROSSSLOT 校验，跨 slot MGET 正常执行")
    void testNonClusterMode_noCrossSlotCheck() {
        EmbeddedChannel channel = createNonClusterChannel();
        String key1 = "session:info:{standalone-a}";
        String key2 = "session:attr:{standalone-b}";
        assertFalse(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应不同 slot（cluster 模式下本应被拒）");

        String response = sendCommand(channel, "MGET", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-CROSSSLOT"),
                "非集群模式不应 CROSSSLOT，实际: " + response);
        assertFalse(response.startsWith("-MOVED"),
                "非集群模式不应 MOVED，实际: " + response);
    }

    // ==================== EVAL 回归：checkCrossSlotForScript 仍生效 ====================

    @Test
    @DisplayName("EVAL 多 key 跨 slot 仍由 checkCrossSlotForScript 拒绝（向后兼容回归）")
    void testEvalCrossSlotStillWorks() {
        String key1 = "session:info:{eval-a}";
        String key2 = "session:attr:{eval-b}";
        assertFalse(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应不同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String script = "redis.call('SET', KEYS[1], 'v') return redis.call('PEXPIRE', KEYS[2], 1000)";
        String response = sendCommand(channel, "EVAL", script, "2", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT "),
                "EVAL 跨 slot 多 key 应返回 CROSSSLOT，实际: " + response);
    }

    // ==================== 辅助方法：构造不同集群通道 ====================

    /**
     * 创建本地槽位场景的通道：指定 slot 分配给本节点。
     */
    private EmbeddedChannel createChannelWithLocalSlot(int slot) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        slotManager.addSlots(slot);

        return buildChannel(memoryStore, commandHandler, protocolParser, clusterConfig, slotManager);
    }

    private void addMyNode(ClusterConfig clusterConfig) {
        ClusterNode myNode = new ClusterNode(NODE_ID_1);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        myNode.setBusPort(17000);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);
        // P1-13：命令路由门控依赖 cluster_state=ok。
        clusterConfig.setState("ok");
    }

    private EmbeddedChannel buildChannel(MemoryStore memoryStore, DefaultCommandHandler commandHandler,
                                         RedisProtocolParser protocolParser, ClusterConfig clusterConfig,
                                         SlotManager slotManager) {
        ClusterStateManager stateManager = new ClusterStateManager(clusterConfig);
        ClusterCommandHandler clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null, null, null);

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, protocolParser, 0,
                true, clusterConfig, slotManager);
        handler.setClusterCommandHandler(clusterCommandHandler);

        return new EmbeddedChannel(handler);
    }
}