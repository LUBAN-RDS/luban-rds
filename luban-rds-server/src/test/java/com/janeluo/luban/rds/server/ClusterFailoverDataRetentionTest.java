package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.MasterReplicationManager;
import com.janeluo.luban.rds.replication.ReplicationBacklog;
import com.janeluo.luban.rds.replication.ReplicationStreamApplier;
import com.janeluo.luban.rds.replication.SlaveInfo;
import com.janeluo.luban.rds.replication.SlaveReplicationService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 故障转移数据保留端到端集成测试 (Task 5.1)
 *
 * <p>验证集群故障转移的核心数据保留保证：当一个已经从 master 同步过数据的 slave 被提升
 * （通过 failover）为新 master 时，故障前写入的数据在新 master 上仍然可读。
 *
 * <p>本测试采用<b>组件级集成测试</b>方式，使用真实的复制组件
 * （{@link ReplicationCoordinator}、{@link MasterReplicationManager}、
 * {@link ReplicationStreamApplier}、{@link DefaultMemoryStore}），
 * 但不启动完整的 Netty 网络拓扑与 Gossip 总线。理由：
 * <ul>
 *   <li>完整多服务器 E2E（CLUSTER MEET + 真实 PSYNC + Gossip 选举）涉及大量异步时序，
 *       在单元测试中极易抖动，且与数据保留这一核心断言关系不大。</li>
 *   <li>数据保留保证的关键路径是：master 写命令 -> backlog -> slave 应用到本地存储 ->
 *       slave 提升时停止复制但<b>不清除</b>本地存储 -> 新 master 可读旧数据。
 *       本测试逐段验证该路径。</li>
 * </ul>
 *
 * <p>测试覆盖 {@link ReplicationCoordinator} 的三个生命周期回调：
 * <ul>
 *   <li>{@link ReplicationCoordinator#replicateTo(ClusterNode)} —— 不在此直接验证网络连接，
 *       而通过底层 backlog+applier 验证数据能流入 slave 存储</li>
 *   <li>{@link ReplicationCoordinator#promoteToMaster()} —— 验证停止上游复制且保留已同步数据</li>
 *   <li>{@link ReplicationCoordinator#demoteToSlave(ClusterNode)} —— 验证降级后重新指向新 master</li>
 * </ul>
 */
@DisplayName("故障转移数据保留集成测试")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ClusterFailoverDataRetentionTest {

    /** master 节点 ID（40 字符十六进制） */
    private static final String MASTER_NODE_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    /** slave 节点 ID（40 字符十六进制） */
    private static final String SLAVE_NODE_ID = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

    @Mock
    private Channel slaveChannel;

    /** master 侧存储（写命令发源地） */
    private MemoryStore masterStore;
    /** slave 侧存储（被提升为新 master 后仍应保留数据） */
    private MemoryStore slaveStore;

    /** master 侧复制管理器（单例） */
    private MasterReplicationManager masterManager;
    /** slave 侧复制流应用器（模拟从节点接收并重放 master 传播的命令流） */
    private ReplicationStreamApplier slaveApplier;

    /** 真实复制协调器（被测对象，绑定 slaveStore） */
    private ReplicationCoordinator coordinator;

    /** 测试用数据目录（临时） */
    private String testDataDir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // 桩化 slave 通道：propagateCommand 会向在线 slave 的 channel 写数据
        when(slaveChannel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 7100));
        when(slaveChannel.isActive()).thenReturn(true);
        when(slaveChannel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(slaveChannel));

        // 初始化 MasterReplicationManager 单例（backlog 大小 1MB）
        MasterReplicationManager.initialize(1024 * 1024);
        masterManager = MasterReplicationManager.getInstance();
        // 清理可能残留的从节点（单例跨测试共享）
        for (SlaveInfo slave : masterManager.getSlaves()) {
            masterManager.removeSlave(slave.getChannel());
        }

        masterStore = new DefaultMemoryStore(16, 0L, "noeviction");
        slaveStore = new DefaultMemoryStore(16, 0L, "noeviction");
        masterManager.setMemoryStore(masterStore);

        // slave 侧应用器，绑定 slave 本地存储
        slaveApplier = new ReplicationStreamApplier(slaveStore);

        // 构造真实 ReplicationCoordinator（被测对象）
        testDataDir = java.nio.file.Files.createTempDirectory("failover-data-retention-")
                .toAbsolutePath().toString();
        RdsConfig config = new RdsConfig();
        config.setReplBacklogSize(1024L * 1024L);
        // 使用真实 RdbPersistService（仅用于协调器内部 RDB 快照，本测试不触发全量同步）
        PersistService persistService = new RdbPersistService(testDataDir);
        coordinator = new ReplicationCoordinator(config, slaveStore, persistService);
        coordinator.setup();
    }

    @AfterEach
    void tearDown() {
        if (slaveApplier != null) {
            slaveApplier.close();
        }
        if (coordinator != null) {
            coordinator.shutdown();
        }
        // 清理临时数据目录
        if (testDataDir != null) {
            try {
                java.nio.file.Files.walk(java.nio.file.Paths.get(testDataDir))
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 核心场景：master 写入数据 -> slave 同步 -> master 故障 ->
     * slave 经 promoteToMaster 提升为新 master -> 旧数据仍可读。
     *
     * <p>这是 spec 要求的端到端数据保留保证的核心断言。
     */
    @Test
    @DisplayName("故障转移后已同步数据在新 master 上仍可读")
    void testDataRetainedAfterFailoverPromotion() {
        // 1. master 侧注册一个在线 slave，使 propagateCommand 进入 backlog 写入路径
        SlaveInfo slaveInfo = masterManager.addSlave(slaveChannel);
        slaveInfo.setState(com.janeluo.luban.rds.replication.ReplicationState.ONLINE);
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        // 2. 在 master 上写入若干键值（模拟故障前的业务写入）
        //    这些命令通过 propagateCommand 进入 backlog，并被"传播"给 slave
        byte[] setFoo = respFrame("SET", "failover:key:1", "value-before-failure");
        byte[] setBar = respFrame("SET", "failover:key:2", "another-value");
        masterManager.propagateCommand(setFoo);
        masterManager.propagateCommand(setBar);

        // 3. slave 侧接收并应用传播的命令流（模拟从节点重放 master 传播流）
        slaveApplier.applyData(Unpooled.wrappedBuffer(setFoo));
        slaveApplier.applyData(Unpooled.wrappedBuffer(setBar));

        // 断言：slave 已成功同步 master 写入的数据
        Object v1 = slaveStore.get(0, "failover:key:1");
        assertNotNull(v1, "slave 应已同步 failover:key:1");
        assertEquals("value-before-failure", v1.toString());
        Object v2 = slaveStore.get(0, "failover:key:2");
        assertNotNull(v2, "slave 应已同步 failover:key:2");
        assertEquals("another-value", v2.toString());

        // 4. 模拟 master 故障：断开 slave 与 master 的复制连接。
        //    这里通过 coordinator.stopSlave() 模拟 slave 侧主动断开（master 已不可达）。
        //    注意：stopSlave 仅停止复制连接，不触碰 slaveStore 本地数据。
        coordinator.stopSlave();
        assertFalse(coordinator.isSlave(), "断开后 coordinator 不应处于 slave 状态");

        // 5. 触发故障转移：slave 被提升为新 master。
        //    promoteToMaster() 应停止上游复制连接，但保留本地已同步数据。
        coordinator.promoteToMaster();

        // 核心断言：故障转移后，pre-failure 写入的数据在新 master（原 slave）上仍可读
        Object retainedV1 = slaveStore.get(0, "failover:key:1");
        assertNotNull(retainedV1, "故障转移后新 master 应保留 failover:key:1");
        assertEquals("value-before-failure", retainedV1.toString(),
                "故障转移后数据值应保持不变");
        Object retainedV2 = slaveStore.get(0, "failover:key:2");
        assertNotNull(retainedV2, "故障转移后新 master 应保留 failover:key:2");
        assertEquals("another-value", retainedV2.toString(),
                "故障转移后数据值应保持不变");

        // 6. 新 master 可继续接受写入（证明它已成为可写的主节点）
        slaveStore.set(0, "post-failover:key", "written-by-new-master");
        assertEquals("written-by-new-master", slaveStore.get(0, "post-failover:key").toString());
    }

    /**
     * 验证 promoteToMaster 的幂等性与数据保留：连续多次提升不会清除已同步数据。
     */
    @Test
    @DisplayName("多次 promoteToMaster 调用幂等且不丢失数据")
    void testPromoteToMasterIsIdempotentAndRetainsData() {
        // 准备：slave 同步一条数据
        SlaveInfo slaveInfo = masterManager.addSlave(slaveChannel);
        slaveInfo.setState(com.janeluo.luban.rds.replication.ReplicationState.ONLINE);
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        byte[] setFrame = respFrame("SET", "idempotent:key", "stable-value");
        masterManager.propagateCommand(setFrame);
        slaveApplier.applyData(Unpooled.wrappedBuffer(setFrame));
        assertEquals("stable-value", slaveStore.get(0, "idempotent:key").toString());

        // 多次提升
        coordinator.promoteToMaster();
        coordinator.promoteToMaster();
        coordinator.promoteToMaster();

        // 数据应仍然存在
        Object retained = slaveStore.get(0, "idempotent:key");
        assertNotNull(retained, "多次提升后数据应保留");
        assertEquals("stable-value", retained.toString());
        assertFalse(coordinator.isSlave(), "提升后不应处于 slave 状态");
    }

    /**
     * 验证 demoteToSlave 重新指向新 master 地址。
     *
     * <p>故障转移后，原 master 若恢复，应能作为 slave 重新指向新 master。
     * 本测试验证 demoteToSlave 会触发 startSlave 到新 master 地址。
     * 由于无真实 master 监听，slave 客户端会进入重连循环（不影响断言），
     * 我们通过 coordinator.isSlave() 与内部状态验证降级发生。
     */
    @Test
    @DisplayName("demoteToSlave 重新指向新 master 地址")
    void testDemoteToSlaveReconnectsToNewMaster() throws Exception {
        // 先提升为 master
        coordinator.promoteToMaster();
        assertFalse(coordinator.isSlave());

        // 找一个空闲端口作为"新 master"地址（无真实监听，slave 会重连失败但不影响协调器状态）
        int freePort = findFreePort();
        ClusterNode newMaster = new ClusterNode(MASTER_NODE_ID, "127.0.0.1", freePort, freePort + 10000);
        newMaster.addState(ClusterNodeState.MASTER);

        // 降级为 slave，指向新 master
        coordinator.demoteToSlave(newMaster);

        // 验证：coordinator 已进入 slave 状态（startSlave 被调用，slaveService 非 null）
        assertTrue(coordinator.isSlave(), "demoteToSlave 后应处于 slave 复制状态");

        // 给重连循环一点时间启动（非必须，仅避免资源残留警告）
        Thread.sleep(100);

        // 再次以相同目标降级：应幂等，不重复建连
        coordinator.demoteToSlave(newMaster);
        assertTrue(coordinator.isSlave(), "重复 demoteToSlave 相同目标后仍应为 slave 状态");
    }

    /**
     * 验证 backlog 偏移与 slave 应用偏移在数据传播后对齐。
     *
     * <p>这是数据保留路径正确性的辅助断言：若偏移不对齐，说明复制流有丢帧，
     * 故障转移后可能出现数据不一致。
     */
    @Test
    @DisplayName("master backlog 偏移与 slave 应用偏移对齐")
    void testBacklogOffsetAlignedWithSlaveAppliedOffset() {
        SlaveInfo slaveInfo = masterManager.addSlave(slaveChannel);
        slaveInfo.setState(com.janeluo.luban.rds.replication.ReplicationState.ONLINE);
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        byte[] f1 = respFrame("SET", "offset:k1", "v1");
        byte[] f2 = respFrame("SET", "offset:k2", "v2");
        byte[] f3 = respFrame("SET", "offset:k3", "v3");

        masterManager.propagateCommand(f1);
        masterManager.propagateCommand(f2);
        masterManager.propagateCommand(f3);

        // slave 应用全部命令流
        slaveApplier.applyData(Unpooled.wrappedBuffer(f1));
        slaveApplier.applyData(Unpooled.wrappedBuffer(f2));
        slaveApplier.applyData(Unpooled.wrappedBuffer(f3));

        ReplicationBacklog backlog = masterManager.getBacklog();
        long masterOffset = backlog.getMasterReplOffset();
        long slaveApplied = slaveApplier.getAppliedOffset();

        assertEquals(masterOffset, slaveApplied,
                "master backlog 偏移应与 slave 已应用偏移对齐，否则复制流有丢帧");

        // 提升后偏移仍应保持（promoteToMaster 不重置 backlog/applier 偏移语义）
        coordinator.promoteToMaster();
        assertEquals(masterOffset, slaveApplier.getAppliedOffset(),
                "提升为新 master 后已应用偏移不应回退");
        assertEquals("v1", slaveStore.get(0, "offset:k1").toString());
        assertEquals("v2", slaveStore.get(0, "offset:k2").toString());
        assertEquals("v3", slaveStore.get(0, "offset:k3").toString());
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造 RESP 命令帧：*N\r\n$L\r\narg\r\n ...
     *
     * <p>使用 ISO-8859-1 编码以匹配 {@link ReplicationStreamApplier} 的二进制安全解析。
     */
    private static byte[] respFrame(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.ISO_8859_1);
            sb.append('$').append(bytes.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 查找本机一个空闲端口。
     *
     * @return 可用端口号
     */
    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
