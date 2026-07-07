package com.janeluo.luban.rds.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 parseResp 对 TCP 半包的处理：响应跨多个 TCP 段时，首段不完整应返回 null 并回退 readerIndex，
 * 累积完整后应正确解析。复现 CLI 创建集群时 CLUSTER NODES bulk 响应半包导致 "无响应" 的根因。
 *
 * @author janeluo
 * @since 1.0.2
 */
public class ClusterNodesPartialParseTest {

    /**
     * 模拟 CLUSTER NODES 的 bulk 响应被拆成两段：
     * 第一段仅含长度行和部分内容（半包），parseResp 应返回 null 且 readerIndex 回退；
     * 累积完整内容后应解析为完整字符串。
     */
    @Test
    public void testBulkStringPartialThenComplete() {
        RedisProtocolParser parser = new RedisProtocolParser();

        // 构造一个 CLUSTER NODES 风格的 bulk payload（含裸 \n 行尾，对齐 clusterNodes() 输出）
        String nodesText = "0426e79a0195ebdb7148a664e2fb44a4b5cf0552 127.0.0.1:9736@19736 master - 0 0 0 connected 0-5461\n"
                + "9d8cd03fb888b24ba8c0b68aec40742c28650292 127.0.0.1:9737@19737 master - 0 0 0 connected 5462-10922\n";
        String fullResp = "$" + nodesText.length() + "\r\n" + nodesText + "\r\n";
        byte[] fullBytes = fullResp.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        // 半包：仅前半字节
        int splitAt = 20;
        ByteBuf partial = Unpooled.wrappedBuffer(fullBytes, 0, splitAt);

        int readerBefore = partial.readerIndex();
        Object result = parser.parseResp(partial);
        assertNull("半包 bulk 应返回 null", result);
        assertEquals("半包时 readerIndex 应回退到解析起点（类型字节前）",
                readerBefore, partial.readerIndex());
        partial.release();

        // 完整：累积全部字节
        ByteBuf complete = Unpooled.wrappedBuffer(fullBytes);
        Object fullResult = parser.parseResp(complete);
        assertNotNull("完整 bulk 不应返回 null", fullResult);
        assertTrue("应解析为 String", fullResult instanceof String);
        assertEquals("内容应与原始 nodesText 一致", nodesText, fullResult);
        complete.release();
    }

    /**
     * 模拟客户端 handler 累积 buffer 的循环解析：
     * 第一次喂入半包，第二次喂入剩余，最终应得到完整响应。
     */
    @Test
    public void testAccumulatedParsingAcrossSegments() {
        RedisProtocolParser parser = new RedisProtocolParser();

        String nodesText = "abc123 127.0.0.1:9736@19736 master - 0 0 0 connected 0-5461\n";
        String fullResp = "$" + nodesText.length() + "\r\n" + nodesText + "\r\n";
        byte[] fullBytes = fullResp.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        ByteBuf accumulation = Unpooled.buffer(1024);

        // 第一段：长度行 + 部分内容
        accumulation.writeBytes(fullBytes, 0, 10);
        accumulation.markReaderIndex();
        Object r1 = parser.parseResp(accumulation);
        assertNull("首段半包应返回 null", r1);
        accumulation.resetReaderIndex();

        // 第二段：追加剩余字节
        accumulation.writeBytes(fullBytes, 10, fullBytes.length - 10);
        Object r2 = parser.parseResp(accumulation);
        assertNotNull("累积完整后应解析成功", r2);
        assertTrue("应解析为 String", r2 instanceof String);
        assertEquals("内容应一致", nodesText, r2);
        accumulation.release();
    }

    /**
     * 验证 $-1\r\n（合法 RESP null bulk）字节被完整消费，不回退，不会导致死循环。
     */
    @Test
    public void testNullBulkConsumedNotReset() {
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf buf = Unpooled.wrappedBuffer("$-1\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        Object result = parser.parseResp(buf);
        assertNull("$-1 应返回 null", result);
        assertEquals("$-1\\r\\n 应被完整消费，readerIndex 前进到末尾",
                buf.writerIndex(), buf.readerIndex());
        buf.release();
    }
}
