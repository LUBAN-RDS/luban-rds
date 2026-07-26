package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import com.janeluo.luban.rds.persistence.impl.AofPersistService;
import com.janeluo.luban.rds.persistence.impl.NonePersistService;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AOF 写命令接入命令分发路径的集成测试（C3 接入点）。
 *
 * <p>验证 {@link RedisServerHandler#processCommand} 在命令执行后将写命令的原始
 * RESP 帧写入 AOF（通过 {@link PersistService#recordCommand(byte[])}）：
 * <ul>
 *   <li>SET（写命令）记录到 AOF</li>
 *   <li>GET（只读命令）不记录</li>
 *   <li>SELECT 作为 db 上下文标记记录到 AOF（与 Redis 一致）</li>
 *   <li>FLUSHALL 记录到 AOF</li>
 *   <li>非 AOF 模式（NonePersistService）recordCommand 为 no-op，AOF 文件不产生</li>
 *   <li>BGREWRITEAOF 通过 ServerContext 回调触发异步 rewrite</li>
 * </ul>
 */
public class AofWriteHookTest {

    private static final String TEST_DATA_DIR = "./target/test-data/aof-write-hook-test";

    private MemoryStore memoryStore;
    private DefaultCommandHandler commandHandler;
    private RedisProtocolParser parser;
    private AofPersistService aofPersistService;
    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        cleanTestDataDir();
        File dataDir = new File(TEST_DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        memoryStore = new DefaultMemoryStore();
        commandHandler = new DefaultCommandHandler();
        parser = new RedisProtocolParser();
        // fsyncInterval=0 保证写入后立即落盘，便于断言
        aofPersistService = new AofPersistService(TEST_DATA_DIR, 0);
        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, parser, 0, false, null, null);
        handler.setPersistService(aofPersistService);
        // 注册 AOF rewrite 回调，供 BGREWRITEAOF 命令触发
        ServerContext.setAofRewriteCallback(() -> aofPersistService.rewrite(memoryStore));
        channel = new EmbeddedChannel(handler);
    }

    @After
    public void tearDown() {
        try {
            if (channel != null) {
                channel.finishAndReleaseAll();
            }
        } catch (Exception ignored) {
        }
        ServerContext.setAofRewriteCallback(null);
        if (aofPersistService != null) {
            aofPersistService.close();
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

    /**
     * 构造 RESP 命令帧字节。
     */
    private static byte[] respFrame(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.ISO_8859_1);
            sb.append('$').append(bytes.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 向 EmbeddedChannel 发送一条 RESP 命令帧，并消费其响应。
     */
    private void sendCommand(String... args) {
        ByteBuf input = Unpooled.wrappedBuffer(respFrame(args));
        channel.writeInbound(input);
        channel.flush();
        // 读取并释放响应，避免泄漏
        ByteBuf resp = channel.readOutbound();
        if (resp != null) {
            resp.release();
        }
    }

    private static byte[] readFile(File file) throws java.io.IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private byte[] readAof() throws java.io.IOException {
        File aofFile = new File(TEST_DATA_DIR, "appendonly.aof");
        assertTrue("AOF 文件应存在", aofFile.exists());
        return readFile(aofFile);
    }

    /**
     * SET 命令后 AOF 应包含其 RESP 字节。
     */
    @Test
    public void testSetCommandRecordedToAof() throws Exception {
        sendCommand("SET", "key1", "value1");
        byte[] aof = readAof();
        byte[] expected = respFrame("SET", "key1", "value1");
        assertEquals("SET 的 RESP 帧应被记录到 AOF",
                new String(expected, StandardCharsets.ISO_8859_1),
                new String(aof, StandardCharsets.ISO_8859_1));
    }

    /**
     * GET（只读命令）不应记录到 AOF。
     */
    @Test
    public void testGetCommandNotRecorded() throws Exception {
        sendCommand("SET", "k", "v");
        sendCommand("GET", "k");
        byte[] aof = readAof();
        String aofStr = new String(aof, StandardCharsets.ISO_8859_1);
        // AOF 应只包含 SET，不包含 GET
        assertTrue("AOF 应包含 SET", aofStr.contains("SET"));
        assertTrue("AOF 不应包含 GET 命令", !aofStr.contains("GET"));
    }

    /**
     * SELECT 命令应作为 db 上下文标记记录到 AOF。
     */
    @Test
    public void testSelectCommandRecordedAsDbMarker() throws Exception {
        sendCommand("SELECT", "1");
        byte[] aof = readAof();
        byte[] expected = respFrame("SELECT", "1");
        assertEquals("SELECT 的 RESP 帧应作为 db 标记记录到 AOF",
                new String(expected, StandardCharsets.ISO_8859_1),
                new String(aof, StandardCharsets.ISO_8859_1));
    }

    /**
     * FLUSHALL 命令应记录到 AOF。
     */
    @Test
    public void testFlushallCommandRecorded() throws Exception {
        sendCommand("SET", "k", "v");
        sendCommand("FLUSHALL");
        byte[] aof = readAof();
        String aofStr = new String(aof, StandardCharsets.ISO_8859_1);
        assertTrue("AOF 应包含 SET", aofStr.contains("SET"));
        assertTrue("AOF 应包含 FLUSHALL", aofStr.contains("FLUSHALL"));
    }

    /**
     * 非 AOF 模式（NonePersistService，default recordCommand 空实现）不应写入 AOF 文件。
     */
    @Test
    public void testNonAofModeDoesNotRecord() {
        // 用独立的目录与 channel 验证非 AOF 模式
        String nonAofDir = "./target/test-data/aof-write-hook-test-noneAof";
        cleanDir(nonAofDir);
        try {
            MemoryStore store = new DefaultMemoryStore();
            DefaultCommandHandler ch = new DefaultCommandHandler();
            RedisProtocolParser pp = new RedisProtocolParser();
            PersistService none = new NonePersistService();
            RedisServerHandler handler = new RedisServerHandler(
                    store, ch, pp, 0, false, null, null);
            handler.setPersistService(none);
            EmbeddedChannel ch2 = new EmbeddedChannel(handler);
            ByteBuf input = Unpooled.wrappedBuffer(respFrame("SET", "x", "y"));
            ch2.writeInbound(input);
            ch2.flush();
            ByteBuf resp = ch2.readOutbound();
            if (resp != null) {
                resp.release();
            }
            ch2.finishAndReleaseAll();
            none.close();
            // 非 AOF 模式不应产生 appendonly.aof
            File aofFile = new File(nonAofDir, "appendonly.aof");
            assertTrue("非 AOF 模式不应产生 AOF 文件", !aofFile.exists());
        } finally {
            cleanDir(nonAofDir);
        }
    }

    private void cleanDir(String dir) {
        File d = new File(dir);
        if (d.exists()) {
            File[] files = d.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            d.delete();
        }
    }

    /**
     * BGREWRITEAOF 命令应通过 ServerContext 回调触发异步 rewrite。
     *
     * <p>rewrite 会基于当前 MemoryStore 重建 AOF：先写入 SET key value（db0），
     * 因此触发后 AOF 应包含反映当前内存状态的 SET 命令。
     */
    @Test
    public void testBgrewriteaofTriggersRewrite() throws Exception {
        // 先写入数据
        sendCommand("SET", "rewriteKey", "rewriteVal");
        // 读取并清空响应缓冲
        // 触发 BGREWRITEAOF
        sendCommand("BGREWRITEAOF");

        // rewrite 通过单线程异步执行，等待完成
        Thread.sleep(500);

        byte[] aof = readAof();
        String aofStr = new String(aof, StandardCharsets.ISO_8859_1);
        // rewrite 后 AOF 应包含基于内存重建的 SET 命令（key=rewriteKey）
        assertTrue("BGREWRITEAOF 后 AOF 应包含 rewriteKey 的 SET 命令",
                aofStr.contains("rewriteKey"));
    }

    /**
     * 验证 BGREWRITEAOF 响应为 started（后台执行）。
     */
    @Test
    public void testBgrewriteaofResponseStarted() {
        ByteBuf input = Unpooled.wrappedBuffer(respFrame("BGREWRITEAOF"));
        channel.writeInbound(input);
        channel.flush();
        ByteBuf resp = channel.readOutbound();
        assertNotNull("BGREWRITEAOF 应有响应", resp);
        String respStr = resp.toString(StandardCharsets.UTF_8);
        resp.release();
        assertTrue("BGREWRITEAOF 响应应为 started",
                respStr.contains("Background append only file rewriting started"));
    }

    /**
     * 验证 ServerContext 回调确实被调用（计数器自增）。
     */
    @Test
    public void testAofRewriteCallbackInvoked() throws Exception {
        final AtomicInteger invokeCount = new AtomicInteger(0);
        ServerContext.setAofRewriteCallback(() -> {
            invokeCount.incrementAndGet();
            // 不实际执行 rewrite，仅验证回调被触发
        });
        sendCommand("BGREWRITEAOF");
        // 给异步任务一点时间
        Thread.sleep(200);
        assertTrue("BGREWRITEAOF 应触发 ServerContext 回调", invokeCount.get() >= 1);
    }
}
