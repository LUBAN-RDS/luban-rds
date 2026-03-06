package com.janeluo.luban.rds.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis RESP 协议解析器
 *
 * 严格遵循 RESP 协议规范：
 * 1. 协议层文本（命令名、简单字符串、错误消息）使用 UTF-8 编码
 * 2. 批量字符串（Bulk String）保持二进制安全，直接处理原始字节
 * 3. 服务器不介入客户端的编解码逻辑，仅作为字节流的透明传输者
 *
 * 性能优化：
 * 1. 使用 ThreadLocal 缓存 StringBuilder，避免频繁创建对象
 * 2. 预定义常用响应的字节数组，避免重复编码
 * 3. 优化整数解析，避免字符串转换
 */
public class RedisProtocolParser {

    /** ThreadLocal StringBuilder 缓存 */
    private static final ThreadLocal<StringBuilder> STRING_BUILDER_CACHE =
            ThreadLocal.withInitial(() -> new StringBuilder(64));

    /** CRLF 换行符 */
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    /** 空 Bulk String 响应 */
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.UTF_8);

    /** 空 Array 响应 */
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.UTF_8);

    /** 空 Array 响应 */
    private static final byte[] EMPTY_ARRAY = "*0\r\n".getBytes(StandardCharsets.UTF_8);

    /** 整数响应缓存（0-31） */
    private static final byte[][] INT_CACHE = new byte[32][];

    static {
        for (int i = 0; i < 32; i++) {
            INT_CACHE[i] = (":" + i + "\r\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 解析 ByteBuf 中的命令
     *
     * @param buffer 输入缓冲区
     * @return 解析成功的 Command 对象，数据不完整时返回 null
     */
    public Command parse(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return null;
        }

        buffer.markReaderIndex();

        byte firstByte = buffer.readByte();

        if (firstByte == '*') {
            Command command = parseArray(buffer);
            if (command != null) {
                return command;
            }
        }

        buffer.resetReaderIndex();
        return null;
    }

    /**
     * 解析数组类型的命令
     */
    private Command parseArray(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length <= 0) {
            return null;
        }

        byte[][] argsBytes = new byte[length][];
        for (int i = 0; i < length; i++) {
            if (!buffer.isReadable()) {
                return null;
            }

            byte type = buffer.readByte();
            if (type == '$') {
                argsBytes[i] = parseBulkStringBytes(buffer);
                if (argsBytes[i] == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        if (argsBytes.length > 0) {
            String commandName = new String(argsBytes[0], StandardCharsets.UTF_8);
            String[] args = new String[argsBytes.length];
            for (int i = 0; i < argsBytes.length; i++) {
                args[i] = new String(argsBytes[i], StandardCharsets.ISO_8859_1);
            }
            return new Command(commandName, args);
        }

        return null;
    }

    /**
     * 解析整数
     *
     * @param buffer 输入缓冲区
     * @return 解析的整数值，解析失败返回 -1
     */
    public int parseInteger(ByteBuf buffer) {
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

    /**
     * 解析批量字符串，返回原始字节
     * 批量字符串是二进制安全的，直接返回原始字节不做任何编码转换
     */
    private byte[] parseBulkStringBytes(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length < 0) {
            return null;
        }

        if (buffer.readableBytes() < length + 2) {
            return null;
        }

        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        buffer.skipBytes(2);

        return bytes;
    }

    /**
     * 解析批量字符串，使用 ISO-8859-1 转换为 String
     * ISO-8859-1 是单字节编码，可以无损地将任意字节序列转换为字符串
     * 这样可以保证二进制数据的完整性
     */
    private String parseBulkString(ByteBuf buffer) {
        byte[] bytes = parseBulkStringBytes(buffer);
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * 解析 RESP 协议数据
     *
     * @param buffer 输入缓冲区
     * @return 解析后的对象，数据不完整时返回 null
     */
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
            case '%':
                return parseMapValues(buffer);
            case '~':
                return parseSetValues(buffer);
            case '|':
                return parseAttributeValues(buffer);
            case '_':
                return parseNull(buffer);
            case ',':
                return parseDouble(buffer);
            case '#':
                return parseBoolean(buffer);
            case '(':
                return parseBigNumber(buffer);
            default:
                buffer.resetReaderIndex();
                return null;
        }
    }

    /**
     * 解析简单字符串，使用 UTF-8 编码
     * 简单字符串用于协议层文本，如状态回复
     */
    private String parseSimpleString(ByteBuf buffer) {
        return parseLineUtf8(buffer);
    }

    /**
     * 解析错误消息，使用 UTF-8 编码
     */
    private String parseError(ByteBuf buffer) {
        return parseLineUtf8(buffer);
    }

    /**
     * 解析一行文本，使用 UTF-8 编码
     * 用于简单字符串、错误消息等协议层文本
     */
    private String parseLineUtf8(ByteBuf buffer) {
        int startIndex = buffer.readerIndex();
        int crlfIndex = -1;

        for (int i = startIndex; i < buffer.writerIndex() - 1; i++) {
            if (buffer.getByte(i) == '\r' && buffer.getByte(i + 1) == '\n') {
                crlfIndex = i;
                break;
            }
        }

        if (crlfIndex == -1) {
            return null;
        }

        int length = crlfIndex - startIndex;
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        buffer.skipBytes(2);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 解析数组值
     */
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

    /**
     * 解析 RESP3 Map 类型
     */
    private Map<Object, Object> parseMapValues(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length < 0) {
            return null;
        }

        Map<Object, Object> map = new HashMap<>(length);
        for (int i = 0; i < length; i++) {
            if (!buffer.isReadable()) {
                buffer.resetReaderIndex();
                return null;
            }

            Object key = parseResp(buffer);
            if (key == null) {
                buffer.resetReaderIndex();
                return null;
            }

            if (!buffer.isReadable()) {
                buffer.resetReaderIndex();
                return null;
            }

            Object value = parseResp(buffer);
            if (value == null) {
                buffer.resetReaderIndex();
                return null;
            }

            map.put(key, value);
        }

        return map;
    }

    /**
     * 解析 RESP3 Set 类型
     */
    private Set<Object> parseSetValues(ByteBuf buffer) {
        int length = parseInteger(buffer);
        if (length < 0) {
            return null;
        }

        Set<Object> set = new HashSet<>(length);
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

            set.add(value);
        }

        return set;
    }

    /**
     * 解析 RESP3 Attribute 类型
     */
    private Map<Object, Object> parseAttributeValues(ByteBuf buffer) {
        return parseMapValues(buffer);
    }

    /**
     * 解析 RESP3 Null 类型
     */
    private Object parseNull(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            buffer.resetReaderIndex();
            return null;
        }

        byte b = buffer.readByte();
        if (b == '\r') {
            if (buffer.readableBytes() > 0 && buffer.readByte() == '\n') {
                return null;
            }
        }

        buffer.resetReaderIndex();
        return null;
    }

    /**
     * 解析 RESP3 Double 类型
     */
    private Double parseDouble(ByteBuf buffer) {
        String value = parseLineUtf8(buffer);
        if (value == null) {
            return null;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析 RESP3 Boolean 类型
     */
    private Boolean parseBoolean(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            buffer.resetReaderIndex();
            return null;
        }

        byte b = buffer.readByte();
        if (b == 't' || b == 'f') {
            if (buffer.readableBytes() >= 1 && buffer.readByte() == '\r'
                    && buffer.readableBytes() >= 1 && buffer.readByte() == '\n') {
                return b == 't';
            }
        }

        buffer.resetReaderIndex();
        return null;
    }

    /**
     * 解析 RESP3 Big Number 类型
     */
    private String parseBigNumber(ByteBuf buffer) {
        return parseLineUtf8(buffer);
    }

    /**
     * 序列化响应对象为 RESP 格式
     *
     * @param response 响应对象
     * @return 序列化后的 ByteBuf
     */
    public ByteBuf serialize(Object response) {
        if (response == null) {
            return serializeNull();
        }

        if (response instanceof byte[]) {
            return serializeBulkString((byte[]) response);
        }

        if (response instanceof String) {
            String str = (String) response;
            if (str.startsWith("+") || str.startsWith("-") || str.startsWith(":") || str.startsWith("*")
                    || str.startsWith("%") || str.startsWith("~") || str.startsWith("|") || str.startsWith("_")
                    || str.startsWith(",") || str.startsWith("#") || str.startsWith("(")) {
                ByteBuf buffer = Unpooled.directBuffer(str.length());
                buffer.writeBytes(str.getBytes(StandardCharsets.UTF_8));
                return buffer;
            }
            if (str.startsWith("$")) {
                ByteBuf buffer = Unpooled.directBuffer(str.length());
                buffer.writeBytes(str.getBytes(StandardCharsets.ISO_8859_1));
                return buffer;
            }
            if (str.startsWith("ERR")) {
                return serializeError(str);
            }
            return serializeBulkString(str);
        }

        if (response instanceof Long || response instanceof Integer) {
            return serializeInteger(((Number) response).longValue());
        }

        if (response instanceof Double) {
            return serializeDouble((Double) response);
        }

        if (response instanceof Boolean) {
            return serializeBoolean((Boolean) response);
        }

        if (response instanceof List) {
            return serializeArray((List<?>) response);
        }

        if (response instanceof Map) {
            return serializeMap((Map<?, ?>) response);
        }

        if (response instanceof Set) {
            return serializeSet((Set<?>) response);
        }

        return serializeBulkString(response.toString());
    }

    /**
     * 序列化简单字符串，使用 UTF-8 编码
     */
    private ByteBuf serializeSimpleString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte('+');
        buffer.writeBytes(bytes);
        buffer.writeBytes(CRLF);
        return buffer;
    }

    /**
     * 序列化错误消息，使用 UTF-8 编码
     */
    private ByteBuf serializeError(String error) {
        byte[] bytes = error.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte('-');
        buffer.writeBytes(bytes);
        buffer.writeBytes(CRLF);
        return buffer;
    }

    /**
     * 序列化整数
     */
    private ByteBuf serializeInteger(long value) {
        if (value >= 0 && value < INT_CACHE.length) {
            ByteBuf buffer = Unpooled.directBuffer(INT_CACHE[(int) value].length);
            buffer.writeBytes(INT_CACHE[(int) value]);
            return buffer;
        }

        byte[] bytes = longToBytes(value);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte(':');
        buffer.writeBytes(bytes);
        buffer.writeBytes(CRLF);
        return buffer;
    }

    /**
     * 序列化批量字符串（二进制安全）
     * 直接写入原始字节，不做任何编码转换
     *
     * @param value 字节数组
     * @return 序列化后的 ByteBuf
     */
    public ByteBuf serializeBulkString(byte[] value) {
        if (value == null) {
            return Unpooled.directBuffer(5).writeBytes("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        }

        byte[] lengthBytes = longToBytes(value.length);
        ByteBuf buffer = Unpooled.directBuffer(1 + lengthBytes.length + 2 + value.length + 2);
        buffer.writeByte('$');
        buffer.writeBytes(lengthBytes);
        buffer.writeBytes(CRLF);
        buffer.writeBytes(value);
        buffer.writeBytes(CRLF);
        return buffer;
    }

    /**
     * 序列化字符串为批量字符串
     * 使用 ISO-8859-1 编码确保二进制安全
     * ISO-8859-1 是单字节编码，可以无损地将字符串转换回原始字节
     *
     * @param value 字符串值
     * @return 序列化后的 ByteBuf
     */
    public ByteBuf serializeBulkString(String value) {
        if (value == null) {
            return Unpooled.directBuffer(5).writeBytes("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        }
        return serializeBulkString(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * 序列化数组
     */
    private ByteBuf serializeArray(List<?> values) {
        if (values == null) {
            return Unpooled.directBuffer(5).writeBytes("*-1\r\n".getBytes(StandardCharsets.UTF_8));
        }

        byte[] lengthBytes = longToBytes(values.size());
        int estimatedSize = 1 + lengthBytes.length + 2 + values.size() * 16;
        ByteBuf buffer = Unpooled.directBuffer(estimatedSize);
        buffer.writeByte('*');
        buffer.writeBytes(lengthBytes);
        buffer.writeBytes(CRLF);

        for (Object value : values) {
            ByteBuf itemBuffer = serializeArrayItem(value);
            buffer.writeBytes(itemBuffer);
            itemBuffer.release();
        }

        return buffer;
    }

    /**
     * 序列化数组元素
     */
    private ByteBuf serializeArrayItem(Object value) {
        if (value == null) {
            return Unpooled.directBuffer(5).writeBytes("$-1\r\n".getBytes(StandardCharsets.UTF_8));
        }

        if (value instanceof byte[]) {
            return serializeBulkString((byte[]) value);
        }

        if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith(":")) {
                try {
                    String numStr = str.substring(1).trim();
                    long num = Long.parseLong(numStr);
                    return serializeInteger(num);
                } catch (NumberFormatException e) {
                    ByteBuf buffer = Unpooled.directBuffer(str.length());
                    buffer.writeBytes(str.getBytes(StandardCharsets.UTF_8));
                    return buffer;
                }
            } else if (str.startsWith("+") || str.startsWith("-") || str.startsWith("*")
                    || str.startsWith("%") || str.startsWith("~") || str.startsWith("|") || str.startsWith("_")
                    || str.startsWith(",") || str.startsWith("#") || str.startsWith("(")) {
                ByteBuf buffer = Unpooled.directBuffer(str.length());
                buffer.writeBytes(str.getBytes(StandardCharsets.UTF_8));
                return buffer;
            }
            if (str.startsWith("$")) {
                ByteBuf buffer = Unpooled.directBuffer(str.length());
                buffer.writeBytes(str.getBytes(StandardCharsets.ISO_8859_1));
                return buffer;
            }
            return serializeBulkString(str);
        }

        if (value instanceof Long || value instanceof Integer) {
            return serializeInteger(((Number) value).longValue());
        }

        return serializeBulkString(value.toString());
    }

    /**
     * 序列化空值
     */
    private ByteBuf serializeNull() {
        return Unpooled.directBuffer(3).writeBytes("_\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 序列化 RESP3 Map 类型
     */
    private ByteBuf serializeMap(Map<?, ?> map) {
        if (map == null) {
            return Unpooled.directBuffer(5).writeBytes("%-1\r\n".getBytes(StandardCharsets.UTF_8));
        }

        byte[] lengthBytes = longToBytes(map.size());
        int estimatedSize = 1 + lengthBytes.length + 2 + map.size() * 32;
        ByteBuf buffer = Unpooled.directBuffer(estimatedSize);
        buffer.writeByte('%');
        buffer.writeBytes(lengthBytes);
        buffer.writeBytes(CRLF);

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            ByteBuf keyBuffer = serializeArrayItem(entry.getKey());
            buffer.writeBytes(keyBuffer);
            keyBuffer.release();

            ByteBuf valueBuffer = serializeArrayItem(entry.getValue());
            buffer.writeBytes(valueBuffer);
            valueBuffer.release();
        }

        return buffer;
    }

    /**
     * 序列化 RESP3 Set 类型
     */
    private ByteBuf serializeSet(Set<?> set) {
        if (set == null) {
            return Unpooled.directBuffer(5).writeBytes("~-1\r\n".getBytes(StandardCharsets.UTF_8));
        }

        byte[] lengthBytes = longToBytes(set.size());
        int estimatedSize = 1 + lengthBytes.length + 2 + set.size() * 16;
        ByteBuf buffer = Unpooled.directBuffer(estimatedSize);
        buffer.writeByte('~');
        buffer.writeBytes(lengthBytes);
        buffer.writeBytes(CRLF);

        for (Object value : set) {
            ByteBuf itemBuffer = serializeArrayItem(value);
            buffer.writeBytes(itemBuffer);
            itemBuffer.release();
        }

        return buffer;
    }

    /**
     * 序列化 RESP3 Double 类型
     */
    private ByteBuf serializeDouble(double value) {
        String str = Double.toString(value);
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.directBuffer(1 + bytes.length + 2);
        buffer.writeByte(',');
        buffer.writeBytes(bytes);
        buffer.writeBytes(CRLF);
        return buffer;
    }

    /**
     * 序列化 RESP3 Boolean 类型
     */
    private ByteBuf serializeBoolean(boolean value) {
        byte[] bytes = value ? "#t\r\n".getBytes(StandardCharsets.UTF_8) : "#f\r\n".getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.directBuffer(bytes.length);
        buffer.writeBytes(bytes);
        return buffer;
    }

    /**
     * 将 long 转换为字节数组
     */
    private byte[] longToBytes(long value) {
        StringBuilder sb = STRING_BUILDER_CACHE.get();
        sb.setLength(0);
        sb.append(value);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
