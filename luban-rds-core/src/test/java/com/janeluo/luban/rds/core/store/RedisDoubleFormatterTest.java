package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 断言与本地 Redis 7.0.12（addReplyDouble / %.17g 语义）实测输出一致，
 * 含整数快路径（|v| ≤ 2^62 且为整数 → 精确整数串）与 17 位有效数字四舍五入。
 */
class RedisDoubleFormatterTest {
    @Test
    void integralValuesHaveNoDecimalPoint() {
        assertEquals("1", RedisDoubleFormatter.format(1.0));
        assertEquals("-3", RedisDoubleFormatter.format(-3.0));
        assertEquals("0", RedisDoubleFormatter.format(0.0));
        assertEquals("0", RedisDoubleFormatter.format(-0.0)); // Redis 输出 "0"
    }

    @Test
    void fractionalSeventeenSignificantDigits() {
        // 实测：0.1 → "0.10000000000000001"、1.1 → "1.1000000000000001"（%.17g 精确二进制四舍五入）
        assertEquals("1.5", RedisDoubleFormatter.format(1.5));
        assertEquals("0.10000000000000001", RedisDoubleFormatter.format(0.1));
        assertEquals("1.1000000000000001", RedisDoubleFormatter.format(1.1));
        assertEquals("0.30000000000000004", RedisDoubleFormatter.format(0.1 + 0.2));
        assertEquals("2.2200000000000002", RedisDoubleFormatter.format(2.22));
        assertEquals("3.1415926535897931", RedisDoubleFormatter.format(3.141592653589793));
        assertEquals("0.0048999999999999998", RedisDoubleFormatter.format(0.0049));
        assertEquals("0.00048999999999999998", RedisDoubleFormatter.format(0.00049));
    }

    @Test
    void scientificNotationForSmallExponent() {
        // X < -4 → 科学计数法（小写 e、指数补零到 2 位）
        assertEquals("1.0000000000000001e-05", RedisDoubleFormatter.format(0.00001));
        assertEquals("1.5e-05", RedisDoubleFormatter.format(0.000015));
        assertEquals("2.5000000000000001e-05", RedisDoubleFormatter.format(0.000025));
        assertEquals("9.8999999999999994e-05", RedisDoubleFormatter.format(0.000099));
        assertEquals("9.9999999999999991e-22", RedisDoubleFormatter.format(1e-21));
        assertEquals("0.0001", RedisDoubleFormatter.format(0.0001)); // X = -4 仍定点
    }

    @Test
    void specialValues() {
        assertEquals("nan", RedisDoubleFormatter.format(Double.NaN));
        assertEquals("inf", RedisDoubleFormatter.format(Double.POSITIVE_INFINITY));
        assertEquals("-inf", RedisDoubleFormatter.format(Double.NEGATIVE_INFINITY));
    }

    @Test
    void integerFastPathWithinTwoPow62() {
        // Redis 整数快路径：|v| ≤ 2^62 且为整数 → 精确整数串（不做 17 位截断）
        assertEquals("12345678901234568", RedisDoubleFormatter.format(12345678901234568.0));
        assertEquals("4611686018427387392", RedisDoubleFormatter.format(4611686018427387392.0));
        assertEquals("4611686018427387904", RedisDoubleFormatter.format(Math.scalb(1.0, 62))); // 2^62 边界仍定点
        assertEquals("-4611686018427386880", RedisDoubleFormatter.format(-4611686018427386880.0));
        assertEquals("1000000000000000000", RedisDoubleFormatter.format(1e18));
    }

    @Test
    void largeBeyondTwoPow62UsesScientific() {
        // |v| > 2^62 → 科学计数法
        assertEquals("1e+19", RedisDoubleFormatter.format(1e19));
        assertEquals("1e+20", RedisDoubleFormatter.format(1e20));
        assertEquals("1e+21", RedisDoubleFormatter.format(1e21));
        assertEquals("5e+18", RedisDoubleFormatter.format(5e18));
        assertEquals("4.7e+18", RedisDoubleFormatter.format(4.7e18));
        assertEquals("4.999999999999999e+18", RedisDoubleFormatter.format(4.999999999999999e18));
        assertEquals("9.2233720368547758e+18", RedisDoubleFormatter.format(9.223372036854776e18));
        assertEquals("1.7976931348623157e+308", RedisDoubleFormatter.format(Double.MAX_VALUE));
    }
}