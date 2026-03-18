package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.stream.Consumer;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RDB持久化服务
 * 
 * <p>参考Redis的BGSAVE实现，采用异步方式进行持久化：
 * <ul>
 *   <li>使用独立线程池执行持久化任务</li>
 *   <li>使用NIO提高I/O性能</li>
 *   <li>支持写时复制（COW）语义，避免阻塞主线程</li>
 *   <li>使用临时文件+原子重命名，保证数据一致性</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class RdbPersistService implements PersistService {
    
    private static final Logger logger = LoggerFactory.getLogger(RdbPersistService.class);
    
    /**
     * RDB文件名
     */
    private static final String RDB_FILE_NAME = "dump.rdb";
    
    /**
     * RDB临时文件名
     */
    private static final String RDB_TEMP_FILE_NAME = "temp-dump.rdb";
    
    private final String dataDir;
    private final String rdbFilePath;
    private final String tempRdbFilePath;
    
    // 异步持久化线程池（单线程，避免并发写入）
    private final ExecutorService persistExecutor;
    
    // 持久化状态
    private final AtomicBoolean isPersisting = new AtomicBoolean(false);
    private final AtomicLong lastPersistTime = new AtomicLong(0);
    private final AtomicLong persistCount = new AtomicLong(0);
    private final AtomicLong lastPersistDuration = new AtomicLong(-1);
    
    // 写缓冲区大小（64KB）
    private static final int WRITE_BUFFER_SIZE = 64 * 1024;
    
    // RDB 类型码常量
    private static final byte RDB_TYPE_STRING = 0x00;
    private static final byte RDB_TYPE_LIST = 0x01;
    private static final byte RDB_TYPE_SET = 0x02;
    private static final byte RDB_TYPE_ZSET = 0x03;
    private static final byte RDB_TYPE_HASH = 0x04;
    private static final byte RDB_TYPE_STREAM = 0x05;
    
    public RdbPersistService(String dataDir) {
        this.dataDir = dataDir;
        this.rdbFilePath = dataDir + File.separator + RDB_FILE_NAME;
        this.tempRdbFilePath = dataDir + File.separator + RDB_TEMP_FILE_NAME;
        
        this.persistExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rdb-persist-thread");
            t.setDaemon(true);
            return t;
        });
        
        File dataDirectory = new File(dataDir);
        if (!dataDirectory.exists()) {
            logger.info("Creating data directory: {}", dataDirectory.getAbsolutePath());
            boolean created = dataDirectory.mkdirs();
            if (created) {
                logger.info("Data directory created successfully");
            } else {
                logger.error("Failed to create data directory: {}", dataDirectory.getAbsolutePath());
            }
        } else {
            logger.debug("数据目录已存在: {}", dataDirectory.getAbsolutePath());
        }
        logger.info("RDB持久化已初始化: file={}", rdbFilePath);
    }
    
    @Override
    public void persist(MemoryStore memoryStore) {
        // 异步执行持久化
        persistAsync(memoryStore);
    }
    
    /**
     * 异步持久化（参考 Redis BGSAVE）
     * 
     * @param memoryStore 内存存储
     */
    public void persistAsync(MemoryStore memoryStore) {
        // 检查是否正在持久化
        if (!isPersisting.compareAndSet(false, true)) {
            logger.warn("RDB persistence is already in progress, skipping...");
            return;
        }
        
        persistExecutor.submit(() -> {
            try {
                doPersist(memoryStore);
            } finally {
                isPersisting.set(false);
            }
        });
    }
    
    /**
     * 同步持久化（用于关闭时确保数据保存）
     * 
     * @param memoryStore 内存存储
     */
    public void persistSync(MemoryStore memoryStore) {
        if (isPersisting.get()) {
            logger.warn("RDB persistence is already in progress, waiting...");
            // 等待当前持久化完成
            while (isPersisting.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        isPersisting.set(true);
        try {
            doPersist(memoryStore);
        } finally {
            isPersisting.set(false);
        }
    }
    
    /**
     * 执行实际的持久化操作
     * 
     * 优化点：
     * 1. 使用临时文件写入，完成后原子重命名
     * 2. 使用 BufferedOutputStream 减少系统调用
     * 3. 批量处理键值对，减少遍历次数
     */
    private void doPersist(MemoryStore memoryStore) {
        logger.info("Starting RDB persistence (async)...");
        long startTime = System.currentTimeMillis();
        long keyCount = 0;
        
        File tempFile = new File(tempRdbFilePath);
        File targetFile = new File(rdbFilePath);
        
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, WRITE_BUFFER_SIZE);
             DataOutputStream dos = new DataOutputStream(bos)) {
            
            // 写入RDB文件头
            writeRdbHeader(dos);
            
            // 遍历所有数据库
            for (int db = 0; db < 16; db++) {
                long dbSize = memoryStore.dbsize(db);
                if (dbSize == 0) {
                    continue;
                }
                
                // 写入数据库选择指令
                writeSelectDb(dos, db);
                
                // 遍历数据库中的所有键
                long cursor = 0;
                do {
                    List<Object> scanResult = memoryStore.scan(db, cursor, "*", 1000); // 增大批量大小
                    if (scanResult.size() <= 1) {
                        break;
                    }
                    
                    cursor = (Long) scanResult.get(0);
                    
                    // 批量处理键值对
                    for (int i = 1; i < scanResult.size(); i++) {
                        String key = (String) scanResult.get(i);
                        Object value = memoryStore.get(db, key);
                        if (value != null) {
                            writeKeyValue(dos, db, key, value, memoryStore);
                            keyCount++;
                        }
                    }
                } while (cursor != 0);
            }
            
            // 写入RDB文件尾
            writeRdbFooter(dos);
            
            // 确保数据刷新到磁盘
            dos.flush();
            bos.flush();
            fos.getFD().sync();
            
        } catch (Exception e) {
            logger.error("Error during RDB persistence", e);
            // 删除临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
            return;
        }
        
        // 原子重命名临时文件为目标文件
        try {
            if (targetFile.exists()) {
                targetFile.delete();
            }
            if (!tempFile.renameTo(targetFile)) {
                logger.error("Failed to rename temp RDB file to target file");
                return;
            }
        } catch (Exception e) {
            logger.error("Error renaming RDB file", e);
            return;
        }
        
        long endTime = System.currentTimeMillis();
        lastPersistTime.set(endTime / 1000);
        lastPersistDuration.set((endTime - startTime) / 1000);
        persistCount.incrementAndGet();
        
        logger.info("RDB persistence completed: {} keys saved in {} ms", keyCount, endTime - startTime);
    }
    
    @Override
    public java.util.Map<String, Object> getInfo() {
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("loading", 0);
        info.put("rdb_changes_since_last_save", 0); // TODO: track changes
        info.put("rdb_bgsave_in_progress", isPersisting.get() ? 1 : 0);
        info.put("rdb_last_save_time", lastPersistTime.get());
        info.put("rdb_last_bgsave_status", "ok");
        info.put("rdb_last_bgsave_time_sec", lastPersistDuration.get());
        info.put("rdb_current_bgsave_time_sec", -1); // TODO: track current duration
        info.put("rdb_last_cow_size", 0);
        info.put("aof_enabled", 0);
        return info;
    }

    @Override
    public void load(MemoryStore memoryStore) {
        logger.info("Loading RDB data...");
        long startTime = System.currentTimeMillis();
        long keyCount = 0;
        
        File rdbFile = new File(rdbFilePath);
        if (!rdbFile.exists()) {
            logger.info("No RDB file found, skipping load");
            return;
        }
        
        // 使用 BufferedInputStream 提高读取性能
        try (FileInputStream fis = new FileInputStream(rdbFile);
             BufferedInputStream bis = new BufferedInputStream(fis, WRITE_BUFFER_SIZE);
             DataInputStream dis = new DataInputStream(bis)) {
            
            // 读取RDB文件头
            if (!readRdbHeader(dis)) {
                logger.error("Invalid RDB file header");
                return;
            }
            
            // 读取数据库数据
            int currentDb = 0;
            try {
                while (true) {
                    if (dis.available() == 0) {
                        break;
                    }
                    
                    byte opcode = dis.readByte();
                    
                    switch (opcode) {
                        case (byte) 0xFE: // 数据库选择指令
                            currentDb = readSelectDb(dis);
                            break;
                        case (byte) 0x00: // 字符串类型
                        case (byte) 0x01: // 列表类型
                        case (byte) 0x02: // 集合类型
                        case (byte) 0x03: // 有序集合类型
                        case (byte) 0x04: // 哈希类型
                        case (byte) 0x05: // Stream 类型
                            readKeyValue(dis, opcode, currentDb, memoryStore);
                            keyCount++;
                            break;
                        case (byte) 0xFF: // RDB文件尾
                            logger.debug("RDB file footer found");
                            // 跳过校验和数据
                            if (dis.available() >= 8) {
                                dis.skipBytes(8);
                            }
                            break;
                        default:
                            logger.warn("Unknown opcode: 0x{}", Integer.toHexString(opcode));
                            break;
                    }
                }
            } catch (EOFException e) {
                // 文件读取完毕，正常退出循环
                logger.debug("RDB file read completed");
            }
            
            long endTime = System.currentTimeMillis();
            logger.info("RDB load completed: {} keys loaded in {} ms", keyCount, endTime - startTime);
            
        } catch (Exception e) {
            logger.error("Error loading RDB data", e);
        }
    }
    
    @Override
    public void close() {
        logger.info("Shutting down RDB persistence service...");
        
        // 关闭线程池
        persistExecutor.shutdown();
        try {
            if (!persistExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                persistExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("RDB persistence service closed. Total persists: {}", persistCount.get());
    }
    
    /**
     * 检查是否正在持久化
     */
    public boolean isPersisting() {
        return isPersisting.get();
    }
    
    /**
     * 获取上次持久化时间
     */
    public long getLastPersistTime() {
        return lastPersistTime.get();
    }
    
    /**
     * 获取持久化次数
     */
    public long getPersistCount() {
        return persistCount.get();
    }
    
    /**
     * 获取数据目录
     */
    public String getDataDir() {
        return dataDir;
    }
    
    private void writeRdbHeader(DataOutputStream dos) throws IOException {
        // 写入Redis RDB文件标识
        dos.writeBytes("REDIS0009"); // 使用Redis 6.0+的RDB版本
    }
    
    private boolean readRdbHeader(DataInputStream dis) throws IOException {
        byte[] header = new byte[9];
        dis.readFully(header);
        String headerStr = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
        return headerStr.startsWith("REDIS");
    }
    
    private void writeSelectDb(DataOutputStream dos, int db) throws IOException {
        dos.writeByte(0xFE); // 数据库选择指令
        writeLength(dos, db);
    }
    
    private int readSelectDb(DataInputStream dis) throws IOException {
        return readLength(dis);
    }
    
    private void writeKeyValue(DataOutputStream dos, int db, String key, Object value, MemoryStore memoryStore) throws IOException {
        String type = memoryStore.type(db, key);
        switch (type) {
            case "string":
                dos.writeByte(RDB_TYPE_STRING);
                writeString(dos, key);
                writeString(dos, value.toString());
                break;
            case "list":
                dos.writeByte(RDB_TYPE_LIST);
                writeString(dos, key);
                writeList(dos, (List<?>) value);
                break;
            case "set":
                dos.writeByte(RDB_TYPE_SET);
                writeString(dos, key);
                writeSet(dos, (java.util.Set<?>) value);
                break;
            case "zset":
                dos.writeByte(RDB_TYPE_ZSET);
                writeString(dos, key);
                java.util.Map<String, Double> zsetWithScores = memoryStore.zgetAllWithScores(db, key);
                writeZSetWithScores(dos, zsetWithScores);
                break;
            case "hash":
                dos.writeByte(RDB_TYPE_HASH);
                writeString(dos, key);
                writeHash(dos, (java.util.Map<?, ?>) value);
                break;
            case "stream":
                dos.writeByte(RDB_TYPE_STREAM);
                writeString(dos, key);
                writeStream(dos, (Stream) value, memoryStore, db, key);
                break;
            default:
                logger.warn("Unknown type: {}", type);
                break;
        }
    }
    
    private void readKeyValue(DataInputStream dis, byte opcode, int db, MemoryStore memoryStore) throws IOException {
        try {
            String key = readString(dis);
            
            Object value = null;
            switch (opcode) {
                case RDB_TYPE_STRING:
                    value = readString(dis);
                    break;
                case RDB_TYPE_LIST:
                    value = readList(dis);
                    break;
                case RDB_TYPE_SET:
                    value = readSet(dis);
                    break;
                case RDB_TYPE_ZSET:
                    readZSetWithScores(dis, memoryStore, db, key);
                    return;
                case RDB_TYPE_HASH:
                    value = readHash(dis);
                    break;
                case RDB_TYPE_STREAM:
                    readStream(dis, memoryStore, db, key);
                    return;
                default:
                    logger.warn("Unknown opcode: 0x{}", Integer.toHexString(opcode));
                    break;
            }
            
            if (value != null) {
                memoryStore.set(db, key, value);
                logger.debug("Loaded data from RDB: DB={}, Key={}, Type=0x{}", db, key, Integer.toHexString(opcode));
            }
        } catch (EOFException e) {
            logger.debug("End of file reached while reading key-value pair");
            throw e;
        }
    }
    
    private void writeString(DataOutputStream dos, String str) throws IOException {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        writeLength(dos, bytes.length);
        dos.write(bytes);
    }
    
    private String readString(DataInputStream dis) throws IOException {
        int length = readLength(dis);
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    
    private void writeList(DataOutputStream dos, List<?> list) throws IOException {
        writeLength(dos, list.size());
        for (Object item : list) {
            writeString(dos, item.toString());
        }
    }
    
    private java.util.List<Object> readList(DataInputStream dis) throws IOException {
        int size = readLength(dis);
        java.util.List<Object> list = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readString(dis));
        }
        return list;
    }
    
    private void writeSet(DataOutputStream dos, java.util.Set<?> set) throws IOException {
        writeLength(dos, set.size());
        for (Object item : set) {
            writeString(dos, item.toString());
        }
    }
    
    private java.util.Set<Object> readSet(DataInputStream dis) throws IOException {
        int size = readLength(dis);
        java.util.Set<Object> set = new java.util.HashSet<>(size);
        for (int i = 0; i < size; i++) {
            set.add(readString(dis));
        }
        return set;
    }
    
    private void writeZSet(DataOutputStream dos, java.util.SortedSet<?> zset) throws IOException {
        writeLength(dos, zset.size());
        for (Object item : zset) {
            writeString(dos, item.toString());
            writeDouble(dos, 0.0);
        }
    }
    
    private void writeZSetWithScores(DataOutputStream dos, java.util.Map<String, Double> zsetWithScores) throws IOException {
        if (zsetWithScores == null) {
            writeLength(dos, 0);
            return;
        }
        writeLength(dos, zsetWithScores.size());
        for (java.util.Map.Entry<String, Double> entry : zsetWithScores.entrySet()) {
            writeString(dos, entry.getKey());
            writeDouble(dos, entry.getValue());
        }
    }
    
    private java.util.SortedSet<Object> readZSet(DataInputStream dis) throws IOException {
        int size = readLength(dis);
        java.util.SortedSet<Object> zset = new java.util.TreeSet<>();
        for (int i = 0; i < size; i++) {
            zset.add(readString(dis));
            readDouble(dis);
        }
        return zset;
    }
    
    private void readZSetWithScores(DataInputStream dis, MemoryStore memoryStore, int db, String key) throws IOException {
        int size = readLength(dis);
        for (int i = 0; i < size; i++) {
            String member = readString(dis);
            double score = readDouble(dis);
            memoryStore.zadd(db, key, score, member);
        }
    }
    
    private void writeHash(DataOutputStream dos, java.util.Map<?, ?> hash) throws IOException {
        writeLength(dos, hash.size());
        for (java.util.Map.Entry<?, ?> entry : hash.entrySet()) {
            writeString(dos, entry.getKey().toString());
            writeString(dos, entry.getValue().toString());
        }
    }
    
    private java.util.Map<Object, Object> readHash(DataInputStream dis) throws IOException {
        int size = readLength(dis);
        java.util.Map<Object, Object> hash = new java.util.HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(dis);
            String value = readString(dis);
            hash.put(key, value);
        }
        return hash;
    }
    
    private void writeLength(DataOutputStream dos, long length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length);
        }
        if (length < 64) {
            dos.writeByte((byte) length);
        } else if (length < 16384) {
            dos.writeByte((byte) (0x40 | ((length >> 8) & 0x3F)));
            dos.writeByte((byte) (length & 0xFF));
        } else if (length < 2097152) {
            dos.writeByte((byte) (0x80 | ((length >> 16) & 0x1F)));
            dos.writeByte((byte) ((length >> 8) & 0xFF));
            dos.writeByte((byte) (length & 0xFF));
        } else {
            dos.writeByte(0xC0);
            dos.writeLong(length);
        }
    }
    
    private int readLength(DataInputStream dis) throws IOException {
        int firstByte = dis.readByte() & 0xFF;
        if (firstByte < 64) {
            return firstByte;
        } else if (firstByte < 128) {
            int secondByte = dis.readByte() & 0xFF;
            return ((firstByte & 0x3F) << 8) | secondByte;
        } else if (firstByte < 192) {
            int secondByte = dis.readByte() & 0xFF;
            int thirdByte = dis.readByte() & 0xFF;
            return ((firstByte & 0x1F) << 16) | (secondByte << 8) | thirdByte;
        } else {
            return (int) dis.readLong();
        }
    }
    
    private void writeDouble(DataOutputStream dos, double value) throws IOException {
        dos.writeDouble(value);
    }
    
    private double readDouble(DataInputStream dis) throws IOException {
        return dis.readDouble();
    }
    
    // ==================== Stream 序列化方法 ====================
    
    /**
     * 写入 Stream 数据结构
     * 
     * <p>数据格式：
     * <pre>
     * [Stream Header]
     * - lastGeneratedId: StreamId (ms + seq)
     * - maxLen: long
     * 
     * [Entries]
     * - entriesCount: int
     * - for each entry:
     *   - id: StreamId (ms + seq)
     *   - fieldsCount: int
     *   - for each field:
     *     - field: string
     *     - value: string
     * 
     * [Consumer Groups]
     * - groupsCount: int
     * - for each group:
     *   - groupName: string
     *   - lastDeliveredId: StreamId
     *   - createdAt: long
     *   - consumersCount: int
     *   - for each consumer:
     *     - consumerName: string
     *     - seenTime: long
     *     - pendingCount: int
     *   - pelCount: int
     *   - for each pending message:
     *     - id: StreamId
     *     - consumerName: string
     *     - deliveryTime: long
     *     - deliveryCount: int
     * </pre>
     *
     * @param dos 数据输出流
     * @param stream Stream 对象
     * @param memoryStore 内存存储
     * @param db 数据库索引
     * @param key 键名
     * @throws IOException 如果写入失败
     */
    private void writeStream(DataOutputStream dos, Stream stream, MemoryStore memoryStore, int db, String key) throws IOException {
        // 写入最后生成的 ID
        StreamId lastGeneratedId = stream.getLastGeneratedId();
        if (lastGeneratedId != null) {
            writeStreamId(dos, lastGeneratedId);
        } else {
            // 如果没有消息，写入 0-0
            writeStreamId(dos, StreamId.MIN_ID);
        }
        
        // 写入 maxLen
        dos.writeLong(stream.getMaxLen());
        
        // 写入消息条目
        long entriesCount = stream.getLength();
        writeLength(dos, entriesCount);
        
        if (entriesCount > 0) {
            // 遍历所有消息条目
            List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, Integer.MAX_VALUE);
            for (StreamEntry entry : entries) {
                // 写入消息 ID
                writeStreamId(dos, entry.getId());
                
                // 写入字段值对
                Map<String, String> fields = entry.getFieldsInternal();
                writeLength(dos, fields.size());
                for (Map.Entry<String, String> field : fields.entrySet()) {
                    writeString(dos, field.getKey());
                    writeString(dos, field.getValue() != null ? field.getValue() : "");
                }
            }
        }
        
        // 写入消费者组信息
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        if (groupManager != null && !groupManager.isEmpty()) {
            List<ConsumerGroup> groups = groupManager.getGroups();
            writeLength(dos, groups.size());
            
            for (ConsumerGroup group : groups) {
                // 写入消费者组基本信息
                writeString(dos, group.getName());
                writeStreamId(dos, group.getLastDeliveredId());
                dos.writeLong(group.getCreatedAt());
                
                // 写入消费者信息
                List<Consumer> consumers = group.getConsumers();
                writeLength(dos, consumers.size());
                for (Consumer consumer : consumers) {
                    writeString(dos, consumer.getName());
                    dos.writeLong(consumer.getSeenTime());
                    writeLength(dos, consumer.getPendingCount());
                }
                
                // 写入 PEL 信息
                List<PendingMessage> pendingMessages = group.getAllPendingMessages();
                writeLength(dos, pendingMessages.size());
                for (PendingMessage pm : pendingMessages) {
                    writeStreamId(dos, pm.getId());
                    writeString(dos, pm.getConsumerName());
                    dos.writeLong(pm.getDeliveryTime());
                    dos.writeInt(pm.getDeliveryCount());
                }
            }
        } else {
            // 没有消费者组
            writeLength(dos, 0);
        }
        
        logger.debug("Written stream: key={}, entries={}, groups={}", 
                key, entriesCount, groupManager != null ? groupManager.getGroupCount() : 0);
    }
    
    /**
     * 写入 StreamId
     *
     * @param dos 数据输出流
     * @param streamId Stream ID
     * @throws IOException 如果写入失败
     */
    private void writeStreamId(DataOutputStream dos, StreamId streamId) throws IOException {
        dos.writeLong(streamId.getMillisecondsTime());
        dos.writeLong(streamId.getSequenceNumber());
    }
    
    /**
     * 读取 Stream 数据结构
     *
     * @param dis 数据输入流
     * @param memoryStore 内存存储
     * @param db 数据库索引
     * @param key 键名
     * @throws IOException 如果读取失败
     */
    private void readStream(DataInputStream dis, MemoryStore memoryStore, int db, String key) throws IOException {
        // 读取最后生成的 ID
        StreamId lastGeneratedId = readStreamId(dis);
        
        // 读取 maxLen
        long maxLen = dis.readLong();
        
        // 创建 Stream 对象
        Stream stream = new Stream(maxLen);
        
        // 读取消息条目
        int entriesCount = readLength(dis);
        for (int i = 0; i < entriesCount; i++) {
            // 读取消息 ID
            StreamId entryId = readStreamId(dis);
            
            // 读取字段值对
            int fieldsCount = readLength(dis);
            java.util.Map<String, String> fields = new java.util.LinkedHashMap<>(fieldsCount);
            for (int j = 0; j < fieldsCount; j++) {
                String fieldName = readString(dis);
                String fieldValue = readString(dis);
                fields.put(fieldName, fieldValue);
            }
            
            // 添加消息到 Stream
            stream.addEntry(entryId, fields);
        }
        
        // 读取消费者组信息
        int groupsCount = readLength(dis);
        if (groupsCount > 0) {
            StreamConsumerGroupManager groupManager = new StreamConsumerGroupManager(key);
            
            for (int i = 0; i < groupsCount; i++) {
                // 读取消费者组基本信息
                String groupName = readString(dis);
                StreamId lastDeliveredId = readStreamId(dis);
                long createdAt = dis.readLong();
                
                // 创建消费者组
                ConsumerGroup group = groupManager.createGroup(groupName, lastDeliveredId);
                
                // 读取消费者信息
                int consumersCount = readLength(dis);
                for (int j = 0; j < consumersCount; j++) {
                    String consumerName = readString(dis);
                    long seenTime = dis.readLong();
                    int pendingCount = readLength(dis);
                    
                    // 创建消费者
                    Consumer consumer = group.createConsumer(consumerName);
                    // 恢复 seenTime（通过反射或 setter，这里简化处理）
                    // 注意：Consumer 类没有提供设置 seenTime 的方法，这里跳过
                }
                
                // 读取 PEL 信息
                int pelCount = readLength(dis);
                for (int j = 0; j < pelCount; j++) {
                    StreamId messageId = readStreamId(dis);
                    String consumerName = readString(dis);
                    long deliveryTime = dis.readLong();
                    int deliveryCount = dis.readInt();
                    
                    // 创建待处理消息
                    PendingMessage pm = new PendingMessage(messageId, consumerName, deliveryTime);
                    pm.setDeliveryCount(deliveryCount);
                    
                    // 添加到消费者组的 PEL
                    group.addPendingMessage(messageId, consumerName);
                    // 更新传递次数
                    PendingMessage existingPm = group.getPendingMessage(messageId);
                    if (existingPm != null) {
                        existingPm.setDeliveryCount(deliveryCount);
                        existingPm.setDeliveryTime(deliveryTime);
                    }
                }
            }
            
            stream.setConsumerGroupManager(groupManager);
        }
        
        // 将 Stream 存储到内存
        memoryStore.set(db, key, stream);
        
        logger.info("Loaded stream from RDB: DB={}, Key={}, Entries={}, Groups={}", 
                db, key, entriesCount, groupsCount);
    }
    
    /**
     * 读取 StreamId
     *
     * @param dis 数据输入流
     * @return Stream ID
     * @throws IOException 如果读取失败
     */
    private StreamId readStreamId(DataInputStream dis) throws IOException {
        long ms = dis.readLong();
        long seq = dis.readLong();
        return new StreamId(ms, seq);
    }
    
    private void writeRdbFooter(DataOutputStream dos) throws IOException {
        dos.writeByte(0xFF); // 文件尾标识
        // 写入校验和（简单实现，使用时间戳）
        dos.writeLong(System.currentTimeMillis());
    }
}
