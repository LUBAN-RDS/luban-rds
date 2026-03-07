package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RTransaction;
import org.redisson.api.RedissonClient;
import org.redisson.api.TransactionOptions;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Transaction Support Test")
public class TransactionSupportTest {

    private static NettyRedisServer server;
    private static RedissonClient redisson;
    private static int port;

    @BeforeAll
    public static void setUpAll() {
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(5000);
        config.setCodec(new StringCodec());
        redisson = Redisson.create(config);
    }

    @AfterAll
    public static void tearDownAll() {
        if (redisson != null) {
            redisson.shutdown();
        }
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    public void setUp() {
        if (server != null && server.getMemoryStore() != null) {
            server.getMemoryStore().flushAll();
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
    @DisplayName("Test basic MULTI/EXEC transaction")
    void testBasicMultiExec() {
        RTransaction transaction = redisson.createTransaction(TransactionOptions.defaults());
        
        try {
            RBucket<String> bucket = transaction.getBucket("testKey");
            bucket.set("testValue");
            
            transaction.commit();
            
            RBucket<String> resultBucket = redisson.getBucket("testKey");
            assertEquals("testValue", resultBucket.get());
        } catch (Exception e) {
            transaction.rollback();
            fail("Transaction should succeed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test transaction with RMap operations")
    void testTransactionWithRMap() {
        RTransaction transaction = redisson.createTransaction(TransactionOptions.defaults());
        
        try {
            RMap<String, String> map = transaction.getMap("txMap");
            map.put("key1", "value1");
            map.put("key2", "value2");
            
            transaction.commit();
            
            RMap<String, String> resultMap = redisson.getMap("txMap");
            assertEquals("value1", resultMap.get("key1"));
            assertEquals("value2", resultMap.get("key2"));
            assertEquals(2, resultMap.size());
        } catch (Exception e) {
            transaction.rollback();
            fail("Transaction should succeed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test transaction with multiple operations")
    void testTransactionWithMultipleOperations() {
        RTransaction transaction = redisson.createTransaction(TransactionOptions.defaults());
        
        try {
            RMap<String, String> map = transaction.getMap("multiOpMap");
            map.put("key1", "value1");
            
            RBucket<String> bucket = transaction.getBucket("multiOpBucket");
            bucket.set("bucketValue");
            
            transaction.commit();
            
            RMap<String, String> resultMap = redisson.getMap("multiOpMap");
            assertEquals("value1", resultMap.get("key1"));
            
            RBucket<String> resultBucket = redisson.getBucket("multiOpBucket");
            assertEquals("bucketValue", resultBucket.get());
        } catch (Exception e) {
            transaction.rollback();
            fail("Transaction should succeed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test transaction rollback")
    void testTransactionRollback() {
        RMap<String, String> map = redisson.getMap("rollbackMap");
        map.put("existingKey", "existingValue");
        
        RTransaction transaction = redisson.createTransaction(TransactionOptions.defaults());
        
        try {
            RMap<String, String> txMap = transaction.getMap("rollbackMap");
            txMap.put("existingKey", "newValue");
            txMap.put("newKey", "newValue2");
            
            transaction.rollback();
            
            assertEquals("existingValue", map.get("existingKey"));
            assertNull(map.get("newKey"));
        } catch (Exception e) {
            fail("Rollback should succeed: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test transaction isolation")
    void testTransactionIsolation() {
        RMap<String, String> map = redisson.getMap("isolationMap");
        map.put("key1", "originalValue");
        
        RTransaction transaction = redisson.createTransaction(TransactionOptions.defaults());
        
        try {
            RMap<String, String> txMap = transaction.getMap("isolationMap");
            txMap.put("key1", "transactionValue");
            
            assertEquals("originalValue", map.get("key1"), "Outside transaction should see original value");
            
            transaction.commit();
            
            assertEquals("transactionValue", map.get("key1"), "After commit, should see transaction value");
        } catch (Exception e) {
            transaction.rollback();
            fail("Transaction should succeed: " + e.getMessage());
        }
    }
}