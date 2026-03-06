package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

public class DebugHscanTest2 {
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
        // Test with key that doesn't exist
        String[] args = new String[]{RdsCommandConstant.HSCAN, "nonexistent", "0", "COUNT", "100"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        System.out.println("===== NONEEXISTENT KEY =====");
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
        
        // Test with existing key
        store.hset(db, "ignew-mysql1:session:attr:{1aaa456f-d4c0-4f1b-a084-36c471d4e44e}", "a1", "v1");
        String[] args2 = new String[]{RdsCommandConstant.HSCAN, "ignew-mysql1:session:attr:{1aaa456f-d4c0-4f1b-a084-36c471d4e44e}", "0", "COUNT", "100"};
        Object resp2 = handler.handle(db, args2, store);
        String s2 = String.valueOf(resp2);
        System.out.println("===== EXISTING KEY =====");
        System.out.println(s2);
        System.out.println("===== RAW BYTES =====");
        for (byte b : s2.getBytes()) {
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
    }
}