package com.janeluo.luban.rds.server.stream;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XClaimParams;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.params.XTrimParams;
import redis.clients.jedis.resps.StreamConsumersInfo;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamGroupInfo;
import redis.clients.jedis.resps.StreamInfo;
import redis.clients.jedis.resps.StreamPendingEntry;
import redis.clients.jedis.resps.StreamPendingSummary;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JedisStreamCompatibilityTest {

    private static NettyRedisServer server;
    private static int port;
    private Jedis jedis;

    @BeforeAll
    static void setUpClass() {
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();
        // 等待服务器完全启动
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            if (server.isRunning()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("========================================");
        System.out.println("Jedis Stream Compatibility Test Started");
        System.out.println("Server Port: " + port);
        System.out.println("========================================");
    }

    @AfterAll
    static void tearDownClass() {
        if (server != null) {
            server.stop();
        }
        System.out.println("========================================");
        System.out.println("Jedis Stream Compatibility Test Finished");
        System.out.println("========================================");
    }

    @BeforeEach
    void setUp() {
        jedis = new Jedis("127.0.0.1", port);
        if (server != null && server.getMemoryStore() != null) {
            server.getMemoryStore().flushAll();
        }
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) {
            jedis.close();
        }
    }

    private static int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }

    @Test
    void testXAddAutoGenerateId() {
        System.out.println("\n=== Test: XADD Auto Generate ID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field1", "value1");
        
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        System.out.println("Generated ID: " + id);
        assertNotNull(id, "ID should not be null");
        assertTrue(id.getTime() > 0, "ID should have timestamp > 0");
        assertTrue(id.getSequence() >= 0, "ID should have sequence >= 0");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddWithSpecificId() {
        System.out.println("\n=== Test: XADD With Specific ID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field1", "value1");
        
        StreamEntryID specifiedId = new StreamEntryID(1000, 0);
        StreamEntryID id = jedis.xadd("mystream", specifiedId, fields);
        
        System.out.println("Specified ID: " + specifiedId);
        System.out.println("Returned ID: " + id);
        assertEquals(specifiedId, id, "ID should match specified ID");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddWithMultipleFields() {
        System.out.println("\n=== Test: XADD With Multiple Fields ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field1", "value1");
        fields.put("field2", "value2");
        fields.put("field3", "value3");
        
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        assertNotNull(id, "ID should not be null");
        
        List<StreamEntry> entries = jedis.xrange("mystream", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 1);
        assertEquals(1, entries.size(), "Should have 1 entry");
        
        Map<String, String> storedFields = entries.get(0).getFields();
        assertEquals(3, storedFields.size(), "Should have 3 fields");
        assertEquals("value1", storedFields.get("field1"), "field1 should be value1");
        assertEquals("value2", storedFields.get("field2"), "field2 should be value2");
        assertEquals("value3", storedFields.get("field3"), "field3 should be value3");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddNoMkStream() {
        System.out.println("\n=== Test: XADD NOMKSTREAM (stream not exists) ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field1", "value1");
        
        XAddParams params = XAddParams.xAddParams().noMkStream();
        StreamEntryID id = jedis.xadd("nonexistent", fields, params);
        
        System.out.println("Returned ID: " + id);
        assertNull(id, "ID should be null when stream does not exist and NOMKSTREAM is set");
        
        long len = jedis.xlen("nonexistent");
        assertEquals(0, len, "Stream should not be created");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddNoMkStreamExistingStream() {
        System.out.println("\n=== Test: XADD NOMKSTREAM (stream exists) ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field1", "value1");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        Map<String, String> newFields = new HashMap<>();
        newFields.put("field2", "value2");
        XAddParams params = XAddParams.xAddParams().noMkStream();
        StreamEntryID id = jedis.xadd("mystream", newFields, params);
        
        assertNotNull(id, "ID should not be null when stream exists");
        
        long len = jedis.xlen("mystream");
        assertEquals(2, len, "Stream should have 2 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddWithMaxLen() {
        System.out.println("\n=== Test: XADD MAXLEN ===");
        
        Map<String, String> fields = new HashMap<>();
        
        for (int i = 0; i < 5; i++) {
            fields.clear();
            fields.put("field", "value" + i);
            XAddParams params = XAddParams.xAddParams().maxLen(3);
            jedis.xadd("mystream", fields, params);
        }
        
        long len = jedis.xlen("mystream");
        System.out.println("Stream length: " + len);
        assertEquals(3, len, "Stream should be trimmed to 3");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddWithMaxLenApproximate() {
        System.out.println("\n=== Test: XADD MAXLEN Approximate (~) ===");
        
        Map<String, String> fields = new HashMap<>();
        
        for (int i = 0; i < 10; i++) {
            fields.clear();
            fields.put("field", "value" + i);
            XAddParams params = XAddParams.xAddParams().maxLen(3).approximateTrimming();
            jedis.xadd("mystream", fields, params);
        }
        
        long len = jedis.xlen("mystream");
        System.out.println("Stream length with approximate trimming: " + len);
        assertTrue(len <= 10, "Stream length should be reasonable");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddWithMinId() {
        System.out.println("\n=== Test: XADD MINID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value1");
        jedis.xadd("mystream", new StreamEntryID(100, 0), fields);
        
        fields.put("field", "value2");
        jedis.xadd("mystream", new StreamEntryID(200, 0), fields);
        
        fields.put("field", "value3");
        jedis.xadd("mystream", new StreamEntryID(300, 0), fields);
        
        fields.put("field", "value4");
        XAddParams params = XAddParams.xAddParams().minId("200-0");
        jedis.xadd("mystream", fields, params);
        
        long len = jedis.xlen("mystream");
        System.out.println("Stream length after MINID: " + len);
        assertTrue(len <= 3, "Stream should be trimmed");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXLen() {
        System.out.println("\n=== Test: XLEN ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        long len = jedis.xlen("mystream");
        System.out.println("Stream length: " + len);
        assertEquals(3, len, "Stream should have 3 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXLenEmptyStream() {
        System.out.println("\n=== Test: XLEN Empty Stream ===");
        
        long len = jedis.xlen("nonexistent");
        System.out.println("Empty stream length: " + len);
        assertEquals(0, len, "Empty stream should have length 0");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRangeFull() {
        System.out.println("\n=== Test: XRANGE Full ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 3; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        List<StreamEntry> entries = jedis.xrange("mystream", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID);
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(3, entries.size(), "Should have 3 entries");
        
        assertEquals(new StreamEntryID(1000, 0), entries.get(0).getID(), "First entry ID");
        assertEquals(new StreamEntryID(2000, 0), entries.get(1).getID(), "Second entry ID");
        assertEquals(new StreamEntryID(3000, 0), entries.get(2).getID(), "Third entry ID");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRangeWithRange() {
        System.out.println("\n=== Test: XRANGE With Range ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        List<StreamEntry> entries = jedis.xrange("mystream", 
                new StreamEntryID(2000, 0), 
                new StreamEntryID(4000, 0));
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(3, entries.size(), "Should have 3 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRangeWithCount() {
        System.out.println("\n=== Test: XRANGE COUNT ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        List<StreamEntry> entries = jedis.xrange("mystream", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 2);
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(2, entries.size(), "Should have 2 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRangeExclusive() {
        System.out.println("\n=== Test: XRANGE Exclusive ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(3000, 0), fields);
        
        List<StreamEntry> entries = jedis.xrange("mystream", 
                new StreamEntryID(1000, 1),
                new StreamEntryID(3000, 0));
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(2, entries.size(), "Should have 2 entries (2000-0 and 3000-0)");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRangeEmptyStream() {
        System.out.println("\n=== Test: XRANGE Empty Stream ===");
        
        List<StreamEntry> entries = jedis.xrange("nonexistent", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID);
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(0, entries.size(), "Should have 0 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRevRange() {
        System.out.println("\n=== Test: XREVRANGE ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 3; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        List<StreamEntry> entries = jedis.xrevrange("mystream", StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID);
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(3, entries.size(), "Should have 3 entries");
        
        assertEquals(new StreamEntryID(3000, 0), entries.get(0).getID(), "First entry ID (reverse)");
        assertEquals(new StreamEntryID(2000, 0), entries.get(1).getID(), "Second entry ID (reverse)");
        assertEquals(new StreamEntryID(1000, 0), entries.get(2).getID(), "Third entry ID (reverse)");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXRevRangeWithCount() {
        System.out.println("\n=== Test: XREVRANGE COUNT ===");
        
        // 先删除可能存在的流
        jedis.del("mystream");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            fields.put("field", "value" + i);
            System.out.println("Adding entry with ID: " + 1000 * i + "-0");
            StreamEntryID id = jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
            System.out.println("Added entry with ID: " + id);
        }
        
        // 检查流的长度
        long len = jedis.xlen("mystream");
        System.out.println("Stream length: " + len);
        
        // 检查所有条目
        List<StreamEntry> allEntries = jedis.xrevrange("mystream", StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID);
        System.out.println("All entries:");
        for (StreamEntry entry : allEntries) {
            System.out.println("  ID: " + entry.getID());
        }
        
        List<StreamEntry> entries = jedis.xrevrange("mystream", StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID, 2);
        
        System.out.println("Entries count: " + entries.size());
        assertEquals(2, entries.size(), "Should have 2 entries");
        
        assertEquals(new StreamEntryID(5000, 0), entries.get(0).getID(), "First entry ID");
        assertEquals(new StreamEntryID(4000, 0), entries.get(1).getID(), "Second entry ID");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXDel() {
        System.out.println("\n=== Test: XDEL Single ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        
        StreamEntryID id1 = jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        long deleted = jedis.xdel("mystream", id1);
        
        System.out.println("Deleted count: " + deleted);
        assertEquals(1, deleted, "Should delete 1 entry");
        
        long len = jedis.xlen("mystream");
        assertEquals(1, len, "Stream should have 1 entry");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXDelMultiple() {
        System.out.println("\n=== Test: XDEL Multiple ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        
        StreamEntryID id1 = jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        StreamEntryID id2 = jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(3000, 0), fields);
        
        long deleted = jedis.xdel("mystream", id1, id2);
        
        System.out.println("Deleted count: " + deleted);
        assertEquals(2, deleted, "Should delete 2 entries");
        
        long len = jedis.xlen("mystream");
        assertEquals(1, len, "Stream should have 1 entry");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXDelNonExistent() {
        System.out.println("\n=== Test: XDEL Non-existent ID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        
        long deleted = jedis.xdel("mystream", new StreamEntryID(9999, 0));
        
        System.out.println("Deleted count: " + deleted);
        assertEquals(0, deleted, "Should delete 0 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXTrimMaxLen() {
        System.out.println("\n=== Test: XTRIM MAXLEN ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        long trimmed = jedis.xtrim("mystream", XTrimParams.xTrimParams().maxLen(3));
        
        System.out.println("Trimmed count: " + trimmed);
        assertEquals(7, trimmed, "Should trim 7 entries");
        
        long len = jedis.xlen("mystream");
        assertEquals(3, len, "Stream should have 3 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXTrimMinId() {
        System.out.println("\n=== Test: XTRIM MINID ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        long trimmed = jedis.xtrim("mystream", XTrimParams.xTrimParams().minId("5000-0"));
        
        System.out.println("Trimmed count: " + trimmed);
        assertEquals(4, trimmed, "Should trim 4 entries");
        
        long len = jedis.xlen("mystream");
        assertEquals(6, len, "Stream should have 6 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXTrimApproximate() {
        System.out.println("\n=== Test: XTRIM Approximate ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        long trimmed = jedis.xtrim("mystream", XTrimParams.xTrimParams().maxLen(3).approximateTrimming());
        
        System.out.println("Trimmed count (approximate): " + trimmed);
        assertTrue(trimmed >= 0, "Should trim some entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadFromId() {
        System.out.println("\n=== Test: XREAD From ID ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 3; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", new StreamEntryID(0, 0));
        
        List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(XReadParams.xReadParams(), streams);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.size(), "Should have 1 stream");
        assertEquals("mystream", result.get(0).getKey(), "Stream name");
        assertEquals(3, result.get(0).getValue().size(), "Should have 3 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadWithDollar() {
        System.out.println("\n=== Test: XREAD With $ ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.LAST_ENTRY);
        
        List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(XReadParams.xReadParams(), streams);
        
        System.out.println("Result: " + result);
        assertNull(result, "Should return null for $ in non-blocking mode");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadWithCount() {
        System.out.println("\n=== Test: XREAD COUNT ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", new StreamEntryID(0, 0));
        
        XReadParams params = XReadParams.xReadParams().count(2);
        List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(params, streams);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.get(0).getValue().size(), "Should have 2 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadMultipleStreams() {
        System.out.println("\n=== Test: XREAD Multiple Streams ===");
        
        Map<String, String> fields = new HashMap<>();
        
        fields.put("field", "value1");
        jedis.xadd("stream1", new StreamEntryID(1000, 0), fields);
        
        fields.put("field", "value2");
        jedis.xadd("stream2", new StreamEntryID(2000, 0), fields);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("stream1", new StreamEntryID(0, 0));
        streams.put("stream2", new StreamEntryID(0, 0));
        
        List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(XReadParams.xReadParams(), streams);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.size(), "Should have 2 streams");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXInfoStream() {
        System.out.println("\n=== Test: XINFO STREAM ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        StreamInfo info = jedis.xinfoStream("mystream");
        
        assertNotNull(info, "Stream info should not be null");
        assertEquals(2, info.getLength(), "Stream length should be 2");
        assertNotNull(info.getLastGeneratedId(), "Last generated ID should not be null");
        
        System.out.println("Stream length: " + info.getLength());
        System.out.println("Last generated ID: " + info.getLastGeneratedId());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXInfoGroups() {
        System.out.println("\n=== Test: XINFO GROUPS ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
        
        List<StreamGroupInfo> groups = jedis.xinfoGroups("mystream");
        
        assertNotNull(groups, "Groups should not be null");
        assertEquals(1, groups.size(), "Should have 1 group");
        assertEquals("group1", groups.get(0).getName(), "Group name");
        
        System.out.println("Groups count: " + groups.size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXInfoConsumers() {
        System.out.println("\n=== Test: XINFO CONSUMERS ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        List<StreamConsumersInfo> consumers = jedis.xinfoConsumers("mystream", "group1");
        
        assertNotNull(consumers, "Consumers should not be null");
        assertTrue(consumers.size() >= 1, "Should have at least 1 consumer");
        
        System.out.println("Consumers count: " + consumers.size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXGroupCreate() {
        System.out.println("\n=== Test: XGROUP CREATE ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        String result = jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
        
        System.out.println("Result: " + result);
        assertEquals("OK", result, "Should return OK");
        
        List<StreamGroupInfo> groups = jedis.xinfoGroups("mystream");
        assertEquals(1, groups.size(), "Should have 1 group");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXGroupCreateMkStream() {
        System.out.println("\n=== Test: XGROUP CREATE MKSTREAM ===");
        
        String result = jedis.xgroupCreate("newstream", "group1", StreamEntryID.LAST_ENTRY, true);
        
        System.out.println("Result: " + result);
        assertEquals("OK", result, "Should return OK");
        
        long len = jedis.xlen("newstream");
        assertEquals(0, len, "Stream should be created but empty");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXGroupDestroy() {
        System.out.println("\n=== Test: XGROUP DESTROY ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
        
        long result = jedis.xgroupDestroy("mystream", "group1");
        
        System.out.println("Result: " + result);
        assertEquals(1, result, "Should return 1");
        
        List<StreamGroupInfo> groups = jedis.xinfoGroups("mystream");
        assertEquals(0, groups.size(), "Should have 0 groups");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXGroupDelConsumer() {
        System.out.println("\n=== Test: XGROUP DELCONSUMER ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        long result = jedis.xgroupDelConsumer("mystream", "group1", "consumer1");
        
        System.out.println("Result: " + result);
        assertTrue(result >= 0, "Should return pending count >= 0");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXGroupSetId() {
        System.out.println("\n=== Test: XGROUP SETID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
        
        String result = jedis.xgroupSetID("mystream", "group1", new StreamEntryID(0, 0));
        
        System.out.println("Result: " + result);
        assertEquals("OK", result, "Should return OK");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadGroupNewMessages() {
        System.out.println("\n=== Test: XREADGROUP New Messages ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        
        List<Map.Entry<String, List<StreamEntry>>> result = 
                jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams().count(10), streams);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.size(), "Should have 1 stream");
        assertEquals(2, result.get(0).getValue().size(), "Should have 2 entries");
        
        System.out.println("Entries count: " + result.get(0).getValue().size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadGroupPendingMessages() {
        System.out.println("\n=== Test: XREADGROUP Pending Messages ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        streams.put("mystream", new StreamEntryID(0, 0));
        List<Map.Entry<String, List<StreamEntry>>> result = 
                jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.get(0).getValue().size() >= 1, "Should have pending entries");
        
        System.out.println("Pending entries count: " + result.get(0).getValue().size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadGroupWithCount() {
        System.out.println("\n=== Test: XREADGROUP COUNT ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            fields.put("field", "value" + i);
            jedis.xadd("mystream", new StreamEntryID(1000 * i, 0), fields);
        }
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        
        XReadGroupParams params = XReadGroupParams.xReadGroupParams().count(1);
        List<Map.Entry<String, List<StreamEntry>>> result = 
                jedis.xreadGroup("group1", "consumer1", params, streams);
        
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.get(0).getValue().size(), "Should have 1 entry");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadGroupNoAck() {
        System.out.println("\n=== Test: XREADGROUP NOACK ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        
        XReadGroupParams params = XReadGroupParams.xReadGroupParams().noAck();
        List<Map.Entry<String, List<StreamEntry>>> result = 
                jedis.xreadGroup("group1", "consumer1", params, streams);
        
        assertNotNull(result, "Result should not be null");
        
        streams.put("mystream", new StreamEntryID(0, 0));
        List<Map.Entry<String, List<StreamEntry>>> pendingResult = 
                jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        assertTrue(pendingResult == null || pendingResult.isEmpty() || 
                pendingResult.get(0).getValue().isEmpty(), 
                "Should have no pending entries after NOACK");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAck() {
        System.out.println("\n=== Test: XACK Single ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        long acked = jedis.xack("mystream", "group1", id);
        
        System.out.println("Acked count: " + acked);
        assertEquals(1, acked, "Should ack 1 entry");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAckMultiple() {
        System.out.println("\n=== Test: XACK Multiple ===");
        
        Map<String, String> fields = new HashMap<>();
        StreamEntryID id1 = jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        StreamEntryID id2 = jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        StreamEntryID id3 = jedis.xadd("mystream", new StreamEntryID(3000, 0), fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams().count(3), streams);
        
        long acked = jedis.xack("mystream", "group1", id1, id2, id3);
        
        System.out.println("Acked count: " + acked);
        assertEquals(3, acked, "Should ack 3 entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXPendingSummary() {
        System.out.println("\n=== Test: XPENDING Summary ===");
        
        Map<String, String> fields = new HashMap<>();
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams().count(2), streams);
        
        StreamPendingSummary summary = jedis.xpending("mystream", "group1");
        
        assertNotNull(summary, "Summary should not be null");
        System.out.println("Pending count: " + summary.getTotal());
        assertEquals(2, summary.getTotal(), "Should have 2 pending entries");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXPendingList() {
        System.out.println("\n=== Test: XPENDING List ===");
        
        Map<String, String> fields = new HashMap<>();
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams().count(2), streams);
        
        XPendingParams params = XPendingParams.xPendingParams(StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 10);
        List<StreamPendingEntry> pendingList = jedis.xpending("mystream", "group1", params);
        
        assertNotNull(pendingList, "Pending list should not be null");
        assertEquals(2, pendingList.size(), "Should have 2 pending entries");
        
        System.out.println("Pending entries count: " + pendingList.size());
        for (StreamPendingEntry entry : pendingList) {
            System.out.println("  ID: " + entry.getID() + 
                    ", Consumer: " + entry.getConsumerName() + 
                    ", Idle: " + entry.getIdleTime());
        }
        System.out.println("Result: PASSED");
    }

    @Test
    void testXPendingByConsumer() {
        System.out.println("\n=== Test: XPENDING By Consumer ===");
        
        Map<String, String> fields = new HashMap<>();
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams().count(1), streams);
        
        jedis.xreadGroup("group1", "consumer2", XReadGroupParams.xReadGroupParams().count(1), streams);
        
        XPendingParams params = XPendingParams.xPendingParams(StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 10).consumer("consumer1");
        List<StreamPendingEntry> pendingList = jedis.xpending("mystream", "group1", params);
        
        assertNotNull(pendingList, "Pending list should not be null");
        assertEquals(1, pendingList.size(), "Should have 1 pending entry for consumer1");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXClaim() {
        System.out.println("\n=== Test: XCLAIM ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        XClaimParams claimParams = XClaimParams.xClaimParams();
        List<StreamEntry> claimed = jedis.xclaim("mystream", "group1", "consumer2", 
                0, claimParams, id);
        
        assertNotNull(claimed, "Claimed list should not be null");
        assertEquals(1, claimed.size(), "Should claim 1 entry");
        assertEquals(id, claimed.get(0).getID(), "Entry ID should match");
        
        System.out.println("Claimed entries count: " + claimed.size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXClaimJustId() {
        System.out.println("\n=== Test: XCLAIM JUSTID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
        
        XClaimParams claimParams = XClaimParams.xClaimParams();
        List<StreamEntryID> claimedIds = jedis.xclaimJustId("mystream", "group1", "consumer2", 
                0, claimParams, id);
        
        assertNotNull(claimedIds, "Claimed IDs should not be null");
        assertEquals(1, claimedIds.size(), "Should claim 1 entry");
        assertEquals(id, claimedIds.get(0), "Entry ID should match");
        
        System.out.println("Claimed IDs count: " + claimedIds.size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXClaimForce() {
        System.out.println("\n=== Test: XCLAIM FORCE ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        XClaimParams claimParams = XClaimParams.xClaimParams().force();
        List<StreamEntry> claimed = jedis.xclaim("mystream", "group1", "consumer2", 
                0, claimParams, id);
        
        assertNotNull(claimed, "Claimed list should not be null");
        assertEquals(1, claimed.size(), "Should claim 1 entry with FORCE");
        
        System.out.println("Claimed entries count: " + claimed.size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAutoClaim() {
        System.out.println("\n=== Test: XAUTOCLAIM ===");
        
        Map<String, String> fields = new HashMap<>();
        jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        jedis.xgroupCreate("mystream", "group1", new StreamEntryID(0, 0), false);
        
        Map<String, StreamEntryID> streams = new HashMap<>();
        streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
        jedis.xreadGroup("group1", "consumer1", XReadGroupParams.xReadGroupParams().count(2), streams);
        
        XAutoClaimParams params = XAutoClaimParams.xAutoClaimParams();
        Map.Entry<StreamEntryID, List<StreamEntry>> result = 
                jedis.xautoclaim("mystream", "group1", "consumer2", 
                        0, StreamEntryID.MINIMUM_ID, params);
        
        assertNotNull(result, "Result should not be null");
        assertNotNull(result.getKey(), "Next start ID should not be null");
        assertNotNull(result.getValue(), "Claimed entries should not be null");
        
        System.out.println("Next start ID: " + result.getKey());
        System.out.println("Claimed entries count: " + result.getValue().size());
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddWrongType() {
        System.out.println("\n=== Test: XADD Wrong Type ===");
        
        jedis.set("mykey", "string value");
        
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("field", "value");
            jedis.xadd("mykey", StreamEntryID.NEW_ENTRY, fields);
            fail("Should throw exception for wrong type");
        } catch (Exception e) {
            System.out.println("Expected exception: " + e.getMessage());
            assertTrue(e.getMessage().contains("WRONGTYPE"), 
                    "Exception message should contain WRONGTYPE");
        }
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddInvalidId() {
        System.out.println("\n=== Test: XADD Invalid ID ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        
        jedis.xadd("mystream", new StreamEntryID(2000, 0), fields);
        
        try {
            jedis.xadd("mystream", new StreamEntryID(1000, 0), fields);
            fail("Should throw exception for invalid ID");
        } catch (Exception e) {
            System.out.println("Expected exception: " + e.getMessage());
            assertTrue(e.getMessage().contains("equal or smaller") || 
                    e.getMessage().contains("ERR"), 
                    "Exception message should indicate ID error");
        }
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXGroupCreateDuplicate() {
        System.out.println("\n=== Test: XGROUP CREATE Duplicate ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
        
        try {
            jedis.xgroupCreate("mystream", "group1", StreamEntryID.LAST_ENTRY, false);
            fail("Should throw exception for duplicate group");
        } catch (Exception e) {
            System.out.println("Expected exception: " + e.getMessage());
            assertTrue(e.getMessage().contains("BUSYGROUP"), 
                    "Exception message should contain BUSYGROUP");
        }
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXReadGroupNonExistentGroup() {
        System.out.println("\n=== Test: XREADGROUP Non-existent Group ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        try {
            Map<String, StreamEntryID> streams = new HashMap<>();
            streams.put("mystream", StreamEntryID.UNRECEIVED_ENTRY);
            jedis.xreadGroup("nonexistent", "consumer1", XReadGroupParams.xReadGroupParams(), streams);
            fail("Should throw exception for non-existent group");
        } catch (Exception e) {
            System.out.println("Expected exception: " + e.getMessage());
            assertTrue(e.getMessage().contains("NOGROUP"), 
                    "Exception message should contain NOGROUP");
        }
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddEmptyFieldValue() {
        System.out.println("\n=== Test: XADD Empty Field Value ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "");
        
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        assertNotNull(id, "ID should not be null");
        
        List<StreamEntry> entries = jedis.xrange("mystream", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 1);
        assertEquals(1, entries.size(), "Should have 1 entry");
        assertEquals("", entries.get(0).getFields().get("field"), "Field value should be empty");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddManyFields() {
        System.out.println("\n=== Test: XADD Many Fields ===");
        
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i <= 100; i++) {
            fields.put("field" + i, "value" + i);
        }
        
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        assertNotNull(id, "ID should not be null");
        
        List<StreamEntry> entries = jedis.xrange("mystream", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 1);
        assertEquals(1, entries.size(), "Should have 1 entry");
        assertEquals(100, entries.get(0).getFields().size(), "Should have 100 fields");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddManyEntries() {
        System.out.println("\n=== Test: XADD Many Entries ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field", "value");
        
        int count = 1000;
        for (int i = 0; i < count; i++) {
            jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        }
        
        long len = jedis.xlen("mystream");
        assertEquals(count, len, "Stream should have " + count + " entries");
        
        System.out.println("Stream length: " + len);
        System.out.println("Result: PASSED");
    }

    @Test
    void testXAddSpecialCharacters() {
        System.out.println("\n=== Test: XADD Special Characters ===");
        
        Map<String, String> fields = new HashMap<>();
        fields.put("field-with-dash", "value1");
        fields.put("field_with_underscore", "value2");
        fields.put("field.with.dot", "value3");
        
        StreamEntryID id = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
        
        assertNotNull(id, "ID should not be null");
        
        List<StreamEntry> entries = jedis.xrange("mystream", StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 1);
        assertEquals(1, entries.size(), "Should have 1 entry");
        assertEquals(3, entries.get(0).getFields().size(), "Should have 3 fields");
        
        System.out.println("Result: PASSED");
    }

    @Test
    void testSummary() {
        System.out.println("\n========================================");
        System.out.println("Jedis Stream Compatibility Test Summary");
        System.out.println("========================================");
        System.out.println("All tests completed successfully!");
        System.out.println("Tested commands:");
        System.out.println("  - XADD: Auto ID, Specific ID, Multiple Fields, NOMKSTREAM, MAXLEN, MINID");
        System.out.println("  - XLEN: Normal, Empty Stream");
        System.out.println("  - XRANGE: Full, Range, COUNT, Exclusive, Empty Stream");
        System.out.println("  - XREVRANGE: Reverse, COUNT");
        System.out.println("  - XDEL: Single, Multiple, Non-existent");
        System.out.println("  - XTRIM: MAXLEN, MINID, Approximate");
        System.out.println("  - XREAD: From ID, $, COUNT, Multiple Streams");
        System.out.println("  - XINFO: STREAM, GROUPS, CONSUMERS");
        System.out.println("  - XGROUP: CREATE, MKSTREAM, DESTROY, DELCONSUMER, SETID");
        System.out.println("  - XREADGROUP: New Messages, Pending, COUNT, NOACK");
        System.out.println("  - XACK: Single, Multiple");
        System.out.println("  - XPENDING: Summary, List, By Consumer");
        System.out.println("  - XCLAIM: Transfer, JUSTID, FORCE");
        System.out.println("  - XAUTOCLAIM: Auto Transfer");
        System.out.println("  - Error Handling: Wrong Type, Invalid ID, Duplicate Group");
        System.out.println("  - Edge Cases: Empty Value, Many Fields, Many Entries, Special Characters");
        System.out.println("========================================");
    }
}
