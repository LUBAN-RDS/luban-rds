package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.protocol.Command;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redisson SerializationCodec 兼容性最终测试
 * 
 * 目标：找出服务器需要做什么才能兼容 Redisson SerializationCodec
 */
@DisplayName("Redisson SerializationCodec 兼容性最终测试")
class RedissonFinalCompatibilityTest {

    private static NettyRedisServer server;
    private static int port;

    @BeforeAll
    static void setUpAll() {
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();
    }

    @AfterAll
    static void tearDownAll() {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void setUp() {
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

    static class TestObject implements Serializable {
        private String name;
        private int value;

        public TestObject() {}
        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestObject that = (TestObject) o;
            return value == that.value && java.util.Objects.equals(name, that.name);
        }
    }

    @Test
    @DisplayName("步骤1: 验证 ByteArrayCodec 正常工作")
    void step1_verifyByteArrayCodec() throws Exception {
        System.out.println("\n=== 步骤1: 验证 ByteArrayCodec ===");
        
        byte[] testData = new byte[256];
        for (int i = 0; i < 256; i++) {
            testData[i] = (byte) i;
        }
        
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port).setTimeout(3000);
        config.setCodec(new ByteArrayCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            RBucket<byte[]> bucket = redisson.getBucket("testBytes");
            bucket.set(testData);
            byte[] retrieved = bucket.get();
            
            System.out.println("存储长度: " + testData.length);
            System.out.println("读取长度: " + (retrieved != null ? retrieved.length : "null"));
            System.out.println("数据匹配: " + java.util.Arrays.equals(testData, retrieved));
            
            assertArrayEquals(testData, retrieved, "ByteArrayCodec 应该正常工作");
            System.out.println("✓ ByteArrayCodec 正常工作");
            
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("步骤2: 分析 SerializationCodec 发送的数据")
    void step2_analyzeSerializationCodecData() throws Exception {
        System.out.println("\n=== 步骤2: 分析 SerializationCodec ===");
        
        // 序列化字段名和值
        ByteArrayOutputStream fieldBaos = new ByteArrayOutputStream();
        ObjectOutputStream fieldOos = new ObjectOutputStream(fieldBaos);
        fieldOos.writeObject("key1");
        fieldOos.close();
        byte[] serializedField = fieldBaos.toByteArray();
        
        TestObject obj = new TestObject("test", 123);
        ByteArrayOutputStream objBaos = new ByteArrayOutputStream();
        ObjectOutputStream objOos = new ObjectOutputStream(objBaos);
        objOos.writeObject(obj);
        objOos.close();
        byte[] serializedObj = objBaos.toByteArray();
        
        System.out.println("字段序列化字节(" + serializedField.length + "): " + bytesToHex(serializedField));
        System.out.println("对象序列化字节(" + serializedObj.length + "): " + bytesToHex(serializedObj, 30));
        
        // 检查 UTF-8 编码的影响
        String fieldAsUtf8 = new String(serializedField, StandardCharsets.UTF_8);
        byte[] fieldUtf8Bytes = fieldAsUtf8.getBytes(StandardCharsets.UTF_8);
        System.out.println("\nUTF-8 编码后的字段(" + fieldUtf8Bytes.length + "): " + bytesToHex(fieldUtf8Bytes));
        
        // 使用 SerializationCodec 发送
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port).setTimeout(3000);
        config.setCodec(new SerializationCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            String mapKey = "testSerMap";
            RMap<String, TestObject> map = redisson.getMap(mapKey);
            
            System.out.println("\n执行 map.put(\"key1\", obj)...");
            map.put("key1", obj);
            
            // 检查服务器存储
            MemoryStore store = server.getMemoryStore();
            Object storedMap = store.get(0, mapKey);
            
            if (storedMap instanceof java.util.Map) {
                java.util.Map<?, ?> hash = (java.util.Map<?, ?>) storedMap;
                
                for (java.util.Map.Entry<?, ?> entry : hash.entrySet()) {
                    System.out.println("\n--- 字段 ---");
                    if (entry.getKey() instanceof String) {
                        byte[] storedField = ((String) entry.getKey()).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("存储的字段(" + storedField.length + "): " + bytesToHex(storedField));
                        System.out.println("预期字段(" + serializedField.length + "): " + bytesToHex(serializedField));
                        System.out.println("匹配: " + java.util.Arrays.equals(serializedField, storedField));
                    }
                    
                    System.out.println("\n--- 值 ---");
                    if (entry.getValue() instanceof String) {
                        byte[] storedValue = ((String) entry.getValue()).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("存储的值(" + storedValue.length + "): " + bytesToHex(storedValue, 30));
                        System.out.println("预期值(" + serializedObj.length + "): " + bytesToHex(serializedObj, 30));
                        System.out.println("匹配: " + java.util.Arrays.equals(serializedObj, storedValue));
                    }
                }
            }
            
            // 尝试读取
            System.out.println("\n执行 map.get(\"key1\")...");
            TestObject retrieved = map.get("key1");
            System.out.println("读取结果: " + (retrieved != null ? "成功" : "null"));
            
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("步骤3: 测试 RBucket 与 SerializationCodec")
    void step3_testRBucketWithSerializationCodec() throws Exception {
        System.out.println("\n=== 步骤3: 测试 RBucket 与 SerializationCodec ===");
        
        TestObject obj = new TestObject("test", 123);
        
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + port).setTimeout(3000);
        config.setCodec(new SerializationCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            String key = "testSerBucket";
            RBucket<TestObject> bucket = redisson.getBucket(key);
            
            System.out.println("存储对象...");
            bucket.set(obj);
            
            MemoryStore store = server.getMemoryStore();
            Object stored = store.get(0, key);
            
            if (stored instanceof String) {
                byte[] storedBytes = ((String) stored).getBytes(StandardCharsets.ISO_8859_1);
                System.out.println("存储长度: " + storedBytes.length);
                System.out.println("存储字节(前30): " + bytesToHex(storedBytes, 30));
            }
            
            System.out.println("\n读取对象...");
            TestObject retrieved = bucket.get();
            
            System.out.println("读取结果: " + (retrieved != null ? "成功, name=" + retrieved.name + ", value=" + retrieved.value : "null"));
            
            // 检查是否匹配
            assertEquals(obj, retrieved, "RBucket 应该能正确读取 SerializationCodec 数据");
            System.out.println("✓ RBucket 与 SerializationCodec 正常工作");
            
        } finally {
            redisson.shutdown();
        }
    }

    private String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, bytes.length);
    }

    private String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString().trim();
    }
}