package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.protocol.Command;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test server RESP parsing behavior with various encodings
 */
@DisplayName("RESP Encoding Analysis Tests")
class RespEncodingAnalysisTest {

    @Test
    @DisplayName("Test parsing with different byte sequences")
    void testParseWithDifferentByteSequences() {
        System.out.println("=== Testing RESP Parsing ===\n");
        
        // Test 1: Valid UTF-8 string
        testParseBulkString("Hello World".getBytes(StandardCharsets.UTF_8), "ASCII string");
        
        // Test 2: Java serialization magic bytes
        testParseBulkString(new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05}, "Java serialization magic");
        
        // Test 3: All byte values
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }
        testParseBulkString(allBytes, "All byte values");
        
        // Test 4: Simulate what Redisson sends (UTF-8 encoded Java serialization)
        byte[] serialized = new byte[]{(byte)0xAC, (byte)0xED, 0x00, 0x05, 0x74, 0x00, 0x04, 0x6B, 0x65, 0x79, 0x31};
        
        System.out.println("\n=== Simulating Redisson behavior ===");
        
        // What if Redisson sends with UTF-8 encoding?
        String utf8Str = new String(serialized, StandardCharsets.UTF_8);
        System.out.println("UTF-8 decoded string length: " + utf8Str.length());
        byte[] utf8Bytes = utf8Str.getBytes(StandardCharsets.UTF_8);
        System.out.println("UTF-8 re-encoded byte length: " + utf8Bytes.length);
        System.out.println("UTF-8 bytes: " + bytesToHex(utf8Bytes, utf8Bytes.length));
        
        // Build RESP command with UTF-8 encoded data but original length
        StringBuilder resp = new StringBuilder();
        resp.append("*1\r\n");
        resp.append("$").append(serialized.length).append("\r\n");  // Use original length
        resp.append(utf8Str);  // Use UTF-8 decoded string
        resp.append("\r\n");
        
        byte[] commandBytes = resp.toString().getBytes(StandardCharsets.UTF_8);
        System.out.println("\nRESP command with original length:");
        System.out.println("  Declared length: " + serialized.length);
        System.out.println("  Command bytes (first 50): " + bytesToHex(commandBytes, 50));
        
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf buffer = Unpooled.copiedBuffer(commandBytes);
        Command cmd = parser.parse(buffer);
        
        if (cmd != null && cmd.getArgs().length > 0) {
            String parsedValue = cmd.getArgs()[0];
            byte[] parsedBytes = parsedValue.getBytes(StandardCharsets.ISO_8859_1);
            System.out.println("  Parsed byte length: " + parsedBytes.length);
            System.out.println("  Parsed bytes: " + bytesToHex(parsedBytes));
        }
        buffer.release();
        
        // What if Redisson sends with correct UTF-8 length?
        System.out.println("\n\nRESP command with UTF-8 length:");
        resp = new StringBuilder();
        resp.append("*1\r\n");
        resp.append("$").append(utf8Bytes.length).append("\r\n");  // Use UTF-8 length
        resp.append(utf8Str);
        resp.append("\r\n");
        
        commandBytes = resp.toString().getBytes(StandardCharsets.UTF_8);
        System.out.println("  Declared length: " + utf8Bytes.length);
        System.out.println("  Command bytes (first 50): " + bytesToHex(commandBytes, 50));
        
        buffer = Unpooled.copiedBuffer(commandBytes);
        cmd = parser.parse(buffer);
        
        if (cmd != null && cmd.getArgs().length > 0) {
            String parsedValue = cmd.getArgs()[0];
            byte[] parsedBytes = parsedValue.getBytes(StandardCharsets.ISO_8859_1);
            System.out.println("  Parsed byte length: " + parsedBytes.length);
            System.out.println("  Parsed bytes: " + bytesToHex(parsedBytes));
        }
        buffer.release();
    }
    
    private void testParseBulkString(byte[] data, String description) {
        System.out.println("\n--- Testing: " + description + " ---");
        System.out.println("Input bytes (" + data.length + "): " + bytesToHex(data, Math.min(data.length, 30)));
        
        // Build RESP command
        StringBuilder resp = new StringBuilder();
        resp.append("*1\r\n");
        resp.append("$").append(data.length).append("\r\n");
        resp.append(new String(data, StandardCharsets.ISO_8859_1));
        resp.append("\r\n");
        
        byte[] commandBytes = resp.toString().getBytes(StandardCharsets.ISO_8859_1);
        
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf buffer = Unpooled.copiedBuffer(commandBytes);
        Command cmd = parser.parse(buffer);
        
        if (cmd != null && cmd.getArgs().length > 0) {
            String parsedValue = cmd.getArgs()[0];
            byte[] parsedBytes = parsedValue.getBytes(StandardCharsets.ISO_8859_1);
            
            System.out.println("Parsed bytes (" + parsedBytes.length + "): " + bytesToHex(parsedBytes, Math.min(parsedBytes.length, 30)));
            System.out.println("Match: " + java.util.Arrays.equals(data, parsedBytes));
            
            assertEquals(data.length, parsedBytes.length, "Length should match");
            assertArrayEquals(data, parsedBytes, "Data should match");
        } else {
            System.out.println("Parse failed!");
        }
        
        buffer.release();
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
    
    private String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, bytes.length);
    }
}