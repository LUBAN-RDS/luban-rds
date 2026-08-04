package com.janeluo.luban.rds.core.store;

import com.janeluo.luban.rds.core.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValueSerialization 对 ZSET/STREAM 值对象的序列化往返测试（N-31）。
 * <p>
 * 回归保护：MIGRATE 迁移 ZSET/STREAM 键时，值类型 ZSetStore（曾未实现 Serializable）
 * 与 Stream（含锁/Logger/等待队列不可序列化）曾直接抛 NotSerializableException，
 * 导致 dumpKey 返回 null、迁移报 -ERR error dumping key。
 * </p>
 */
class ValueSerializationRoundTripTest {

    @Test
    @DisplayName("N-31：ZSetStore 序列化往返后语义不变")
    void testZSetStoreRoundTrip() throws Exception {
        MemoryStore store = new DefaultMemoryStore();
        store.zadd(0, "zset-key", 1.5, "member1");
        store.zadd(0, "zset-key", 2.5, "member2");

        Object value = store.get(0, "zset-key");
        assertNotNull(value, "ZSET 键的存储值不应为 null");

        byte[] bytes = ValueSerialization.serialize(value);
        assertTrue(bytes.length > 0, "序列化不应抛 NotSerializableException");

        Object restored = ValueSerialization.deserialize(bytes);
        assertNotNull(restored);

        // 还原对象应能作为 ZSetStore 重新落库且语义不变
        MemoryStore store2 = new DefaultMemoryStore();
        store2.set(0, "zset-copy", restored);
        assertEquals(1.5, store2.zscore(0, "zset-copy", "member1"));
        assertEquals(2.5, store2.zscore(0, "zset-copy", "member2"));
        assertNull(store2.zscore(0, "zset-copy", "missing"));
    }

    @Test
    @DisplayName("N-31：Stream 序列化往返后消息不丢失且可继续写入")
    void testStreamRoundTrip() throws Exception {
        MemoryStore store = new DefaultMemoryStore();
        Map<String, String> fields = new HashMap<>();
        fields.put("field1", "value1");
        store.xadd(0, "stream-key", null, fields, false, null, null, null, false);
        Map<String, String> fields2 = new HashMap<>();
        fields2.put("field2", "value2");
        store.xadd(0, "stream-key", null, fields2, false, null, null, null, false);

        Object value = store.get(0, "stream-key");
        assertNotNull(value);
        assertTrue(value instanceof Stream, "存储值应为 Stream 实例");

        byte[] bytes = ValueSerialization.serialize(value);
        assertTrue(bytes.length > 0, "序列化不应抛 NotSerializableException");

        Object restored = ValueSerialization.deserialize(bytes);
        assertTrue(restored instanceof Stream, "反序列化应还原为 Stream 实例");

        // 还原对象应能作为 Stream 重新落库：消息不丢失、ID 游标可用
        MemoryStore store2 = new DefaultMemoryStore();
        store2.set(0, "stream-copy", restored);
        assertEquals(2, store2.xlen(0, "stream-copy"), "往返后消息数应不变");

        // 重建的锁/游标应可用：继续 XADD 自动生成 ID 不抛异常
        Map<String, String> fields3 = new HashMap<>();
        fields3.put("field3", "value3");
        store2.xadd(0, "stream-copy", null, fields3, false, null, null, null, false);
        assertEquals(3, store2.xlen(0, "stream-copy"), "还原后的流应可继续写入");
    }
}
