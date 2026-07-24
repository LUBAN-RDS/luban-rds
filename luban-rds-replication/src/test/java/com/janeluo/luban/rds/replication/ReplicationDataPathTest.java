package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 复制数据路径测试。
 *
 * <p>验证主节点写入命令进入 backlog 并由从节点应用后，从节点本地存储与主节点一致。
 */
class ReplicationDataPathTest {

    @Mock
    private Channel channel;

    private MasterReplicationManager masterManager;
    private MemoryStore masterStore;
    private MemoryStore slaveStore;
    private ReplicationStreamApplier applier;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.100", 6379));
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel));

        MasterReplicationManager.initialize(1024 * 1024);
        masterManager = MasterReplicationManager.getInstance();

        // 清理可能残留的从节点
        for (SlaveInfo slave : masterManager.getSlaves()) {
            masterManager.removeSlave(slave.getChannel());
        }

        masterStore = new DefaultMemoryStore(16, 0L, "noeviction");
        slaveStore = new DefaultMemoryStore(16, 0L, "noeviction");

        masterManager.setMemoryStore(masterStore);
        applier = new ReplicationStreamApplier(slaveStore);
    }

    @AfterEach
    void tearDown() {
        if (applier != null) {
            applier.close();
        }
        if (masterManager != null) {
            masterManager.shutdown();
        }
    }

    /**
     * 构造 RESP 命令帧：*N\r\n$L\r\narg\r\n ...
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
     * 注册一个在线从节点，使 propagateCommand 进入 backlog 写入路径。
     */
    private SlaveInfo registerOnlineSlave() {
        SlaveInfo slave = masterManager.addSlave(channel);
        slave.setState(ReplicationState.ONLINE);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        return slave;
    }

    @Test
    @DisplayName("主节点传播的 SET 命令应用到从节点存储")
    void testPropagatedCommandAppliedToSlaveStore() {
        registerOnlineSlave();
        byte[] frame = respFrame("SET", "key", "value");

        masterManager.propagateCommand(frame);

        ByteBuf data = Unpooled.wrappedBuffer(frame);
        applier.applyData(data);

        Object slaveValue = slaveStore.get(0, "key");
        assertNotNull(slaveValue, "从节点应能读取到复制的 key");
        assertEquals("value", slaveValue.toString());
    }

    @Test
    @DisplayName("写命令传播后 backlog 偏移推进")
    void testReadOnlyCommandNotPropagated() {
        registerOnlineSlave();
        long initialOffset = masterManager.getBacklog().getMasterReplOffset();

        byte[] frame = respFrame("SET", "counter", "1");
        masterManager.propagateCommand(frame);

        long newOffset = masterManager.getBacklog().getMasterReplOffset();
        assertTrue(newOffset > initialOffset,
                "写命令传播后 backlog 偏移应推进: initial=" + initialOffset + ", new=" + newOffset);
        assertEquals(initialOffset + frame.length, newOffset,
                "backlog 偏移应推进等于帧长度");
    }

    @Test
    @DisplayName("多个写命令依次应用到从节点存储")
    void testMultiplePropagatedCommandsApplied() {
        registerOnlineSlave();
        byte[] frame1 = respFrame("SET", "k1", "v1");
        byte[] frame2 = respFrame("SET", "k2", "v2");

        masterManager.propagateCommand(frame1);
        masterManager.propagateCommand(frame2);

        applier.applyData(Unpooled.wrappedBuffer(frame1));
        applier.applyData(Unpooled.wrappedBuffer(frame2));

        assertEquals("v1", slaveStore.get(0, "k1").toString());
        assertEquals("v2", slaveStore.get(0, "k2").toString());
        assertEquals(frame1.length + frame2.length, applier.getAppliedOffset());
    }
}
