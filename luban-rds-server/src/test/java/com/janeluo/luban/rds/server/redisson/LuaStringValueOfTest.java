package com.janeluo.luban.rds.server.redisson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaString;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LuaString.valueOf Test")
class LuaStringValueOfTest {

    @Test
    @DisplayName("Test LuaString.valueOf with byte array")
    void testLuaStringValueOf() {
        System.out.println("=== Testing LuaString.valueOf(byte[]) ===\n");
        
        byte[] testData = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        LuaString ls = LuaString.valueOf(testData);
        byte[] result = new byte[ls.length()];
        ls.copyInto(0, result, 0, result.length);
        
        System.out.println("Original: " + bytesToHex(testData));
        System.out.println("After LuaString: " + bytesToHex(result));
        System.out.println("Match: " + java.util.Arrays.equals(testData, result));
        
        assertArrayEquals(testData, result);
    }
    
    @Test
    @DisplayName("Test problematic bytes 0x80-0x9F")
    void testProblematicBytes() {
        System.out.println("\n=== Testing 0x80-0x9F bytes ===\n");
        
        byte[] testData = new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0x9F};
        
        LuaString ls = LuaString.valueOf(testData);
        byte[] result = new byte[ls.length()];
        ls.copyInto(0, result, 0, result.length);
        
        System.out.println("Original: " + bytesToHex(testData));
        System.out.println("After LuaString: " + bytesToHex(result));
        System.out.println("Match: " + java.util.Arrays.equals(testData, result));
        
        assertArrayEquals(testData, result);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}