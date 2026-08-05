package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OffHeapLeakDetectionTest {

    @Test
    void mixedWriteReplaceDeleteNoLeak() {
        OffHeapStringEngine engine = new OffHeapStringEngine(100);
        // 大量写、替换、删、过期、淘汰
        for (int i = 0; i < 1000; i++) {
            engine.set(0, "k" + i, "x".repeat(200));
        }
        for (int i = 0; i < 500; i++) {
            engine.del(0, "k" + i);           // del
        }
        for (int i = 500; i < 1000; i++) {
            engine.set(0, "k" + i, "y".repeat(300)); // replace
        }
        engine.flushdb(0);
        // 关闭后堆外占用应为 0
        engine.close();
        assertEquals(0, engine.estimateUsedMemory());
        // ADVANCED 模式下若有泄漏，log 会打印 LEAK 报告（CI grep 日志）
    }
}
