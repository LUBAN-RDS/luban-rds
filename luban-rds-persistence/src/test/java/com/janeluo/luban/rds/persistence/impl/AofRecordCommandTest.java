package com.janeluo.luban.rds.persistence.impl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AOF {@code recordCommand(byte[])} 单元测试（C3）。
 *
 * <p>验证 AOF 写命令记录的核心契约：
 * <ul>
 *   <li>原始 RESP 字节帧被原样追加到 AOF 文件</li>
 *   <li>{@code isRunning == false} 时为 no-op</li>
 *   <li>{@code fsyncInterval == 0} 时记录后立即 flush（数据可被后续读取）</li>
 *   <li>二进制安全：非 ASCII 字节被完整保留</li>
 * </ul>
 */
public class AofRecordCommandTest {

    private static final String TEST_DATA_DIR = "./target/test-data/aof-record-test";

    /**
     * 构造 RESP 命令帧：{@code *N\r\n$L\r\narg\r\n ...}，使用 ISO-8859-1 编码保证二进制安全。
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
     * 构造二进制安全的 RESP 命令帧，参数以原始字节给出。
     */
    private static byte[] respFrameBytes(byte[]... args) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write('*');
            baos.write(Integer.toString(args.length).getBytes(StandardCharsets.US_ASCII));
            baos.write('\r');
            baos.write('\n');
            for (byte[] arg : args) {
                baos.write('$');
                baos.write(Integer.toString(arg.length).getBytes(StandardCharsets.US_ASCII));
                baos.write('\r');
                baos.write('\n');
                baos.write(arg);
                baos.write('\r');
                baos.write('\n');
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
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

    @Before
    public void setUp() {
        cleanTestDataDir();
        File dataDir = new File(TEST_DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    @After
    public void tearDown() {
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
     * recordCommand 应将原始 RESP 字节帧追加写入 AOF 文件。
     */
    @Test
    public void testRecordCommandWritesRespFrame() throws Exception {
        AofPersistService service = new AofPersistService(TEST_DATA_DIR, 1);
        try {
            byte[] frame = respFrame("SET", "key1", "value1");
            service.recordCommand(frame);
            service.flushForTest();
        } finally {
            service.close();
        }

        File aofFile = new File(TEST_DATA_DIR, "appendonly.aof");
        assertTrue("AOF 文件应存在", aofFile.exists());
        byte[] content = readFile(aofFile);
        byte[] expected = respFrame("SET", "key1", "value1");
        assertArrayEquals("AOF 内容应与写入的 RESP 帧一致", expected, content);
    }

    /**
     * isRunning == false 时 recordCommand 应为 no-op，AOF 文件不包含该命令字节。
     */
    @Test
    public void testRecordCommandNoopWhenNotRunning() throws Exception {
        AofPersistService service = new AofPersistService(TEST_DATA_DIR, 1);
        try {
            // 关闭后 isRunning == false
            service.stopForTest();
            byte[] frame = respFrame("SET", "shouldNotBeWritten", "x");
            service.recordCommand(frame);
            service.flushForTest();
        } finally {
            service.close();
        }

        File aofFile = new File(TEST_DATA_DIR, "appendonly.aof");
        assertTrue("AOF 文件应存在", aofFile.exists());
        byte[] content = readFile(aofFile);
        assertEquals("isRunning=false 时 AOF 不应写入任何字节", 0, content.length);
    }

    /**
     * fsyncInterval == 0 时 recordCommand 应立即 flush，无需额外调用即可被读取。
     */
    @Test
    public void testRecordCommandFlushesWhenFsyncIntervalZero() throws Exception {
        AofPersistService service = new AofPersistService(TEST_DATA_DIR, 0);
        File aofFile = new File(TEST_DATA_DIR, "appendonly.aof");
        try {
            byte[] frame = respFrame("SET", "flushKey", "flushVal");
            service.recordCommand(frame);
            // 不调用任何额外 flush，直接读取文件 —— fsyncInterval == 0 时应已落盘
            byte[] content = readFile(aofFile);
            assertArrayEquals("fsyncInterval=0 时记录后应立即 flush", frame, content);
        } finally {
            service.close();
        }
    }

    /**
     * 二进制安全：包含非 ASCII 字节的 RESP 帧应被完整保留。
     *
     * <p>本测试聚焦 {@code recordCommand} 写入路径的二进制安全契约：
     * 任意字节经 ISO-8859-1 写入后，AOF 文件中的字节应与原始帧完全一致。
     * （{@code load} 路径的行解析目前基于 {@code readLine}，对含 0x0A 的任意
     * 二进制 payload 不保证往返，属独立关注点，不在 C3 范围内。）
     */
    @Test
    public void testRecordCommandBinarySafe() throws Exception {
        AofPersistService service = new AofPersistService(TEST_DATA_DIR, 0);
        try {
            // 构造包含全 0-255 字节范围的非 ASCII payload
            byte[] binaryPayload = new byte[256];
            for (int i = 0; i < 256; i++) {
                binaryPayload[i] = (byte) i;
            }
            byte[] frame = respFrameBytes("SET".getBytes(StandardCharsets.US_ASCII),
                    "binKey".getBytes(StandardCharsets.US_ASCII),
                    binaryPayload);
            service.recordCommand(frame);
        } finally {
            service.close();
        }

        File aofFile = new File(TEST_DATA_DIR, "appendonly.aof");
        byte[] content = readFile(aofFile);
        // 重新构造期望帧（二进制 payload 完整保留）
        byte[] binaryPayload = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryPayload[i] = (byte) i;
        }
        byte[] expected = respFrameBytes("SET".getBytes(StandardCharsets.US_ASCII),
                "binKey".getBytes(StandardCharsets.US_ASCII),
                binaryPayload);
        assertArrayEquals("二进制 payload 应被完整保留（ISO-8859-1 二进制安全）", expected, content);
    }

    /**
     * 多条命令应按追加顺序写入 AOF。
     */
    @Test
    public void testMultipleCommandsAppendedInOrder() throws Exception {
        AofPersistService service = new AofPersistService(TEST_DATA_DIR, 0);
        try {
            service.recordCommand(respFrame("SET", "k1", "v1"));
            service.recordCommand(respFrame("SET", "k2", "v2"));
            service.recordCommand(respFrame("DEL", "k1"));
        } finally {
            service.close();
        }

        File aofFile = new File(TEST_DATA_DIR, "appendonly.aof");
        byte[] content = readFile(aofFile);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(respFrame("SET", "k1", "v1"));
        expected.write(respFrame("SET", "k2", "v2"));
        expected.write(respFrame("DEL", "k1"));
        assertArrayEquals(expected.toByteArray(), content);
    }

    /**
     * default 空实现：非 AOF 的 PersistService（如 RdbPersistService）调用 recordCommand 不应抛异常。
     */
    @Test
    public void testDefaultRecordCommandIsNoopForRdbService() {
        RdbPersistService rdb = new RdbPersistService(TEST_DATA_DIR);
        try {
            // default 方法空实现，调用应无异常
            rdb.recordCommand(respFrame("SET", "x", "y"));
        } finally {
            rdb.close();
        }
    }
}
