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
 * 测试服务器 RESP 解析器的二进制安全性
 */
@DisplayName("RESP Parser Binary Safety Test")
class RespParserBinarySafetyTest {

    @Test
    @DisplayName("Test parse bulk string with all byte values")
    void testParseAllByteValues() {
        System.out.println("=== Testing RESP parser with all byte values ===\n");
        
        RedisProtocolParser parser = new RedisProtocolParser();
        
        // Test all possible byte values (0x00 - 0xFF)
        for (int b = 0; b < 256; b++) {
            byte[] testData = new byte[]{(byte) b};
            
            // Build RESP command: *1\r\n$1\r\n<byte>\r\n
            StringBuilder resp = new StringBuilder();
            resp.append("*1\r\n$1\r\n");
            resp.append(new String(testData, StandardCharsets.ISO_8859_1));
            resp.append("\r\n");
            
            byte[] commandBytes = resp.toString().getBytes(StandardCharsets.ISO_8859_1);
            
            ByteBuf buffer = Unpooled.copiedBuffer(commandBytes);
            Command cmd = parser.parse(buffer);
            
            if (cmd == null || cmd.getArgs().length == 0) {
                System.out.println("FAIL: byte 0x" + String.format("%02X", b) + " - parse failed");
                fail("Parse failed for byte 0x" + String.format("%02X", b));
            } else {
                byte[] parsed = cmd.getArgs()[0].getBytes(StandardCharsets.ISO_8859_1);
                if (parsed.length != 1 || parsed[0] != (byte) b) {
                    System.out.println("FAIL: byte 0x" + String.format("%02X", b) + 
                        " - got 0x" + String.format("%02X", parsed[0] & 0xFF));
                    fail("Mismatch for byte 0x" + String.format("%02X", b));
                }
            }
            
            buffer.release();
        }
        
        System.out.println("SUCCESS: All 256 byte values parsed correctly!");
    }

    @Test
    @DisplayName("Test parse with Java serialization bytes")
    void testParseJavaSerializationBytes() {
        System.out.println("\n=== Testing Java serialization bytes ===\n");
        
        // Java serialization magic number: AC ED 00 05
        byte[] javaMagic = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        // Build RESP command
        StringBuilder resp = new StringBuilder();
        resp.append("*1\r\n$4\r\n");
        resp.append(new String(javaMagic, StandardCharsets.ISO_8859_1));
        resp.append("\r\n");
        
        byte[] commandBytes = resp.toString().getBytes(StandardCharsets.ISO_8859_1);
        
        System.out.println("Command bytes: " + bytesToHex(commandBytes));
        
        RedisProtocolParser parser = new RedisProtocolParser();
        ByteBuf buffer = Unpooled.copiedBuffer(commandBytes);
        Command cmd = parser.parse(buffer);
        
        assertNotNull(cmd, "Command should not be null");
        assertEquals(1, cmd.getArgs().length, "Should have 1 argument");
        
        byte[] parsed = cmd.getArgs()[0].getBytes(StandardCharsets.ISO_8859_1);
        System.out.println("Parsed bytes: " + bytesToHex(parsed));
        
        assertArrayEquals(javaMagic, parsed, "Java magic bytes should match");
        
        buffer.release();
    }

    @Test
    @DisplayName("Test compare SET vs HSET parsing")
    void testCompareSetVsHsetParsing() {
        System.out.println("\n=== Comparing SET vs HSET parsing ===\n");
        
        // Same binary data
        byte[] binaryData = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x74, 0x00, 0x04, 0x6B, 0x65, 0x79, 0x31};
        
        // Build SET command: SET key value
        StringBuilder setCmd = new StringBuilder();
        setCmd.append("*3\r\n$3\r\nSET\r\n$4\r\ntest\r\n$").append(binaryData.length).append("\r\n");
        setCmd.append(new String(binaryData, StandardCharsets.ISO_8859_1));
        setCmd.append("\r\n");
        
        byte[] setBytes = setCmd.toString().getBytes(StandardCharsets.ISO_8859_1);
        
        // Build HSET command: HSET key field value
        StringBuilder hsetCmd = new StringBuilder();
        hsetCmd.append("*4\r\n$4\r\nHSET\r\n$4\r\ntest\r\n$").append(binaryData.length).append("\r\n");
        hsetCmd.append(new String(binaryData, StandardCharsets.ISO_8859_1));
        hsetCmd.append("\r\n$").append(binaryData.length).append("\r\n");
        hsetCmd.append(new String(binaryData, StandardCharsets.ISO_8859_1));
        hsetCmd.append("\r\n");
        
        byte[] hsetBytes = hsetCmd.toString().getBytes(StandardCharsets.ISO_8859_1);
        
        RedisProtocolParser parser = new RedisProtocolParser();
        
        // Parse SET
        ByteBuf setBuffer = Unpooled.copiedBuffer(setBytes);
        Command setCmdParsed = parser.parse(setBuffer);
        
        System.out.println("SET command parsed:");
        System.out.println("  Command: " + setCmdParsed.getName());
        System.out.println("  Key: " + setCmdParsed.getArgs()[1]);
        System.out.println("  Value bytes: " + bytesToHex(setCmdParsed.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1)));
        
        // Parse HSET
        ByteBuf hsetBuffer = Unpooled.copiedBuffer(hsetBytes);
        Command hsetCmdParsed = parser.parse(hsetBuffer);
        
        System.out.println("\nHSET command parsed:");
        System.out.println("  Command: " + hsetCmdParsed.getName());
        System.out.println("  Key: " + hsetCmdParsed.getArgs()[1]);
        System.out.println("  Field bytes: " + bytesToHex(hsetCmdParsed.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1)));
        System.out.println("  Value bytes: " + bytesToHex(hsetCmdParsed.getArgs()[3].getBytes(StandardCharsets.ISO_8859_1)));
        
        // Both should have same binary data
        byte[] setValue = setCmdParsed.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1);
        byte[] hsetField = hsetCmdParsed.getArgs()[2].getBytes(StandardCharsets.ISO_8859_1);
        byte[] hsetValue = hsetCmdParsed.getArgs()[3].getBytes(StandardCharsets.ISO_8859_1);
        
        System.out.println("\nComparison:");
        System.out.println("  SET value == HSET field: " + java.util.Arrays.equals(setValue, hsetField));
        System.out.println("  SET value == HSET value: " + java.util.Arrays.equals(setValue, hsetValue));
        System.out.println("  Original data match: " + java.util.Arrays.equals(binaryData, setValue));
        
        assertArrayEquals(binaryData, setValue, "SET value should match original");
        assertArrayEquals(binaryData, hsetField, "HSET field should match original");
        assertArrayEquals(binaryData, hsetValue, "HSET value should match original");
        
        setBuffer.release();
        hsetBuffer.release();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}