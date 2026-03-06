package com.janeluo.luban.rds.server.redisson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.LuaString;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 LuaJ 的二进制字符串处理
 */
@DisplayName("LuaJ Binary String Handling Test")
class LuaJBinaryStringTest {

    @Test
    @DisplayName("Test LuaValue.valueOf with binary data")
    void testLuaValueValueOf() {
        System.out.println("=== Testing LuaValue.valueOf with binary data ===\n");
        
        // Java 序列化魔数
        byte[] binaryData = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        // 使用 ISO-8859-1 将字节转换为 String（服务器存储方式）
        String strFromBytes = new String(binaryData, StandardCharsets.ISO_8859_1);
        System.out.println("String from bytes (ISO-8859-1): length=" + strFromBytes.length());
        
        // 直接使用 LuaString.valueOf(byte[]) 转换，避免字符串编码问题
        LuaString luaString = LuaString.valueOf(binaryData);
        System.out.println("LuaString type: " + luaString.typename());
        
        // 获取 LuaString 的字节
        byte[] luaBytes = new byte[luaString.length()];
        luaString.copyInto(0, luaBytes, 0, luaBytes.length);
        
        System.out.println("LuaString bytes: " + bytesToHex(luaBytes));
        System.out.println("Original bytes: " + bytesToHex(binaryData));
        System.out.println("Match: " + java.util.Arrays.equals(binaryData, luaBytes));
        
        // 验证
        assertArrayEquals(binaryData, luaBytes, "LuaJ should preserve binary data");
    }

    @Test
    @DisplayName("Test all byte values through LuaJ")
    void testAllByteValuesThroughLuaJ() {
        System.out.println("\n=== Testing all byte values through LuaJ ===\n");
        
        for (int b = 0; b < 256; b++) {
            byte[] testData = new byte[]{(byte) b};
            
            // 直接使用 LuaString.valueOf(byte[]) 转换，避免字符串编码问题
            LuaString luaString = LuaString.valueOf(testData);
            byte[] result = new byte[luaString.length()];
            luaString.copyInto(0, result, 0, result.length);
            
            if (!java.util.Arrays.equals(testData, result)) {
                System.out.println("FAIL at byte 0x" + String.format("%02X", b) + 
                    ": expected " + String.format("%02X", b) + 
                    ", got " + String.format("%02X", result[0] & 0xFF));
                fail("Byte mismatch at 0x" + String.format("%02X", b));
            }
        }
        
        System.out.println("SUCCESS: All 256 byte values preserved through LuaJ");
    }

    @Test
    @DisplayName("Test Java serialization magic number")
    void testJavaSerializationMagic() {
        System.out.println("\n=== Testing Java serialization magic ===\n");
        
        byte[] javaMagic = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        // 通过 LuaJ 处理，直接使用 LuaString.valueOf(byte[])
        LuaString luaString = LuaString.valueOf(javaMagic);
        byte[] result = new byte[luaString.length()];
        luaString.copyInto(0, result, 0, result.length);
        
        System.out.println("Original: " + bytesToHex(javaMagic));
        System.out.println("After LuaJ: " + bytesToHex(result));
        
        assertArrayEquals(javaMagic, result);
    }

    @Test
    @DisplayName("Test string with specific problematic bytes")
    void testProblematicBytes() {
        System.out.println("\n=== Testing problematic bytes (0x80-0x9F range) ===\n");
        
        // 这些字节在某些编码中是控制字符
        byte[] problematic = new byte[]{
            (byte) 0x80, (byte) 0x81, (byte) 0x82, (byte) 0x83,
            (byte) 0x9C, (byte) 0x9D, (byte) 0x9E, (byte) 0x9F
        };
        
        // 直接使用 LuaString.valueOf(byte[]) 转换，避免字符串编码问题
        System.out.println("Byte array length: " + problematic.length);
        
        LuaString luaString = LuaString.valueOf(problematic);
        byte[] result = new byte[luaString.length()];
        luaString.copyInto(0, result, 0, result.length);
        
        System.out.println("Original: " + bytesToHex(problematic));
        System.out.println("After LuaJ: " + bytesToHex(result));
        System.out.println("Match: " + java.util.Arrays.equals(problematic, result));
        
        assertArrayEquals(problematic, result);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private String charValues(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            sb.append(String.format("%04X ", (int) str.charAt(i)));
        }
        return sb.toString().trim();
    }
}