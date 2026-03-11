package com.janeluo.luban.rds.core.stream;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class StreamTest {

    @Test
    public void testAddMultipleEntries() {
        Stream stream = new Stream();
        
        // 添加5个条目
        for (int i = 1; i <= 5; i++) {
            Map<String, String> fields = new HashMap<>();
            fields.put("field", "value" + i);
            StreamId id = new StreamId(1000L * i, 0L);
            stream.addEntry(id, fields);
            System.out.println("Added entry with ID: " + id + ", total entries: " + stream.getLength());
        }
        
        // 检查流的长度
        assertEquals("Stream should have 5 entries", 5, stream.getLength());
        
        // 测试反向范围查询
        StreamId maxId = StreamId.MAX_ID;
        StreamId minId = StreamId.MIN_ID;
        var entries = stream.getRangeReverse(minId, maxId, false, false, 2);
        
        // 检查结果
        assertEquals("Should return 2 entries", 2, entries.size());
        assertEquals("First entry ID should be 5000-0", new StreamId(5000L, 0L), entries.get(0).getId());
        assertEquals("Second entry ID should be 4000-0", new StreamId(4000L, 0L), entries.get(1).getId());
        
        System.out.println("Test passed!");
    }
}
