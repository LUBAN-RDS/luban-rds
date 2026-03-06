package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import org.junit.Test;

public class DebugBulkString2 {
    @Test
    public void test() {
        String field = "字段1";
        
        // Direct check of bulkString implementation
        byte[] bytes = field.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.out.println("ISO-8859-1 bytes.length: " + bytes.length);
        for (byte b : bytes) {
            System.out.print(String.format("%02x ", b));
        }
        System.out.println();
        
        // What bulkString does
        String result = "$" + bytes.length + "\r\n" + field + "\r\n";
        System.out.println("Result: [" + result + "]");
        System.out.println("Result bytes: " + result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
}