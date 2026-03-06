package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

public class DebugHscanTest3 {
    private MemoryStore store;
    private HashCommandHandler handler;
    private final int db = 0;

    @Before
    public void setUp() {
        store = new DefaultMemoryStore();
        handler = new HashCommandHandler();
    }

    @Test
    public void testDebug() {
        // Test with Chinese characters
        store.hset(db, "hk", "字段1", "值1");
        store.hset(db, "hk", "field2", "value2");
        
        String[] args = new String[]{RdsCommandConstant.HSCAN, "hk", "0", "COUNT", "100"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        System.out.println("===== WITH CHINESE =====");
        System.out.println(s);
        System.out.println("===== RAW BYTES =====");
        for (byte b : s.getBytes()) {
            if (b >= 32 && b < 127) {
                System.out.print((char)b + " ");
            } else if (b == '\r') {
                System.out.print("\\r ");
            } else if (b == '\n') {
                System.out.print("\\n ");
            } else {
                System.out.print(String.format("[%02x]", b) + " ");
            }
        }
        System.out.println();
        
        // Check what bulkString returns
        String field = "字段1";
        String bulk = com.janeluo.luban.rds.common.constant.RdsResponseConstant.bulkString(field);
        System.out.println("===== BULK STRING FOR '字段1' =====");
        System.out.println(bulk);
        System.out.println("Expected length: " + field.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        System.out.println("ISO-8859-1 length: " + field.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1).length);
    }
}