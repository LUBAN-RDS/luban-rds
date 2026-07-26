package com.janeluo.luban.rds.replication;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * slave offset + WAIT + INFO replication 端到端验证（C6）
 *
 * <p>C6 是 C2+C5 的下游症状（slave offset 恒为 0）。本测试验证在 C2（PSYNC 路由 +
 * REPLCONF 逐条等待 + onOnline/sendAck）和 C5（窗口期重放 + offset 对齐）修复后：
 *
 * <ol>
 *   <li>slave 上报 {@code REPLCONF ACK <offset>} 后，master 的 {@link SlaveInfo#getOffset()}
 *       反映真实偏移量（不再恒为 0）。</li>
 *   <li>{@link MasterReplicationManager#getSyncedSlavesCount(long)} 基于 slave 真实 offset
 *       统计已同步副本数。</li>
 *   <li>{@link WaitCommandExecutor#execute(int, long)} 基于真实 slave offset 统计，
 *       能在 slave ACK 到位后返回正确副本数。</li>
 *   <li>{@code INFO replication} 的 {@code slave0:...,offset=<n>} 行反映真实偏移量。</li>
 * </ol>
 *
 * <p>这些路径在 C2 修复前因 slave 从未发送 ACK、{@code propagateCommand} 的
 * {@code isOnline()} 恒为 false 而 all-dead：slave offset 恒为 0、WAIT 恒返回 0、
 * INFO 恒报 offset=0。本测试以 C2+C5 修复后的状态机为前提，断言端到端正确性。
 */
class SlaveOffsetWaitVerificationTest {

    @Mock
    private Channel channel;

    private MasterReplicationManager manager;
    private ReplicationBacklog backlog;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.100", 6379));
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel));

        // 每个用例独立初始化单例，避免跨用例状态污染
        MasterReplicationManager.initialize(1024 * 1024);
        manager = MasterReplicationManager.getInstance();
        backlog = manager.getBacklog();
        backlog.clear();

        for (SlaveInfo slave : manager.getSlaves()) {
            manager.removeSlave(slave.getChannel());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (SlaveInfo slave : manager.getSlaves()) {
            manager.removeSlave(slave.getChannel());
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    // ==================== 1. REPLCONF ACK 更新 slave offset ====================

    @Test
    @DisplayName("REPLCONF ACK 上报后 SlaveInfo.offset 反映真实偏移量（不再恒为 0）")
    void testReplconfAckUpdatesSlaveOffset() {
        SlaveInfo slave = manager.addSlave(channel);
        assertEquals(0, slave.getOffset(), "新连接 slave offset 初始应为 0");

        // 模拟 slave 全量同步完成后上报 ACK offset=5000
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "5000"});

        assertNull(result, "ACK 分支应返回 null（无响应体）");
        assertEquals(5000, slave.getOffset(), "ACK 后 slave offset 应为上报值");
        assertEquals(ReplicationState.ONLINE, slave.getState(), "ACK 后 slave 应进入 ONLINE");
        assertTrue(slave.isOnline(), "ACK 后 slave.isOnline() 应为 true");
        assertTrue(slave.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE), "应设置 ONLINE flag");
    }

    @Test
    @DisplayName("REPLCONF ACK 多次上报 offset 单调递增反映真实进度")
    void testReplconfAckProgressiveOffset() {
        SlaveInfo slave = manager.addSlave(channel);

        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "100"});
        assertEquals(100, slave.getOffset());

        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "350"});
        assertEquals(350, slave.getOffset());

        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "1000"});
        assertEquals(1000, slave.getOffset(), "offset 应跟随最新 ACK 上报值");
    }

    @Test
    @DisplayName("propagateCommand 在 slave ONLINE 后累加 slave offset")
    void testPropagateCommandIncrementsSlaveOffset() {
        SlaveInfo slave = manager.addSlave(channel);
        // 模拟 slave 已通过 ACK 进入 ONLINE
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "0"});
        assertTrue(slave.isOnline());
        assertEquals(0, slave.getOffset());

        byte[] command = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n".getBytes();
        long masterBefore = backlog.getMasterReplOffset();

        manager.propagateCommand(command);

        // master offset 推进 command.length
        assertEquals(masterBefore + command.length, backlog.getMasterReplOffset());
        // slave offset 也应累加 command.length（C2 修复前 isOnline() 恒 false，此处恒为 0）
        assertEquals(command.length, slave.getOffset(),
            "propagateCommand 应在 slave online 时累加 slave offset");
    }

    // ==================== 2. getSyncedSlavesCount 返回真实值 ====================

    @Test
    @DisplayName("getSyncedSlavesCount 基于 slave 真实 offset 统计已同步副本数")
    void testGetSyncedSlavesCountRealOffset() {
        SlaveInfo slave = manager.addSlave(channel);
        // slave offset=0，未 ONLINE，不计入
        assertEquals(0, manager.getSyncedSlavesCount(0),
            "未 ONLINE 的 slave 不应计入已同步副本");

        // slave 上报 ACK=1000 并进入 ONLINE
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "1000"});

        assertEquals(1, manager.getSyncedSlavesCount(0), "offset>=0 应计数 1");
        assertEquals(1, manager.getSyncedSlavesCount(1000), "offset>=1000 应计数 1");
        assertEquals(0, manager.getSyncedSlavesCount(1001), "offset>=1001 应计数 0（slave 仅到 1000）");
    }

    @Test
    @DisplayName("getSyncedSlavesCount 多 slave 场景按各自真实 offset 统计")
    void testGetSyncedSlavesCountMultipleSlaves() {
        // 第二个 slave 用独立的 channel mock
        Channel channel2 = mock(Channel.class);
        when(channel2.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.101", 6380));
        when(channel2.isActive()).thenReturn(true);
        when(channel2.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel2));

        SlaveInfo slave1 = manager.addSlave(channel);
        SlaveInfo slave2 = manager.addSlave(channel2);

        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "500"});
        manager.handleReplconf(channel2, new String[]{"REPLCONF", "ack", "800"});

        assertEquals(2, manager.getSyncedSlavesCount(0), "两个 slave 都 >=0");
        assertEquals(2, manager.getSyncedSlavesCount(500), "两个 slave 都 >=500");
        assertEquals(1, manager.getSyncedSlavesCount(501), "仅 slave2(offset=800) >=501");
        assertEquals(1, manager.getSyncedSlavesCount(800), "仅 slave2(offset=800) >=800");
        assertEquals(0, manager.getSyncedSlavesCount(801), "无 slave >=801");

        // 清理第二个 slave，避免污染后续用例
        manager.removeSlave(channel2);
    }

    @Test
    @DisplayName("getSyncedSlavesCount 在 propagateCommand 推进 master offset 后正确反映未同步")
    void testGetSyncedSlavesCountAfterPropagate() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "0"});
        assertTrue(slave.isOnline());

        // master 写入一条命令，master offset 推进，但 slave 尚未 ACK 新 offset
        byte[] command = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes();
        manager.propagateCommand(command);

        long masterOffset = backlog.getMasterReplOffset();
        // propagateCommand 内部已 incrementOffset，slave offset 跟上了 master
        assertEquals(masterOffset, slave.getOffset());
        assertEquals(1, manager.getSyncedSlavesCount(masterOffset),
            "slave offset 跟随 master offset，应判定为已同步");
    }

    // ==================== 3. WAIT 命令基于真实 slave offset 统计 ====================

    @Test
    @DisplayName("WAIT timeout=0 基于真实 slave offset 返回已同步副本数")
    void testWaitImmediateReturnsRealSyncedCount() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "2000"});

        WaitCommandExecutor executor = new WaitCommandExecutor(manager);

        // master offset=0（未写入命令），slave offset=2000 >= 0，应返回 1
        int synced = executor.execute(1, 0);
        assertEquals(1, synced, "WAIT 应基于真实 slave offset 返回 1 个已同步副本");

        // 写入命令后 master offset 推进，slave offset 同步推进
        byte[] command = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes();
        manager.propagateCommand(command);
        long masterOffset = backlog.getMasterReplOffset();

        // 再次 WAIT，slave offset 已通过 propagateCommand 跟上 master offset
        int synced2 = executor.execute(1, 0);
        assertEquals(1, synced2, "slave offset 跟随 master，WAIT 应返回 1");
        assertTrue(slave.getOffset() >= masterOffset, "slave offset 应 >= master offset");
    }

    @Test
    @DisplayName("WAIT timeout=0 无已同步 slave 时返回 0（C2 修复前的症状不应复现）")
    void testWaitImmediateNoSyncedSlave() {
        // 添加 slave 但不上报 ACK（未 ONLINE）
        manager.addSlave(channel);

        WaitCommandExecutor executor = new WaitCommandExecutor(manager);
        int synced = executor.execute(1, 0);
        assertEquals(0, synced, "未 ONLINE 的 slave 不应被 WAIT 计入");
    }

    @Test
    @DisplayName("WAIT 带超时：slave ACK 到位后满足数量立即返回")
    void testWaitWithTimeoutSatisfiedImmediately() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "0"});
        // 写入命令使 master offset > 0
        byte[] command = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes();
        manager.propagateCommand(command);
        // slave offset 已通过 propagateCommand 跟上
        long masterOffset = backlog.getMasterReplOffset();
        assertEquals(masterOffset, slave.getOffset());

        WaitCommandExecutor executor = new WaitCommandExecutor(manager);
        long start = System.currentTimeMillis();
        int synced = executor.execute(1, 2000);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, synced, "slave 已同步，WAIT 应立即返回 1");
        assertTrue(elapsed < 500, "已满足条件应快速返回，不应等满超时（实际 " + elapsed + "ms）");
    }

    @Test
    @DisplayName("WAIT 带超时：slave 始终未同步则超时返回当前已同步数")
    void testWaitWithTimeoutUnsatisfied() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "0"});
        // master 写入命令推进 offset，但模拟 slave 处理慢：
        // 这里 slave 通过 propagateCommand 已 incrementOffset 跟上，所以为了构造"未同步"场景，
        // 直接把 slave offset 回拨到 0（模拟 slave 尚未处理该命令、未回 ACK）
        byte[] command = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes();
        manager.propagateCommand(command);
        slave.updateOffset(0); // 模拟 slave 实际处理进度落后

        long masterOffset = backlog.getMasterReplOffset();
        assertTrue(masterOffset > 0);
        assertTrue(slave.getOffset() < masterOffset, "slave offset 应落后于 master");

        WaitCommandExecutor executor = new WaitCommandExecutor(manager);
        long start = System.currentTimeMillis();
        int synced = executor.execute(1, 300);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(0, synced, "slave 未同步到 master offset，应返回 0");
        assertTrue(elapsed >= 250, "应等满超时（实际 " + elapsed + "ms）");
    }

    @Test
    @DisplayName("WAIT numSlaves<=0 返回 0")
    void testWaitZeroOrNegativeNumSlaves() {
        WaitCommandExecutor executor = new WaitCommandExecutor(manager);
        assertEquals(0, executor.execute(0, 1000));
        assertEquals(0, executor.execute(-1, 1000));
    }

    // ==================== 4. INFO replication 报告真实 offset ====================

    @Test
    @DisplayName("INFO replication 的 slave0 行 offset 反映真实偏移量")
    void testInfoReplicationReportsRealOffset() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "12345"});

        String info = manager.getReplicationInfo();

        assertTrue(info.contains("# Replication"), "应包含 # Replication 段");
        assertTrue(info.contains("role:master"), "应报告 role:master");
        assertTrue(info.contains("connected_slaves:1"), "应报告 connected_slaves:1");

        // 解析 slave0 行
        long reportedOffset = extractSlaveOffset(info, 0);
        assertEquals(12345, reportedOffset,
            "INFO replication 的 slave0 offset 应为 ACK 上报的真实值（C2 修复前恒为 0）");
    }

    @Test
    @DisplayName("INFO replication offset 跟随 slave ACK 进度更新")
    void testInfoReplicationOffsetTracksAck() {
        SlaveInfo slave = manager.addSlave(channel);

        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "100"});
        assertEquals(100, extractSlaveOffset(manager.getReplicationInfo(), 0));

        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "9999"});
        assertEquals(9999, extractSlaveOffset(manager.getReplicationInfo(), 0),
            "INFO replication offset 应跟随最新 ACK 更新");
    }

    @Test
    @DisplayName("INFO replication 在 propagateCommand 后反映累加后的 slave offset")
    void testInfoReplicationAfterPropagate() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "0"});

        byte[] command = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n".getBytes();
        manager.propagateCommand(command);

        String info = manager.getReplicationInfo();
        long reported = extractSlaveOffset(info, 0);
        assertEquals(command.length, reported,
            "INFO replication 应报告 propagateCommand 累加后的 slave offset");
        assertEquals(backlog.getMasterReplOffset(), reported,
            "slave offset 应等于 master offset（已同步）");
    }

    @Test
    @DisplayName("INFO replication slave 行包含 state=online（ACK 后状态正确）")
    void testInfoReplicationSlaveStateOnline() {
        SlaveInfo slave = manager.addSlave(channel);
        manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "0"});

        String info = manager.getReplicationInfo();
        // slave0 行应包含 state=online
        Pattern p = Pattern.compile("slave0:.*state=(\\w+)");
        Matcher m = p.matcher(info);
        assertTrue(m.find(), "应能匹配 slave0 state");
        assertEquals("online", m.group(1), "ACK 后 slave state 应为 online");
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 INFO replication 输出中解析第 index 个 slave 的 offset 字段。
     */
    private long extractSlaveOffset(String info, int index) {
        String prefix = "slave" + index + ":";
        int start = info.indexOf(prefix);
        assertNotEquals(-1, start, "INFO 中应包含 " + prefix + " 行");
        int end = info.indexOf("\r\n", start);
        assertNotEquals(-1, end, "slave 行应以 \\r\\n 结尾");
        String line = info.substring(start, end);
        Pattern p = Pattern.compile("offset=(\\d+)");
        Matcher m = p.matcher(line);
        assertTrue(m.find(), "slave 行应包含 offset=<n>: " + line);
        return Long.parseLong(m.group(1));
    }
}
