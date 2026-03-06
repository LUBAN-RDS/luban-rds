package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redisson SerializationCodec 深度分析测试
 */
@DisplayName("Redisson SerializationCodec 深度分析")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedissonSerializationCodecTest {

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
    @Order(1)
    @DisplayName("分析 SerializationCodec 发送的数据格式")
    void testAnalyzeSerializationCodecData() throws Exception {
        // 使用 ByteArrayCodec 来捕获 Redisson 发送的原始数据
        Config configBytes = new Config();
        configBytes.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configBytes.setCodec(new ByteArrayCodec());
        
        RedissonClient redissonBytes = Redisson.create(configBytes);
        
        try {
            // 使用 SerializationCodec 序列化对象
            TestObject obj = new TestObject("test", 123);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] expectedSerializedBytes = baos.toByteArray();
            
            System.out.println("=== 预期序列化字节 ===");
            System.out.println("长度: " + expectedSerializedBytes.length);
            System.out.println("前20字节: " + bytesToHex(expectedSerializedBytes, 20));
            System.out.println("魔数: " + bytesToHex(expectedSerializedBytes, 4));
            
            // 使用 SerializationCodec 存储到 RBucket
            Config configSer = new Config();
            configSer.useSingleServer()
                    .setAddress("redis://127.0.0.1:" + port)
                    .setRetryAttempts(3)
                    .setRetryInterval(100)
                    .setTimeout(3000);
            configSer.setCodec(new SerializationCodec());
            
            RedissonClient redissonSer = Redisson.create(configSer);
            try {
                String key = "testSerBucket";
                RBucket<TestObject> bucket = redissonSer.getBucket(key);
                bucket.set(obj);
                
                // 使用 ByteArrayCodec 读取存储的原始数据
                RBucket<byte[]> bucketBytes = redissonBytes.getBucket(key);
                byte[] storedBytes = bucketBytes.get();
                
                System.out.println("\n=== 服务器存储的字节 ===");
                if (storedBytes != null) {
                    System.out.println("长度: " + storedBytes.length);
                    System.out.println("前20字节: " + bytesToHex(storedBytes, 20));
                    System.out.println("魔数: " + bytesToHex(storedBytes, 4));
                    
                    boolean match = java.util.Arrays.equals(expectedSerializedBytes, storedBytes);
                    System.out.println("字节匹配: " + match);
                    
                    if (!match) {
                        // 检查服务器存储的String表示
                        MemoryStore store = server.getMemoryStore();
                        Object rawValue = store.get(0, key);
                        System.out.println("\n=== 服务器存储的原始对象 ===");
                        System.out.println("类型: " + (rawValue != null ? rawValue.getClass() : "null"));
                        if (rawValue instanceof String) {
                            String strValue = (String) rawValue;
                            System.out.println("字符串长度: " + strValue.length());
                            byte[] strBytes = strValue.getBytes(StandardCharsets.ISO_8859_1);
                            System.out.println("字符串转字节前20: " + bytesToHex(strBytes, 20));
                            System.out.println("字符串字节匹配: " + java.util.Arrays.equals(expectedSerializedBytes, strBytes));
                        }
                    }
                } else {
                    System.out.println("存储字节为 null!");
                }
                
                // 验证是否能正确读回
                TestObject retrieved = bucket.get();
                System.out.println("\n=== 读回结果 ===");
                if (retrieved != null) {
                    System.out.println("对象: " + retrieved.getName() + ", " + retrieved.getValue());
                    assertEquals(obj, retrieved);
                } else {
                    System.out.println("读回对象为 null!");
                }
                
            } finally {
                redissonSer.shutdown();
            }
        } finally {
            redissonBytes.shutdown();
        }
    }

    @Test
    @Order(2)
    @DisplayName("分析 RMap with SerializationCodec 的数据")
    void testAnalyzeRMapSerializationCodec() throws Exception {
        Config configSer = new Config();
        configSer.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configSer.setCodec(new SerializationCodec());
        
        RedissonClient redissonSer = Redisson.create(configSer);
        
        try {
            TestObject obj = new TestObject("test", 123);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] expectedObjBytes = baos.toByteArray();
            
            System.out.println("=== RMap SerializationCodec 分析 ===");
            System.out.println("预期对象序列化字节前20: " + bytesToHex(expectedObjBytes, 20));
            
            String mapKey = "testRMapSer";
            RMap<String, TestObject> map = redissonSer.getMap(mapKey);
            
            System.out.println("\n执行 map.put(\"key1\", obj)...");
            map.put("key1", obj);
            
            MemoryStore store = server.getMemoryStore();
            Object storedValue = store.get(0, mapKey);
            
            System.out.println("\n=== 服务器存储分析 ===");
            System.out.println("存储类型: " + (storedValue != null ? storedValue.getClass() : "null"));
            
            if (storedValue instanceof Map) {
                Map<?, ?> hash = (Map<?, ?>) storedValue;
                System.out.println("Hash大小: " + hash.size());
                
                for (Map.Entry<?, ?> entry : hash.entrySet()) {
                    System.out.println("\n--- 字段 ---");
                    Object field = entry.getKey();
                    System.out.println("字段类型: " + (field != null ? field.getClass() : "null"));
                    if (field instanceof String) {
                        byte[] fieldBytes = ((String) field).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("字段字节前20: " + bytesToHex(fieldBytes, 20));
                    }
                    
                    System.out.println("\n--- 值 ---");
                    Object value = entry.getValue();
                    System.out.println("值类型: " + (value != null ? value.getClass() : "null"));
                    if (value instanceof String) {
                        byte[] valueBytes = ((String) value).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("值字节前20: " + bytesToHex(valueBytes, 20));
                        System.out.println("值字节长度: " + valueBytes.length);
                        System.out.println("预期对象字节长度: " + expectedObjBytes.length);
                        System.out.println("字节匹配: " + java.util.Arrays.equals(expectedObjBytes, valueBytes));
                    }
                }
            }
            
            System.out.println("\n执行 map.get(\"key1\")...");
            TestObject retrieved = map.get("key1");
            
            System.out.println("\n=== 读回结果 ===");
            if (retrieved != null) {
                System.out.println("对象: " + retrieved.getName() + ", " + retrieved.getValue());
                assertEquals(obj, retrieved);
            } else {
                System.out.println("读回对象为 null!");
                
                // 直接检查HGET
                String directValue = store.hget(0, mapKey, "key1");
                System.out.println("直接HGET结果: " + (directValue != null ? "非null, 长度=" + directValue.length() : "null"));
                if (directValue != null) {
                    byte[] directBytes = directValue.getBytes(StandardCharsets.ISO_8859_1);
                    System.out.println("直接HGET字节前20: " + bytesToHex(directBytes, 20));
                }
            }
            
        } finally {
            redissonSer.shutdown();
        }
    }

    @Test
    @Order(3)
    @DisplayName("使用 ByteArrayCodec 测试 RMap")
    void testRMapWithByteArrayCodec() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] serializedBytes = baos.toByteArray();
        
        // 序列化字段名
        byte[] fieldBytes = "key1".getBytes(StandardCharsets.UTF_8);
        
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        config.setCodec(new ByteArrayCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            String mapKey = "testRMapByteArray";
            RMap<byte[], byte[]> map = redisson.getMap(mapKey);
            
            System.out.println("=== RMap ByteArrayCodec 测试 ===");
            System.out.println("字段字节: " + bytesToHex(fieldBytes, fieldBytes.length));
            System.out.println("值字节前20: " + bytesToHex(serializedBytes, 20));
            
            map.put(fieldBytes, serializedBytes);
            
            MemoryStore store = server.getMemoryStore();
            Object storedValue = store.get(0, mapKey);
            
            System.out.println("\n服务器存储类型: " + (storedValue != null ? storedValue.getClass() : "null"));
            
            if (storedValue instanceof Map) {
                Map<?, ?> hash = (Map<?, ?>) storedValue;
                System.out.println("Hash大小: " + hash.size());
                
                for (Map.Entry<?, ?> entry : hash.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        byte[] kf = ((String) entry.getKey()).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("存储字段: " + bytesToHex(kf, kf.length));
                    }
                    if (entry.getValue() instanceof String) {
                        byte[] kv = ((String) entry.getValue()).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("存储值前20: " + bytesToHex(kv, 20));
                        System.out.println("值匹配: " + java.util.Arrays.equals(serializedBytes, kv));
                    }
                }
            }
            
            byte[] retrieved = map.get(fieldBytes);
            if (retrieved != null) {
                System.out.println("\n读回字节前20: " + bytesToHex(retrieved, 20));
                System.out.println("字节匹配: " + java.util.Arrays.equals(serializedBytes, retrieved));
                assertArrayEquals(serializedBytes, retrieved);
            } else {
                System.out.println("\n读回为 null!");
            }
            
        } finally {
            redisson.shutdown();
        }
    }

    private String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(bytes.length, maxLen);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString().trim();
    }
}