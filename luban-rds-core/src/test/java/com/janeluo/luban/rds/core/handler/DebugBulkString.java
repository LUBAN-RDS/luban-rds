package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import org.junit.Test;

public class DebugBulkString {
    @Test
    public void test() {
        String field = "字段1";
        
        // What bulkString returns
        String bulk = RdsResponseConstant.bulkString(field);
        System.out.println("bulkString result: [" + bulk + "]");
        
        // The length in bulk string header
        int lengthStart = 1;
        int lengthEnd = bulk.indexOf("\r\n");
        String lengthStr = bulk.substring(lengthStart, lengthEnd);
        System.out.println("bulkString declares length: " + lengthStr);
        
        // Actual bytes when serialized
        byte[] isoBytes = field.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] utfBytes = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("ISO-8859-1 byte length: " + isoBytes.length);
        System.out.println("UTF-8 byte length: " + utfBytes.length);
        
        // The problem is: what does serialize do with this string?
        // Let's check what happens in RedisProtocolParser.serialize
        System.out.println("\n--- serialize behavior for string starting with $ ---");
        String testStr = "$3\r\nabc\r\n";
        System.out.println("Test string: " + testStr);
        System.out.println("Starts with $: " + testStr.startsWith("$"));
    }
}