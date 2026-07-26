package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 全量同步窗口期命令缓冲与重放测试（C5）
 *
 * <p>验证 {@link MasterReplicationManager#performFullSync(Channel)} 在 RDB 传输完成后、
 * 从节点标记为 ONLINE 之前，会从 backlog 重放快照偏移量之后的窗口期命令，
 * 避免窗口期写入对从节点永久丢失。
 *
 * <p>通过注入自定义的 {@link RdbSnapshotGenerator} 子类控制 RDB 传输与窗口期写入的时序，
 * 不依赖真实 RDB 落盘。
 */
class FullSyncWindowReplayTest {

    @Mock
    private Channel channel;

    private MasterReplicationManager manager;
    private ReplicationBacklog backlog;
    private MemoryStore memoryStore;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.100", 6379));
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel));

        // 每个测试用例独立初始化单例，避免跨用例状态污染
        MasterReplicationManager.initialize(1024);
        manager = MasterReplicationManager.getInstance();
        backlog = manager.getBacklog();
        backlog.clear();

        for (SlaveInfo slave : manager.getSlaves()) {
            manager.removeSlave(slave.getChannel());
        }

        memoryStore = new DefaultMemoryStore();
        manager.setMemoryStore(memoryStore);
    }

    @AfterEach
    void tearDown() throws Exception {
        // 清理本测试添加的 slave，避免单例跨用例状态污染。
        // 不调用 manager.shutdown()：单例的 asyncExecutor 是 cached pool，
        // shutdown 会让后续测试的 performFullSync 抛 RejectedExecutionException。
        for (SlaveInfo slave : manager.getSlaves()) {
            manager.removeSlave(slave.getChannel());
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    @DisplayName("窗口期写入不丢失：RDB 传输期间写入的命令应在重放后发送给从节点")
    void testWindowCommandsReplayedToSlave() throws Exception {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.FULL_SYNC);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING | SlaveInfo.SLAVE_FLAG_FULL_SYNC);
        slave.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        // 预先写入一条命令到 backlog（RDB 落盘前已存在的数据）
        byte[] preCommand = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n".getBytes();
        backlog.append(preCommand);

        ControllableSnapshotGenerator generator = injectControllableGenerator();
        // 在 generateAndTransfer 内部、snapshotOffset 记录后、返回前，写入窗口期命令
        byte[] windowCommand1 = "*3\r\n$3\r\nSET\r\n$4\r\nwin1\r\n$2\r\nv1\r\n".getBytes();
        byte[] windowCommand2 = "*3\r\n$3\r\nSET\r\n$4\r\nwin2\r\n$2\r\nv2\r\n".getBytes();
        generator.onAfterSnapshotOffset = () -> {
            backlog.append(windowCommand1);
            backlog.append(windowCommand2);
        };

        assertTrue(manager.performFullSync(channel));

        // 等待异步任务完成（slave 进入 ONLINE 或超出超时）
        awaitSlaveOnline(slave, 5000);

        // 从节点应已标记为 ONLINE
        assertEquals(ReplicationState.ONLINE, slave.getState());
        assertTrue(slave.isOnline());

        // 从节点 offset 应等于当前 master offset（窗口期命令已重放并更新 offset）
        assertEquals(backlog.getMasterReplOffset(), slave.getOffset());

        // 验证窗口期命令已通过 channel 发送（重放）
        List<byte[]> written = collectWrittenBytes(channel);
        assertContainsConcatenation(written, windowCommand1, "window command 1 should be replayed");
        assertContainsConcatenation(written, windowCommand2, "window command 2 should be replayed");

        // snapshotOffset 应为 RDB 落盘时刻（preCommand 写入后，windowCommand 写入前）
        assertEquals(preCommand.length, generator.capturedSnapshotOffset);
    }

    @Test
    @DisplayName("重放期间不并发直发：重放期间 slave 处于 SYNCING，propagateCommand 不直发")
    void testNoConcurrentDirectSendDuringReplay() throws Exception {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.FULL_SYNC);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING | SlaveInfo.SLAVE_FLAG_FULL_SYNC);
        slave.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        ControllableSnapshotGenerator generator = injectControllableGenerator();
        // 在窗口期重放前（即 snapshotOffset 记录后）写入命令，模拟窗口期写入。
        // propagateCommand 内部会 backlog.append 并尝试直发给 online slave；
        // 此时 slave 仍是 SYNCING（尚未 ONLINE），应被 isOnline() 检查跳过。
        byte[] windowCommand = "*3\r\n$3\r\nSET\r\n$3\r\nwin\r\n$1\r\nv\r\n".getBytes();
        generator.onAfterSnapshotOffset = () -> {
            manager.propagateCommand(windowCommand);
        };

        assertTrue(manager.performFullSync(channel));
        awaitSlaveOnline(slave, 5000);

        // 重放完成后 slave 应 ONLINE
        assertEquals(ReplicationState.ONLINE, slave.getState());
        assertTrue(slave.isOnline());

        // 窗口期命令应通过重放路径发送（仅一次，不是 propagateCommand 直发两次）
        List<byte[]> written = collectWrittenBytes(channel);
        int matchCount = countOccurrences(written, windowCommand);
        // windowCommand 出现一次（重放）；propagateCommand 内部因为 slave 不 isOnline 应跳过
        assertEquals(1, matchCount,
            "window command should be sent exactly once via replay, not via concurrent direct send");
    }

    @Test
    @DisplayName("backlog 不足时回退：窗口期数据被覆盖则重放失败，slave 不进入 ONLINE")
    void testFallbackWhenBacklogInsufficient() throws Exception {
        // 当前 backlog 容量为 1024 字节。在 snapshotOffset 记录后写入远超容量的数据，
        // 使 snapshotOffset 处的数据被环形缓冲区覆盖，getBacklogData 返回 null。
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.FULL_SYNC);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING | SlaveInfo.SLAVE_FLAG_FULL_SYNC);
        slave.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        ControllableSnapshotGenerator generator = injectControllableGenerator();

        // 在 snapshotOffset 记录后，写入大量数据覆盖 backlog（容量 1024 字节）
        generator.onAfterSnapshotOffset = () -> {
            // 写入 4096 字节，远超 backlog 容量，snapshotOffset 处的数据被覆盖
            backlog.append(new byte[4096]);
        };

        assertTrue(manager.performFullSync(channel));

        // 等待异步任务稳定：重放失败路径不会进入 ONLINE
        awaitCondition(() -> slave.getState() != ReplicationState.FULL_SYNC
                          || slave.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE)
                          || !slave.isSyncing(), 5000);
        // 给足时间让失败路径执行完（removeFlag SYNCING 等）
        Thread.sleep(150);

        // slave 不应进入 ONLINE（重放失败）
        assertNotEquals(ReplicationState.ONLINE, slave.getState());
        assertFalse(slave.isOnline());
    }

    @Test
    @DisplayName("无窗口期写入：snapshotOffset 等于当前 offset，无需重放，直接 ONLINE")
    void testNoWindowCommandsToReplay() throws Exception {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.FULL_SYNC);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING | SlaveInfo.SLAVE_FLAG_FULL_SYNC);
        slave.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);

        // 预先写入数据
        byte[] preCommand = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes();
        backlog.append(preCommand);

        ControllableSnapshotGenerator generator = injectControllableGenerator();
        // 不写入任何窗口期命令

        assertTrue(manager.performFullSync(channel));
        awaitSlaveOnline(slave, 5000);

        assertEquals(ReplicationState.ONLINE, slave.getState());
        assertTrue(slave.isOnline());
        assertEquals(backlog.getMasterReplOffset(), slave.getOffset());
        // slave offset 应等于 snapshotOffset（preCommand.length），无窗口期增量
        assertEquals(preCommand.length, slave.getOffset());
    }

    // ==================== 辅助方法 ====================

    private ControllableSnapshotGenerator injectControllableGenerator() {
        ControllableSnapshotGenerator generator = new ControllableSnapshotGenerator(backlog);
        manager.setSnapshotGenerator(generator);
        return generator;
    }

    private void awaitSlaveOnline(SlaveInfo slave, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (slave.isOnline()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /**
     * 收集所有通过 channel.writeAndFlush 写入的字节数据。
     */
    private List<byte[]> collectWrittenBytes(Channel ch) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ch, atLeast(0)).writeAndFlush(captor.capture());
        List<byte[]> result = new ArrayList<>();
        for (Object o : captor.getAllValues()) {
            if (o instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) o;
                byte[] bytes = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), bytes);
                result.add(bytes);
            } else if (o instanceof byte[]) {
                result.add((byte[]) o);
            }
        }
        return result;
    }

    private void assertContainsConcatenation(List<byte[]> written, byte[] expected, String message) {
        int total = 0;
        for (byte[] b : written) {
            total += b.length;
        }
        byte[] all = new byte[total];
        int pos = 0;
        for (byte[] b : written) {
            System.arraycopy(b, 0, all, pos, b.length);
            pos += b.length;
        }
        assertTrue(contains(all, expected), message);
    }

    private int countOccurrences(List<byte[]> written, byte[] expected) {
        int total = 0;
        for (byte[] b : written) {
            total += b.length;
        }
        byte[] all = new byte[total];
        int pos = 0;
        for (byte[] b : written) {
            System.arraycopy(b, 0, all, pos, b.length);
            pos += b.length;
        }
        return countMatches(all, expected);
    }

    private boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return true;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private int countMatches(byte[] haystack, byte[] needle) {
        if (needle.length == 0) {
            return 0;
        }
        int count = 0;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            count++;
        }
        return count;
    }

    /**
     * 可控的 RdbSnapshotGenerator：在 snapshotOffset 记录后、返回前触发回调，
     * 模拟 RDB 落盘后的窗口期写入。不依赖真实 RDB 文件。
     */
    static class ControllableSnapshotGenerator extends RdbSnapshotGenerator {
        private final ReplicationBacklog backlog;
        volatile Runnable onAfterSnapshotOffset;
        volatile long capturedSnapshotOffset = -1;

        ControllableSnapshotGenerator(ReplicationBacklog backlog) {
            super(null, System.getProperty("java.io.tmpdir"));
            this.backlog = backlog;
        }

        @Override
        public SnapshotResult generateAndTransfer(MemoryStore memoryStore, Channel channel,
                                                  TransferProgressMonitor progressMonitor,
                                                  ReplicationBacklog backlogParam) {
            // 模拟 RDB 落盘后记录 snapshotOffset
            long snapshotOffset = backlog != null ? backlog.getMasterReplOffset() : -1;
            capturedSnapshotOffset = snapshotOffset;

            // 触发窗口期写入（在 snapshotOffset 记录后、返回前）
            if (onAfterSnapshotOffset != null) {
                onAfterSnapshotOffset.run();
            }

            // 模拟成功传输 1 字节（>0 表示成功）
            return new SnapshotResult(1, snapshotOffset);
        }
    }
}
