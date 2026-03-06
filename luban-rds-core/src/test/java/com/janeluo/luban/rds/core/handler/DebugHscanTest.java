package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

public class DebugHscanTest {
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
        store.hset(db, "ignew-mysql1:session:attr:{1aaa456f-d4c0-4f1b-a084-36c471d4e44e}", "a1", "v1");
        String[] args = new String[]{RdsCommandConstant.HSCAN, "ignew-mysql1:session:attr:{1aaa456f-d4c0-4f1b-a084-36c471d4e44e}", "0", "COUNT", "100"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        System.out.println("===== RESPONSE =====");
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
    }
}