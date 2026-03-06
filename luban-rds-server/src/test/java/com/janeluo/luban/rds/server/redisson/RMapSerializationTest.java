package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RMapSerializationTest {

    private static NettyRedisServer server;
    private static int port;

    @BeforeAll
    public static void setUpAll() {
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();
    }

    @AfterAll
    public static void tearDownAll() {
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
    @DisplayName("Test RMap with SerializationCodec - detailed")
    void testRMapSerializationCodecDetailed() throws Exception {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        config.setCodec(new SerializationCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            TestObject obj = new TestObject("test", 123);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] expectedBytes = baos.toByteArray();
            
            System.out.println("=== RMap SerializationCodec 详细测试 ===");
            System.out.println("序列化对象字节前20: " + bytesToHex(java.util.Arrays.copyOf(expectedBytes, Math.min(20, expectedBytes.length))));
            
            String mapName = "testMapSerialization";
            RMap<String, TestObject> map = redisson.getMap(mapName);
            
            System.out.println("\n执行 map.put(\"key1\", obj)...");
            map.put("key1", obj);
            
            MemoryStore store = server.getMemoryStore();
            System.out.println("\n检查服务器存储...");
            
            Object storedHash = store.get(0, mapName);
            System.out.println("存储类型: " + (storedHash != null ? storedHash.getClass() : "null"));
            
            if (storedHash instanceof Map) {
                Map<?, ?> hash = (Map<?, ?>) storedHash;
                System.out.println("Hash 大小: " + hash.size());
                
                for (Map.Entry<?, ?> entry : hash.entrySet()) {
                    System.out.println("\n字段: " + bytesToHex(entry.getKey().toString().getBytes(StandardCharsets.ISO_8859_1)));
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        String strValue = (String) value;
                        byte[] valueBytes = strValue.getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("值前20字节: " + bytesToHex(java.util.Arrays.copyOf(valueBytes, Math.min(20, valueBytes.length))));
                        System.out.println("值长度: " + valueBytes.length);
                        
                        boolean matches = java.util.Arrays.equals(expectedBytes, valueBytes);
                        System.out.println("字节匹配: " + matches);
                    }
                }
            } else if (storedHash instanceof String) {
                System.out.println("存储为字符串: " + storedHash);
            } else {
                System.out.println("存储为其他类型");
            }
            
            System.out.println("\n执行 map.get(\"key1\")...");
            TestObject retrieved = map.get("key1");
            
            if (retrieved == null) {
                System.out.println("返回 null!");
                
                System.out.println("\n直接检查 HGET...");
                String directValue = store.hget(0, mapName, "key1");
                System.out.println("直接 HGET 结果: " + (directValue != null ? "非null, 长度=" + directValue.length() : "null"));
                if (directValue != null) {
                    byte[] directBytes = directValue.getBytes(StandardCharsets.ISO_8859_1);
                    System.out.println("直接 HGET 前20字节: " + bytesToHex(java.util.Arrays.copyOf(directBytes, Math.min(20, directBytes.length))));
                }
                
            } else {
                System.out.println("返回对象: " + retrieved.getName() + ", " + retrieved.getValue());
            }
            
            assertNotNull(retrieved, "应该返回非null对象");
            assertEquals(obj, retrieved, "对象应该匹配");
            
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("Test RMap with ByteArrayCodec")
    void testRMapByteArrayCodec() throws Exception {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        config.setCodec(new ByteArrayCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            TestObject obj = new TestObject("test", 123);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] expectedBytes = baos.toByteArray();
            
            System.out.println("\n=== RMap ByteArrayCodec 测试 ===");
            System.out.println("序列化对象字节前20: " + bytesToHex(java.util.Arrays.copyOf(expectedBytes, Math.min(20, expectedBytes.length))));
            
            String mapName = "testMapByteArray";
            RMap<String, byte[]> map = redisson.getMap(mapName);
            
            map.put("key1", expectedBytes);
            
            byte[] retrieved = map.get("key1");
            
            if (retrieved == null) {
                System.out.println("返回 null!");
            } else {
                System.out.println("返回字节前20: " + bytesToHex(java.util.Arrays.copyOf(retrieved, Math.min(20, retrieved.length))));
                System.out.println("字节匹配: " + java.util.Arrays.equals(expectedBytes, retrieved));
            }
            
            assertNotNull(retrieved, "应该返回非null字节");
            assertArrayEquals(expectedBytes, retrieved, "字节应该匹配");
            
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("Test RMap with StringCodec")
    void testRMapStringCodec() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        config.setCodec(new StringCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            System.out.println("\n=== RMap StringCodec 测试 ===");
            
            String mapName = "testMapString";
            RMap<String, String> map = redisson.getMap(mapName);
            
            map.put("key1", "value1");
            
            String retrieved = map.get("key1");
            System.out.println("返回值: " + retrieved);
            
            assertEquals("value1", retrieved, "字符串应该匹配");
            
        } finally {
            redisson.shutdown();
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}