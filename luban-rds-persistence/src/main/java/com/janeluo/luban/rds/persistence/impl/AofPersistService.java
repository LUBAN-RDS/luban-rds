package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.stream.ConsumerGroup;
import com.janeluo.luban.rds.core.stream.PendingMessage;
import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamConsumerGroupManager;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * AOF持久化服务
 *
 * <p>实现Redis AOF（Append Only File）持久化机制：
 * <ul>
 *   <li>记录所有写命令到AOF文件</li>
 *   <li>支持定期fsync确保数据落盘</li>
 *   <li>支持AOF重写压缩文件大小</li>
 *   <li>启动时重放AOF命令恢复数据</li>
 * </ul>
 *
 * @author janeluo
 * @since 1.0.0
 */
public class AofPersistService implements PersistService {

    private static final Logger logger = LoggerFactory.getLogger(AofPersistService.class);

    /**
     * AOF文件名
     */
    private static final String AOF_FILE_NAME = "appendonly.aof";
    private final String aofFilePath;
    private final String aofTempFilePath;
    private FileOutputStream aofOutputStream;
    private OutputStreamWriter aofWriter;
    private final ExecutorService executorService;
    private volatile boolean isRunning = true;
    private final int fsyncInterval; // fsync间隔（秒）

    public AofPersistService(String dataDir, int fsyncInterval) {
        this.aofFilePath = dataDir + File.separator + AOF_FILE_NAME;
        this.aofTempFilePath = dataDir + File.separator + "appendonly.aof.tmp";
        this.fsyncInterval = fsyncInterval;

        // 确保数据目录存在
        File dataDirectory = new File(dataDir);
        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs();
        }

        // 初始化AOF文件写入器
        try {
            this.aofOutputStream = new FileOutputStream(aofFilePath, true);
            // 显式指定 ISO-8859-1 编码，保证二进制安全（任意字节无损往返）
            this.aofWriter = new OutputStreamWriter(aofOutputStream, StandardCharsets.ISO_8859_1);
            logger.info("AOF写入器已初始化: file={}", aofFilePath);
        } catch (IOException e) {
            logger.error("初始化AOF写入器失败: file={}", aofFilePath, e);
        }

        // 创建线程池用于异步fsync
        this.executorService = Executors.newSingleThreadExecutor();

        // 启动定期fsync任务
        startFsyncTask();
    }

    @Override
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("aof_enabled", 1);
        info.put("aof_rewrite_in_progress", 0);
        info.put("aof_rewrite_scheduled", 0);
        info.put("aof_last_rewrite_time_sec", -1);
        info.put("aof_current_rewrite_time_sec", -1);
        info.put("aof_last_bgrewrite_status", "ok");
        info.put("aof_last_write_status", "ok");
        info.put("aof_last_cow_size", 0);

        long currentSize = 0;
        try {
            File file = new File(aofFilePath);
            if (file.exists()) {
                currentSize = file.length();
            }
        } catch (Exception e) {
            // ignore
        }
        info.put("aof_current_size", currentSize);
        info.put("aof_base_size", currentSize); // simple assumption
        info.put("aof_pending_rewrite", 0);
        info.put("aof_buffer_length", 0);
        info.put("aof_rewrite_buffer_length", 0);
        info.put("aof_pending_bio_fsync", 0);
        info.put("aof_delayed_fsync", 0);
        return info;
    }

    @Override
    public void persist(MemoryStore memoryStore) {
        // AOF持久化是实时的，通过记录写命令来实现
        // 这里不需要特殊处理，因为写命令已经在执行时被记录了
        logger.debug("AOF persistence triggered");
    }

    @Override
    public void load(MemoryStore memoryStore) {
        logger.info("正在加载AOF数据: file={}", aofFilePath);
        long startTime = System.currentTimeMillis();
        int commandCount = 0;

        File aofFile = new File(aofFilePath);
        if (!aofFile.exists()) {
            logger.info("No AOF file found, skipping load");
            return;
        }

        // 使用字节流 + ISO-8859-1 解码 RESP 帧：保证二进制安全（任意字节无损往返）。
        // 旧的基于 BufferedReader.readLine() 的解析方式会吃掉帧内的 \r\n，
        // 再用 split("\\r\\n") 解析永远只剩一个元素，导致命令根本无法恢复。
        // 因此 AOF 加载采用标准 RESP 帧读取：先读 *N 数组头，再按 $L<body> 解码每个参数。
        try (InputStream rawIn = new FileInputStream(aofFile);
             DataInputStream in = new DataInputStream(rawIn)) {
            while (true) {
                List<String> args = readRespArray(in);
                if (args == null) {
                    // 文件正常结束（EOF 在帧开始处）
                    break;
                }
                if (args.isEmpty()) {
                    // 跳过空行/不完整帧（兼容旧格式尾部空白）
                    continue;
                }
                executeCommand(args, memoryStore);
                commandCount++;
            }

            long endTime = System.currentTimeMillis();
            logger.info("AOF加载完成: {} 条命令已加载, 耗时 {} ms", commandCount, endTime - startTime);

        } catch (EOFException eof) {
            // 读取中遇到中途 EOF：记录已加载命令数后正常退出
            logger.info("AOF加载到文件末尾: 已加载 {} 条命令", commandCount);
        } catch (Exception e) {
            logger.error("加载AOF数据失败: file={}, 已加载命令数={}", aofFilePath, commandCount, e);
            System.err.println("加载AOF数据失败: file=" + aofFilePath + " commandCount=" + commandCount + " err=" + e);
        }
    }

    /**
     * 从输入流读取一个 RESP 数组帧，转换为参数列表。
     *
     * <p>读取规则（与 {@code writeRespCommand} 写入格式严格对应）：
     * <ol>
     *   <li>先读一行：以 {@code *N} 表示数组元素个数</li>
     *   <li>对每个元素依次读 {@code $L\r\n<L 字节>\r\n}，按 ISO-8859-1 解码为字符串</li>
     * </ol>
     *
     * @return 参数列表；{@code null} 表示文件末尾（帧开始处即 EOF）；空列表表示读到空白行
     * @throws IOException 读取失败或帧格式损坏
     */
    private List<String> readRespArray(DataInputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        if (header.isEmpty()) {
            return new ArrayList<>();
        }
        if (!header.startsWith("*")) {
            // 兼容：跳过非数组头行（旧格式残留），返回空列表让上层跳过
            return new ArrayList<>();
        }
        int argCount = Integer.parseInt(header.substring(1));
        List<String> args = new ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            String bulkHeader = readLine(in);
            if (bulkHeader == null || !bulkHeader.startsWith("$")) {
                throw new IOException("期望 $bulkheader 但读到: " + bulkHeader);
            }
            int length = Integer.parseInt(bulkHeader.substring(1));
            if (length < 0) {
                args.add(null);
                continue;
            }
            byte[] buf = new byte[length];
            in.readFully(buf);
            // 期望帧尾 \r\n
            int cr = in.read();
            int lf = in.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException("bulk 末尾期望 \\r\\n 但读到: " + cr + "/" + lf);
            }
            args.add(new String(buf, StandardCharsets.ISO_8859_1));
        }
        return args;
    }

    /**
     * 读取一行以 {@code \r\n} 结尾的 ASCII 文本（不含行尾），EOF 时返回 {@code null}。
     */
    private String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return bout.toString(StandardCharsets.ISO_8859_1);
                }
                // 非标准行尾：把 \r 与回退读到的字节都纳入
                bout.write('\r');
                if (next != -1) {
                    bout.write(next);
                }
            } else if (b == '\n') {
                // 兼容 \n 行尾
                return bout.toString(StandardCharsets.ISO_8859_1);
            } else {
                bout.write(b);
            }
        }
        return bout.size() > 0 ? bout.toString(StandardCharsets.ISO_8859_1) : null;
    }

    @Override
    public void close() {
        isRunning = false;

        // 关闭AOF文件写入器
        if (aofWriter != null) {
            try {
                aofWriter.flush();
                aofWriter.close();
            } catch (IOException e) {
                logger.error("Error closing AOF writer", e);
            }
        }

        // 关闭文件输出流
        if (aofOutputStream != null) {
            try {
                aofOutputStream.close();
            } catch (IOException e) {
                logger.error("Error closing AOF output stream", e);
            }
        }

        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }

        logger.info("AOF persistence service closed");
    }

    /**
     * 记录写命令到 AOF 文件。
     *
     * <p>接收原始 RESP 帧字节并原样追加写入 AOF 文件，与复制传播使用的
     * {@code rawRespFrame} 是同一份数据，避免重复序列化。使用 ISO-8859-1
     * 编码写入以保证二进制安全（任意字节均可无损往返）。
     *
     * <p>当 {@code fsyncInterval == 0} 时，写入后立即 flush，确保数据落盘。
     *
     * @param respFrame 原始 RESP 命令帧字节（不可为 null）
     */
    @Override
    public void recordCommand(byte[] respFrame) {
        if (!isRunning || aofWriter == null) {
            return;
        }
        if (respFrame == null || respFrame.length == 0) {
            return;
        }

        try {
            // ISO-8859-1 编码保证二进制安全：任意字节 -> 字符 -> 原字节无损往返
            String frame = new String(respFrame, StandardCharsets.ISO_8859_1);
            aofWriter.write(frame, 0, frame.length());

            // fsync 间隔为 0 时立即落盘
            if (fsyncInterval == 0) {
                flush();
            }
        } catch (IOException e) {
            logger.error("Error recording command to AOF", e);
        }
    }

    /**
     * 执行AOF重写
     *
     * @param memoryStore 内存存储实例
     */
    public void rewrite(MemoryStore memoryStore) {
        logger.info("Starting AOF rewrite...");
        long startTime = System.currentTimeMillis();
        boolean rewriteSucceeded = false;
        OutputStreamWriter tempWriter = null;
        try {
            tempWriter = new OutputStreamWriter(
                    new FileOutputStream(aofTempFilePath), StandardCharsets.ISO_8859_1);
            // 遍历所有数据库
            for (int db = 0; db < 16; db++) { // Redis默认支持16个数据库
                long dbSize = memoryStore.dbsize(db);
                if (dbSize == 0) {
                    continue;
                }

                // 写入SELECT命令
                writeSelectCommand(tempWriter, db);

                // 遍历数据库中的所有键
                long cursor = 0;
                do {
                    List<Object> scanResult = memoryStore.scan(db, cursor, "*", 100);
                    if (scanResult.size() <= 1) { // 只有游标，没有键
                        break;
                    }

                    cursor = (Long) scanResult.get(0);

                    // 处理每个键：按类型生成重建命令（C11）
                    for (int i = 1; i < scanResult.size(); i++) {
                        String key = (String) scanResult.get(i);
                        writeRebuildCommand(tempWriter, db, key, memoryStore);
                    }
                } while (cursor != 0);
            }

            tempWriter.flush();

            // 在 Files.move 之前必须关闭所有正在持有该两个文件的写入流：
            //   1) tempWriter 持有 appendonly.aof.tmp —— Windows 下文件被占用会使 move 失败，
            //      且 Files.move(..., REPLACE_EXISTING) 在 Windows 上会先删除目标文件再尝试 move，
            //      一旦 move 失败，目标 aof 文件已被删除但新文件没生成，造成数据丢失。
            //   2) aofWriter/aofOutputStream 以追加模式持有 appendonly.aof —— 同样会阻止覆盖。
            // 因此两路写入流都在 move 前显式关闭，move 完成后再重新打开 aofWriter。
            try { tempWriter.close(); } catch (IOException ignore) { /* close quietly */ }
            tempWriter = null;

            if (aofWriter != null) {
                try { aofWriter.close(); } catch (IOException ignore) { /* close quietly */ }
                aofWriter = null;
            }
            if (aofOutputStream != null) {
                try { aofOutputStream.close(); } catch (IOException ignore) { /* close quietly */ }
                aofOutputStream = null;
            }

            // 重命名临时文件为AOF文件
            Files.move(new File(aofTempFilePath).toPath(),
                    new File(aofFilePath).toPath(), StandardCopyOption.REPLACE_EXISTING);
            rewriteSucceeded = true;

            // 重新初始化AOF写入器
            this.aofOutputStream = new FileOutputStream(aofFilePath, true); // 追加模式
            this.aofWriter = new OutputStreamWriter(aofOutputStream, StandardCharsets.ISO_8859_1);

            long endTime = System.currentTimeMillis();
            logger.info("AOF rewrite completed in {} ms", endTime - startTime);

        } catch (Exception e) {
            // 打印到 stderr 便于测试与排查，避免 NOP logger 吞掉关键错误
            logger.error("Error during AOF rewrite", e);
            System.err.println("Error during AOF rewrite: " + e);
        } finally {
            // 关闭临时写入器（若尚未关闭）
            if (tempWriter != null) {
                try { tempWriter.close(); } catch (IOException ignore) { /* close quietly */ }
            }
            // 若重写未成功，删除残留的临时文件；若成功，临时文件已被 move 走，文件不存在跳过
            File tempFile = new File(aofTempFilePath);
            if (tempFile.exists() && !rewriteSucceeded) {
                tempFile.delete();
            }
            // 重写失败时，恢复 aofWriter（防止 aofWriter 被关闭后无法继续追加写）
            if (!rewriteSucceeded && aofWriter == null && aofOutputStream == null) {
                try {
                    this.aofOutputStream = new FileOutputStream(aofFilePath, true);
                    this.aofWriter = new OutputStreamWriter(aofOutputStream, StandardCharsets.ISO_8859_1);
                } catch (IOException e) {
                    logger.error("Reopen AOF writer after failed rewrite failed: {}", aofFilePath, e);
                }
            }
        }
    }

    private void startFsyncTask() {
        executorService.submit(() -> {
            while (isRunning) {
                try {
                    Thread.sleep(fsyncInterval * 1000);
                    flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void flush() {
        if (aofWriter != null && aofOutputStream != null) {
            try {
                aofWriter.flush();
                // 强制操作系统将缓冲区写入磁盘
                aofOutputStream.getFD().sync();
            } catch (IOException e) {
                logger.error("Error flushing AOF", e);
            }
        }
    }

    /**
     * 测试辅助方法：手动触发 flush（将缓冲区数据落盘到 AOF 文件）。
     */
    void flushForTest() {
        flush();
    }

    /**
     * 测试辅助方法：标记服务已停止（isRunning = false），用于验证停止后不再写入。
     */
    void stopForTest() {
        isRunning = false;
    }

    private int currentDb = 0;

    private void parseAndExecuteCommand(String line, MemoryStore memoryStore) {
        // 兼容旧入口：仍基于行解析，仅在没有帧头时返回。
        // 实际加载路径已切换为 readRespArray + executeCommand，详见 load()。
        if (!line.startsWith("*")) {
            return;
        }
        try {
            List<String> args = parseRespArray(line);
            executeCommand(args, memoryStore);
        } catch (Exception e) {
            logger.error("Error parsing AOF command: {}", line, e);
        }
    }

    /**
     * 按参数列表分发执行一条 AOF 命令。
     *
     * <p>由 {@link #load(MemoryStore)} 通过二进制安全的 RESP 帧解析后调用，
     * 也由旧的 {@link #parseAndExecuteCommand(String, MemoryStore)} 行解析路径调用。
     *
     * @param args        命令参数列表（首元素为命令名），不可为空
     * @param memoryStore 内存存储实例
     */
    private void executeCommand(List<String> args, MemoryStore memoryStore) {
        if (args == null || args.isEmpty()) {
            return;
        }

        try {
            String command = args.get(0).toUpperCase();

            switch (command) {
                case "SELECT":
                    if (args.size() >= 2) {
                        currentDb = Integer.parseInt(args.get(1));
                    }
                    break;

                case "SET":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String value = args.get(2);
                        if (args.size() >= 5 && args.get(3).equalsIgnoreCase("EX")) {
                            long expireSeconds = Long.parseLong(args.get(4));
                            memoryStore.setWithExpire(currentDb, key, value, expireSeconds);
                        } else if (args.size() >= 5 && args.get(3).equalsIgnoreCase("PX")) {
                            long expireMs = Long.parseLong(args.get(4));
                            memoryStore.setWithExpireMs(currentDb, key, value, expireMs);
                        } else {
                            memoryStore.set(currentDb, key, value);
                        }
                    }
                    break;

                case "DEL":
                    if (args.size() >= 2) {
                        for (int i = 1; i < args.size(); i++) {
                            memoryStore.del(currentDb, args.get(i));
                        }
                    }
                    break;

                case "EXPIRE":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        long seconds = Long.parseLong(args.get(2));
                        memoryStore.expire(currentDb, key, seconds);
                    }
                    break;

                case "PEXPIRE":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        long milliseconds = Long.parseLong(args.get(2));
                        memoryStore.pexpire(currentDb, key, milliseconds);
                    }
                    break;

                case "PEXPIREAT":
                    // 绝对时间戳（毫秒）：remaining = expireAtMs - now
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        long expireAtMs = Long.parseLong(args.get(2));
                        long now = System.currentTimeMillis();
                        long remaining = expireAtMs - now;
                        if (remaining <= 0) {
                            // 已过期：删除已加载的键，避免复活
                            memoryStore.del(currentDb, key);
                            logger.debug("Skip expired key on AOF load: key={}, expireAtMs={}, now={}",
                                    key, expireAtMs, now);
                        } else {
                            memoryStore.pexpire(currentDb, key, remaining);
                        }
                    }
                    break;

                case "INCR":
                case "INCRBY":
                    if (args.size() >= 2) {
                        String key = args.get(1);
                        long increment = args.size() >= 3 ? Long.parseLong(args.get(2)) : 1;
                        memoryStore.incrby(currentDb, key, increment);
                    }
                    break;

                case "DECR":
                case "DECRBY":
                    if (args.size() >= 2) {
                        String key = args.get(1);
                        long decrement = args.size() >= 3 ? Long.parseLong(args.get(2)) : 1;
                        memoryStore.incrby(currentDb, key, -decrement);
                    }
                    break;

                case "MSET":
                    if (args.size() >= 3) {
                        String[] keysAndValues = args.subList(1, args.size()).toArray(new String[0]);
                        memoryStore.mset(currentDb, keysAndValues);
                    }
                    break;

                case "HSET":
                case "HMSET":
                    if (args.size() >= 4) {
                        String key = args.get(1);
                        String[] fieldsAndValues = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.hmset(currentDb, key, fieldsAndValues);
                    }
                    break;

                case "HDEL":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String[] fields = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.hdel(currentDb, key, fields);
                    }
                    break;

                case "HINCRBY":
                    if (args.size() >= 4) {
                        String key = args.get(1);
                        String field = args.get(2);
                        long increment = Long.parseLong(args.get(3));
                        memoryStore.hincrby(currentDb, key, field, increment);
                    }
                    break;

                case "LPUSH":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String[] values = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.lpush(currentDb, key, values);
                    }
                    break;

                case "RPUSH":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String[] values = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.rpush(currentDb, key, values);
                    }
                    break;

                case "LPOP":
                    if (args.size() >= 2) {
                        memoryStore.lpop(currentDb, args.get(1));
                    }
                    break;

                case "RPOP":
                    if (args.size() >= 2) {
                        memoryStore.rpop(currentDb, args.get(1));
                    }
                    break;

                case "LREM":
                    if (args.size() >= 4) {
                        String key = args.get(1);
                        int count = Integer.parseInt(args.get(2));
                        String value = args.get(3);
                        memoryStore.lrem(currentDb, key, count, value);
                    }
                    break;

                case "LSET":
                    if (args.size() >= 4) {
                        String key = args.get(1);
                        int index = Integer.parseInt(args.get(2));
                        String value = args.get(3);
                        memoryStore.lset(currentDb, key, index, value);
                    }
                    break;

                case "SADD":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String[] members = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.sadd(currentDb, key, members);
                    }
                    break;

                case "SREM":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String[] members = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.srem(currentDb, key, members);
                    }
                    break;

                case "ZADD":
                    if (args.size() >= 4) {
                        String key = args.get(1);
                        for (int i = 2; i < args.size(); i += 2) {
                            if (i + 1 < args.size()) {
                                double score = Double.parseDouble(args.get(i));
                                String member = args.get(i + 1);
                                memoryStore.zadd(currentDb, key, score, member);
                            }
                        }
                    }
                    break;

                case "ZREM":
                    if (args.size() >= 3) {
                        String key = args.get(1);
                        String[] members = args.subList(2, args.size()).toArray(new String[0]);
                        memoryStore.zrem(currentDb, key, members);
                    }
                    break;

                case "XADD":
                    // XADD key ID field value [field value ...]
                    if (args.size() >= 4) {
                        String key = args.get(1);
                        String idStr = args.get(2);
                        StreamId id = parseStreamId(idStr);
                        Map<String, String> fields = new java.util.LinkedHashMap<>();
                        for (int i = 3; i + 1 < args.size(); i += 2) {
                            fields.put(args.get(i), args.get(i + 1));
                        }
                        memoryStore.xadd(currentDb, key, id, fields,
                                false, null, null, null, false);
                    }
                    break;

                case "XGROUP":
                    // XGROUP CREATE key group ID [MKSTREAM]
                    if (args.size() >= 5 && "CREATE".equalsIgnoreCase(args.get(1))) {
                        String key = args.get(2);
                        String group = args.get(3);
                        StreamId id = parseStreamId(args.get(4));
                        memoryStore.xgroupCreate(currentDb, key, group, id, true);
                    }
                    break;

                case "XCLAIM":
                    // XCLAIM key group consumer min-idle-time ID [ID ...] [FORCE] [JUSTID]
                    if (args.size() >= 6) {
                        String key = args.get(1);
                        String group = args.get(2);
                        String consumer = args.get(3);
                        long minIdleTime = Long.parseLong(args.get(4));
                        List<StreamId> ids = new ArrayList<>();
                        boolean force = false;
                        for (int i = 5; i < args.size(); i++) {
                            String a = args.get(i).toUpperCase();
                            if ("FORCE".equals(a)) {
                                force = true;
                            } else if ("JUSTID".equals(a) || "IDLE".equals(a) || "TIME".equals(a)
                                    || "RETRYCOUNT".equals(a)) {
                                // IDLE/TIME/RETRYCOUNT 各带一个参数，跳过下一项
                                if ("IDLE".equals(a) || "TIME".equals(a) || "RETRYCOUNT".equals(a)) {
                                    i++;
                                }
                            } else {
                                try {
                                    ids.add(StreamId.parse(args.get(i)));
                                } catch (IllegalArgumentException e) {
                                    // 跳过无法解析的 ID
                                }
                            }
                        }
                        if (!ids.isEmpty()) {
                            StreamId[] idArray = ids.toArray(new StreamId[0]);
                            memoryStore.xclaim(currentDb, key, group, consumer,
                                    minIdleTime, idArray, false, force);
                        }
                    }
                    break;

                case "FLUSHDB":
                    memoryStore.flushdb(currentDb);
                    break;

                case "FLUSHALL":
                    memoryStore.flushAll();
                    break;

                default:
                    logger.debug("Unsupported AOF command: {}", command);
            }
        } catch (Exception e) {
            logger.error("Error parsing AOF command: {}", args, e);
        }
    }

    private List<String> parseRespArray(String line) {
        List<String> args = new ArrayList<>();
        String[] parts = line.split("\\r\\n");

        if (parts.length < 1 || !parts[0].startsWith("*")) {
            return args;
        }

        int argCount = Integer.parseInt(parts[0].substring(1));
        int partIndex = 1;

        for (int i = 0; i < argCount && partIndex < parts.length; i++) {
            if (parts[partIndex].startsWith("$")) {
                int length = Integer.parseInt(parts[partIndex].substring(1));
                partIndex++;
                if (partIndex < parts.length) {
                    args.add(parts[partIndex]);
                }
            }
            partIndex++;
        }

        return args;
    }

    /**
     * 解析 Stream ID 字符串：{@code $} 表示从最新消息开始（返回 null 由 store 侧处理），
     * {@code 0}/{@code 0-0} 返回 {@link StreamId#MIN_ID}，其余调用 {@link StreamId#parse}。
     */
    private static StreamId parseStreamId(String idStr) {
        if (idStr == null || "$".equals(idStr)) {
            return null;
        }
        if ("0".equals(idStr) || "0-0".equals(idStr)) {
            return StreamId.MIN_ID;
        }
        return StreamId.parse(idStr);
    }

    private void writeSelectCommand(Writer writer, int db) throws IOException {
        String[] args = new String[]{"SELECT", String.valueOf(db)};
        StringBuilder sb = new StringBuilder();
        sb.append("*")
                .append(args.length)
                .append("\r\n");

        for (String arg : args) {
            byte[] argBytes = arg.getBytes(StandardCharsets.ISO_8859_1);
            sb.append("$")
                    .append(argBytes.length)
                    .append("\r\n")
                    .append(arg)
                    .append("\r\n");
        }

        writer.write(sb.toString());
    }

    /**
     * 按键类型生成 AOF 重建命令（C11）。
     *
     * <p>根据 {@code memoryStore.type(db, key)} 分支生成对应类型的重建命令：
     * <ul>
     *   <li>string: {@code SET key value}</li>
     *   <li>list: {@code RPUSH key v1 v2 ...}</li>
     *   <li>set: {@code SADD key m1 m2 ...}</li>
     *   <li>hash: {@code HSET key f1 v1 f2 v2 ...}</li>
     *   <li>zset: {@code ZADD key s1 m1 s2 m2 ...}</li>
     *   <li>stream: 逐条 {@code XADD key id field value} +
     *       {@code XGROUP CREATE key group <lastDeliveredId>} 恢复消费者组 +
     *       逐条 {@code XCLAIM key group consumer 0 <id> FORCE} 恢复 PEL</li>
     * </ul>
     *
     * <p>带 TTL 的键在重建命令后追加 {@code PEXPIREAT key <timestampMs>}（绝对时间戳，毫秒）。
     * 空集合（list/set/hash 为空、zset 无成员、stream 无消息）不写重建命令（Redis 行为）。
     * 所有字节数据用 ISO-8859-1 编码保证二进制安全。
     *
     * @param writer      临时 AOF 写入器
     * @param db          数据库索引
     * @param key         键名
     * @param memoryStore 内存存储实例
     * @throws IOException 写入失败
     */
    private void writeRebuildCommand(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        String type = memoryStore.type(db, key);
        if ("none".equals(type)) {
            // 键可能在 scan 与 write 之间过期/被删，跳过避免写出孤立命令
            logger.debug("Skip rewrite key {} (type=none)", key);
            return;
        }

        boolean wroteData;
        switch (type) {
            case "string":
                wroteData = writeStringRebuild(writer, db, key, memoryStore);
                break;
            case "list":
                wroteData = writeListRebuild(writer, db, key, memoryStore);
                break;
            case "set":
                wroteData = writeSetRebuild(writer, db, key, memoryStore);
                break;
            case "hash":
                wroteData = writeHashRebuild(writer, db, key, memoryStore);
                break;
            case "zset":
                wroteData = writeZsetRebuild(writer, db, key, memoryStore);
                break;
            case "stream":
                wroteData = writeStreamRebuild(writer, db, key, memoryStore);
                break;
            default:
                logger.warn("Unknown type: {} (key={}), skip rewrite", type, key);
                return;
        }

        // 仅在写了数据命令时才追加 TTL（空集合不写，也不写 PEXPIREAT）
        if (wroteData) {
            writeExpireIfAny(writer, db, key, memoryStore);
        }
    }

    /**
     * string: {@code SET key value}。空字符串仍需写入（Redis 中空字符串是合法值）。
     */
    private boolean writeStringRebuild(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        Object value = memoryStore.get(db, key);
        if (value == null) {
            return false;
        }
        writeRespCommand(writer, "SET", key, value.toString());
        return true;
    }

    /**
     * list: {@code RPUSH key v1 v2 ...}（一次性追加所有元素）。空列表不写。
     */
    private boolean writeListRebuild(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        List<String> list = memoryStore.lrange(db, key, 0, -1);
        if (list == null || list.isEmpty()) {
            return false;
        }
        String[] args = new String[list.size() + 2];
        args[0] = "RPUSH";
        args[1] = key;
        for (int i = 0; i < list.size(); i++) {
            args[i + 2] = list.get(i);
        }
        writeRespCommand(writer, args);
        return true;
    }

    /**
     * set: {@code SADD key m1 m2 ...}。空集合不写。
     */
    private boolean writeSetRebuild(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        Set<String> members = memoryStore.smembers(db, key);
        if (members == null || members.isEmpty()) {
            return false;
        }
        String[] args = new String[members.size() + 2];
        args[0] = "SADD";
        args[1] = key;
        int idx = 2;
        for (String m : members) {
            args[idx++] = m;
        }
        writeRespCommand(writer, args);
        return true;
    }

    /**
     * hash: {@code HSET key f1 v1 f2 v2 ...}。空哈希不写。
     */
    private boolean writeHashRebuild(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        Map<String, String> hash = memoryStore.hgetall(db, key);
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        String[] args = new String[hash.size() * 2 + 2];
        args[0] = "HSET";
        args[1] = key;
        int idx = 2;
        for (Map.Entry<String, String> e : hash.entrySet()) {
            args[idx++] = e.getKey();
            args[idx++] = e.getValue() != null ? e.getValue() : "";
        }
        writeRespCommand(writer, args);
        return true;
    }

    /**
     * zset: {@code ZADD key s1 m1 s2 m2 ...}。空 zset 不写。
     */
    private boolean writeZsetRebuild(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        Map<String, Double> zset = memoryStore.zgetAllWithScores(db, key);
        if (zset == null || zset.isEmpty()) {
            return false;
        }
        String[] args = new String[zset.size() * 2 + 2];
        args[0] = "ZADD";
        args[1] = key;
        int idx = 2;
        for (Map.Entry<String, Double> e : zset.entrySet()) {
            args[idx++] = formatScore(e.getValue());
            args[idx++] = e.getKey();
        }
        writeRespCommand(writer, args);
        return true;
    }

    /**
     * stream 重建（C11）：
     * <ol>
     *   <li>逐条 {@code XADD key id field value [field value ...]} 恢复消息</li>
     *   <li>{@code XGROUP CREATE key group <lastDeliveredId>} 恢复每个消费者组</li>
     *   <li>扫描每个组 PEL，逐条 {@code XCLAIM key group consumer 0 <id> FORCE} 恢复 pending 消息</li>
     * </ol>
     *
     * <p>空 stream（无消息）不写重建命令（与 Redis 行为一致）。
     * 参考 RDB 侧 {@code writeStream} 的 PEL 序列化逻辑，保持两路径一致。
     */
    private boolean writeStreamRebuild(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        Stream stream = memoryStore.getStream(db, key);
        if (stream == null || stream.isEmpty()) {
            return false;
        }

        // 1. 逐条 XADD 恢复消息
        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID,
                false, false, Integer.MAX_VALUE);
        for (StreamEntry entry : entries) {
            Map<String, String> fields = entry.getFieldsInternal();
            // Redis 7.0+ 支持空字段；XADD 至少需要 key + id；空字段时仍发出 XADD key id（不带 field）
            if (fields == null || fields.isEmpty()) {
                writeRespCommand(writer, "XADD", key, entry.getId().toString());
            } else {
                String[] args = new String[fields.size() * 2 + 3];
                args[0] = "XADD";
                args[1] = key;
                args[2] = entry.getId().toString();
                int idx = 3;
                for (Map.Entry<String, String> f : fields.entrySet()) {
                    args[idx++] = f.getKey();
                    args[idx++] = f.getValue() != null ? f.getValue() : "";
                }
                writeRespCommand(writer, args);
            }
        }

        // 2 + 3. 恢复消费者组 + PEL
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        if (groupManager == null || groupManager.isEmpty()) {
            return true;
        }

        for (ConsumerGroup group : groupManager.getGroups()) {
            StreamId lastDeliveredId = group.getLastDeliveredId();
            String lastDeliveredIdStr = lastDeliveredId != null ? lastDeliveredId.toString() : "$";
            // XGROUP CREATE key group <lastDeliveredId>
            writeRespCommand(writer, "XGROUP", "CREATE", key, group.getName(), lastDeliveredIdStr);

            // 扫描 PEL，逐条 XCLAIM key group consumer 0 <id> FORCE
            // 使用 FORCE：AOF 加载时 PEL 为空，需强制将消息加入 PEL
            List<PendingMessage> pendingMessages = group.getAllPendingMessages();
            for (PendingMessage pm : pendingMessages) {
                writeRespCommand(writer, "XCLAIM", key, group.getName(),
                        pm.getConsumerName(), "0", pm.getId().toString(), "FORCE");
            }
        }

        return true;
    }

    /**
     * 若键有 TTL，追加 {@code PEXPIREAT key <timestampMs>}（绝对时间戳，毫秒）。
     * 无 TTL（pttl <= 0）不追加。
     */
    private void writeExpireIfAny(Writer writer, int db, String key, MemoryStore memoryStore)
            throws IOException {
        long pttl = memoryStore.pttl(db, key);
        if (pttl <= 0) {
            return;
        }
        long expireAtMs = System.currentTimeMillis() + pttl;
        writeRespCommand(writer, "PEXPIREAT", key, String.valueOf(expireAtMs));
    }

    /**
     * 写入一条 RESP 命令帧：{@code *N\r\n$L\r\narg\r\n ...}，使用 ISO-8859-1 编码保证二进制安全。
     */
    private void writeRespCommand(Writer writer, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            // 用 ISO-8859-1 计算字节数，保证 length 与实际写入字节一致（二进制安全）
            byte[] argBytes = arg.getBytes(StandardCharsets.ISO_8859_1);
            sb.append("$").append(argBytes.length).append("\r\n");
            // arg 中可能含 ISO-8859-1 范围外的字符（来自二进制数据的解码），
            // 这里直接写字符串：FileWriter/OutputStreamWriter 用 ISO-8859-1 编码会正确还原字节
            sb.append(arg).append("\r\n");
        }
        writer.write(sb.toString());
    }

    /**
     * 格式化 zset 分数为字符串。整数分数去掉小数部分（如 1.0 -> "1"），非整数保留。
     */
    private static String formatScore(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return Double.toString(score);
        }
        if (score == Math.rint(score) && !Double.isInfinite(score)) {
            return Long.toString((long) score);
        }
        return Double.toString(score);
    }
}
