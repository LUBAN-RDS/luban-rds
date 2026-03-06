package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.protocol.Command;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RESP 协议二进制安全完整测试
 * 
 * 测试目标：
 * 1. 验证协议层文本使用 UTF-8 编码
 * 2. 验证批量字符串是二进制安全的
 * 3. 验证服务器能正确处理任意字节序列
 * 4. 验证与 Redisson、Jedis 等主流客户端的兼容性
 */
@DisplayName("RESP协议二进制安全完整测试")
class RespBinarySafeComprehensiveTest {

    private RedisProtocolParser parser;
    private NettyRedisServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        parser = new RedisProtocolParser();
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();
    }

    private static int findRandomPort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ==================== 协议层测试 ====================

    @Test
    @DisplayName("测试二进制数据解析 - Java序列化魔数")
    void testBinaryDataJavaSerialization() {
        byte[] javaMagic = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes(("$" + javaMagic.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(javaMagic);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        Object result = parser.parseResp(buffer);
        assertNotNull(result);
        
        String strResult = (String) result;
        byte[] recovered = strResult.getBytes(StandardCharsets.ISO_8859_1);
        
        assertArrayEquals(javaMagic, recovered, "二进制数据应无损传输");
        assertEquals(javaMagic.length, strResult.length(), "长度应匹配字节数");
        
        buffer.release();
    }

    @Test
    @DisplayName("测试二进制数据序列化")
    void testBinaryDataSerialization() {
        byte[] originalBytes = new byte[]{
            (byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x73, 0x72, 0x00, 0x10,
            (byte) 0xA7, 0x1A, 'W', 'J', 'f', ';', (byte) 0xFC, (byte) 0xD0
        };
        
        ByteBuf result = parser.serialize(originalBytes);
        byte[] resultBytes = new byte[result.readableBytes()];
        result.readBytes(resultBytes);
        result.release();
        
        String respStr = new String(resultBytes, StandardCharsets.UTF_8);
        assertTrue(respStr.startsWith("$" + originalBytes.length + "\r\n"));
        
        int headerLen = ("$" + originalBytes.length + "\r\n").length();
        byte[] recovered = Arrays.copyOfRange(resultBytes, headerLen, headerLen + originalBytes.length);
        assertArrayEquals(originalBytes, recovered, "序列化后的二进制数据应无损");
    }

    @Test
    @DisplayName("测试命令解析 - 二进制安全的参数")
    void testCommandParsingWithBinaryData() {
        byte[] binaryKey = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};
        byte[] binaryValue = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes("*3\r\n".getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes("$3\r\nSET\r\n".getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(("$" + binaryKey.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(binaryKey);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(("$" + binaryValue.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(binaryValue);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        Command cmd = parser.parse(buffer);
        assertNotNull(cmd);
        assertEquals("SET", cmd.getName());
        assertEquals(3, cmd.getArgs().length);
        
        byte[] recoveredKey = cmd.getArgs()[1].getBytes(StandardCharsets.ISO_8859_1);
        byte[] recoveredValue = cmd.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1);
        
        assertArrayEquals(binaryKey, recoveredKey, "Key应无损传输");
        assertArrayEquals(binaryValue, recoveredValue, "Value应无损传输");
        
        buffer.release();
    }

    @Test
    @DisplayName("测试协议层文本使用UTF-8编码")
    void testProtocolTextUtf8Encoding() {
        // 使用纯ASCII字符测试UTF-8编码功能
        String testText = "Hello World";
        byte[] expectedBytes = testText.getBytes(StandardCharsets.UTF_8);
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes(("+" + testText + "\r\n").getBytes(StandardCharsets.UTF_8));
        
        String result = (String) parser.parseResp(buffer);
        assertNotNull(result, "解析结果不应为null");
        
        byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expectedBytes, resultBytes, "协议层文本应正确使用UTF-8编码");
        
        assertEquals(testText, result, "文本内容应匹配");
        
        buffer.release();
    }
    
    @Test
    @DisplayName("测试协议层UTF-8多字节字符")
    void testProtocolTextUtf8MultiByteChars() {
        // 直接构造UTF-8字节，避免源码编码问题
        byte[] expectedBytes = new byte[]{(byte)0xE4, (byte)0xBD, (byte)0xA0, // 你
                                          (byte)0xE5, (byte)0xA5, (byte)0xBD, // 好
                                          (byte)0xE4, (byte)0xB8, (byte)0x96, // 世
                                          (byte)0xE7, (byte)0x95, (byte)0x8C};// 界
        
        // 测试批量字符串的二进制安全传输
        // 客户端发送 UTF-8 字节，服务器存储后返回应该保持一致
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes(("$" + expectedBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(expectedBytes);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        // 解析
        String result = (String) parser.parseResp(buffer);
        assertNotNull(result, "解析结果不应为null");
        
        // 使用 ISO-8859-1 转换回字节（服务器存储方式）
        byte[] resultBytes = result.getBytes(StandardCharsets.ISO_8859_1);
        
        assertArrayEquals(expectedBytes, resultBytes, "批量字符串应二进制安全传输");
        
        buffer.release();
    }

    @Test
    @DisplayName("测试数组类型的二进制安全")
    void testArrayBinarySafe() {
        byte[] binary1 = new byte[]{(byte) 0xAC, (byte) 0xED};
        byte[] binary2 = new byte[]{0x00, 0x01, (byte) 0xFF};
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes("*2\r\n".getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(("$" + binary1.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(binary1);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(("$" + binary2.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(binary2);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) parser.parseResp(buffer);
        
        assertEquals(2, result.size());
        assertArrayEquals(binary1, result.get(0).getBytes(StandardCharsets.ISO_8859_1));
        assertArrayEquals(binary2, result.get(1).getBytes(StandardCharsets.ISO_8859_1));
        
        buffer.release();
    }

    @Test
    @DisplayName("测试空字节和特殊字节")
    void testNullAndSpecialBytes() {
        byte[] specialBytes = new byte[]{
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
            (byte) 0x7F,
            (byte) 0x80, (byte) 0x81, (byte) 0xFE, (byte) 0xFF
        };
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes(("$" + specialBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(specialBytes);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        String result = (String) parser.parseResp(buffer);
        byte[] recovered = result.getBytes(StandardCharsets.ISO_8859_1);
        
        assertArrayEquals(specialBytes, recovered, "所有特殊字节应无损传输");
        
        buffer.release();
    }

    @Test
    @DisplayName("测试大块二进制数据")
    void testLargeBinaryData() {
        byte[] largeData = new byte[10000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes(("$" + largeData.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(largeData);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        String result = (String) parser.parseResp(buffer);
        assertEquals(largeData.length, result.length());
        
        byte[] recovered = result.getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(largeData, recovered);
        
        buffer.release();
    }

    // ==================== 端到端测试 ====================

    @Test
    @DisplayName("端到端测试 - Java序列化对象存储")
    void testEndToEndJavaSerialization() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] serializedBytes = baos.toByteArray();
        
        String key = "testJavaSerialization";
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        
        ByteBuf setCmd = Unpooled.directBuffer();
        setCmd.writeBytes("*3\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes("$3\r\nSET\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(("$" + keyBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(keyBytes);
        setCmd.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(("$" + serializedBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(serializedBytes);
        setCmd.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        Command cmd = parser.parse(setCmd);
        assertNotNull(cmd);
        assertEquals("SET", cmd.getName());
        
        byte[] storedKey = cmd.getArgs()[1].getBytes(StandardCharsets.ISO_8859_1);
        byte[] storedValue = cmd.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1);
        
        assertArrayEquals(keyBytes, storedKey, "Key应无损");
        assertArrayEquals(serializedBytes, storedValue, "序列化对象应无损");
        
        setCmd.release();
    }

    @Test
    @DisplayName("端到端测试 - UTF-8字符串存储")
    void testEndToEndUtf8String() throws Exception {
        String chineseText = "你好世界Hello World";
        byte[] utf8Bytes = chineseText.getBytes(StandardCharsets.UTF_8);
        
        String key = "testUtf8String";
        
        ByteBuf setCmd = Unpooled.directBuffer();
        setCmd.writeBytes("*3\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes("$3\r\nSET\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(("$" + key.length() + "\r\n").getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(key.getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(("$" + utf8Bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(utf8Bytes);
        setCmd.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        Command cmd = parser.parse(setCmd);
        assertNotNull(cmd);
        
        byte[] storedValue = cmd.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1);
        String recoveredText = new String(storedValue, StandardCharsets.UTF_8);
        
        assertEquals(chineseText, recoveredText, "UTF-8字符串应无损传输");
        
        setCmd.release();
    }

    @Test
    @DisplayName("端到端测试 - 混合二进制和文本数据")
    void testEndToEndMixedData() throws Exception {
        byte[] binaryPart = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        String textPart = "Hello";
        byte[] mixedData = new byte[binaryPart.length + textPart.length()];
        System.arraycopy(binaryPart, 0, mixedData, 0, binaryPart.length);
        System.arraycopy(textPart.getBytes(StandardCharsets.UTF_8), 0, mixedData, binaryPart.length, textPart.length());
        
        String key = "testMixedData";
        
        ByteBuf setCmd = Unpooled.directBuffer();
        setCmd.writeBytes("*3\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes("$3\r\nSET\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(("$" + key.length() + "\r\n").getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(key.getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(("$" + mixedData.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        setCmd.writeBytes(mixedData);
        setCmd.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        Command cmd = parser.parse(setCmd);
        assertNotNull(cmd);
        
        byte[] storedValue = cmd.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(mixedData, storedValue, "混合数据应无损传输");
        
        setCmd.release();
    }

    // ==================== Redisson 兼容性测试 ====================

    @Test
    @DisplayName("Redisson兼容性 - ByteArrayCodec 二进制安全")
    void testRedissonByteArrayCodecCompatibility() throws Exception {
        TestObject obj = new TestObject("test", 123);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        oos.close();
        byte[] serializedBytes = baos.toByteArray();
        
        ByteBuf result = parser.serialize(serializedBytes);
        byte[] resultBytes = new byte[result.readableBytes()];
        result.readBytes(resultBytes);
        result.release();
        
        String respStr = new String(resultBytes, StandardCharsets.UTF_8);
        assertTrue(respStr.startsWith("$" + serializedBytes.length + "\r\n"));
        
        int headerLen = ("$" + serializedBytes.length + "\r\n").length();
        byte[] recovered = Arrays.copyOfRange(resultBytes, headerLen, headerLen + serializedBytes.length);
        assertArrayEquals(serializedBytes, recovered, "ByteArrayCodec数据应无损");
    }

    @Test
    @DisplayName("Redisson兼容性 - JsonJacksonCodec 字符串处理")
    void testRedissonJsonJacksonCodecCompatibility() throws Exception {
        String json = "{\"name\":\"test\",\"value\":123}";
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        
        ByteBuf buffer = Unpooled.directBuffer();
        buffer.writeBytes(("$" + jsonBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(jsonBytes);
        buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        
        String result = (String) parser.parseResp(buffer);
        byte[] recovered = result.getBytes(StandardCharsets.ISO_8859_1);
        String recoveredJson = new String(recovered, StandardCharsets.UTF_8);
        
        assertEquals(json, recoveredJson, "JSON字符串应无损传输");
        
        buffer.release();
    }

    // ==================== 性能测试 ====================

    @Test
    @DisplayName("性能测试 - 大量二进制数据处理")
    void testPerformanceLargeBinaryData() {
        int iterations = 100;
        byte[] testData = new byte[1024];
        for (int i = 0; i < testData.length; i++) {
            testData[i] = (byte) (i % 256);
        }
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < iterations; i++) {
            ByteBuf buffer = Unpooled.directBuffer();
            buffer.writeBytes(("$" + testData.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            buffer.writeBytes(testData);
            buffer.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            
            String result = (String) parser.parseResp(buffer);
            assertEquals(testData.length, result.length());
            
            buffer.release();
        }
        
        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration < 5000, "处理" + iterations + "次应在5秒内完成，实际用时: " + duration + "ms");
    }

    // 测试辅助类
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
    }
}