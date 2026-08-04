package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RdbDataLoader#finishLoading(MemoryStore)} keysLoaded 计数修复验证（阶段 10.4）。
 *
 * <p><b>bug 根因</b>：原 {@code RdbDataLoader} 的 {@code keysLoaded} 字段在 {@code startLoading}
 * 中仅 {@code set(0)}，加载全程从不 {@code incrementAndGet}；而真正统计 keyCount 的
 * {@link RdbPersistService#load} 是 {@code void}，不返回计数。结果 {@code finishLoading} 恒返回 0。</p>
 *
 * <p><b>修复</b>：新增 {@link RdbPersistService#loadWithKeyCount(MemoryStore)} 返回 keyCount，
 * {@code RdbDataLoader.finishLoading} 改调它并把返回值 set 进 keysLoaded。</p>
 *
 * <p>本测试：向 store 写入 N 个 key → persistSync 落盘 dump.rdb → 用 RdbDataLoader 的
 * startLoading/writeChunk/finishLoading 流程加载，断言 finishLoading 返回 N（修复前为 0）。</p>
 */
public class RdbDataLoaderKeysLoadedTest {

    private static final String TEST_DATA_DIR = "./target/test-data/rdb-keysloaded-test";

    private RdbPersistService persistService;
    private RdbDataLoader dataLoader;
    private File dataDir;

    @BeforeEach
    public void setUp() {
        cleanDir();
        dataDir = new File(TEST_DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        persistService = new RdbPersistService(TEST_DATA_DIR);
        dataLoader = new RdbDataLoader(persistService, TEST_DATA_DIR);
    }

    @AfterEach
    public void tearDown() {
        if (persistService != null) {
            persistService.close();
        }
        cleanDir();
    }

    private void cleanDir() {
        File dir = new File(TEST_DATA_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    @Test
    public void finishLoading_returnsNonZeroKeysLoaded_afterFix() {
        int n = 5;
        MemoryStore src = new DefaultMemoryStore();
        for (int i = 0; i < n; i++) {
            src.set(0, "key" + i, "value" + i);
        }

        // 落盘 dump.rdb
        persistService.persistSync(src);
        File dump = new File(TEST_DATA_DIR, "dump.rdb");
        assertTrue(dump.exists(), "dump.rdb 应已落盘");
        assertTrue(dump.length() > 0, "dump.rdb 应非空");

        // 把 dump.rdb 字节读出，经 writeChunk 喂给 RdbDataLoader（模拟从主节点接收 RDB）
        byte[] rdbBytes = readAll(dump);
        assertNotNull(rdbBytes);
        assertTrue(rdbBytes.length > 0, "rdb 字节应非空");

        MemoryStore target = new DefaultMemoryStore();
        boolean started = dataLoader.startLoading(target, null);
        assertTrue(started, "startLoading 应成功");

        // 分块喂入（模拟网络分片）
        int chunk = 1024;
        int off = 0;
        while (off < rdbBytes.length) {
            int len = Math.min(chunk, rdbBytes.length - off);
            ByteBuf buf = Unpooled.wrappedBuffer(rdbBytes, off, len);
            assertTrue(dataLoader.writeChunk(buf), "writeChunk 应成功");
            buf.release();
            off += len;
        }

        long keys = dataLoader.finishLoading(target);

        // 核心断言：修复前为 0，修复后应等于写入的 key 数
        assertTrue(keys > 0,
                () -> "finishLoading 返回的 keysLoaded 应 > 0（修复前恒为 0），实际=" + keys);
        assertEquals((long) n, keys, "keysLoaded 应等于写入的 key 数");
        assertEquals(keys, dataLoader.getKeysLoaded(), "getKeysLoaded 应与返回值一致");

        // 顺便验证数据真的加载进来了
        for (int i = 0; i < n; i++) {
            assertEquals("value" + i, target.get(0, "key" + i));
        }
    }

    @Test
    public void loadWithKeyCount_returnsKeyCount() {
        // 直接验证 RdbPersistService.loadWithKeyCount（修复的核心新增方法）
        MemoryStore src = new DefaultMemoryStore();
        src.set(0, "a", "1");
        src.set(0, "b", "2");
        src.set(0, "c", "3");
        persistService.persistSync(src);

        MemoryStore target = new DefaultMemoryStore();
        long count = persistService.loadWithKeyCount(target);

        assertEquals(3L, count, "loadWithKeyCount 应返回 3");
        assertEquals("1", target.get(0, "a"));
        assertEquals("2", target.get(0, "b"));
        assertEquals("3", target.get(0, "c"));
    }

    private static byte[] readAll(File f) {
        try (FileInputStream fis = new FileInputStream(f);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = bis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
