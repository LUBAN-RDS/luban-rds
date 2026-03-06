package com.janeluo.luban.rds.core.handler;

import org.junit.Test;

public class DebugProtocolParser {
    @Test
    public void test() {
        // What happens in serialize method
        String hscanResult = "*2\r\n$1\r\n0\r\n*2\r\n$2\r\na1\r\n$2\r\nv1\r\n";
        
        // In serialize:
        // if (str.startsWith("+") || str.startsWith("-") || str.startsWith(":") || str.startsWith("*"))
        // The string starts with *, so it uses UTF-8 encoding
        
        System.out.println("Input string starts with: '" + hscanResult.charAt(0) + "' (char code: " + (int)hscanResult.charAt(0) + ")");
        System.out.println("Starts with *: " + hscanResult.startsWith("*"));
        System.out.println("Starts with $: " + hscanResult.startsWith("$"));
        System.out.println("Full string: " + hscanResult);
        
        // The problem is: serialize checks for "*" at the START of the string
        // But for HSCAN, the response is a nested structure:
        // *2 (outer array)
        // $1\r\n0\r\n (cursor as bulk string)
        // *2 (inner array with key-value pairs)
        // ...
        
        // So when serialize sees "*2\r\n$1\r\n0\r\n*2\r\n...", it treats it as a simple string
        // because it starts with "*"
        
        // Let's see what encoding is used
        byte[] utf8Bytes = hscanResult.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("\nUTF-8 bytes length: " + utf8Bytes.length);
        
        // The serialize method does:
        // if (str.startsWith("+") || str.startsWith("-") || str.startsWith(":") || str.startsWith("*")) {
        //     // Simple strings, errors, integers, and arrays use UTF-8
        //     ByteBuf buffer = Unpooled.directBuffer(str.length());
        //     buffer.writeBytes(str.getBytes(StandardCharsets.UTF_8));
        //     return buffer;
        // }
        
        // This treats the entire string as a simple string (not interpreting the RESP structure)
        // So it just outputs the bytes as-is with UTF-8 encoding
        
        // But the problem might be: if the string contains non-ASCII characters,
        // the byte length differs between UTF-8 and what the string says
        
        // Actually wait - let's check what happens with binary data
        // In serialize for "$" prefix:
        // if (str.startsWith("$")) {
        //     // Bulk strings are binary-safe, use ISO-8859-1
        //     ByteBuf buffer = Unpooled.directBuffer(str.length());
        //     buffer.writeBytes(str.getBytes(StandardCharsets.ISO_8859_1));
        //     return buffer;
        // }
        
        // For HSCAN, it returns string starting with "*", not "$"
        // So it uses UTF-8 encoding, not ISO-8859-1
        
        // This should be fine for ASCII-only data...
        // But there's another issue!
        
        // Let's look at what the client might be sending
        // The error "got 'a'" - what could cause this?
    }
}