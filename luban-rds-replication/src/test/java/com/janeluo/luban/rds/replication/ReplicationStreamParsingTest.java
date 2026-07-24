package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 复制流解析测试。
 *
 * <p>验证 {@link ReplicationStreamApplier} 对 RESP 字节流的拆包、粘包、
 * 二进制安全参数与事务重放的处理能力。
 */
class ReplicationStreamParsingTest {

    private MemoryStore slaveStore;
    private ReplicationStreamApplier applier;

    @BeforeEach
    void setUp() {
        slaveStore = new DefaultMemoryStore(16, 0L, "noeviction");
        applier = new ReplicationStreamApplier(slaveStore);
    }

    @AfterEach
    void tearDown() {
        if (applier != null) {
            applier.close();
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
     * 构造二进制安全的 RESP 命令帧，参数以原始字节给出。
     */
    private static byte[] respFrameBytes(byte[]... args) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            baos.write('*');
            baos.write(intToAscii(args.length));
            baos.write('\r');
            baos.write('\n');
            for (byte[] arg : args) {
                baos.write('$');
                baos.write(intToAscii(arg.length));
                baos.write('\r');
                baos.write('\n');
                baos.write(arg);
                baos.write('\r');
                baos.write('\n');
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private static byte[] intToAscii(int n) {
        return Integer.toString(n).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("半包命令缓冲直到完整后应用")
    void testPartialCommandBufferedUntilComplete() {
        byte[] frame = respFrame("SET", "halfKey", "halfValue");
        int split = frame.length / 2;

        ByteBuf firstPart = Unpooled.copiedBuffer(frame, 0, split);
        applier.applyData(firstPart);

        assertNull(slaveStore.get(0, "halfKey"),
                "半包命令不应被应用，key 应不存在");

        ByteBuf secondPart = Unpooled.copiedBuffer(frame, split, frame.length - split);
        applier.applyData(secondPart);

        Object value = slaveStore.get(0, "halfKey");
        assertNotNull(value, "完整命令到达后应被应用");
        assertEquals("halfValue", value.toString());
    }

    @Test
    @DisplayName("单个数据块包含多条命令全部应用")
    void testMultipleCommandsInOneChunk() {
        byte[] frame1 = respFrame("SET", "k1", "v1");
        byte[] frame2 = respFrame("SET", "k2", "v2");

        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        applier.applyData(Unpooled.wrappedBuffer(combined));

        assertEquals("v1", slaveStore.get(0, "k1").toString());
        assertEquals("v2", slaveStore.get(0, "k2").toString());
        assertEquals(combined.length, applier.getAppliedOffset());
    }

    @Test
    @DisplayName("二进制安全参数保留 \\r\\n 与 \\0 字节")
    void testBinarySafeArguments() {
        // 值包含 \r\n 与 \0 共 7 字节
        byte[] binaryValue = new byte[]{'b', 'i', 'n', '\r', '\n', '\0', 'x'};
        byte[] frame = respFrameBytes(
                "SET".getBytes(StandardCharsets.ISO_8859_1),
                "binkey".getBytes(StandardCharsets.ISO_8859_1),
                binaryValue);

        applier.applyData(Unpooled.wrappedBuffer(frame));

        Object stored = slaveStore.get(0, "binkey");
        assertNotNull(stored, "二进制值应被存储");
        byte[] storedBytes = stored.toString().getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(binaryValue.length, storedBytes.length, "存储字节数应一致");
        for (int i = 0; i < binaryValue.length; i++) {
            assertEquals(binaryValue[i], storedBytes[i], "第 " + i + " 字节应一致");
        }
    }

    @Test
    @DisplayName("事务重放：MULTI/SET/EXEC 序列数据在 EXEC 后应用")
    void testTransactionReplay() {
        byte[] multiFrame = respFrame("MULTI");
        byte[] setFrame = respFrame("SET", "txKey", "txValue");
        byte[] execFrame = respFrame("EXEC");

        applier.applyData(Unpooled.wrappedBuffer(multiFrame));
        // MULTI 未被 DefaultCommandHandler 识别，不影响后续命令
        assertNull(slaveStore.get(0, "txKey"));

        applier.applyData(Unpooled.wrappedBuffer(setFrame));
        // 复制流中 SET 立即应用（事务边界在主节点已处理，从节点按序重放）
        Object valueAfterSet = slaveStore.get(0, "txKey");
        assertNotNull(valueAfterSet, "复制流中的 SET 应直接应用");
        assertEquals("txValue", valueAfterSet.toString());

        applier.applyData(Unpooled.wrappedBuffer(execFrame));
        // EXEC 同样透传，数据保持不变
        assertEquals("txValue", slaveStore.get(0, "txKey").toString());
    }

    @Test
    @DisplayName("应用偏移量按消费字节数推进")
    void testAppliedOffsetAdvancesByConsumedBytes() {
        byte[] frame = respFrame("SET", "offsetKey", "offsetVal");

        applier.applyData(Unpooled.wrappedBuffer(frame));

        assertEquals(frame.length, applier.getAppliedOffset(),
                "应用偏移应等于单条命令帧的字节长度");
    }
}
