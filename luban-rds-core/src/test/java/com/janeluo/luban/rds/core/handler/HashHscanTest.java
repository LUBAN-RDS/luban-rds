package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class HashHscanTest {
    private MemoryStore store;
    private HashCommandHandler handler;
    private final int db = 0;

    @Before
    public void setUp() {
        store = new DefaultMemoryStore();
        handler = new HashCommandHandler();
        // prepare fields
        store.hset(db, "hk", "a1", "v1");
        store.hset(db, "hk", "a2", "v2");
        store.hset(db, "hk", "b1", "v3");
    }

    @Test
    public void testHscanBasic() {
        String[] args = new String[]{RdsCommandConstant.HSCAN, "hk", "0", "MATCH", "a*", "COUNT", "10"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        assertTrue(s.startsWith("*2\r\n"));
        assertTrue(s.contains("$1\r\n0\r\n")); // cursor 0 bulk
        assertTrue(s.contains("$2\r\na1\r\n"));
        assertTrue(s.contains("$2\r\nv1\r\n"));
        assertTrue(s.contains("$2\r\na2\r\n"));
        assertTrue(s.contains("$2\r\nv2\r\n"));
    }
}
