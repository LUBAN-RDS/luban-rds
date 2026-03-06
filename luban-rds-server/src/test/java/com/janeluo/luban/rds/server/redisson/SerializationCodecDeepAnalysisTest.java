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
import org.redisson.api.RedissonClient;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 深度分析 SerializationCodec 兼容性问题
 * 目标：找出服务器实现与真正 Redis 的差异
 */
@DisplayName("SerializationCodec 兼容性深度分析")
class SerializationCodecDeepAnalysisTest {

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
    }

    @Test
    @DisplayName("模拟 Redisson SerializationCodec 发送的数据")
    void testSimulateRedissonWireData() throws Exception {
        System.out.println("=== 模拟 Redisson SerializationCodec 发送的数据 ===\n");
        
        // 1. 序列化字段名 "key1"
        ByteArrayOutputStream fieldBaos = new ByteArrayOutputStream();
        ObjectOutputStream fieldOos = new ObjectOutputStream(fieldBaos);
        fieldOos.writeObject("key1");
        fieldOos.close();
        byte[] serializedField = fieldBaos.toByteArray();
        
        System.out.println("字段名序列化:");
        System.out.println("  原始: key1");
        System.out.println("  序列化字节: " + bytesToHex(serializedField));
        System.out.println("  长度: " + serializedField.length);
        
        // 2. 序列化对象
        TestObject obj = new TestObject("test", 123);
        ByteArrayOutputStream objBaos = new ByteArrayOutputStream();
        ObjectOutputStream objOos = new ObjectOutputStream(objBaos);
        objOos.writeObject(obj);
        objOos.close();
        byte[] serializedObj = objBaos.toByteArray();
        
        System.out.println("\n对象序列化:");
        System.out.println("  原始: TestObject{name='test', value=123}");
        System.out.println("  序列化字节(前30): " + bytesToHex(serializedObj, 30));
        System.out.println("  长度: " + serializedObj.length);
        
        // 3. 构造 HSET 命令 (模拟 Redisson 发送格式)
        // HSET mapKey field value
        String mapKey = "testMap";
        
        // Redisson 使用 UTF-8 编码发送批量字符串
        byte[] fieldAsUtf8 = new String(serializedField, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.UTF_8);
        byte[] valueAsUtf8 = new String(serializedObj, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.UTF_8);
        
        System.out.println("\n--- Redisson 发送分析 ---");
        System.out.println("字段 ISO->UTF8 转换: " + bytesToHex(fieldAsUtf8));
        System.out.println("字段 UTF8 长度: " + fieldAsUtf8.length + " (原始: " + serializedField.length + ")");
        System.out.println("值 ISO->UTF8 转换(前30): " + bytesToHex(valueAsUtf8, 30));
        System.out.println("值 UTF8 长度: " + valueAsUtf8.length + " (原始: " + serializedObj.length + ")");
        
        // 检查 UTF-8 编码是否有问题
        if (fieldAsUtf8.length != serializedField.length) {
            System.out.println("\n⚠️ 警告: 字段 UTF-8 编码长度不匹配!");
            // 找出哪些字节被扩展了
            System.out.println("原始字节分析:");
            for (int i = 0; i < serializedField.length; i++) {
                byte b = serializedField[i];
                if ((b & 0xFF) >= 0x80 && (b & 0xFF) <= 0x9F) {
                    System.out.println("  字节[" + i + "] = " + String.format("%02X", b) + " (可能是问题字节)");
                }
            }
        }
        
        if (valueAsUtf8.length != serializedObj.length) {
            System.out.println("\n⚠️ 警告: 值 UTF-8 编码长度不匹配!");
        }
        
        // 4. 构造正确的 RESP 命令
        StringBuilder resp = new StringBuilder();
        resp.append("*4\r\n");
        resp.append("$4\r\nHSET\r\n");
        resp.append("$").append(mapKey.length()).append("\r\n").append(mapKey).append("\r\n");
        
        // 关键: Redisson 如何发送序列化后的字节？
        // 答案: 它直接发送原始字节，不进行 UTF-8 编码！
        String fieldStr = new String(serializedField, StandardCharsets.ISO_8859_1);
        String valueStr = new String(serializedObj, StandardCharsets.ISO_8859_1);
        
        resp.append("$").append(serializedField.length).append("\r\n").append(fieldStr).append("\r\n");
        resp.append("$").append(serializedObj.length).append("\r\n").append(valueStr).append("\r\n");
        
        // 转换为字节 (使用 ISO-8859-1)
        byte[] commandBytes = resp.toString().getBytes(StandardCharsets.ISO_8859_1);
        
        System.out.println("\n--- 构造的 RESP 命令 ---");
        System.out.println("命令字节(前80): " + bytesToHex(commandBytes, 80));
        
        // 5. 解析命令
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf buffer = Unpooled.copiedBuffer(commandBytes);
        Command cmd = parser.parse(buffer);
        
        if (cmd != null) {
            System.out.println("\n--- 解析结果 ---");
            System.out.println("命令: " + cmd.getName());
            System.out.println("参数数量: " + cmd.getArgs().length);
            
            String parsedField = cmd.getArgs()[2];
            String parsedValue = cmd.getArgs()[3];
            
            byte[] parsedFieldBytes = parsedField.getBytes(StandardCharsets.ISO_8859_1);
            byte[] parsedValueBytes = parsedValue.getBytes(StandardCharsets.ISO_8859_1);
            
            System.out.println("解析的字段字节: " + bytesToHex(parsedFieldBytes));
            System.out.println("解析的字段长度: " + parsedFieldBytes.length);
            System.out.println("字段匹配: " + java.util.Arrays.equals(serializedField, parsedFieldBytes));
            
            System.out.println("解析的值字节(前30): " + bytesToHex(parsedValueBytes, 30));
            System.out.println("解析的值长度: " + parsedValueBytes.length);
            System.out.println("值匹配: " + java.util.Arrays.equals(serializedObj, parsedValueBytes));
        }
        
        buffer.release();
    }

    @Test
    @DisplayName("测试 ISO-8859-1 往返转换")
    void testIso88591RoundTrip() {
        System.out.println("=== ISO-8859-1 往返转换测试 ===\n");
        
        // 测试所有可能的字节值
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }
        
        // 转换为 String (ISO-8859-1)
        String str = new String(allBytes, StandardCharsets.ISO_8859_1);
        System.out.println("String 长度: " + str.length());
        
        // 转回字节 (ISO-8859-1)
        byte[] recovered = str.getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("恢复字节长度: " + recovered.length);
        
        // 检查是否匹配
        boolean allMatch = java.util.Arrays.equals(allBytes, recovered);
        System.out.println("所有字节匹配: " + allMatch);
        
        if (!allMatch) {
            // 找出不匹配的字节
            for (int i = 0; i < 256; i++) {
                if (allBytes[i] != recovered[i]) {
                    System.out.println("  字节 " + i + ": 原始=" + String.format("%02X", allBytes[i]) + 
                        ", 恢复=" + String.format("%02X", recovered[i]));
                }
            }
        }
        
        // 检查 String 的 charAt
        System.out.println("\n检查 String 内部字符:");
        for (int i = 0; i < Math.min(20, str.length()); i++) {
            char c = str.charAt(i);
            System.out.println("  char[" + i + "] = " + (int)c + " (0x" + Integer.toHexString(c) + ")");
        }
        
        assertTrue(allMatch, "ISO-8859-1 往返应该无损");
    }

    @Test
    @DisplayName("捕获 Redisson 实际发送的网络数据")
    void testCaptureActualRedissonData() throws Exception {
        System.out.println("=== 捕获 Redisson 实际发送数据 ===\n");
        
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setTimeout(3000);
        config.setCodec(new SerializationCodec());
        
        RedissonClient redisson = Redisson.create(config);
        
        try {
            TestObject obj = new TestObject("test", 123);
            
            // 预先序列化，知道预期数据
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(obj);
            oos.close();
            byte[] expectedObjBytes = baos.toByteArray();
            
            ByteArrayOutputStream fieldBaos = new ByteArrayOutputStream();
            ObjectOutputStream fieldOos = new ObjectOutputStream(fieldBaos);
            fieldOos.writeObject("key1");
            fieldOos.close();
            byte[] expectedFieldBytes = fieldBaos.toByteArray();
            
            System.out.println("预期字段字节: " + bytesToHex(expectedFieldBytes));
            System.out.println("预期值字节(前30): " + bytesToHex(expectedObjBytes, 30));
            
            // 执行 HSET
            String mapKey = "testCaptureMap";
            RMap<String, TestObject> map = redisson.getMap(mapKey);
            map.put("key1", obj);
            
            // 检查服务器存储
            MemoryStore store = server.getMemoryStore();
            Object storedMap = store.get(0, mapKey);
            
            System.out.println("\n--- 服务器存储 ---");
            System.out.println("类型: " + (storedMap != null ? storedMap.getClass() : "null"));
            
            if (storedMap instanceof java.util.Map) {
                java.util.Map<?, ?> hash = (java.util.Map<?, ?>) storedMap;
                System.out.println("大小: " + hash.size());
                
                for (java.util.Map.Entry<?, ?> entry : hash.entrySet()) {
                    System.out.println("\n字段:");
                    Object field = entry.getKey();
                    if (field instanceof String) {
                        byte[] fieldBytes = ((String) field).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("  类型: String");
                        System.out.println("  字节: " + bytesToHex(fieldBytes));
                        System.out.println("  长度: " + fieldBytes.length);
                        System.out.println("  预期长度: " + expectedFieldBytes.length);
                        System.out.println("  匹配: " + java.util.Arrays.equals(expectedFieldBytes, fieldBytes));
                        
                        // 详细对比
                        if (!java.util.Arrays.equals(expectedFieldBytes, fieldBytes)) {
                            System.out.println("  差异分析:");
                            compareBytes(expectedFieldBytes, fieldBytes);
                        }
                    }
                    
                    System.out.println("\n值:");
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        byte[] valueBytes = ((String) value).getBytes(StandardCharsets.ISO_8859_1);
                        System.out.println("  类型: String");
                        System.out.println("  字节(前30): " + bytesToHex(valueBytes, 30));
                        System.out.println("  长度: " + valueBytes.length);
                        System.out.println("  预期长度: " + expectedObjBytes.length);
                        System.out.println("  匹配: " + java.util.Arrays.equals(expectedObjBytes, valueBytes));
                        
                        if (!java.util.Arrays.equals(expectedObjBytes, valueBytes)) {
                            System.out.println("  差异分析:");
                            compareBytes(expectedObjBytes, valueBytes);
                        }
                    }
                }
            }
            
            // 执行 HGET
            System.out.println("\n--- HGET 测试 ---");
            String storedField = store.hget(0, mapKey, new String(expectedFieldBytes, StandardCharsets.ISO_8859_1));
            System.out.println("用预期字段查询: " + (storedField != null ? "找到" : "未找到"));
            
            // 用实际存储的字段查询
            if (storedMap instanceof java.util.Map) {
                java.util.Map<?, ?> hash = (java.util.Map<?, ?>) storedMap;
                for (Object field : hash.keySet()) {
                    String fieldValue = store.hget(0, mapKey, (String) field);
                    System.out.println("用存储字段查询: " + (fieldValue != null ? "找到" : "未找到"));
                }
            }
            
        } finally {
            redisson.shutdown();
        }
    }

    private void compareBytes(byte[] expected, byte[] actual) {
        int maxLen = Math.max(expected.length, actual.length);
        for (int i = 0; i < maxLen; i++) {
            byte e = i < expected.length ? expected[i] : 0;
            byte a = i < actual.length ? actual[i] : 0;
            if (e != a) {
                System.out.println("    [" + i + "] 预期=" + String.format("%02X", e & 0xFF) + 
                    ", 实际=" + String.format("%02X", a & 0xFF));
            }
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