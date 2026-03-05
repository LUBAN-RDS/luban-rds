package com.janeluo.luban.rds.core.handler;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of the Lua struct library for Luban-RDS.
 * Supports a subset of format specifiers used by Redis/Redisson.
 */
public class StructLib extends TwoArgFunction {

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

    static class Pack extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String fmt = args.checkjstring(1);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ByteOrder order = ByteOrder.LITTLE_ENDIAN; // Default to Little Endian for Redis compatibility

            int argIdx = 2;
            int i = 0;
            int len = fmt.length();

            while (i < len) {
                char c = fmt.charAt(i);
                switch (c) {
                    case '>':
                        order = ByteOrder.BIG_ENDIAN;
                        break;
                    case '<':
                        order = ByteOrder.LITTLE_ENDIAN;
                        break;
                    case '!': // Native alignment (ignore for now, treat as default)
                        break;
                    case 'd': // double (8 bytes)
                    {
                        double val = args.checkdouble(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(8).order(order);
                        bb.putDouble(val);
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'L': // unsigned long (4 bytes)
                    {
                        long val = (long) args.checkdouble(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(4).order(order);
                        // Write as unsigned int (4 bytes)
                        bb.putInt((int) (val & 0xFFFFFFFFL));
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'l': // signed long (4 bytes)
                    {
                        long val = (long) args.checkdouble(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(4).order(order);
                        bb.putInt((int) val);
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'I': // unsigned int (4 bytes) - same as L
                    {
                        long val = (long) args.checkdouble(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(4).order(order);
                        bb.putInt((int) (val & 0xFFFFFFFFL));
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'i': // signed int (4 bytes) - same as l
                    {
                        long val = (long) args.checkdouble(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(4).order(order);
                        bb.putInt((int) val);
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'H': // unsigned short (2 bytes)
                    {
                        int val = args.checkint(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(2).order(order);
                        bb.putShort((short) (val & 0xFFFF));
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'h': // signed short (2 bytes)
                    {
                        int val = args.checkint(argIdx++);
                        ByteBuffer bb = ByteBuffer.allocate(2).order(order);
                        bb.putShort((short) val);
                        try {
                            baos.write(bb.array());
                        } catch (IOException e) {
                            // Should not happen
                        }
                    }
                    break;
                    case 'b': // signed byte
                    {
                        int val = args.checkint(argIdx++);
                        baos.write((byte) val);
                    }
                    break;
                    case 'B': // unsigned byte
                    {
                        int val = args.checkint(argIdx++);
                        baos.write((byte) (val & 0xFF));
                    }
                    break;
                    case 'c': // char/string
                    {
                        // Check for length
                        int next = i + 1;
                        int strLen = 0;
                        boolean isC0 = false;
                        if (next < len && Character.isDigit(fmt.charAt(next))) {
                            // Parse length
                            int start = next;
                            while (next < len && Character.isDigit(fmt.charAt(next))) {
                                next++;
                            }
                            String numStr = fmt.substring(start, next);
                            strLen = Integer.parseInt(numStr);
                            i = next - 1; // Advance i
                            if (strLen == 0) {
                                isC0 = true;
                            }
                        } else {
                            // Default c is 1 byte char? In standard struct it is.
                            // But here we might just treat 'c' without number as 1 char?
                            strLen = 1;
                        }

                        if (isC0) {
                            // c0: write variable length string
                            LuaValue val = args.checkvalue(argIdx++);
                            String s = val.tojstring();
                            try {
                                baos.write(s.getBytes("UTF-8")); // Assuming UTF-8 for Lua strings
                            } catch (IOException e) {
                            }
                        } else {
                            // cn: fixed length string
                            LuaValue val = args.checkvalue(argIdx++);
                            String s = val.tojstring();
                            byte[] bytes;
                            try {
                                bytes = s.getBytes("UTF-8");
                            } catch (Exception e) {
                                bytes = new byte[0];
                            }
                            // Pad or truncate
                            if (bytes.length < strLen) {
                                try {
                                    baos.write(bytes);
                                    for (int k = 0; k < strLen - bytes.length; k++) {
                                        baos.write(0);
                                    }
                                } catch (IOException e) {}
                            } else {
                                baos.write(bytes, 0, strLen);
                            }
                        }
                    }
                    break;
                    default:
                        // Ignore unknown chars (like space)
                        break;
                }
                i++;
            }

            try {
                return LuaValue.valueOf(new String(baos.toByteArray(), "ISO-8859-1")); // Return as raw bytes string
            } catch (Exception e) {
                return LuaValue.NIL;
            }
        }
    }

    static class Unpack extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String fmt = args.checkjstring(1);
            String data = args.checkjstring(2);
            int pos = args.optint(3, 1) - 1; // Lua 1-based to Java 0-based

            if (pos < 0) pos = 0;
            
            byte[] bytes;
            try {
                bytes = data.getBytes("ISO-8859-1"); // Get raw bytes
            } catch (Exception e) {
                return LuaValue.NIL;
            }

            ByteBuffer bb = ByteBuffer.wrap(bytes);
            ByteOrder order = ByteOrder.LITTLE_ENDIAN; // Default
            bb.order(order);

            List<LuaValue> results = new ArrayList<>();

            int i = 0;
            int len = fmt.length();
            
            // Set position
            if (pos < bytes.length) {
                bb.position(pos);
            } else {
                // If pos is out of bounds but we expect to read, it might be an issue. 
                // But for c0 it implies empty.
                bb.position(bytes.length);
            }

            while (i < len) {
                char c = fmt.charAt(i);
                switch (c) {
                    case '>':
                        order = ByteOrder.BIG_ENDIAN;
                        bb.order(order);
                        break;
                    case '<':
                        order = ByteOrder.LITTLE_ENDIAN;
                        bb.order(order);
                        break;
                    case '!':
                        break;
                    case 'd':
                        if (bb.remaining() >= 8) {
                            results.add(LuaValue.valueOf(bb.getDouble()));
                        } else {
                            // Fail?
                            return LuaValue.NIL;
                        }
                        break;
                    case 'L': // unsigned long (4 bytes) -> Java long
                        if (bb.remaining() >= 4) {
                            long val = bb.getInt() & 0xFFFFFFFFL;
                            results.add(LuaValue.valueOf(val));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'l': // signed long (4 bytes) -> Java int/long
                        if (bb.remaining() >= 4) {
                            results.add(LuaValue.valueOf(bb.getInt()));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'I':
                        if (bb.remaining() >= 4) {
                            long val = bb.getInt() & 0xFFFFFFFFL;
                            results.add(LuaValue.valueOf(val));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'i':
                        if (bb.remaining() >= 4) {
                            results.add(LuaValue.valueOf(bb.getInt()));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'H': // unsigned short
                        if (bb.remaining() >= 2) {
                            results.add(LuaValue.valueOf(bb.getShort() & 0xFFFF));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'h': // signed short
                        if (bb.remaining() >= 2) {
                            results.add(LuaValue.valueOf(bb.getShort()));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'b': // signed byte
                        if (bb.remaining() >= 1) {
                            results.add(LuaValue.valueOf(bb.get()));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'B': // unsigned byte
                        if (bb.remaining() >= 1) {
                            results.add(LuaValue.valueOf(bb.get() & 0xFF));
                        } else {
                            return LuaValue.NIL;
                        }
                        break;
                    case 'c':
                    {
                        int next = i + 1;
                        int strLen = 0;
                        boolean isC0 = false;
                        if (next < len && Character.isDigit(fmt.charAt(next))) {
                            int start = next;
                            while (next < len && Character.isDigit(fmt.charAt(next))) {
                                next++;
                            }
                            String numStr = fmt.substring(start, next);
                            strLen = Integer.parseInt(numStr);
                            i = next - 1;
                            if (strLen == 0) {
                                isC0 = true;
                            }
                        } else {
                            strLen = 1;
                        }

                        if (isC0) {
                            // c0: read all remaining
                            int remaining = bb.remaining();
                            byte[] buf = new byte[remaining];
                            bb.get(buf);
                            try {
                                results.add(LuaValue.valueOf(new String(buf, "UTF-8"))); // Assuming UTF-8
                            } catch (Exception e) {}
                        } else {
                            // cn: read n bytes
                            if (bb.remaining() >= strLen) {
                                byte[] buf = new byte[strLen];
                                bb.get(buf);
                                try {
                                    results.add(LuaValue.valueOf(new String(buf, "UTF-8")));
                                } catch (Exception e) {}
                            } else {
                                return LuaValue.NIL;
                            }
                        }
                    }
                    break;
                }
                i++;
            }
            
            // Add new position (1-based)
            results.add(LuaValue.valueOf(bb.position() + 1));
            
            return LuaValue.varargsOf(results.toArray(new LuaValue[0]));
        }
    }

    static class Size extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String fmt = args.checkjstring(1);
            int size = 0;
            int i = 0;
            int len = fmt.length();
            while (i < len) {
                char c = fmt.charAt(i);
                switch (c) {
                    case 'd': size += 8; break;
                    case 'L': case 'l': case 'I': case 'i': size += 4; break;
                    case 'H': case 'h': size += 2; break;
                    case 'b': case 'B': size += 1; break;
                    case 'c':
                        int next = i + 1;
                        int strLen = 0;
                        if (next < len && Character.isDigit(fmt.charAt(next))) {
                            int start = next;
                            while (next < len && Character.isDigit(fmt.charAt(next))) {
                                next++;
                            }
                            String numStr = fmt.substring(start, next);
                            strLen = Integer.parseInt(numStr);
                            i = next - 1;
                        } else {
                            strLen = 1;
                        }
                        if (strLen == 0) {
                            // c0 has variable size, cannot determine static size
                            // Return error or 0? Standard struct.size might error?
                            // For now, let's just ignore or return 0
                        }
                        size += strLen;
                        break;
                }
                i++;
            }
            return LuaValue.valueOf(size);
        }
    }
}
