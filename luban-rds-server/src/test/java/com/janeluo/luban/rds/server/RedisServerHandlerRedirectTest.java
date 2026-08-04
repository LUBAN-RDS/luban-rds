package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集群重定向逻辑回归测试（第一批迁移三连：P0-2 / P0-3）
 * <p>
 * 覆盖 RedisServerHandler.checkSlotAndRedirect / checkAskRedirect 在槽位迁移期间的
 * ASK/ASKING 语义，对齐 Redis 7 getNodeByQuery 行为：
 * <ul>
 *   <li>IMPORTING 槽位 + ASKING → 放行执行（修复 A↔B MOVED/ASK 无限循环）</li>
 *   <li>IMPORTING 槽位无 ASKING → -ASK 回源节点</li>
 *   <li>MIGRATING 槽位键已迁走 → 无条件 -ASK（即使带 ASKING，修复孤儿键缺陷）</li>
 *   <li>MIGRATING 槽位键存在 → 正常执行</li>
 *   <li>普通槽位 + ASKING → 正常执行（标志无路由效果）</li>
 * </ul>
 * </p>
 */
class RedisServerHandlerRedirectTest {

    private static final String MY_NODE_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String SOURCE_NODE_ID = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String TARGET_NODE_ID = "c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String ASKING_COMMAND = "*1\r\n$6\r\nASKING\r\n";

    private EmbeddedChannel channel;
    private MemoryStore memoryStore;
    private SlotManager slotManager;

    @BeforeEach
    void setUp() {
        memoryStore = new DefaultMemoryStore();
        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        slotManager = mock(SlotManager.class);

        // 默认：槽位归属本节点（isSlotLocal=true），无 importing/migrating 状态
        when(slotManager.isSlotImporting(anyInt())).thenReturn(false);
        when(slotManager.isSlotMigrating(anyInt())).thenReturn(false);
        when(slotManager.isSlotLocal(anyInt())).thenReturn(true);
        when(clusterConfig.getMyNodeId()).thenReturn(MY_NODE_ID);
        when(clusterConfig.getSlotOwner(anyInt())).thenReturn(MY_NODE_ID);
        // P1-13：默认集群健康（isClusterOk=true），否则 state 门控会 CLUSTERDOWN 所有命令。
        when(clusterConfig.isClusterOk()).thenReturn(true);
        // P1-14：默认本节点是 master（非 slave），不触发 slave 路由门控。
        ClusterNode meMaster = new ClusterNode(MY_NODE_ID, "127.0.0.1", 7000, 17000);
        meMaster.addState(ClusterNodeState.MASTER);
        when(clusterConfig.getMyNode()).thenReturn(meMaster);
        when(clusterConfig.getNode(SOURCE_NODE_ID))
                .thenReturn(new ClusterNode(SOURCE_NODE_ID, "127.0.0.1", 7001, 17001));
        when(clusterConfig.getNode(TARGET_NODE_ID))
                .thenReturn(new ClusterNode(TARGET_NODE_ID, "127.0.0.1", 7002, 17002));

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, new DefaultCommandHandler(), new RedisProtocolParser(),
                0, true, clusterConfig, slotManager);
        channel = new EmbeddedChannel(handler);
    }

    private String sendCommand(String respCommand) {
        channel.writeInbound(Unpooled.copiedBuffer(respCommand.getBytes(StandardCharsets.UTF_8)));
        ByteBuf response = channel.readOutbound();
        assertNotNull(response, "应收到响应: " + respCommand);
        return response.toString(StandardCharsets.UTF_8);
    }

    private String getCommand(String key) {
        return "*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n";
    }

    @Test
    @DisplayName("IMPORTING 槽位 + ASKING → 放行执行，且 ASKING 为一次性")
    void testImportingWithAskingExecutes() {
        String key = "import-key";
        int slot = SlotUtils.keyHashSlot(key);
        when(slotManager.isSlotImporting(slot)).thenReturn(true);
        when(slotManager.getImportingSource(slot)).thenReturn(SOURCE_NODE_ID);

        assertEquals("+OK\r\n", sendCommand(ASKING_COMMAND));
        // 键不存在 → $-1（命令真正执行而非重定向）
        assertEquals("$-1\r\n", sendCommand(getCommand(key)));
        // ASKING 已被消费：第二条命令无 ASKING → -ASK 回源节点
        String resp = sendCommand(getCommand(key));
        assertTrue(resp.startsWith("-ASK " + slot + " 127.0.0.1:7001"), "响应: " + resp);
    }

    @Test
    @DisplayName("IMPORTING 槽位无 ASKING → -ASK 重定向回源节点（P0-2）")
    void testImportingWithoutAskingReturnsAsk() {
        String key = "import-key";
        int slot = SlotUtils.keyHashSlot(key);
        when(slotManager.isSlotImporting(slot)).thenReturn(true);
        when(slotManager.getImportingSource(slot)).thenReturn(SOURCE_NODE_ID);

        String resp = sendCommand(getCommand(key));
        assertTrue(resp.startsWith("-ASK " + slot + " 127.0.0.1:7001"), "响应: " + resp);
    }

    @Test
    @DisplayName("MIGRATING 槽位键已迁走 → 带 ASKING 也返回 -ASK（P0-3 回归保护）")
    void testMigratingMissingKeyAsksEvenWithAsking() {
        String key = "migrating-key";
        int slot = SlotUtils.keyHashSlot(key);
        when(slotManager.isSlotMigrating(slot)).thenReturn(true);
        when(slotManager.getMigratingTarget(slot)).thenReturn(TARGET_NODE_ID);

        assertEquals("+OK\r\n", sendCommand(ASKING_COMMAND));
        String resp = sendCommand(getCommand(key));
        assertTrue(resp.startsWith("-ASK " + slot + " 127.0.0.1:7002"), "响应: " + resp);
    }

    @Test
    @DisplayName("MIGRATING 槽位键存在 → 正常执行")
    void testMigratingKeyExistsExecutes() {
        String key = "migrating-key";
        int slot = SlotUtils.keyHashSlot(key);
        when(slotManager.isSlotMigrating(slot)).thenReturn(true);
        when(slotManager.getMigratingTarget(slot)).thenReturn(TARGET_NODE_ID);
        memoryStore.set(0, key, "v");

        assertEquals("+OK\r\n", sendCommand(ASKING_COMMAND));
        assertEquals("$1\r\nv\r\n", sendCommand(getCommand(key)));
    }

    @Test
    @DisplayName("普通槽位 + ASKING → 正常执行（标志无路由效果）")
    void testNormalSlotWithAskingExecutes() {
        assertEquals("+OK\r\n", sendCommand(ASKING_COMMAND));
        assertEquals("$-1\r\n", sendCommand(getCommand("normal-key")));
    }

    /**
     * P1-1：双表分叉回归。Gossip 学到远程 failover/reshard 后，clusterConfig 已更新槽位
     * 归属（新 owner），但 DefaultSlotManager.mySlots 仍是启动时的快照（isSlotLocal=true）。
     * checkSlotAndRedirect 必须以 clusterConfig 为权威（owner == myNodeId 判定本地性），
     * 而非读 stale 的 slotManager.isSlotLocal，否则会越权服务已不属于自己的槽位。
     */
    @Test
    @DisplayName("P1-1：远程槽位变更后按 clusterConfig 返回 MOVED（不读 stale slotManager.isSlotLocal）")
    void testRemoteSlotMoveRedirectsViaClusterConfig() {
        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        // slotManager 仍是 stale 快照：报告"本地拥有"
        when(slotManager.isSlotImporting(anyInt())).thenReturn(false);
        when(slotManager.isSlotMigrating(anyInt())).thenReturn(false);
        when(slotManager.isSlotLocal(anyInt())).thenReturn(true);
        // 但 clusterConfig（gossip 维护的权威表）已记录槽位被远程接管
        when(clusterConfig.getMyNodeId()).thenReturn(MY_NODE_ID);
        when(clusterConfig.getSlotOwner(anyInt())).thenReturn(SOURCE_NODE_ID);
        // P1-13：集群健康，否则 state 门控先返回 CLUSTERDOWN 而非 MOVED
        when(clusterConfig.isClusterOk()).thenReturn(true);
        // P1-14：本节点是 master，不触发 slave 路由
        ClusterNode meMaster = new ClusterNode(MY_NODE_ID, "127.0.0.1", 7000, 17000);
        meMaster.addState(ClusterNodeState.MASTER);
        when(clusterConfig.getMyNode()).thenReturn(meMaster);
        when(clusterConfig.getNode(SOURCE_NODE_ID))
                .thenReturn(new ClusterNode(SOURCE_NODE_ID, "127.0.0.1", 7001, 17001));

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, new DefaultCommandHandler(), new RedisProtocolParser(),
                0, true, clusterConfig, slotManager);
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        String key = "diverged-key";
        int slot = SlotUtils.keyHashSlot(key);
        String resp = "*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(resp.getBytes(StandardCharsets.UTF_8)));
        ByteBuf response = ch.readOutbound();
        assertNotNull(response);
        String out = response.toString(StandardCharsets.UTF_8);
        assertTrue(out.startsWith("-MOVED " + slot),
                "应返回 -MOVED（owner 已变更），实际: " + out);
    }

    // ==================== P1-13：cluster_state 门控 ====================

    /**
     * 构建一个带自定义 clusterConfig 状态的 handler + channel，用于 P1-13/P1-14 测试。
     *
     * @param clusterOk  clusterConfig.isClusterOk() 返回值
     * @param myNode     clusterConfig.getMyNode() 返回的"本节点"（决定 master/slave 角色）
     * @return 新的 EmbeddedChannel
     */
    private EmbeddedChannel buildChannelWithState(boolean clusterOk, ClusterNode myNode) {
        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        SlotManager sm = mock(SlotManager.class);
        when(sm.isSlotImporting(anyInt())).thenReturn(false);
        when(sm.isSlotMigrating(anyInt())).thenReturn(false);
        when(sm.isSlotLocal(anyInt())).thenReturn(true);
        when(clusterConfig.getMyNodeId()).thenReturn(MY_NODE_ID);
        when(clusterConfig.getMyNode()).thenReturn(myNode);
        when(clusterConfig.isClusterOk()).thenReturn(clusterOk);
        // 槽位归属默认本节点（master 场景）或 master 节点（slave 场景由调用方覆盖）
        when(clusterConfig.getSlotOwner(anyInt())).thenReturn(MY_NODE_ID);
        when(clusterConfig.getNode(MY_NODE_ID)).thenReturn(myNode);
        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, new DefaultCommandHandler(), new RedisProtocolParser(),
                0, true, clusterConfig, sm);
        return new EmbeddedChannel(handler);
    }

    private static String respGet(String key) {
        return "*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n";
    }

    private static String respSet(String key, String val) {
        return "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
    }

    private static String readOut(EmbeddedChannel ch) {
        ByteBuf response = ch.readOutbound();
        assertNotNull(response, "应收到响应");
        return response.toString(StandardCharsets.UTF_8);
    }

    /**
     * P1-13：集群 state=fail 时，键命令返回 -CLUSTERDOWN。
     */
    @Test
    @DisplayName("P1-13：cluster_state=fail 时 GET 返回 -CLUSTERDOWN")
    void testClusterFailStateReturnsClusterDown() {
        ClusterNode master = new ClusterNode(MY_NODE_ID, "127.0.0.1", 7000, 17000);
        master.addState(ClusterNodeState.MASTER);
        EmbeddedChannel ch = buildChannelWithState(false, master);

        ch.writeInbound(Unpooled.copiedBuffer(respGet("anykey").getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(out.startsWith("-CLUSTERDOWN"), "fail 状态应返回 CLUSTERDOWN，实际: " + out);
    }

    /**
     * P1-13：集群 state=fail 但开启 cluster-allow-reads-when-down 时，
     * 只读命令（GET）放行，写命令（SET）仍 CLUSTERDOWN。
     */
    @Test
    @DisplayName("P1-13：allow-reads-when-down=true 时 fail 状态放行读、拒绝写")
    void testClusterFailAllowsReadWhenDown() {
        // 通过 ServerContext 注入开启 allow-reads-when-down 的配置（构造时读取）
        com.janeluo.luban.rds.common.config.RdsConfig cfg = new com.janeluo.luban.rds.common.config.RdsConfig();
        cfg.setClusterAllowReadsWhenDown(true);
        com.janeluo.luban.rds.common.context.ServerContext.setConfig(cfg);
        try {
            ClusterNode master = new ClusterNode(MY_NODE_ID, "127.0.0.1", 7000, 17000);
            master.addState(ClusterNodeState.MASTER);
            EmbeddedChannel ch = buildChannelWithState(false, master);

            // 读命令放行（nil，因为键不存在）
            ch.writeInbound(Unpooled.copiedBuffer(respGet("anykey").getBytes(StandardCharsets.UTF_8)));
            String readOut = readOut(ch);
            assertEquals("$-1\r\n", readOut, "allow-reads-when-down 时读应放行，实际: " + readOut);

            // 写命令仍拒绝
            ch.writeInbound(Unpooled.copiedBuffer(respSet("anykey", "v").getBytes(StandardCharsets.UTF_8)));
            String writeOut = readOut(ch);
            assertTrue(writeOut.startsWith("-CLUSTERDOWN"),
                    "allow-reads-when-down 时写仍应 CLUSTERDOWN，实际: " + writeOut);
        } finally {
            com.janeluo.luban.rds.common.context.ServerContext.setConfig(null);
        }
    }

    /**
     * P1-13：集群 state=ok 时，命令正常服务。
     * （基线对照，确保门控只在 fail 时生效。）
     */
    @Test
    @DisplayName("P1-13：cluster_state=ok 时 GET 正常服务（基线）")
    void testClusterOkStateServesCommand() {
        ClusterNode master = new ClusterNode(MY_NODE_ID, "127.0.0.1", 7000, 17000);
        master.addState(ClusterNodeState.MASTER);
        EmbeddedChannel ch = buildChannelWithState(true, master);

        ch.writeInbound(Unpooled.copiedBuffer(respGet("anykey").getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertEquals("$-1\r\n", out, "ok 状态应正常服务 GET（nil），实际: " + out);
    }

    // ==================== P1-14：slave 写保护 + READONLY 读 ====================

    /**
     * 构建一个 slave 角色的 handler + channel：本节点是 slave，master 拥有所有槽位。
     *
     * @param readonly 客户端是否已声明 READONLY
     * @return [channel, masterNode] —— masterNode 可用于断言 MOVED 目标地址
     */
    private EmbeddedChannel buildSlaveChannel(boolean clusterOk) {
        ClusterConfig clusterConfig = mock(ClusterConfig.class);
        SlotManager sm = mock(SlotManager.class);
        when(sm.isSlotImporting(anyInt())).thenReturn(false);
        when(sm.isSlotMigrating(anyInt())).thenReturn(false);
        when(sm.isSlotLocal(anyInt())).thenReturn(false);

        ClusterNode master = new ClusterNode(MY_NODE_ID, "127.0.0.1", 7000, 17000);
        master.addState(ClusterNodeState.MASTER);

        // 本节点是 slave，其 master 是 MY_NODE_ID
        ClusterNode meSlave = new ClusterNode(TARGET_NODE_ID, "127.0.0.1", 7002, 17002);
        meSlave.addState(ClusterNodeState.SLAVE);
        meSlave.setMasterNodeId(MY_NODE_ID);

        when(clusterConfig.getMyNodeId()).thenReturn(TARGET_NODE_ID);
        when(clusterConfig.getMyNode()).thenReturn(meSlave);
        when(clusterConfig.isClusterOk()).thenReturn(clusterOk);
        // 槽位归属 master
        when(clusterConfig.getSlotOwner(anyInt())).thenReturn(MY_NODE_ID);
        when(clusterConfig.getNode(MY_NODE_ID)).thenReturn(master);

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, new DefaultCommandHandler(), new RedisProtocolParser(),
                0, true, clusterConfig, sm);
        return new EmbeddedChannel(handler);
    }

    /**
     * P1-14：slave 收到写命令 → -READONLY（无论是否声明 READONLY）。
     */
    @Test
    @DisplayName("P1-14：slave 收到 SET 写命令返回 -READONLY")
    void testSlaveWriteReturnsReadonly() {
        EmbeddedChannel ch = buildSlaveChannel(true);
        ch.writeInbound(Unpooled.copiedBuffer(respSet("anykey", "v").getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(out.startsWith("-READONLY"), "slave 写应返回 -READONLY，实际: " + out);
    }

    /**
     * P1-14：slave 收到读命令、客户端未声明 READONLY → MOVED 到 master。
     */
    @Test
    @DisplayName("P1-14：slave 未声明 READONLY 的 GET 返回 MOVED 到 master")
    void testSlaveReadWithoutReadonlyRedirectsToMaster() {
        String key = "slave-key";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel ch = buildSlaveChannel(true);
        ch.writeInbound(Unpooled.copiedBuffer(respGet(key).getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(out.startsWith("-MOVED " + slot + " 127.0.0.1:7000"),
                "slave 未声明 READONLY 读应 MOVED 到 master，实际: " + out);
    }

    /**
     * P1-14：slave 收到读命令、客户端已声明 READONLY → 本 slave 服务读（放行）。
     */
    @Test
    @DisplayName("P1-14：slave 声明 READONLY 后 GET 本地服务读")
    void testSlaveReadWithReadonlyServesLocally() {
        EmbeddedChannel ch = buildSlaveChannel(true);
        // 先发 READONLY 声明
        ch.writeInbound(Unpooled.copiedBuffer("*1\r\n$8\r\nREADONLY\r\n".getBytes(StandardCharsets.UTF_8)));
        String readonlyResp = readOut(ch);
        assertEquals("+OK\r\n", readonlyResp, "READONLY 应返回 +OK");
        // 再发 GET → 本 slave 服务（nil）
        ch.writeInbound(Unpooled.copiedBuffer(respGet("anykey").getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertEquals("$-1\r\n", out, "声明 READONLY 后 slave 应本地服务读，实际: " + out);
    }

    /**
     * P1-14：声明 READONLY 后，写命令仍被拒绝（-READONLY）。
     * 防止"READONLY 后写也放行"的回归。
     */
    @Test
    @DisplayName("P1-14：slave 声明 READONLY 后写仍返回 -READONLY")
    void testSlaveWriteStillReadonlyAfterReadonlyFlag() {
        EmbeddedChannel ch = buildSlaveChannel(true);
        ch.writeInbound(Unpooled.copiedBuffer("*1\r\n$8\r\nREADONLY\r\n".getBytes(StandardCharsets.UTF_8)));
        readOut(ch); // 消费 READONLY 的 +OK
        ch.writeInbound(Unpooled.copiedBuffer(respSet("anykey", "v").getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(out.startsWith("-READONLY"), "READONLY 标志不应使写命令通过，实际: " + out);
    }

    // ============ EVAL/EVALSHA 脚本只读性（修复 Redisson READONLY 报错）============

    /**
     * 只读 EVAL 脚本（PTTL）+ 客户端声明 READONLY → 本 slave 服务读（放行，非 -READONLY）。
     * <p>对应 Redisson 报错场景：从节点不应把只读 EVAL 当写命令拒绝。
     */
    @Test
    @DisplayName("slave READONLY + 只读 EVAL(PTTL) 本地服务（修复 Redisson 报错）")
    void testSlaveEvalReadOnlyServesLocallyWithReadonly() {
        EmbeddedChannel ch = buildSlaveChannel(true);
        // 声明 READONLY
        ch.writeInbound(Unpooled.copiedBuffer("*1\r\n$8\r\nREADONLY\r\n".getBytes(StandardCharsets.UTF_8)));
        readOut(ch); // 消费 +OK
        // 只读脚本：return redis.call('PTTL', KEYS[1])
        ch.writeInbound(Unpooled.copiedBuffer(
                respEval("return redis.call('PTTL', KEYS[1])", 1, "slave-eval-key")
                        .getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(!out.startsWith("-READONLY"),
                "只读 EVAL 在 READONLY 从节点应放行执行，实际: " + out);
    }

    /**
     * 只读 EVAL 脚本（GET）+ 客户端未声明 READONLY → MOVED 到 master。
     * <p>只读脚本按读命令处理：未声明 READONLY 时 slave 不擅自服务，重定向到 master。
     */
    @Test
    @DisplayName("slave 未声明 READONLY 的只读 EVAL(GET) 返回 MOVED 到 master")
    void testSlaveEvalReadOnlyWithoutReadonlyRedirectsToMaster() {
        String key = "slave-eval-ro";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel ch = buildSlaveChannel(true);
        ch.writeInbound(Unpooled.copiedBuffer(
                respEval("return redis.call('GET', KEYS[1])", 1, key)
                        .getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(out.startsWith("-MOVED " + slot + " 127.0.0.1:7000"),
                "只读 EVAL 未声明 READONLY 应 MOVED 到 master，实际: " + out);
    }

    /**
     * 含写操作的 EVAL 脚本（SET）→ -READONLY（无论 READONLY 标志）。
     * <p>从节点永不接受写脚本，保持原拒绝行为。
     */
    @Test
    @DisplayName("slave 收到写 EVAL(SET) 返回 -READONLY")
    void testSlaveEvalWriteReturnsReadonly() {
        EmbeddedChannel ch = buildSlaveChannel(true);
        ch.writeInbound(Unpooled.copiedBuffer(
                respEval("return redis.call('SET', KEYS[1], ARGV[1])", 1, "slave-eval-write", "v")
                        .getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(out.startsWith("-READONLY"),
                "写 EVAL 应被 slave 拒绝返回 -READONLY，实际: " + out);
    }

    /**
     * EVALSHA 只读脚本：先 SCRIPT LOAD 注册，再 EVALSHA → READONLY 从节点放行。
     * <p>验证 EVALSHA 路径能通过脚本缓存取回原文并正确判定只读性。
     */
    @Test
    @DisplayName("slave READONLY + EVALSHA 只读脚本本地服务")
    void testSlaveEvalshaReadOnlyServesLocallyWithReadonly() {
        EmbeddedChannel ch = buildSlaveChannel(true);
        // 声明 READONLY
        ch.writeInbound(Unpooled.copiedBuffer("*1\r\n$8\r\nREADONLY\r\n".getBytes(StandardCharsets.UTF_8)));
        readOut(ch);
        // SCRIPT LOAD 只读脚本
        String script = "return redis.call('EXISTS', KEYS[1])";
        ch.writeInbound(Unpooled.copiedBuffer(
                respScriptLoad(script).getBytes(StandardCharsets.UTF_8)));
        String loadResp = readOut(ch);
        // 提取 sha1（响应格式：$40\r\n<sha>\r\n）
        String sha1 = loadResp.trim().substring(loadResp.trim().length() - 40);
        // EVALSHA 执行
        ch.writeInbound(Unpooled.copiedBuffer(
                respEvalsha(sha1, 1, "slave-evalsha-key").getBytes(StandardCharsets.UTF_8)));
        String out = readOut(ch);
        assertTrue(!out.startsWith("-READONLY"),
                "只读 EVALSHA 在 READONLY 从节点应放行，实际: " + out);
    }

    /**
     * 构造 EVAL 命令的 RESP 帧：EVAL script numkeys key [key ...] arg [arg ...]
     */
    private static String respEval(String script, int numkeys, String key, String... argv) {
        return respScriptCommand("EVAL", script, numkeys, key, argv);
    }

    /**
     * 构造 EVALSHA 命令的 RESP 帧：EVALSHA sha1 numkeys key [key ...] arg [arg ...]
     */
    private static String respEvalsha(String sha1, int numkeys, String key, String... argv) {
        return respScriptCommand("EVALSHA", sha1, numkeys, key, argv);
    }

    /**
     * 构造 SCRIPT LOAD 的 RESP 帧。
     */
    private static String respScriptLoad(String script) {
        return "*3\r\n$6\r\nSCRIPT\r\n$4\r\nLOAD\r\n$" + script.length() + "\r\n" + script + "\r\n";
    }

    /**
     * 构造 EVAL/EVALSHA 命令的 RESP 帧（scriptBody 为脚本文本或 sha1）。
     */
    private static String respScriptCommand(String cmd, String scriptBody, int numkeys, String key, String... argv) {
        StringBuilder sb = new StringBuilder();
        int argc = 3 + 1 + argv.length; // cmd + body + numkeys + key + argv...
        sb.append("*").append(argc).append("\r\n");
        sb.append("$").append(cmd.length()).append("\r\n").append(cmd).append("\r\n");
        sb.append("$").append(scriptBody.length()).append("\r\n").append(scriptBody).append("\r\n");
        sb.append("$1\r\n").append(numkeys).append("\r\n");
        sb.append("$").append(key.length()).append("\r\n").append(key).append("\r\n");
        for (String a : argv) {
            sb.append("$").append(a.length()).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString();
    }
}
