package com.janeluo.luban.rds.protocol;

import io.netty.buffer.ByteBuf;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class TransactionResponseTest {
    
    @Test
    public void testSerializeTransactionResults() {
        RedisProtocolParser parser = new RedisProtocolParser();
        
        // 模拟事务执行结果
        List<Object> results = Arrays.asList(1L, 6L);
        
        // 序列化
        ByteBuf buf = parser.serialize(results);
        
        // 读取字节数据
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        String response = new String(bytes, StandardCharsets.UTF_8);
        
        System.out.println("Serialized response: " + response);
        System.out.println("Hex: " + bytesToHex(bytes));
        
        // 验证格式
        // 期望格式: *2\r\n:1\r\n:6\r\n
        assertTrue("Response should start with *2\\r\\n", response.startsWith("*2\r\n"));
        assertTrue("Response should contain :1\\r\\n", response.contains(":1\r\n"));
        assertTrue("Response should contain :6\\r\\n", response.contains(":6\r\n"));
        
        buf.release();
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
            if (b >= 32 && b <= 126) {
                sb.append('(').append((char) b).append(')');
            }
        }
        return sb.toString();
    }
}
