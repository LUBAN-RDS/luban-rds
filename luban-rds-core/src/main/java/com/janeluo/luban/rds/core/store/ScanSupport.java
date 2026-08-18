package com.janeluo.luban.rds.core.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * SCAN/HSCAN/SSCAN/ZSCAN 统一页算法与游标编解码。
 *
 * <p>游标契约：{@link #DONE}（"0"）= 起始/完成；续游标 = "c:" + hex(最后访问元素)；
 * ZSCAN 为 "c:" + hex(score IEEE 位，固定 16 字符) + hex(member)。非 "c:" 前缀一律视为起始。
 * 页算法保证：每个元素整轮恰好访问一次；遍历期间始终存在的元素至少返回一次（弱一致，与 Redis 同级）。
 */
final class ScanSupport {

    static final String DONE = "0";
    private static final String PREFIX = "c:";
    private static final HexFormat HEX = HexFormat.of();

    private ScanSupport() {}

    /** 单页遍历：从 elements 迭代器消费，停于 visited==count 或 matched==count；返回 [游标, 输出...]。 */
    static List<Object> page(Iterator<Object> elements, int count,
                             Predicate<Object> accept, Function<Object, List<Object>> output) {
        List<Object> result = new ArrayList<>();
        int visited = 0;
        int matched = 0;
        Object lastVisited = null;
        while (elements.hasNext() && visited < count && matched < count) {
            Object e = elements.next();
            visited++;
            if (accept.test(e)) {
                matched++;
                result.addAll(output.apply(e));
            }
            lastVisited = e;
        }
        result.add(0, elements.hasNext() ? encode((String) lastVisited) : DONE);
        return result;
    }

    // ---- 游标编解码 ----

    static String encode(String element) {
        return PREFIX + HEX.formatHex(element.getBytes(StandardCharsets.UTF_8));
    }

    static String encodePair(double score, String member) {
        return PREFIX + HEX.formatHex(longToBytes(Double.doubleToLongBits(score))) + HEX.formatHex(member.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码普通元素游标；非 "c:" 前缀或解码失败返回 null（视为起始）。 */
    static String decodeKey(String cursor) {
        if (cursor == null || !cursor.startsWith(PREFIX)) {
            return null;
        }
        try {
            return new String(HEX.parseHex(cursor.substring(PREFIX.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 解码 ZSCAN 对游标；返回 [score, member]，失败返回 null。 */
    static Object[] decodePair(String cursor) {
        if (cursor == null || !cursor.startsWith(PREFIX)) {
            return null;
        }
        String body = cursor.substring(PREFIX.length());
        if (body.length() < 16) {
            return null;
        }
        try {
            double score = Double.longBitsToDouble(bytesToLong(HEX.parseHex(body.substring(0, 16))));
            String member = new String(HEX.parseHex(body.substring(16)), StandardCharsets.UTF_8);
            return new Object[]{score, member};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) (v & 0xFF); v >>= 8; }
        return b;
    }

    private static long bytesToLong(byte[] b) {
        long v = 0;
        for (byte x : b) { v = (v << 8) | (x & 0xFF); }
        return v;
    }

    /** 两个有序且键不相交的迭代器按字典序归并。 */
    static Iterator<String> merge(Iterator<String> a, Iterator<String> b) {
        return new Iterator<String>() {
            private String nextA = a.hasNext() ? a.next() : null;
            private String nextB = b.hasNext() ? b.next() : null;

            @Override
            public boolean hasNext() {
                return nextA != null || nextB != null;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (nextA == null) { String r = nextB; nextB = b.hasNext() ? b.next() : null; return r; }
                if (nextB == null) { String r = nextA; nextA = a.hasNext() ? a.next() : null; return r; }
                if (nextA.compareTo(nextB) <= 0) {
                    String r = nextA;
                    nextA = a.hasNext() ? a.next() : null;
                    return r;
                }
                String r = nextB;
                nextB = b.hasNext() ? b.next() : null;
                return r;
            }
        };
    }
}
