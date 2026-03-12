package com.janeluo.luban.rds.core.handler;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lua struct库实现
 *
 * <p>为Luban-RDS提供Lua struct库支持，用于二进制数据的打包和解包。
 * 支持Redis/Redisson使用的格式说明符子集。
 *
 * <h2>设计规范</h2>
 * <ul>
 *   <li>安全性 - 所有操作必须进行边界检查</li>
 *   <li>可移植性 - 统一的字节序处理</li>
 *   <li>高效性 - 最小化内存拷贝，热点函数内联</li>
 * </ul>
 *
 * <h2>支持的格式说明符</h2>
 * <ul>
 *   <li>&gt; - 大端字节序</li>
 *   <li>&lt; - 小端字节序（默认）</li>
 *   <li>! - 本机对齐（忽略）</li>
 *   <li>d - double（8字节）</li>
 *   <li>L - unsigned long（4字节）</li>
 *   <li>l - signed long（4字节）</li>
 *   <li>I - unsigned int（4字节）</li>
 *   <li>i - signed int（4字节）</li>
 *   <li>H - unsigned short（2字节）</li>
 *   <li>h - signed short（2字节）</li>
 *   <li>B - unsigned byte（1字节）</li>
 *   <li>b - signed byte（1字节）</li>
 *   <li>cN - N字节字符串，c0表示变长字符串</li>
 * </ul>
 *
 * @author janeluo
 * @since 1.0.0
 */
public class StructLib extends TwoArgFunction {

    /* ==================== 错误码定义 ==================== */

    /**
     * 操作成功
     */
    public static final int UNPACK_OK = 0;
    /**
     * 边界错误
     */
    public static final int UNPACK_ERR_BOUNDS = -1;
    /**
     * 类型错误
     */
    public static final int UNPACK_ERR_TYPE = -2;
    /**
     * 内存错误
     */
    public static final int UNPACK_ERR_MEMORY = -3;
    /**
     * 格式错误
     */
    public static final int UNPACK_ERR_FORMAT = -4;
    /**
     * 参数错误
     */
    public static final int UNPACK_ERR_ARGS = -5;

    /* ==================== 常量定义 ==================== */

    /**
     * 数据类型大小
     */
    private static final int SIZE_DOUBLE = 8;
    private static final int SIZE_LONG = 4;
    private static final int SIZE_SHORT = 2;
    private static final int SIZE_BYTE = 1;

    /**
     * 默认字节序：小端（Redis兼容）
     */
    private static final ByteOrder DEFAULT_ORDER = ByteOrder.LITTLE_ENDIAN;

    @Override
    public LuaValue call(LuaValue modname, LuaValue env) {
        LuaTable struct = new LuaTable();
        struct.set("pack", new Pack());
        struct.set("unpack", new Unpack());
        struct.set("size", new Size());
        env.set("struct", struct);
        if (!env.get("package").isnil()) {
            env.get("package").get("loaded").set("struct", struct);
        }
        return struct;
    }

    /* ==================== 边界检查宏（Java实现） ==================== */

    /**
     * 边界检查
     *
     * @param pos    当前位置
     * @param end    结束位置
     * @param needed 需要的字节数
     * @return true 如果越界
     */
    private static boolean checkBounds(int pos, int end, int needed) {
        return pos + needed > end;
    }

    /**
     * 边界检查（long版本）
     *
     * @param pos    当前位置
     * @param end    结束位置
     * @param needed 需要的字节数
     * @return true 如果越界
     */
    private static boolean checkBounds(long pos, long end, long needed) {
        return pos + needed > end;
    }

    /* ==================== 内联优化函数 ==================== */

    /**
     * 快速读取小端uint32
     * 避免ByteBuffer开销
     */
    private static long readUint32LE(byte[] data, int pos) {
        return ((data[pos] & 0xFFL)) |
                ((data[pos + 1] & 0xFFL) << 8) |
                ((data[pos + 2] & 0xFFL) << 16) |
                ((data[pos + 3] & 0xFFL) << 24);
    }

    /**
     * 快速读取大端uint32
     */
    private static long readUint32BE(byte[] data, int pos) {
        return ((data[pos] & 0xFFL) << 24) |
                ((data[pos + 1] & 0xFFL) << 16) |
                ((data[pos + 2] & 0xFFL) << 8) |
                ((data[pos + 3] & 0xFFL));
    }

    /**
     * 快速读取小端int32
     */
    private static int readInt32LE(byte[] data, int pos) {
        return (int) readUint32LE(data, pos);
    }

    /**
     * 快速读取大端int32
     */
    private static int readInt32BE(byte[] data, int pos) {
        return (int) readUint32BE(data, pos);
    }

    /**
     * 快速写入小端uint32
     */
    private static void writeUint32LE(byte[] data, int pos, long value) {
        data[pos] = (byte) (value & 0xFF);
        data[pos + 1] = (byte) ((value >> 8) & 0xFF);
        data[pos + 2] = (byte) ((value >> 16) & 0xFF);
        data[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * 快速写入大端uint32
     */
    private static void writeUint32BE(byte[] data, int pos, long value) {
        data[pos] = (byte) ((value >> 24) & 0xFF);
        data[pos + 1] = (byte) ((value >> 16) & 0xFF);
        data[pos + 2] = (byte) ((value >> 8) & 0xFF);
        data[pos + 3] = (byte) (value & 0xFF);
    }

    /**
     * 快速写入小端int32
     */
    private static void writeInt32LE(byte[] data, int pos, int value) {
        writeUint32LE(data, pos, value & 0xFFFFFFFFL);
    }

    /**
     * 快速写入大端int32
     */
    private static void writeInt32BE(byte[] data, int pos, int value) {
        writeUint32BE(data, pos, value & 0xFFFFFFFFL);
    }

    /**
     * Pack函数实现
     *
     * <p>将Lua值打包为二进制字符串
     *
     * <p>错误处理：
     * <ul>
     *   <li>参数不足时返回nil</li>
     *   <li>格式错误时返回nil</li>
     * </ul>
     */
    static class Pack extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String fmt = args.checkjstring(1);
            int fmtLen = fmt.length();
            ByteOrder order = DEFAULT_ORDER;

            // 第一遍：计算所需缓冲区大小
            int bufferSize = 0;
            int argCount = 2;
            int[] argIdx = {argCount};

            for (int i = 0; i < fmtLen; i++) {
                char c = fmt.charAt(i);
                switch (c) {
                    case '>':
                    case '<':
                    case '!':
                        break;
                    case 'd':
                        bufferSize += SIZE_DOUBLE;
                        argIdx[0]++;
                        break;
                    case 'L':
                    case 'l':
                    case 'I':
                    case 'i':
                        if (i + 2 < fmtLen && fmt.charAt(i + 1) == 'c' && fmt.charAt(i + 2) == '0') {
                            bufferSize += SIZE_LONG;
                            if (argIdx[0] > args.narg()) {
                                return LuaValue.NIL;
                            }
                            LuaValue val = args.arg(argIdx[0]);
                            if (val.isnumber()) {
                                argIdx[0]++;
                                if (argIdx[0] > args.narg()) {
                                    return LuaValue.NIL;
                                }
                                LuaValue strVal = args.arg(argIdx[0]);
                                if (strVal instanceof org.luaj.vm2.LuaString) {
                                    org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) strVal;
                                    bufferSize += ls.length();
                                } else {
                                    String s = strVal.tojstring();
                                    bufferSize += s.getBytes(StandardCharsets.ISO_8859_1).length;
                                }
                            } else {
                                if (val instanceof org.luaj.vm2.LuaString) {
                                    org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) val;
                                    bufferSize += ls.length();
                                } else {
                                    String s = val.tojstring();
                                    bufferSize += s.getBytes(StandardCharsets.ISO_8859_1).length;
                                }
                            }
                            argIdx[0]++;
                            i += 2;
                        } else {
                            bufferSize += SIZE_LONG;
                            argIdx[0]++;
                        }
                        break;
                    case 'H':
                    case 'h':
                        bufferSize += SIZE_SHORT;
                        argIdx[0]++;
                        break;
                    case 'b':
                    case 'B':
                        bufferSize += SIZE_BYTE;
                        argIdx[0]++;
                        break;
                    case 'c':
                        int[] result = parseCStringLen(fmt, i);
                        int strLen = result[0];
                        i = result[1];
                        if (strLen == 0) {
                            if (argIdx[0] > args.narg()) {
                                return LuaValue.NIL;
                            }
                            LuaValue val = args.arg(argIdx[0]);
                            if (val instanceof org.luaj.vm2.LuaString) {
                                org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) val;
                                bufferSize += ls.length();
                            } else {
                                String s = val.tojstring();
                                bufferSize += s.getBytes(StandardCharsets.ISO_8859_1).length;
                            }
                        } else {
                            bufferSize += strLen;
                        }
                        argIdx[0]++;
                        break;
                    default:
                        break;
                }
            }

            // 分配缓冲区
            byte[] buffer = new byte[bufferSize];
            int pos = 0;

            // 第二遍：执行打包
            order = DEFAULT_ORDER;
            argIdx[0] = 2;

            for (int i = 0; i < fmtLen; i++) {
                char c = fmt.charAt(i);

                boolean isCombinedFormat = (c == 'L' || c == 'l' || c == 'I' || c == 'i')
                        && i + 2 < fmtLen && fmt.charAt(i + 1) == 'c' && fmt.charAt(i + 2) == '0';

                if (!isCombinedFormat && needArgument(c) && argIdx[0] > args.narg()) {
                    return LuaValue.NIL;
                }

                switch (c) {
                    case '>':
                        order = ByteOrder.BIG_ENDIAN;
                        break;
                    case '<':
                        order = ByteOrder.LITTLE_ENDIAN;
                        break;
                    case '!':
                        break;
                    case 'd':
                        pos = packDouble(buffer, pos, args.checkdouble(argIdx[0]++), order);
                        break;
                    case 'L':
                    case 'I':
                        if (i + 2 < fmtLen && fmt.charAt(i + 1) == 'c' && fmt.charAt(i + 2) == '0') {
                            LuaValue firstArg = args.arg(argIdx[0]);
                            byte[] strBytes;
                            long strLen;
                            if (firstArg.isnumber()) {
                                strLen = firstArg.tolong();
                                argIdx[0]++;
                                LuaValue strVal = args.arg(argIdx[0]++);
                                if (strVal instanceof org.luaj.vm2.LuaString) {
                                    org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) strVal;
                                    strBytes = new byte[ls.length()];
                                    ls.copyInto(0, strBytes, 0, strBytes.length);
                                } else {
                                    strBytes = strVal.tojstring().getBytes(StandardCharsets.ISO_8859_1);
                                }
                            } else {
                                if (firstArg instanceof org.luaj.vm2.LuaString) {
                                    org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) firstArg;
                                    strBytes = new byte[ls.length()];
                                    ls.copyInto(0, strBytes, 0, strBytes.length);
                                } else {
                                    strBytes = firstArg.tojstring().getBytes(StandardCharsets.ISO_8859_1);
                                }
                                strLen = strBytes.length;
                                argIdx[0]++;
                            }
                            pos = packUnsignedLong(buffer, pos, strLen, order);
                            System.arraycopy(strBytes, 0, buffer, pos, strBytes.length);
                            pos += strBytes.length;
                            i += 2;
                        } else {
                            pos = packUnsignedLong(buffer, pos, args.checkdouble(argIdx[0]++), order);
                        }
                        break;
                    case 'l':
                    case 'i':
                        if (i + 2 < fmtLen && fmt.charAt(i + 1) == 'c' && fmt.charAt(i + 2) == '0') {
                            LuaValue firstArg = args.arg(argIdx[0]);
                            byte[] strBytes;
                            int strLen;
                            if (firstArg.isnumber()) {
                                strLen = firstArg.toint();
                                argIdx[0]++;
                                LuaValue strVal = args.arg(argIdx[0]++);
                                if (strVal instanceof org.luaj.vm2.LuaString) {
                                    org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) strVal;
                                    strBytes = new byte[ls.length()];
                                    ls.copyInto(0, strBytes, 0, strBytes.length);
                                } else {
                                    strBytes = strVal.tojstring().getBytes(StandardCharsets.ISO_8859_1);
                                }
                            } else {
                                if (firstArg instanceof org.luaj.vm2.LuaString) {
                                    org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) firstArg;
                                    strBytes = new byte[ls.length()];
                                    ls.copyInto(0, strBytes, 0, strBytes.length);
                                } else {
                                    strBytes = firstArg.tojstring().getBytes(StandardCharsets.ISO_8859_1);
                                }
                                strLen = strBytes.length;
                                argIdx[0]++;
                            }
                            pos = packSignedLong(buffer, pos, strLen, order);
                            System.arraycopy(strBytes, 0, buffer, pos, strBytes.length);
                            pos += strBytes.length;
                            i += 2;
                        } else {
                            pos = packSignedLong(buffer, pos, args.checkdouble(argIdx[0]++), order);
                        }
                        break;
                    case 'H':
                        pos = packUnsignedShort(buffer, pos, args.checkint(argIdx[0]++), order);
                        break;
                    case 'h':
                        pos = packSignedShort(buffer, pos, args.checkint(argIdx[0]++), order);
                        break;
                    case 'b':
                        pos = packByte(buffer, pos, args.checkint(argIdx[0]++));
                        break;
                    case 'B':
                        pos = packUnsignedByte(buffer, pos, args.checkint(argIdx[0]++));
                        break;
                    case 'c':
                        int[] result = parseCStringLen(fmt, i);
                        int strLen = result[0];
                        i = result[1];
                        pos = packString(buffer, pos, args.arg(argIdx[0]++), strLen);
                        break;
                    default:
                        break;
                }
            }

            return org.luaj.vm2.LuaString.valueOf(buffer);
        }

        private boolean needArgument(char c) {
            return c != '>' && c != '<' && c != '!' && c != ' ';
        }

        private int packDouble(byte[] buffer, int pos, double value, ByteOrder order) {
            ByteBuffer.wrap(buffer, pos, SIZE_DOUBLE).order(order).putDouble(value);
            return pos + SIZE_DOUBLE;
        }

        private int packUnsignedLong(byte[] buffer, int pos, double value, ByteOrder order) {
            long val = (long) value;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                writeUint32LE(buffer, pos, val);
            } else {
                writeUint32BE(buffer, pos, val);
            }
            return pos + SIZE_LONG;
        }

        private int packSignedLong(byte[] buffer, int pos, double value, ByteOrder order) {
            int val = (int) value;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                writeInt32LE(buffer, pos, val);
            } else {
                writeInt32BE(buffer, pos, val);
            }
            return pos + SIZE_LONG;
        }

        private int packUnsignedShort(byte[] buffer, int pos, int value, ByteOrder order) {
            int val = value & 0xFFFF;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                buffer[pos] = (byte) (val & 0xFF);
                buffer[pos + 1] = (byte) ((val >> 8) & 0xFF);
            } else {
                buffer[pos] = (byte) ((val >> 8) & 0xFF);
                buffer[pos + 1] = (byte) (val & 0xFF);
            }
            return pos + SIZE_SHORT;
        }

        private int packSignedShort(byte[] buffer, int pos, int value, ByteOrder order) {
            short val = (short) value;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                buffer[pos] = (byte) (val & 0xFF);
                buffer[pos + 1] = (byte) ((val >> 8) & 0xFF);
            } else {
                buffer[pos] = (byte) ((val >> 8) & 0xFF);
                buffer[pos + 1] = (byte) (val & 0xFF);
            }
            return pos + SIZE_SHORT;
        }

        private int packByte(byte[] buffer, int pos, int value) {
            buffer[pos] = (byte) value;
            return pos + SIZE_BYTE;
        }

        private int packUnsignedByte(byte[] buffer, int pos, int value) {
            buffer[pos] = (byte) (value & 0xFF);
            return pos + SIZE_BYTE;
        }

        private int packString(byte[] buffer, int pos, LuaValue value, int strLen) {
            byte[] bytes;
            if (value instanceof org.luaj.vm2.LuaString) {
                org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) value;
                bytes = new byte[ls.length()];
                ls.copyInto(0, bytes, 0, bytes.length);
            } else {
                String s = value.tojstring();
                bytes = s.getBytes(StandardCharsets.ISO_8859_1);
            }

            if (strLen == 0) {
                System.arraycopy(bytes, 0, buffer, pos, bytes.length);
                return pos + bytes.length;
            } else {
                int copyLen = Math.min(bytes.length, strLen);
                System.arraycopy(bytes, 0, buffer, pos, copyLen);
                for (int j = copyLen; j < strLen; j++) {
                    buffer[pos + j] = 0;
                }
                return pos + strLen;
            }
        }

        private int[] parseCStringLen(String fmt, int start) {
            int i = start + 1;
            int fmtLen = fmt.length();

            if (i < fmtLen && Character.isDigit(fmt.charAt(i))) {
                int digitStart = i;
                while (i < fmtLen && Character.isDigit(fmt.charAt(i))) {
                    i++;
                }
                int strLen = Integer.parseInt(fmt.substring(digitStart, i));
                return new int[]{strLen, i - 1};
            }
            return new int[]{1, start};
        }
    }

    /**
     * Unpack函数实现
     *
     * <p>从二进制字符串解包为Lua值
     *
     * <p>错误处理：
     * <ul>
     *   <li>边界错误时返回nil</li>
     *   <li>格式错误时返回nil</li>
     * </ul>
     *
     * <p>设计规范：
     * <ul>
     *   <li>所有解包操作前必须进行边界检查</li>
     *   <li>使用快速内联函数避免ByteBuffer开销</li>
     *   <li>统一返回nil表示错误</li>
     * </ul>
     */
    static class Unpack extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String fmt = args.checkjstring(1);

            byte[] bytes;
            LuaValue dataArg = args.arg(2);
            if (dataArg instanceof org.luaj.vm2.LuaString) {
                org.luaj.vm2.LuaString ls = (org.luaj.vm2.LuaString) dataArg;
                bytes = new byte[ls.length()];
                ls.copyInto(0, bytes, 0, bytes.length);
            } else {
                String data = dataArg.tojstring();
                bytes = data.getBytes(StandardCharsets.ISO_8859_1);
            }

            int startPos = args.optint(3, 1) - 1;

            if (startPos < 0) {
                startPos = 0;
            }

            int dataLen = bytes.length;

            // 边界检查：起始位置超出范围
            if (startPos > dataLen) {
                return LuaValue.NIL;
            }

            int pos = startPos;
            int fmtLen = fmt.length();
            ByteOrder order = DEFAULT_ORDER;
            List<LuaValue> results = new ArrayList<>();

            for (int i = 0; i < fmtLen; i++) {
                char c = fmt.charAt(i);

                switch (c) {
                    case '>':
                        order = ByteOrder.BIG_ENDIAN;
                        break;
                    case '<':
                        order = ByteOrder.LITTLE_ENDIAN;
                        break;
                    case '!':
                        break;
                    case 'd':
                        if (checkBounds(pos, dataLen, SIZE_DOUBLE)) {
                            return LuaValue.NIL;
                        }
                        results.add(unpackDouble(bytes, pos, order));
                        pos += SIZE_DOUBLE;
                        break;
                    case 'L':
                    case 'I':
                        if (checkBounds(pos, dataLen, SIZE_LONG)) {
                            return LuaValue.NIL;
                        }
                    {
                        long lenVal;
                        if (order == ByteOrder.LITTLE_ENDIAN) {
                            lenVal = readUint32LE(bytes, pos);
                        } else {
                            lenVal = readUint32BE(bytes, pos);
                        }
                        pos += SIZE_LONG;

                        if (i + 2 < fmtLen && fmt.charAt(i + 1) == 'c' && fmt.charAt(i + 2) == '0') {
                            i += 2;
                            if (checkBounds(pos, dataLen, (int) lenVal)) {
                                return LuaValue.NIL;
                            }
                            results.add(unpackString(bytes, pos, (int) lenVal));
                            pos += lenVal;
                        } else {
                            results.add(LuaValue.valueOf(lenVal));
                        }
                    }
                    break;
                    case 'l':
                    case 'i':
                        if (checkBounds(pos, dataLen, SIZE_LONG)) {
                            return LuaValue.NIL;
                        }
                    {
                        int lenVal;
                        if (order == ByteOrder.LITTLE_ENDIAN) {
                            lenVal = readInt32LE(bytes, pos);
                        } else {
                            lenVal = readInt32BE(bytes, pos);
                        }
                        pos += SIZE_LONG;

                        if (i + 2 < fmtLen && fmt.charAt(i + 1) == 'c' && fmt.charAt(i + 2) == '0') {
                            i += 2;
                            if (lenVal < 0 || checkBounds(pos, dataLen, lenVal)) {
                                return LuaValue.NIL;
                            }
                            results.add(unpackString(bytes, pos, lenVal));
                            pos += lenVal;
                        } else {
                            results.add(LuaValue.valueOf(lenVal));
                        }
                    }
                    break;
                    case 'H':
                        if (checkBounds(pos, dataLen, SIZE_SHORT)) {
                            return LuaValue.NIL;
                        }
                        results.add(unpackUnsignedShort(bytes, pos, order));
                        pos += SIZE_SHORT;
                        break;
                    case 'h':
                        if (checkBounds(pos, dataLen, SIZE_SHORT)) {
                            return LuaValue.NIL;
                        }
                        results.add(unpackSignedShort(bytes, pos, order));
                        pos += SIZE_SHORT;
                        break;
                    case 'b':
                        if (checkBounds(pos, dataLen, SIZE_BYTE)) {
                            return LuaValue.NIL;
                        }
                        results.add(unpackSignedByte(bytes, pos));
                        pos += SIZE_BYTE;
                        break;
                    case 'B':
                        if (checkBounds(pos, dataLen, SIZE_BYTE)) {
                            return LuaValue.NIL;
                        }
                        results.add(unpackUnsignedByte(bytes, pos));
                        pos += SIZE_BYTE;
                        break;
                    case 'c':
                        int[] result = parseCStringLen(fmt, i);
                        int strLen = result[0];
                        i = result[1];

                        if (strLen == 0) {
                            int remaining = dataLen - pos;
                            if (remaining > 0) {
                                results.add(unpackString(bytes, pos, remaining));
                                pos += remaining;
                            } else {
                                results.add(LuaValue.valueOf(""));
                            }
                        } else {
                            if (checkBounds(pos, dataLen, strLen)) {
                                return LuaValue.NIL;
                            }
                            results.add(unpackString(bytes, pos, strLen));
                            pos += strLen;
                        }
                        break;
                    default:
                        break;
                }
            }

            return LuaValue.varargsOf(results.toArray(new LuaValue[0]));
        }

        private int[] parseCStringLen(String fmt, int start) {
            int i = start + 1;
            int fmtLen = fmt.length();

            if (i < fmtLen && Character.isDigit(fmt.charAt(i))) {
                int digitStart = i;
                while (i < fmtLen && Character.isDigit(fmt.charAt(i))) {
                    i++;
                }
                int strLen = Integer.parseInt(fmt.substring(digitStart, i));
                return new int[]{strLen, i - 1};
            }
            return new int[]{1, start};
        }

        // ========== 内联优化的解包函数 ==========

        private LuaValue unpackDouble(byte[] data, int pos, ByteOrder order) {
            double val = ByteBuffer.wrap(data, pos, SIZE_DOUBLE).order(order).getDouble();
            return LuaValue.valueOf(val);
        }

        private LuaValue unpackUnsignedLong(byte[] data, int pos, ByteOrder order) {
            long val;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                val = readUint32LE(data, pos);
            } else {
                val = readUint32BE(data, pos);
            }
            return LuaValue.valueOf(val);
        }

        private LuaValue unpackSignedLong(byte[] data, int pos, ByteOrder order) {
            int val;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                val = readInt32LE(data, pos);
            } else {
                val = readInt32BE(data, pos);
            }
            return LuaValue.valueOf(val);
        }

        private LuaValue unpackUnsignedShort(byte[] data, int pos, ByteOrder order) {
            int val;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                val = ((data[pos] & 0xFF)) | ((data[pos + 1] & 0xFF) << 8);
            } else {
                val = ((data[pos] & 0xFF) << 8) | ((data[pos + 1] & 0xFF));
            }
            return LuaValue.valueOf(val & 0xFFFF);
        }

        private LuaValue unpackSignedShort(byte[] data, int pos, ByteOrder order) {
            int val;
            if (order == ByteOrder.LITTLE_ENDIAN) {
                val = ((data[pos] & 0xFF)) | ((data[pos + 1] & 0xFF) << 8);
            } else {
                val = ((data[pos] & 0xFF) << 8) | ((data[pos + 1] & 0xFF));
            }
            // 转换为有符号short
            return LuaValue.valueOf((short) val);
        }

        private LuaValue unpackSignedByte(byte[] data, int pos) {
            return LuaValue.valueOf((byte) data[pos]);
        }

        private LuaValue unpackUnsignedByte(byte[] data, int pos) {
            return LuaValue.valueOf(data[pos] & 0xFF);
        }

        private LuaValue unpackString(byte[] data, int pos, int len) {
            byte[] copy = new byte[len];
            System.arraycopy(data, pos, copy, 0, len);
            return org.luaj.vm2.LuaString.valueOf(copy);
        }
    }

    /**
     * Size函数实现
     *
     * <p>计算格式字符串对应的二进制数据大小
     *
     * <p>注意：c0（变长字符串）无法确定静态大小，返回nil
     */
    static class Size extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String fmt = args.checkjstring(1);
            int size = 0;
            int fmtLen = fmt.length();

            for (int i = 0; i < fmtLen; i++) {
                char c = fmt.charAt(i);
                switch (c) {
                    case 'd':
                        size += SIZE_DOUBLE;
                        break;
                    case 'L':
                    case 'l':
                    case 'I':
                    case 'i':
                        size += SIZE_LONG;
                        break;
                    case 'H':
                    case 'h':
                        size += SIZE_SHORT;
                        break;
                    case 'b':
                    case 'B':
                        size += SIZE_BYTE;
                        break;
                    case 'c':
                        int[] result = parseCStringLen(fmt, i);
                        int strLen = result[0];
                        i = result[1];
                        if (strLen == 0) {
                            // c0 has variable size, cannot determine static size
                            // 返回nil表示无法确定
                            return LuaValue.NIL;
                        }
                        size += strLen;
                        break;
                    default:
                        break;
                }
            }
            return LuaValue.valueOf(size);
        }

        private int[] parseCStringLen(String fmt, int start) {
            int i = start + 1;
            int fmtLen = fmt.length();

            if (i < fmtLen && Character.isDigit(fmt.charAt(i))) {
                int digitStart = i;
                while (i < fmtLen && Character.isDigit(fmt.charAt(i))) {
                    i++;
                }
                int strLen = Integer.parseInt(fmt.substring(digitStart, i));
                return new int[]{strLen, i - 1};
            }
            return new int[]{1, start};
        }
    }
}
