package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

public class RedissonEncodingTest {

    private static NettyRedisServer server;
    private static RedissonClient redissonByteArray;
    private static RedissonClient redissonString;
    private static int port;

    @BeforeAll
    public static void setUpAll() {
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();

        Config configByteArray = new Config();
        configByteArray.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configByteArray.setCodec(new ByteArrayCodec());
        redissonByteArray = Redisson.create(configByteArray);

        Config configString = new Config();
        configString.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configString.setCodec(new StringCodec());
        redissonString = Redisson.create(configString);
    }

    @AfterAll
    public static void tearDownAll() {
        if (redissonByteArray != null) {
            redissonByteArray.shutdown();
        }
        if (redissonString != null) {
            redissonString.shutdown();
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
    @DisplayName("Test user reported issue - SerializationCodec")
    void testUserReportedIssue() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] originalSerializedBytes = baos.toByteArray();
        
        System.out.println("=== 用户报告问题测试 ===");
        System.out.println("原始序列化字节长度: " + originalSerializedBytes.length);
        System.out.println("原始序列化字节前20: " + bytesToHex(java.util.Arrays.copyOf(originalSerializedBytes, Math.min(20, originalSerializedBytes.length))));
        
        Config configSerialization = new Config();
        configSerialization.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configSerialization.setCodec(new SerializationCodec());
        
        RedissonClient redissonSerialization = Redisson.create(configSerialization);
        
        try {
            String key = "testSerializationIssue";
            RBucket<TestObject> bucket = redissonSerialization.getBucket(key);
            bucket.set(obj);
            
            Object storedValue = server.getMemoryStore().get(0, key);
            System.out.println("服务器存储类型: " + (storedValue != null ? storedValue.getClass() : "null"));
            
            if (storedValue instanceof String) {
                String storedStr = (String) storedValue;
                System.out.println("服务器存储字符串长度: " + storedStr.length());
                byte[] storedBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
                System.out.println("服务器存储字节前20: " + bytesToHex(java.util.Arrays.copyOf(storedBytes, Math.min(20, storedBytes.length))));
                
                boolean bytesMatch = java.util.Arrays.equals(originalSerializedBytes, storedBytes);
                System.out.println("字节匹配: " + bytesMatch);
                
                if (!bytesMatch) {
                    System.out.println("\n=== 错误分析 ===");
                    System.out.println("预期字节前20: " + bytesToHex(java.util.Arrays.copyOf(originalSerializedBytes, Math.min(20, originalSerializedBytes.length))));
                    System.out.println("实际字节前20: " + bytesToHex(java.util.Arrays.copyOf(storedBytes, Math.min(20, storedBytes.length))));
                    
                    String utf8Encoded = new String(originalSerializedBytes, StandardCharsets.ISO_8859_1);
                    byte[] utf8Bytes = utf8Encoded.getBytes(StandardCharsets.UTF_8);
                    System.out.println("如果 ISO->UTF8 编码: " + bytesToHex(java.util.Arrays.copyOf(utf8Bytes, Math.min(20, utf8Bytes.length))));
                }
            }
            
            TestObject retrieved = bucket.get();
            System.out.println("检索对象: " + retrieved.getName() + ", " + retrieved.getValue());
            assertEquals(obj, retrieved, "对象应该正确检索");
        } finally {
            redissonSerialization.shutdown();
        }
    }

    @Test
    @DisplayName("Test ByteArrayCodec consistency")
    void testByteArrayCodecConsistency() throws Exception {
        byte[] testData = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05, (byte)0xA7, 0x1A, 'W', 'J', 'f', ';', (byte)0xFC, (byte)0xD0};
        
        System.out.println("\n=== ByteArrayCodec 一致性测试 ===");
        System.out.println("原始数据: " + bytesToHex(testData));
        
        String key = "testByteArray";
        RBucket<byte[]> bucket = redissonByteArray.getBucket(key);
        bucket.set(testData);
        
        Object storedValue = server.getMemoryStore().get(0, key);
        if (storedValue instanceof String) {
            String storedStr = (String) storedValue;
            byte[] storedBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
            System.out.println("服务器存储: " + bytesToHex(storedBytes));
            assertArrayEquals(testData, storedBytes, "服务器存储应该匹配");
        }
        
        byte[] retrieved = bucket.get();
        System.out.println("检索数据: " + bytesToHex(retrieved));
        assertArrayEquals(testData, retrieved, "检索数据应该匹配");
    }

    @Test
    @DisplayName("Test RESP protocol encoding")
    void testRespProtocolEncoding() throws Exception {
        byte[] testData = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05};
        
        System.out.println("\n=== RESP 协议编码测试 ===");
        System.out.println("原始数据: " + bytesToHex(testData));
        
        RedisProtocolParser parser = new RedisProtocolParser();
        
        ByteBuf respBuffer = parser.serialize(testData);
        byte[] respBytes = new byte[respBuffer.readableBytes()];
        respBuffer.readBytes(respBytes);
        respBuffer.release();
        
        System.out.println("RESP 编码: " + bytesToHex(respBytes));
        
        String expectedResp = "$4\r\n" + new String(testData, StandardCharsets.ISO_8859_1) + "\r\n";
        byte[] expectedBytes = expectedResp.getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("预期 RESP: " + bytesToHex(expectedBytes));
        
        assertArrayEquals(expectedBytes, respBytes, "RESP 编码应该正确");
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}