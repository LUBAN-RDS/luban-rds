package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直接测试 Lua 二进制数据处理
 */
@DisplayName("Lua Binary Data Direct Test")
class LuaBinaryDataDirectTest {

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
    @DisplayName("直接测试 EVAL 命令")
    void testDirectEval() throws Exception {
        System.out.println("\n=== 直接测试 EVAL 命令 ===\n");
        
        // 序列化字段名 "key1"
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject("key1");
        oos.close();
        byte[] serializedField = baos.toByteArray();
        
        System.out.println("序列化字段长度: " + serializedField.length);
        System.out.println("序列化字段: " + bytesToHex(serializedField));
        
        // 序列化对象
        TestObject obj = new TestObject("test", 123);
        baos = new ByteArrayOutputStream();
        oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] serializedValue = baos.toByteArray();
        
        System.out.println("\n序列化值长度: " + serializedValue.length);
        System.out.println("序列化值: " + bytesToHex(serializedValue, 30));
        
        // 直接使用 redis-cli 风格的命令测试
        // 使用原始 TCP 连接
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setTimeout(3000);
        config.setCodec(new SerializationCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            String mapKey = "testDirectEval";
            
            // 使用 RMap 会自动使用 EVAL 命令
            RMap<String, TestObject> map = redisson.getMap(mapKey);
            map.put("key1", obj);
            
            // 检查服务器存储
            MemoryStore store = server.getMemoryStore();
            Object storedMap = store.get(0, mapKey);
            
            System.out.println("\n--- 服务器存储分析 ---");
            if (storedMap instanceof java.util.Map) {
                java.util.Map<?, ?> hash = (java.util.Map<?, ?>) storedMap;
                
                for (java.util.Map.Entry<?, ?> entry : hash.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        byte[] fieldBytes = ((String) entry.getKey()).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("存储字段长度: " + fieldBytes.length);
                        System.out.println("存储字段: " + bytesToHex(fieldBytes));
                        System.out.println("字段匹配: " + java.util.Arrays.equals(serializedField, fieldBytes));
                    }
                    
                    if (entry.getValue() instanceof String) {
                        byte[] valueBytes = ((String) entry.getValue()).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("\n存储值长度: " + valueBytes.length);
                        System.out.println("存储值: " + bytesToHex(valueBytes, 30));
                        System.out.println("值匹配: " + java.util.Arrays.equals(serializedValue, valueBytes));
                    }
                }
            }
            
            // 读取测试
            TestObject retrieved = map.get("key1");
            System.out.println("\n--- 读取结果 ---");
            System.out.println("读取: " + (retrieved != null ? "成功 " + retrieved.name + ", " + retrieved.value : "null"));
            
            // 验证
            if (retrieved == null) {
                System.out.println("\n!!! 读取失败 !!!");
            }
            
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