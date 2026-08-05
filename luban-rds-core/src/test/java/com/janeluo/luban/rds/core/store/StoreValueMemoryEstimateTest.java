package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 StoreValue.estimateSize 对 String 的内存计量按 UTF-8 字节长度，
 * 而非错误的 length()*2（UTF-16 char count）。JDK9+ String 内部为 byte[]，
 * Latin-1 编码时每个字符仅 1 字节，按 char*2 会把 ASCII 估算翻倍。
 *
 * <p>StoreValue 是 DefaultMemoryStore 的 private 内部类，这里通过
 * DefaultMemoryStore.set + getUsedMemory 间接验证其估算逻辑。
 */
class StoreValueMemoryEstimateTest {

    private DefaultMemoryStore store;

    @BeforeEach
    void setup() {
        store = new DefaultMemoryStore();
    }

    @AfterEach
    void teardown() {
        store.close();
    }

    @Test
    void asciiStringSizeShouldBeAboutByteLength() {
        // 1000 个 ASCII 字符：Latin-1 编码 1 byte/char，UTF-8 同样 1 byte/char
        String ascii = "a".repeat(1000);
        store.set(0, "k", ascii);
        long used = store.getUsedMemory();
        // 修正前按 length*2 算成 2000（+ overhead）会 > 1500；
        // 修正后应为 UTF-8 字节长度(1000) + overhead，明显 < 1500。
        assertTrue(used < 1500,
                "ASCII string 1000B should estimate < 1500, got " + used);
    }

    @Test
    void emptyStringOverhead() {
        store.set(0, "k", "");
        long used = store.getUsedMemory();
        assertTrue(used > 0 && used < 300,
                "empty string overhead should be within (0,300), got " + used);
    }
}
