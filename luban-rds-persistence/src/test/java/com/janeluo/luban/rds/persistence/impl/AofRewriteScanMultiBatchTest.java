package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 AOF 重写对超过单批 COUNT（100）的键做多批 scan 遍历时全键无丢失。
 * <p>
 * R4 规格场景：数据库存在超过单批 COUNT（100）的键，执行 AOF 重写 →
 * AOF 包含全部键的重建命令，无丢失。
 * </p>
 * <p>
 * 根因背景：SCAN 族重写为 last-visited-key 编码游标后，AofPersistService
 * 以 {@code scan(db, cursor, "*", 100, null)} 在 {@code do/while} 循环中
 * 逐批生成重建命令；若游标推进/终止条件有误，第二批及后续批的键会丢失。
 * 本测试插入 110 个键（105 个 string + 5 个结构类型键），
 * 保证首批 scan 返回非零游标、必然触发多批遍历。
 * </p>
 */
public class AofRewriteScanMultiBatchTest {

    /** 单批 scan COUNT，与 AofPersistService 重写时使用的一致。 */
    private static final int SCAN_BATCH = 100;

    /** string 键数量：105 个 string + 5 个结构键 = 110，保证跨 2 批。 */
    private static final int STRING_KEY_COUNT = 105;

    private static final int TOTAL_KEY_COUNT = 110;

    private static final String TEST_DATA_DIR = "./target/test-data/aof-rewrite-scan-multibatch-test";

    private AofPersistService persistService;
    private MemoryStore memoryStore;

    @Before
    public void setUp() {
        cleanTestDataDir();
        File dataDir = new File(TEST_DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        persistService = new AofPersistService(TEST_DATA_DIR, 0);
        memoryStore = new DefaultMemoryStore();
    }

    @After
    public void tearDown() {
        if (persistService != null) {
            persistService.close();
        }
        cleanTestDataDir();
    }

    private void cleanTestDataDir() {
        File dataDir = new File(TEST_DATA_DIR);
        if (dataDir.exists()) {
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            dataDir.delete();
        }
    }

    private static String stringKey(int i) {
        return String.format("mb:aof:str:%04d", i);
    }

    private static String stringValue(int i) {
        return "val-" + i;
    }

    /**
     * 向 store 插入 110 个键（105 个 string + 2 个 hash + 1 个 set + 1 个 zset + 1 个 list）。
     */
    private void insertKeys() {
        for (int i = 0; i < STRING_KEY_COUNT; i++) {
            memoryStore.set(0, stringKey(i), stringValue(i));
        }
        memoryStore.hmset(0, "mb:aof:hash:1", "f1", "v1", "f2", "v2");
        memoryStore.hmset(0, "mb:aof:hash:2", "f1", "v1");
        memoryStore.sadd(0, "mb:aof:set:1", "m1", "m2", "m3");
        memoryStore.zadd(0, "mb:aof:zset:1", 1.0, "a");
        memoryStore.zadd(0, "mb:aof:zset:1", 2.0, "b");
        memoryStore.rpush(0, "mb:aof:list:1", "l1", "l2", "l3");
    }

    /**
     * 验证重写前的数据量，并确认首批 scan（COUNT=100）返回非零游标，
     * 即测试数据必然触发多批遍历（若为单批，本测试失去验证意义）。
     */
    private void assertMultiBatchPrecondition() {
        assertEquals("插入键总数应为 110", TOTAL_KEY_COUNT, memoryStore.dbsize(0));

        List<Object> firstPage = memoryStore.scan(0, "0", "*", SCAN_BATCH, null);
        assertTrue("首批 scan 应返回 100 个键（COUNT=100）",
                firstPage.size() == SCAN_BATCH + 1);
        String cursor = (String) firstPage.get(0);
        assertTrue("首批 scan 后游标应为非零（必须跨多批遍历），实际=" + cursor,
                !"0".equals(cursor));
    }

    /**
     * 统计 content 中 pattern 的出现次数。
     */
    private static int countOccurrences(String content, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(pattern, idx)) >= 0) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    /**
     * 读取重写后的 AOF 文件内容（ISO-8859-1 编码，与 AofPersistService 写入一致）。
     */
    private static String readAofContent() throws IOException {
        File aofFile = new File(TEST_DATA_DIR + File.separator + "appendonly.aof");
        assertTrue("重写后应存在 AOF 文件: " + aofFile.getAbsolutePath(), aofFile.exists());
        byte[] bytes = Files.readAllBytes(Paths.get(aofFile.toURI()));
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * 构造某 string 键的 RESP 形式 SET 重建命令全文。
     * 形如 {@code *3\r\n$3\r\nSET\r\n$<keylen>\r\n<key>\r\n$<vallen>\r\n<val>\r\n}。
     */
    private static String respSetCommand(String key, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append("*3\r\n");
        appendRespArg(sb, "SET");
        appendRespArg(sb, key);
        appendRespArg(sb, value);
        return sb.toString();
    }

    private static void appendRespArg(StringBuilder sb, String arg) {
        byte[] bytes = arg.getBytes(StandardCharsets.ISO_8859_1);
        sb.append("$").append(bytes.length).append("\r\n").append(arg).append("\r\n");
    }

    /**
     * 110 个键 AOF 重写后：文件中每个键的重建命令齐全、命令条数与键数一致，
     * 且重写文件可完整加载（数量与内容无丢失）。
     */
    @Test
    public void testAofRewriteAllKeysSurviveAcrossBatches() throws IOException {
        insertKeys();
        assertMultiBatchPrecondition();

        persistService.rewrite(memoryStore);

        String content = readAofContent();

        // 每个 string 键都应有完整的 RESP 形式 SET 重建命令
        for (int i = 0; i < STRING_KEY_COUNT; i++) {
            String cmd = respSetCommand(stringKey(i), stringValue(i));
            assertTrue("AOF 应包含 string 键 " + stringKey(i) + " 的 SET 重建命令",
                    content.contains(cmd));
        }

        // 命令条数校验：SET 恰好 105 条，各结构键重建命令各 1 条
        assertEquals("SET 重建命令应为 105 条", STRING_KEY_COUNT,
                countOccurrences(content, "\r\nSET\r\n"));
        assertEquals("HSET 重建命令应为 2 条", 2,
                countOccurrences(content, "\r\nHSET\r\n"));
        assertEquals("SADD 重建命令应为 1 条", 1,
                countOccurrences(content, "\r\nSADD\r\n"));
        assertEquals("ZADD 重建命令应为 1 条", 1,
                countOccurrences(content, "\r\nZADD\r\n"));
        assertEquals("RPUSH 重建命令应为 1 条", 1,
                countOccurrences(content, "\r\nRPUSH\r\n"));

        // 端到端：重写后的 AOF 可完整加载，键数量与内容无丢失
        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("AOF 加载后键总数应为 110，无丢失",
                TOTAL_KEY_COUNT, newStore.dbsize(0));

        assertEquals("val-0", newStore.get(0, stringKey(0)));
        assertEquals("val-104", newStore.get(0, stringKey(STRING_KEY_COUNT - 1)));

        assertEquals("hash", newStore.type(0, "mb:aof:hash:1"));
        Map<String, String> hash1 = newStore.hgetall(0, "mb:aof:hash:1");
        assertEquals(2, hash1.size());
        assertEquals("v2", hash1.get("f2"));

        assertEquals("set", newStore.type(0, "mb:aof:set:1"));
        Set<String> set1 = newStore.smembers(0, "mb:aof:set:1");
        assertEquals(3, set1.size());
        assertTrue(set1.contains("m1"));

        assertEquals("zset", newStore.type(0, "mb:aof:zset:1"));
        assertEquals(2L, newStore.zcard(0, "mb:aof:zset:1"));
        assertEquals(Double.valueOf(2.0), newStore.zscore(0, "mb:aof:zset:1", "b"));

        assertEquals("list", newStore.type(0, "mb:aof:list:1"));
        assertEquals(3, newStore.lrange(0, "mb:aof:list:1", 0, -1).size());
    }
}
