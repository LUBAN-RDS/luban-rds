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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群模式 EVAL/EVALSHA 脚本命令的 slot 重定向与 CROSSSLOT 校验测试。
 * <p>
 * 验证修复：EVAL/EVALSHA 不再跳过集群重定向检查，对齐 Redis 原生集群语义。
 * </p>
 */
class ClusterEvalSlotRedirectTest extends AbstractClusterHandlerTest {

    /** 简单脚本：返回 KEYS[1] 的 PTTL（模拟 RedissonSessionDao.READ_SCRIPT） */
    private static final String READ_SCRIPT = "return redis.call('PTTL', KEYS[1])";
    /** 简单脚本：写入 KEYS[1] 后返回 OK（模拟写操作） */
    private static final String SET_SCRIPT =
            "redis.call('SET', KEYS[1], ARGV[1]) return redis.call('PEXPIRE', KEYS[1], ARGV[2])";

    @Test
    @DisplayName("单 key EVAL：key 在本节点时正常执行")
    void testSingleKeyEvalLocalSlot() {
        String key = "session:info:{local-id}";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "EVAL", READ_SCRIPT, "1", key);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "本地槽位不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "单 key 不应 CROSSSLOT，实际: " + response);
        // PTTL 对不存在的 key 返回 -2
        assertEquals(":-2\r\n", response, "未设置的 key PTTL 应为 -2");
    }

    @Test
    @DisplayName("单 key EVAL：key 不在本节点时返回 -MOVED")
    void testSingleKeyEvalMovedRedirect() {
        String key = "session:info:{moved-id}";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel channel = createChannelWithMovedRedirect(slot);

        String response = sendCommand(channel, "EVAL", READ_SCRIPT, "1", key);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-MOVED "), "应返回 MOVED 重定向，实际: " + response);
        assertTrue(response.contains(slot + " 127.0.0.1:7001"),
                "应包含槽位号和目标节点地址，实际: " + response);
    }

    @Test
    @DisplayName("多 key EVAL：所有 KEYS 同 slot（hash tag）时正常执行")
    void testMultiKeyEvalSameSlot() {
        // 通过 {tag} hash tag 保证两个 key 同 slot
        String key1 = "session:info:{tag1}";
        String key2 = "session:attr:{tag1}";
        assertEquals(SlotUtils.keyHashSlot(key1), SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        // 脚本：SET 两个 key，再 PEXPIRE KEYS[2]，返回 1
        String script = "redis.call('SET', KEYS[1], 'v') redis.call('SET', KEYS[2], 'v') return redis.call('PEXPIRE', KEYS[2], 1000)";
        String response = sendCommand(channel, "EVAL", script, "2", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "本地槽位不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "同 slot 不应 CROSSSLOT，实际: " + response);
        assertEquals(":1\r\n", response, "对已存在的 key PEXPIRE 应返回 1");
    }

    @Test
    @DisplayName("多 key EVAL：KEYS 跨 slot 时返回 -CROSSSLOT")
    void testMultiKeyEvalCrossSlot() {
        String key1 = "session:info:{aaa}";
        String key2 = "session:attr:{bbb}";
        assertFalse(SlotUtils.keyHashSlot(key1) == SlotUtils.keyHashSlot(key2),
                "前置条件：两个 key 应不同 slot");
        int slot = SlotUtils.keyHashSlot(key1);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String script = "redis.call('SET', KEYS[1], 'v') return redis.call('PEXPIRE', KEYS[2], 1000)";
        String response = sendCommand(channel, "EVAL", script, "2", key1, key2);

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-CROSSSLOT "),
                "跨 slot 多 key 脚本应返回 CROSSSLOT，实际: " + response);
    }

    @Test
    @DisplayName("numkeys=0 的 EVAL：不进行重定向，正常执行")
    void testNoKeyEvalNoRedirect() {
        EmbeddedChannel channel = createChannelWithMovedRedirect(0);

        String response = sendCommand(channel, "EVAL", "return 1", "0");

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "无 key 脚本不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "无 key 脚本不应 CROSSSLOT，实际: " + response);
        assertEquals(":1\r\n", response, "脚本应返回 1");
    }

    @Test
    @DisplayName("EVALSHA：单 key 在本节点时正常执行（脚本预加载后）")
    void testEvalShaLocalSlot() {
        String key = "session:info:{sha-id}";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        // 先 SCRIPT LOAD 加载脚本
        String loadResp = sendCommand(channel, "SCRIPT", "LOAD", READ_SCRIPT);
        assertNotNull(loadResp, "SCRIPT LOAD 响应不应为 null");
        assertTrue(loadResp.startsWith("$"), "SCRIPT LOAD 应返回 bulk，实际: " + loadResp);
        String sha1 = loadResp.substring(loadResp.indexOf("\r\n") + 2, loadResp.length() - 2);

        String response = sendCommand(channel, "EVALSHA", sha1, "1", key);

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "本地槽位不应 MOVED，实际: " + response);
        assertEquals(":-2\r\n", response, "未设置的 key PTTL 应为 -2");
    }

    @Test
    @DisplayName("单机模式 EVAL：不进行任何重定向检查，正常执行")
    void testEvalNonClusterMode() {
        EmbeddedChannel channel = createNonClusterChannel();
        String key = "session:info:{standalone-id}";

        String response = sendCommand(channel, "EVAL", SET_SCRIPT, "1", key, "val", "60000");

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-MOVED"), "单机模式不应 MOVED，实际: " + response);
        assertFalse(response.startsWith("-CROSSSLOT"), "单机模式不应 CROSSSLOT，实际: " + response);
    }

    // ==================== 辅助方法：创建不同配置的集群通道 ====================

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

    /**
     * 创建 MOVED 重定向场景的通道：指定 slot 分配给 NODE_ID_2（非本地）。
     */
    private EmbeddedChannel createChannelWithMovedRedirect(int slot) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);
        addOtherNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        if (slot > 0) {
            slotManager.setSlotOwner(slot, NODE_ID_2);
        }

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

    private void addOtherNode(ClusterConfig clusterConfig) {
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);
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
