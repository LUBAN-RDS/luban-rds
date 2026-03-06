package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class DebugHscanProtocol {
    private MemoryStore store;
    private HashCommandHandler handler;
    private final int db = 0;

    @Before
    public void setUp() {
        store = new DefaultMemoryStore();
        handler = new HashCommandHandler();
    }

    @Test
    public void testProtocol() {
        store.hset(db, "ignew-mysql1:session:attr:{1aaa456f-d4c0-4f1b-a084-36c471d4e44e}", "a1", "v1");
        String[] args = new String[]{RdsCommandConstant.HSCAN, "ignew-mysql1:session:attr:{1aaa456f-d4c0-4f1b-a084-36c471d4e44e}", "0", "COUNT", "100"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        
        System.out.println("=== RAW RESPONSE ===");
        System.out.println(s);
        
        // Test serialize
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf buf = parser.serialize(resp);
        
        System.out.println("\n=== SERIALIZED RESPONSE ===");
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        String serialized = new String(bytes, StandardCharsets.UTF_8);
        System.out.println(serialized);
        
        System.out.println("\n=== SERIALIZED BYTES ===");
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b >= 32 && b < 127) {
                System.out.print((char)b + " ");
            } else if (b == '\r') {
                System.out.print("\\r ");
            } else if (b == '\n') {
                System.out.print("\\n ");
            } else {
                System.out.print(String.format("[%02x] ", b));
            }
        }
        System.out.println();
        
        // What does serialize do with strings starting with $?
        String testStr = "$1\r\n0\r\n";
        System.out.println("\n=== TEST: serialize string starting with $ ===");
        ByteBuf testBuf = parser.serialize(testStr);
        byte[] testBytes = new byte[testBuf.readableBytes()];
        testBuf.readBytes(testBytes);
        System.out.println("Input:  " + testStr);
        System.out.println("Output: " + new String(testBytes, StandardCharsets.ISO_8859_1));
        
        buf.release();
        testBuf.release();
    }
}