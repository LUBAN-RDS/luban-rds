package com.janeluo.luban.rds.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis RESP 协议解析器
 * 
 * 性能优化：
 * 1. 使用 ThreadLocal 缓存 StringBuilder，避免频繁创建对象
 * 2. 预定义常用响应的字节数组，避免重复编码
 * 3. 优化整数解析，避免字符串转换
 */
public class RedisProtocolParser {
    
    // ThreadLocal 缓存 StringBuilder，避免频繁创建
    private static final ThreadLocal<StringBuilder> STRING_BUILDER_CACHE = 
            ThreadLocal.withInitial(() -> new StringBuilder(64));
    
    // 预定义常用响应字节数组
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_ARRAY = "*0\r\n".getBytes(StandardCharsets.UTF_8);
    
    // 预定义常用整数响应
    private static final byte[][] INT_CACHE = new byte[32][];
    static {
        for (int i = 0; i < 32; i++) {
            INT_CACHE[i] = (":" + i + "\r\n").getBytes(StandardCharsets.UTF_8);
        }
    }
    
    public Command parse(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return null;
        }
        
        // 标记当前读取位置，以便在解析失败时重置
        buffer.markReaderIndex();
        
        byte firstByte = buffer.readByte();
        
        if (firstByte == '*') {
            Command command = parseArray(buffer);
            if (command != null) {
                return command;
            }
        }
        
        // 解析失败，重置读取位置
        buffer.resetReaderIndex();
        return null;
    }
    
    private Command parseArray(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length <= 0) {
            return null;
        }
        
        String[] args = new String[length];
        for (int i = 0; i < length; i++) {
            if (!buffer.isReadable()) {
                return null;
            }
            
            byte type = buffer.readByte();
            if (type == '$') {
                args[i] = parseBulkString(buffer);
                if (args[i] == null) {
                    return null;
                }
            } else {
                return null;
            }
        }
        
        if (args.length > 0) {
            String commandName = args[0];
            return new Command(commandName, args);
        }
        
        return null;
    }
    
    private int parseInteger(ByteBuf buffer) {
        // 优化：直接解析数字，避免 StringBuilder 和字符串转换
        int result = 0;
        boolean negative = false;
        byte b;
        
        while (buffer.isReadable()) {
            b = buffer.readByte();
            if (b == '\r') {
                if (buffer.readableBytes() > 0 && buffer.readByte() == '\n') {
                    break;
                }
                return -1;
            } else if (b == '-') {
                negative = true;
            } else if (b >= '0' && b <= '9') {
                result = result * 10 + (b - '0');
            } else {
                return -1;
            }
        }
        
        return negative ? -result : result;
    }
    
    private String parseBulkString(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length < 0) {
            return null;
        }
        
        if (buffer.readableBytes() < length + 2) {
            return null;
        }
        
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        // 跳过\r\n
        buffer.skipBytes(2);
        
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    // 解析所有RESP类型
    public Object parseResp(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return null;
        }
        
        buffer.markReaderIndex();
        byte firstByte = buffer.readByte();
        
        switch (firstByte) {
            case '+':
                return parseSimpleString(buffer);
            case '-':
                return parseError(buffer);
            case ':':
                return parseInteger(buffer);
            case '$':
                return parseBulkString(buffer);
            case '*':
                return parseArrayValues(buffer);
            default:
                buffer.resetReaderIndex();
                return null;
        }
    }
    
    private String parseSimpleString(ByteBuf buffer) {
        return parseLine(buffer);
    }
    
    private String parseError(ByteBuf buffer) {
        return parseLine(buffer);
    }
    
    private String parseLine(ByteBuf buffer) {
        // 使用 ThreadLocal 缓存的 StringBuilder
        StringBuilder sb = STRING_BUILDER_CACHE.get();
        sb.setLength(0); // 清空
        
        byte b;
        boolean foundCr = false;
        
        while (buffer.isReadable()) {
            b = buffer.readByte();
            if (b == '\r') {
                foundCr = true;
                if (buffer.readableBytes() > 0 && buffer.readByte() == '\n') {
                    break;
                }
            } else if (foundCr) {
                buffer.resetReaderIndex();
                return null;
            } else {
                sb.append((char) b);
            }
        }
        
        return sb.toString();
    }
    
    private List<Object> parseArrayValues(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length < 0) {
            return null;
        }
        
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            if (!buffer.isReadable()) {
                buffer.resetReaderIndex();
                return null;
            }
            
            Object value = parseResp(buffer);
            if (value == null) {
                buffer.resetReaderIndex();
                return null;
            }
            values.add(value);
        }
        
        return values;
    }
    
    // 序列化所有RESP类型
    public ByteBuf serialize(Object response) {
        if (response == null) {
            return serializeNull();
        }
        
        if (response instanceof String) {
            String str = (String) response;
            // 检查是否为完整的RESP格式字符串
            if (str.startsWith("+") || str.startsWith("-") || str.startsWith(":") || str.startsWith("$") || str.startsWith("*")) {
                // 如果是完整的RESP格式字符串，直接写入
                ByteBuf buffer = Unpooled.directBuffer(str.length());
                buffer.writeBytes(str.getBytes(StandardCharsets.UTF_8));
                return buffer;
            }
            // 检查是否为错误消息
            if (str.startsWith("ERR")) {
                return serializeError(str);
            }
            // 默认作为简单字符串处理
            return serializeSimpleString(str);
        }
        
        if (response instanceof Long || response instanceof Integer) {
            return serializeInteger(((Number) response).longValue());
        }
        
        if (response instanceof List) {
            return serializeArray((List<?>) response);
        }
        
        if (response instanceof byte[]) {
            return serializeBulkString((byte[]) response);
        }
        
        // 其他类型作为简单字符串处理
        return serializeSimpleString(response.toString());
    }
    
    private ByteBuf serializeSimpleString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte('+');
        buffer.writeBytes(bytes);
        buffer.writeBytes("\r\n".getBytes());
        return buffer;
    }
    
    private ByteBuf serializeError(String error) {
        byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte('-');
        buffer.writeBytes(bytes);
        buffer.writeBytes("\r\n".getBytes());
        return buffer;
    }
    
    private ByteBuf serializeInteger(long value) {
        // 优化：使用预定义的常用整数响应
        if (value >= 0 && value < INT_CACHE.length) {
            ByteBuf buffer = Unpooled.directBuffer(INT_CACHE[(int) value].length);
            buffer.writeBytes(INT_CACHE[(int) value]);
            return buffer;
        }
        
        // 其他整数使用优化的转换方法
        byte[] bytes = longToBytes(value);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte(':');
        buffer.writeBytes(bytes);
        buffer.writeBytes(CRLF);
        return buffer;
    }
    
    private ByteBuf serializeBulkString(byte[] value) {
        if (value == null) {
            return Unpooled.directBuffer(5).writeBytes("$-1\r\n".getBytes());
        }
        
        byte[] lengthBytes = longToBytes(value.length);
        ByteBuf buffer = Unpooled.directBuffer(1 + lengthBytes.length + 2 + value.length + 2);
        buffer.writeByte('$');
        buffer.writeBytes(lengthBytes);
        buffer.writeBytes("\r\n".getBytes());
        buffer.writeBytes(value);
        buffer.writeBytes("\r\n".getBytes());
        return buffer;
    }
    
    private ByteBuf serializeBulkString(String value) {
        if (value == null) {
            return Unpooled.directBuffer(5).writeBytes("$-1\r\n".getBytes());
        }
        return serializeBulkString(value.getBytes(StandardCharsets.UTF_8));
    }
    
    private ByteBuf serializeArray(List<?> values) {
        if (values == null) {
            return Unpooled.directBuffer(5).writeBytes("*-1\r\n".getBytes());
        }
        
        byte[] lengthBytes = longToBytes(values.size());
        // 预分配一个合理大小的缓冲区
        int estimatedSize = 1 + lengthBytes.length + 2 + values.size() * 16; // 估算每个元素平均16字节
        ByteBuf buffer = Unpooled.directBuffer(estimatedSize);
        buffer.writeByte('*');
        buffer.writeBytes(lengthBytes);
        buffer.writeBytes("\r\n".getBytes());
        
        for (Object value : values) {
            ByteBuf itemBuffer = serialize(value);
            buffer.writeBytes(itemBuffer);
            itemBuffer.release();
        }
        
        return buffer;
    }
    
    private ByteBuf serializeNull() {
        return Unpooled.directBuffer(5).writeBytes("$-1\r\n".getBytes());
    }
    
    // 优化的长整数转字节数组方法
    private byte[] longToBytes(long value) {
        if (value == 0) {
            return new byte[]{'0'};
        }
        
        boolean negative = value < 0;
        if (negative) {
            value = -value;
        }
        
        // 计算数字的位数
        int digits = 0;
        long temp = value;
        while (temp > 0) {
            temp /= 10;
            digits++;
        }
        
        // 分配缓冲区
        byte[] bytes = new byte[negative ? digits + 1 : digits];
        int index = bytes.length - 1;
        
        // 填充数字
        while (value > 0) {
            bytes[index--] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        
        // 处理负数
        if (negative) {
            bytes[0] = '-';
        }
        
        return bytes;
    }
}
