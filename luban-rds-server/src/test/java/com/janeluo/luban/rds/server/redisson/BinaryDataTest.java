package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class BinaryDataTest {

    private static NettyRedisServer server;
    private static RedissonClient redisson;
    private static RedissonClient redissonByteArray;
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
        if (redisson != null) {
            redisson.shutdown();
        }
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
    @DisplayName("Test binary data storage with byte array")
    void testBinaryDataByteArray() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        
        byte[] originalBytes = baos.toByteArray();
        System.out.println("Original bytes length: " + originalBytes.length);
        System.out.println("Original bytes (hex): " + bytesToHex(originalBytes));
        System.out.println("Original bytes first 10: " + bytesToHex(Arrays.copyOf(originalBytes, Math.min(10, originalBytes.length))));
        
        RBucket<byte[]> bucket = redissonByteArray.getBucket("testBinary");
        bucket.set(originalBytes);
        
        byte[] retrievedBytes = bucket.get();
        System.out.println("Retrieved bytes length: " + retrievedBytes.length);
        System.out.println("Retrieved bytes (hex): " + bytesToHex(retrievedBytes));
        System.out.println("Retrieved bytes first 10: " + bytesToHex(Arrays.copyOf(retrievedBytes, Math.min(10, retrievedBytes.length))));
        
        System.out.println("Arrays equal: " + Arrays.equals(originalBytes, retrievedBytes));
        assertArrayEquals(originalBytes, retrievedBytes, "Binary data should match after round trip");
    }

    @Test
    @DisplayName("Test binary data storage with SerializationCodec")
    void testBinaryDataSerializationCodec() throws Exception {
        Config configSerialization = new Config();
        configSerialization.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        configSerialization.setCodec(new SerializationCodec());
        
        RedissonClient redissonSerialization = Redisson.create(configSerialization);
        
        try {
            TestObject obj = new TestObject("test", 123);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] originalBytes = baos.toByteArray();
            System.out.println("Original serialized bytes (hex): " + bytesToHex(originalBytes));
            
            RBucket<TestObject> bucket = redissonSerialization.getBucket("testSerialization");
            bucket.set(obj);
            
            TestObject retrieved = bucket.get();
            System.out.println("Retrieved object: " + retrieved.getName() + ", " + retrieved.getValue());
            assertEquals(obj, retrieved, "Object should match after round trip");
        } finally {
            redissonSerialization.shutdown();
        }
    }

    @Test
    @DisplayName("Test raw byte values")
    void testRawByteValues() {
        byte[] testBytes = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05};
        System.out.println("Test bytes (hex): " + bytesToHex(testBytes));
        
        String asString = new String(testBytes, StandardCharsets.ISO_8859_1);
        System.out.println("As ISO-8859-1 string length: " + asString.length());
        
        byte[] backToBytes = asString.getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("Back to bytes (hex): " + bytesToHex(backToBytes));
        
        assertArrayEquals(testBytes, backToBytes, "ISO-8859-1 round trip should preserve bytes");
        
        String asUTF8String = new String(testBytes, StandardCharsets.UTF_8);
        System.out.println("As UTF-8 string length: " + asUTF8String.length());
        
        byte[] utf8Bytes = asUTF8String.getBytes(StandardCharsets.UTF_8);
        System.out.println("UTF-8 round trip bytes (hex): " + bytesToHex(utf8Bytes));
    }

    @Test
    @DisplayName("Test server-side storage")
    void testServerSideStorage() throws Exception {
        byte[] testBytes = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05};
        
        String key = "testServerBinary";
        
        RBucket<byte[]> bucket = redissonByteArray.getBucket(key);
        bucket.set(testBytes);
        
        Object storedValue = server.getMemoryStore().get(0, key);
        System.out.println("Stored value type: " + (storedValue != null ? storedValue.getClass() : "null"));
        System.out.println("Stored value: " + storedValue);
        
        if (storedValue instanceof String) {
            String storedStr = (String) storedValue;
            System.out.println("Stored string length: " + storedStr.length());
            byte[] storedBytes = storedStr.getBytes(StandardCharsets.ISO_8859_1);
            System.out.println("Stored bytes (hex): " + bytesToHex(storedBytes));
            assertArrayEquals(testBytes, storedBytes, "Server should store bytes correctly");
        } else {
            fail("Stored value is not a String: " + storedValue);
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