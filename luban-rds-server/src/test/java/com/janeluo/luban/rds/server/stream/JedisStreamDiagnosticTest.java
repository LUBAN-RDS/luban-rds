package com.janeluo.luban.rds.server.stream;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class JedisStreamDiagnosticTest {

    private NettyRedisServer server;
    private int port;
    private Jedis jedis;
    private String testDataDir;

    @BeforeEach
    void setUp() throws InterruptedException {
        port = findRandomPort();
        testDataDir = System.getProperty("java.io.tmpdir") + "/luban-rds-test-" + UUID.randomUUID().toString();
        
        RdsConfig config = createTestConfig();
        server = new NettyRedisServer(config);
        server.start();
        
        System.out.println("Server started, isRunning: " + server.isRunning());
        System.out.println("Server port: " + server.getPort());
        
        Thread.sleep(1000);
        
        jedis = waitForConnection();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (jedis != null) {
            jedis.close();
        }
        if (server != null && server.isRunning()) {
            server.stop();
        }
        Thread.sleep(200);
    }

    private int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }

    private RdsConfig createTestConfig() {
        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setPersistMode("rdb");
        config.setDir(testDataDir);
        config.setRdbSaveInterval(3600);
        return config;
    }

    
    private Jedis waitForConnection() throws InterruptedException {
        int maxRetries = 20;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Jedis testJedis = new Jedis("127.0.0.1", port, 5000);
                String pong = testJedis.ping();
                testJedis.close();
                System.out.println("Connection established, PING response: " + pong);
                return testJedis;
            } catch (Exception e) {
                System.out.println("Connection attempt " + (i + 1) + " failed: " + e.getMessage());
                Thread.sleep(200);
            }
        }
        throw new RuntimeException("Failed to connect to server after " + maxRetries + " retries");
    }

    @Test
    void testPing() {
        System.out.println("Testing PING command...");
        try {
            String result = jedis.ping();
            System.out.println("PING result: " + result);
            assertEquals("PONG", result);
        } catch (Exception e) {
            System.out.println("PING error: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            fail("PING failed: " + e.getMessage());
        }
    }

    @Test
    void testSetGet() {
        System.out.println("Testing SET/GET commands...");
        try {
            jedis.set("testkey", "testvalue");
            String result = jedis.get("testkey");
            System.out.println("GET result: " + result);
            assertEquals("testvalue", result);
        } catch (Exception e) {
            System.out.println("SET/GET error: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            fail("SET/GET failed: " + e.getMessage());
        }
    }

    @Test
    void testXAddBasic() {
        System.out.println("Testing XADD command...");
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("field1", "value1");
            StreamEntryID result = jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
            System.out.println("XADD result: " + result);
            assertNotNull(result);
            assertTrue(result.getTime() > 0);
            assertTrue(result.getSequence() >= 0);
        } catch (Exception e) {
            System.out.println("XADD error: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            fail("XADD failed: " + e.getMessage());
        }
    }

    @Test
    void testXLen() {
        System.out.println("Testing XLEN command...");
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("field1", "value1");
            jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
            jedis.xadd("mystream", StreamEntryID.NEW_ENTRY, fields);
            
            long len = jedis.xlen("mystream");
            System.out.println("XLEN result: " + len);
            assertEquals(2, len);
        } catch (Exception e) {
            System.out.println("XLEN error: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            fail("XLEN failed: " + e.getMessage());
        }
    }
}
