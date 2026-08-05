package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridSerializationTest {

    @Test
    void largeStringFromOffheapSerializeRoundTrip() throws Exception {
        HybridMemoryStore store = new HybridMemoryStore(16, 0, "noeviction", 100);
        String big = "x".repeat(500);
        store.set(0, "k", big);
        // get 返回堆上 String，经 ValueSerialization 序列化
        Object val = store.get(0, "k");
        byte[] bytes = ValueSerialization.serialize(val);
        Object back = ValueSerialization.deserialize(bytes);
        assertEquals(big, back);
        store.close();
    }
}
