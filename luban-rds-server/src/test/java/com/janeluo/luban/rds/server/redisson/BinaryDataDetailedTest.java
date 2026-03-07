package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
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
import org.redisson.config.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class BinaryDataDetailedTest {

    private static NettyRedisServer server;
    private static RedissonClient redissonByteArray;
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
    }

    @AfterAll
    public static void tearDownAll() {
        if (redissonByteArray != null) {
            redissonByteArray.shutdown();
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
    @DisplayName("Test encoding analysis")
    void testEncodingAnalysis() throws Exception {
        byte[] originalBytes = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05, (byte)0xA7, 0x1A, 'W', 'J', 'f', ';', (byte)0xFC, (byte)0xD0};
        System.out.println("=== 原始字节 ===");
        System.out.println("原始字节 (hex): " + bytesToHex(originalBytes));
        
        System.out.println("\n=== ISO-8859-1 编码 ===");
        String isoString = new String(originalBytes, StandardCharsets.ISO_8859_1);
        System.out.println("ISO-8859-1 字符串长度: " + isoString.length());
        byte[] isoBytes = isoString.getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("ISO-8859-1 往返字节 (hex): " + bytesToHex(isoBytes));
        System.out.println("ISO-8859-1 往返匹配: " + Arrays.equals(originalBytes, isoBytes));
        
        System.out.println("\n=== UTF-8 编码 ===");
        String utf8String = new String(originalBytes, StandardCharsets.UTF_8);
        System.out.println("UTF-8 字符串长度: " + utf8String.length());
        byte[] utf8Bytes = utf8String.getBytes(StandardCharsets.UTF_8);
        System.out.println("UTF-8 往返字节 (hex): " + bytesToHex(utf8Bytes));
        System.out.println("UTF-8 往返匹配: " + Arrays.equals(originalBytes, utf8Bytes));
        
        System.out.println("\n=== 错误编码场景 ===");
        String wrongString = new String(originalBytes, StandardCharsets.ISO_8859_1);
        byte[] wrongBytes = wrongString.getBytes(StandardCharsets.UTF_8);
        System.out.println("ISO-8859-1 解码后 UTF-8 编码 (hex): " + bytesToHex(wrongBytes));
        
        System.out.println("\n=== RESP 协议模拟 ===");
        ByteBuf respBuffer = Unpooled.directBuffer();
        String lengthStr = "$" + originalBytes.length + "\r\n";
        respBuffer.writeBytes(lengthStr.getBytes(StandardCharsets.ISO_8859_1));
        respBuffer.writeBytes(originalBytes);
        respBuffer.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        
        byte[] respBytes = new byte[respBuffer.readableBytes()];
        respBuffer.readBytes(respBytes);
        respBuffer.release();
        System.out.println("RESP 编码后 (hex): " + bytesToHex(respBytes));
        
        System.out.println("\n=== 存储测试 ===");
        String key = "testEncodingKey";
        RBucket<byte[]> bucket = redissonByteArray.getBucket(key);
        bucket.set(originalBytes);
        
        Object storedValue = server.getMemoryStore().get(0, key);
        if (storedValue instanceof String) {
            String storedStr = (String) storedValue;
            byte[] storedBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
            System.out.println("服务器存储字节 (hex): " + bytesToHex(storedBytes));
            System.out.println("服务器存储匹配: " + Arrays.equals(originalBytes, storedBytes));
        }
        
        byte[] retrievedBytes = bucket.get();
        System.out.println("检索字节 (hex): " + bytesToHex(retrievedBytes));
        System.out.println("检索匹配: " + Arrays.equals(originalBytes, retrievedBytes));
        
        assertArrayEquals(originalBytes, retrievedBytes, "Binary data should match after round trip");
    }

    @Test
    @DisplayName("Test large binary data")
    void testLargeBinaryData() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(new TestObject("test", 123));
        oos.close();
        byte[] serializedBytes = baos.toByteArray();
        
        System.out.println("=== 大型二进制数据测试 ===");
        System.out.println("序列化字节长度: " + serializedBytes.length);
        System.out.println("前20字节 (hex): " + bytesToHex(Arrays.copyOf(serializedBytes, Math.min(20, serializedBytes.length))));
        
        String key = "testLargeBinary";
        RBucket<byte[]> bucket = redissonByteArray.getBucket(key);
        bucket.set(serializedBytes);
        
        Object storedValue = server.getMemoryStore().get(0, key);
        if (storedValue instanceof String) {
            String storedStr = (String) storedValue;
            byte[] storedBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
            System.out.println("服务器存储前20字节 (hex): " + bytesToHex(Arrays.copyOf(storedBytes, Math.min(20, storedBytes.length))));
            System.out.println("服务器存储长度: " + storedBytes.length);
            assertEquals(serializedBytes.length, storedBytes.length, "Length should match");
            assertArrayEquals(serializedBytes, storedBytes, "Server storage should match");
        }
        
        byte[] retrievedBytes = bucket.get();
        System.out.println("检索前20字节 (hex): " + bytesToHex(Arrays.copyOf(retrievedBytes, Math.min(20, retrievedBytes.length))));
        assertArrayEquals(serializedBytes, retrievedBytes, "Retrieved data should match");
    }

    @Test
    @DisplayName("Test ByteBuf encoding")
    void testByteBufEncoding() {
        byte[] testBytes = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05};
        
        ByteBuf buffer1 = Unpooled.directBuffer();
        buffer1.writeBytes(testBytes);
        
        byte[] fromBuffer1 = new byte[buffer1.readableBytes()];
        buffer1.readBytes(fromBuffer1);
        buffer1.release();
        System.out.println("ByteBuf direct buffer 往返 (hex): " + bytesToHex(fromBuffer1));
        
        ByteBuf buffer2 = Unpooled.buffer();
        buffer2.writeBytes(testBytes);
        
        byte[] fromBuffer2 = new byte[buffer2.readableBytes()];
        buffer2.readBytes(fromBuffer2);
        buffer2.release();
        System.out.println("ByteBuf heap buffer 往返 (hex): " + bytesToHex(fromBuffer2));
        
        assertArrayEquals(testBytes, fromBuffer1);
        assertArrayEquals(testBytes, fromBuffer2);
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
            return value == that.value && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
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