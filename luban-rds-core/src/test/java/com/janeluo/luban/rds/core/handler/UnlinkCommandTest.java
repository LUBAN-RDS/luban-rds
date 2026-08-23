package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UNLINK 命令专项测试（JUnit 5，可被 surefire 运行）。
 *
 * <p>覆盖：多键删除计数（只统计存在的键）、缺参错误串、删除后键不可见
 * （exists=false / type=none）、过期键计数为 0（UNLINK 与 DEL 同语义）、
 * 命令注册表包含 UNLINK。
 *
 * <p>命令全部通过 {@link CommonCommandHandler} 层驱动，断言真实 RESP 字符串。
 */
class UnlinkCommandTest {

    private static final int DB = 0;

    private final CommonCommandHandler common = new CommonCommandHandler();
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new DefaultMemoryStore();
    }

    @Test
    void unlinkCountsOnlyExistingKeys() {
        store.set(DB, "k1", "v1");
        store.set(DB, "k2", "v2");
        store.set(DB, "k3", "v3");
        Object resp = common.handle(DB, new String[]{"UNLINK", "k1", "k2", "nonexist"}, store);
        assertEquals(":2\r\n", resp, "UNLINK 应只统计实际存在的键");
    }

    @Test
    void unlinkNoArgsReturnsError() {
        Object resp = common.handle(DB, new String[]{"UNLINK"}, store);
        assertEquals("-ERR wrong number of arguments for 'unlink' command\r\n", resp, "UNLINK 缺参错误串");
    }

    @Test
    void unlinkThenKeyGone() {
        store.set(DB, "k", "v");
        Object resp = common.handle(DB, new String[]{"UNLINK", "k"}, store);
        assertEquals(":1\r\n", resp, "UNLINK 单个存在的键应返回 1");
        assertFalse(store.exists(DB, "k"), "UNLINK 后键应不存在");
        assertEquals("none", store.type(DB, "k"), "UNLINK 后 TYPE 应为 none");
    }

    @Test
    void unlinkExpiredKeyCountsZero() throws InterruptedException {
        store.setWithExpire(DB, "exp", "v", 1);
        Thread.sleep(1500);
        Object resp = common.handle(DB, new String[]{"UNLINK", "exp"}, store);
        assertEquals(":0\r\n", resp, "已过期键不计入 UNLINK 删除数");
    }

    @Test
    void delExpiredKeyCountsZero() throws InterruptedException {
        store.setWithExpire(DB, "exp", "v", 1);
        Thread.sleep(1500);
        Object resp = common.handle(DB, new String[]{"DEL", "exp"}, store);
        assertEquals(":0\r\n", resp, "已过期键不计入 DEL 删除数");
    }

    @Test
    void unlinkRegisteredInSupportedCommands() {
        assertTrue(common.supportedCommands().contains("UNLINK"), "UNLINK 应注册在 supportedCommands 中");
    }
}