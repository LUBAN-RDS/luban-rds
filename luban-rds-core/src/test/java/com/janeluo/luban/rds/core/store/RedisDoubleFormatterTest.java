package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RedisDoubleFormatterTest {
    @Test
    void integralValuesHaveNoDecimalPoint() {
        assertEquals("1", RedisDoubleFormatter.format(1.0));
        assertEquals("-3", RedisDoubleFormatter.format(-3.0));
        assertEquals("0", RedisDoubleFormatter.format(0.0));
        assertEquals("0", RedisDoubleFormatter.format(-0.0)); // Redis 输出 "0"
    }

    @Test
    void fractionalShortestRepr() {
        assertEquals("1.5", RedisDoubleFormatter.format(1.5));
        assertEquals("0.1", RedisDoubleFormatter.format(0.1));
        assertEquals("0.30000000000000004", RedisDoubleFormatter.format(0.1 + 0.2)); // 非整值最短往返
    }

    @Test
    void specialValues() {
        assertEquals("nan", RedisDoubleFormatter.format(Double.NaN));
        assertEquals("inf", RedisDoubleFormatter.format(Double.POSITIVE_INFINITY));
        assertEquals("-inf", RedisDoubleFormatter.format(Double.NEGATIVE_INFINITY));
    }

    @Test
    void largeIntegralBeyondLong() {
        assertEquals("1e+20", RedisDoubleFormatter.format(1e20)); // 超 long 整值 → 科学计数
    }
}
