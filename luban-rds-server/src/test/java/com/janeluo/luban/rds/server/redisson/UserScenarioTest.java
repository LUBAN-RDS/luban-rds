package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class UserScenarioTest {

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
                .setTimeout(3000);
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

    static class TestObject implements Serializable {
        private String name;
        private int value;

        public TestObject() {}

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestObject that = (TestObject) o;
            return value == that.value && java.util.Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, value);
        }
    }

    @Test
    @DisplayName("Simulate user reported issue")
    void testUserReportedIssueSimulation() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] expectedBytes = baos.toByteArray();
        
        System.out.println("=== 模拟用户报告的问题 ===");
        System.out.println("预期字节 (前20): " + bytesToHex(java.util.Arrays.copyOf(expectedBytes, Math.min(20, expectedBytes.length))));
        System.out.println("预期字节魔数: " + bytesToHex(java.util.Arrays.copyOf(expectedBytes, 2)));
        
        String key = "testUserIssue";
        
        Config configSerialization = new Config();
        configSerialization.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configSerialization.setCodec(new SerializationCodec());
        
        RedissonClient redissonSerialization = Redisson.create(configSerialization);
        try {
            RBucket<TestObject> bucket = redissonSerialization.getBucket(key);
            bucket.set(obj);
            
            MemoryStore store = server.getMemoryStore();
            Object storedValue = store.get(0, key);
            
            System.out.println("\n服务器存储值类型: " + (storedValue != null ? storedValue.getClass().getName() : "null"));
            
            if (storedValue instanceof String) {
                String storedStr = (String) storedValue;
                byte[] actualBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
                
                System.out.println("实际字节 (前20): " + bytesToHex(java.util.Arrays.copyOf(actualBytes, Math.min(20, actualBytes.length))));
                System.out.println("实际字节魔数: " + bytesToHex(java.util.Arrays.copyOf(actualBytes, 2)));
                
                boolean matches = java.util.Arrays.equals(expectedBytes, actualBytes);
                System.out.println("\n字节匹配: " + matches);
                
                if (!matches) {
                    System.out.println("\n=== 问题分析 ===");
                    System.out.println("预期长度: " + expectedBytes.length);
                    System.out.println("实际长度: " + actualBytes.length);
                    
                    for (int i = 0; i < Math.min(expectedBytes.length, actualBytes.length); i++) {
                        if (expectedBytes[i] != actualBytes[i]) {
                            System.out.println("第 " + i + " 字节不匹配: 预期=" + String.format("%02X", expectedBytes[i]) + ", 实际=" + String.format("%02X", actualBytes[i]));
                            break;
                        }
                    }
                    
                    String wrongEncoding = new String(expectedBytes, StandardCharsets.ISO_8859_1);
                    byte[] wrongBytes = wrongEncoding.getBytes(StandardCharsets.UTF_8);
                    System.out.println("\n如果发生 ISO-8859-1 -> UTF-8 错误编码:");
                    System.out.println("错误编码后 (前20): " + bytesToHex(java.util.Arrays.copyOf(wrongBytes, Math.min(20, wrongBytes.length))));
                    
                    fail("二进制数据存储不匹配!");
                } else {
                    System.out.println("\n✓ 二进制数据存储正确!");
                }
            }
            
            TestObject retrieved = bucket.get();
            assertEquals(obj, retrieved, "对象应该正确检索");
            System.out.println("✓ 对象检索正确: " + retrieved.getName() + ", " + retrieved.getValue());
            
        } finally {
            redissonSerialization.shutdown();
        }
    }

    @Test
    @DisplayName("Test with ByteArrayCodec")
    void testWithByteArrayCodec() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] expectedBytes = baos.toByteArray();
        
        System.out.println("\n=== ByteArrayCodec 测试 ===");
        System.out.println("预期字节 (前20): " + bytesToHex(java.util.Arrays.copyOf(expectedBytes, Math.min(20, expectedBytes.length))));
        
        String key = "testByteArrayCodec";
        
        Config configBytes = new Config();
        configBytes.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configBytes.setCodec(new ByteArrayCodec());
        
        RedissonClient redissonBytes = Redisson.create(configBytes);
        try {
            RBucket<byte[]> bucket = redissonBytes.getBucket(key);
            bucket.set(expectedBytes);
            
            MemoryStore store = server.getMemoryStore();
            Object storedValue = store.get(0, key);
            
            if (storedValue instanceof String) {
                String storedStr = (String) storedValue;
                byte[] actualBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
                
                System.out.println("实际字节 (前20): " + bytesToHex(java.util.Arrays.copyOf(actualBytes, Math.min(20, actualBytes.length))));
                
                boolean matches = java.util.Arrays.equals(expectedBytes, actualBytes);
                System.out.println("字节匹配: " + matches);
                assertTrue(matches, "ByteArrayCodec 存储应该正确");
            }
            
            byte[] retrieved = bucket.get();
            assertArrayEquals(expectedBytes, retrieved, "检索数据应该匹配");
            System.out.println("✓ ByteArrayCodec 测试通过");
            
        } finally {
            redissonBytes.shutdown();
        }
    }

    @Test
    @DisplayName("Demonstrate encoding mismatch scenario")
    void testEncodingMismatchDemo() {
        System.out.println("\n=== 编码不匹配演示 ===");
        
        byte[] original = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05};
        System.out.println("原始字节: " + bytesToHex(original));
        
        String asIsoString = new String(original, StandardCharsets.ISO_8859_1);
        System.out.println("ISO-8859-1 解码字符: " + asIsoString.length() + " 个字符");
        
        byte[] correctRoundTrip = asIsoString.getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("ISO-8859-1 往返: " + bytesToHex(correctRoundTrip));
        System.out.println("ISO-8859-1 往返正确: " + java.util.Arrays.equals(original, correctRoundTrip));
        
        byte[] wrongEncoding = asIsoString.getBytes(StandardCharsets.UTF_8);
        System.out.println("\n错误: UTF-8 编码: " + bytesToHex(wrongEncoding));
        System.out.println("这就是用户看到的 C2 AC C3 AD!");
        
        String asUtf8String = new String(original, StandardCharsets.UTF_8);
        byte[] utf8RoundTrip = asUtf8String.getBytes(StandardCharsets.UTF_8);
        System.out.println("\nUTF-8 往返: " + bytesToHex(utf8RoundTrip));
        System.out.println("UTF-8 往返正确: " + java.util.Arrays.equals(original, utf8RoundTrip));
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}