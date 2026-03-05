package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.janeluo.luban.rds.common.constant.RdsResponseConstant;

public class UnsubscribeProtocolTest {

    @Test
    public void testUnsubscribeResponseFormat() {
        RedisProtocolParser parser = new RedisProtocolParser();
        String channelName = "ignew-mysql1:INDEX_HEADER_NOTICE_NUM_CACHE";
        int count = 0;

        // Simulate what RedisServerHandler NOW does (Fix with byte[] optimization):
        Object responseObj = Arrays.asList(
            "unsubscribe".getBytes(StandardCharsets.UTF_8), 
            channelName.getBytes(StandardCharsets.UTF_8), 
            count // Integer
        );
        
        ByteBuf buf = parser.serialize(responseObj);
        String output = buf.toString(StandardCharsets.UTF_8);
        buf.release();

        System.out.println("New Output: " + output);

        // Expected Redis Protocol for UNSUBSCRIBE:
        // *3\r\n
        // $11\r\nunsubscribe\r\n
        // $len\r\nchannel\r\n
        // :count\r\n
        
        String expectedStart = "*3\r\n$11\r\nunsubscribe\r\n";
        // Check if it matches expected
        if (!output.startsWith(expectedStart)) {
            System.out.println("FAILURE: Output does not match expected Bulk String format.");
            System.out.println("Expected to start with: " + expectedStart.replace("\r\n", "\\r\\n"));
            System.out.println("Actual: " + output.replace("\r\n", "\\r\\n"));
        }
        
        // Assert that it IS compliant (this should fail currently)
        Assertions.assertTrue(output.startsWith("*3\r\n$"), "Response should start with Array of Bulk Strings");
        Assertions.assertTrue(output.contains("$11\r\nunsubscribe\r\n"), "First element should be Bulk String 'unsubscribe'");
        Assertions.assertTrue(output.contains(":" + count + "\r\n"), "Third element should be Integer");
    }
}
