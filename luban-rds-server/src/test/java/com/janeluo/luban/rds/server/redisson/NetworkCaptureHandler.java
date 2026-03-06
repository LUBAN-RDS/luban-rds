package com.janeluo.luban.rds.server.redisson;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

/**
 * Network data capture handler for debugging
 */
public class NetworkCaptureHandler extends ChannelInboundHandlerAdapter {
    
    private static final ThreadLocal<byte[]> capturedData = new ThreadLocal<>();
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buffer = (ByteBuf) msg;
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), data);
            capturedData.set(data);
            
            System.out.println("\n=== Captured Network Data ===");
            System.out.println("Total bytes: " + data.length);
            System.out.println("First 100 bytes: " + bytesToHex(data, 100));
            
            // Parse RESP format
            parseRespDebug(data);
        }
        super.channelRead(ctx, msg);
    }
    
    private void parseRespDebug(byte[] data) {
        try {
            String str = new String(data, StandardCharsets.ISO_8859_1);
            int pos = 0;
            
            // Find array marker
            if (str.startsWith("*")) {
                int crlf = str.indexOf("\r\n");
                if (crlf > 0) {
                    int count = Integer.parseInt(str.substring(1, crlf));
                    System.out.println("\nArray count: " + count);
                    pos = crlf + 2;
                    
                    for (int i = 0; i < count && pos < str.length(); i++) {
                        if (str.charAt(pos) == '$') {
                            int nextCrlf = str.indexOf("\r\n", pos);
                            if (nextCrlf > 0) {
                                int len = Integer.parseInt(str.substring(pos + 1, nextCrlf));
                                int dataStart = nextCrlf + 2;
                                int dataEnd = dataStart + len;
                                
                                if (dataEnd <= str.length()) {
                                    String bulkData = str.substring(dataStart, dataEnd);
                                    byte[] bulkBytes = bulkData.getBytes(StandardCharsets.ISO_8859_1);
                                    
                                    System.out.println("\nBulk string " + (i+1) + ":");
                                    System.out.println("  Declared length: " + len);
                                    System.out.println("  Actual byte length: " + bulkBytes.length);
                                    System.out.println("  First 30 bytes: " + bytesToHex(bulkBytes, 30));
                                    
                                    if (bulkBytes.length != len) {
                                        System.out.println("  WARNING: Length mismatch!");
                                    }
                                }
                                pos = dataEnd + 2;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Parse error: " + e.getMessage());
        }
    }
    
    public static byte[] getCapturedData() {
        return capturedData.get();
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