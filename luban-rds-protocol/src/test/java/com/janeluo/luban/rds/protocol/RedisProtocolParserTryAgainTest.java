package com.janeluo.luban.rds.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

/**
 * 验证 "TRYAGAIN ..." 字符串被序列化为 Redis 标准错误响应 -TRYAGAIN ...\r\n。
 */
public class RedisProtocolParserTryAgainTest {

    @Test
    public void serializeTryAgain_producesErrorResponse() {
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf result = parser.serialize("TRYAGAIN leadership lost; retry");
        Assert.assertNotNull(result);
        byte[] bytes = new byte[result.readableBytes()];
        result.getBytes(result.readerIndex(), bytes);
        String resp = new String(bytes, StandardCharsets.ISO_8859_1);
        result.release();
        Assert.assertTrue("应以 -TRYAGAIN 开头，实际: " + resp, resp.startsWith("-TRYAGAIN "));
        Assert.assertTrue("应以 \\r\\n 结尾", resp.endsWith("\r\n"));
    }

    @Test
    public void serializeTryAgain_preservesMessage() {
        RedisProtocolParser parser = new RedisProtocolParser();
        String detail = "leadership lost; propose aborted, retry";
        ByteBuf result = parser.serialize("TRYAGAIN " + detail);
        byte[] bytes = new byte[result.readableBytes()];
        result.getBytes(result.readerIndex(), bytes);
        String resp = new String(bytes, StandardCharsets.ISO_8859_1);
        result.release();
        Assert.assertTrue("应包含原始消息", resp.contains(detail));
    }
}
