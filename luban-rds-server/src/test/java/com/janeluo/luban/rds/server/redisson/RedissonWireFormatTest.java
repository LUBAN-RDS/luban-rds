package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redisson ByteArrayCodec 网络传输测试
 */
@DisplayName("Redisson ByteArrayCodec 网络传输测试")
class RedissonWireFormatTest {

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

    @Test
    @DisplayName("测试 ByteArrayCodec RBucket 原始字节传输")
    void testByteArrayCodecRBucket() throws Exception {
        // 构造原始二进制数据
        byte[] originalBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            originalBytes[i] = (byte) i;
        }
        
        System.out.println("=== ByteArrayCodec RBucket 测试 ===");
        System.out.println("原始数据前20字节: " + bytesToHex(originalBytes, 20));
        System.out.println("原始数据长度: " + originalBytes.length);
        
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setTimeout(3000);
        config.setCodec(new ByteArrayCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            String key = "testByteArrayBucket";
            RBucket<byte[]> bucket = redisson.getBucket(key);
            
            // 存储
            bucket.set(originalBytes);
            
            // 检查服务器存储
            MemoryStore store = server.getMemoryStore();
            Object storedValue = store.get(0, key);
            
            System.out.println("\n服务器存储:");
            System.out.println("类型: " + (storedValue != null ? storedValue.getClass() : "null"));
            
            if (storedValue instanceof String) {
                String strValue = (String) storedValue;
                System.out.println("字符串长度: " + strValue.length());
                byte[] storedBytes = strValue.getBytes(StandardCharsets.ISO_8859_1);
                System.out.println("ISO-8859-1转字节长度: " + storedBytes.length);
                System.out.println("前20字节: " + bytesToHex(storedBytes, 20));
                
                // 检查是否每个字节都正确
                boolean allMatch = true;
                int mismatchIndex = -1;
                for (int i = 0; i < Math.min(originalBytes.length, storedBytes.length); i++) {
                    if (originalBytes[i] != storedBytes[i]) {
                        allMatch = false;
                        mismatchIndex = i;
                        System.out.println("第一个不匹配位置: " + i + 
                            ", 原始: " + String.format("%02X", originalBytes[i]) + 
                            ", 存储: " + String.format("%02X", storedBytes[i]));
                        break;
                    }
                }
                System.out.println("所有字节匹配: " + allMatch);
            }
            
            // 读回
            byte[] retrieved = bucket.get();
            if (retrieved != null) {
                System.out.println("\n读回数据:");
                System.out.println("长度: " + retrieved.length);
                System.out.println("前20字节: " + bytesToHex(retrieved, 20));
                
                assertArrayEquals(originalBytes, retrieved, "数据应该完全一致");
                System.out.println("✓ 数据完全匹配!");
            } else {
                fail("读回数据为 null");
            }
            
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("测试 Java 序列化魔数传输")
    void testJavaSerializationMagicNumber() throws Exception {
        byte[] javaMagic = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        System.out.println("=== Java序列化魔数测试 ===");
        System.out.println("原始魔数: " + bytesToHex(javaMagic, 4));
        
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setTimeout(3000);
        config.setCodec(new ByteArrayCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            String key = "testJavaMagic";
            RBucket<byte[]> bucket = redisson.getBucket(key);
            
            bucket.set(javaMagic);
            
            MemoryStore store = server.getMemoryStore();
            Object storedValue = store.get(0, key);
            
            if (storedValue instanceof String) {
                byte[] storedBytes = ((String) storedValue).getBytes(StandardCharsets.ISO_8859_1);
                System.out.println("服务器存储: " + bytesToHex(storedBytes, storedBytes.length));
            }
            
            byte[] retrieved = bucket.get();
            if (retrieved != null) {
                System.out.println("读回: " + bytesToHex(retrieved, retrieved.length));
                assertArrayEquals(javaMagic, retrieved);
                System.out.println("✓ 魔数匹配!");
            }
            
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    @DisplayName("对比测试：直接RESP命令")
    void testDirectRESPCommand() throws Exception {
        byte[] testData = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05, (byte) 0xFF};
        
        System.out.println("=== 直接RESP命令测试 ===");
        System.out.println("测试数据: " + bytesToHex(testData, testData.length));
        
        // 直接构造 RESP 命令
        StringBuilder resp = new StringBuilder();
        resp.append("*3\r\n");
        resp.append("$3\r\nSET\r\n");
        resp.append("$4\r\ntest\r\n");
        resp.append("$").append(testData.length).append("\r\n");
        
        // 使用 ISO-8859-1 将字节转换为字符串
        String dataStr = new String(testData, StandardCharsets.ISO_8859_1);
        resp.append(dataStr).append("\r\n");
        
        byte[] commandBytes = resp.toString().getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("RESP命令长度: " + commandBytes.length + " 字节");
        System.out.println("RESP命令前50字节: " + bytesToHex(commandBytes, 50));
        
        // 解析命令
        com.janeluo.luban.rds.protocol.RedisProtocolParser parser = 
            new com.janeluo.luban.rds.protocol.RedisProtocolParser();
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.copiedBuffer(commandBytes);
        
        com.janeluo.luban.rds.protocol.Command cmd = parser.parse(buffer);
        if (cmd != null && cmd.getArgs().length >= 3) {
            System.out.println("\n解析成功:");
            System.out.println("命令: " + cmd.getName());
            System.out.println("参数个数: " + cmd.getArgs().length);
            System.out.println("参数列表: " + java.util.Arrays.toString(cmd.getArgs()));
            
            if (cmd.getArgs().length >= 4) {
                byte[] parsedValue = cmd.getArgs()[3].getBytes(StandardCharsets.ISO_8859_1);
                System.out.println("解析的值: " + bytesToHex(parsedValue, parsedValue.length));
                
                assertArrayEquals(testData, parsedValue);
                System.out.println("✓ 直接RESP解析成功!");
            }
        } else {
            fail("RESP命令解析失败或参数不足");
        }
        
        buffer.release();
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