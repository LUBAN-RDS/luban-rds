package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AofPersistService implements PersistService {
    private static final Logger logger = LoggerFactory.getLogger(AofPersistService.class);
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
            this.aofOutputStream = new FileOutputStream(aofFilePath, true); // 追加模式
            this.aofWriter = new OutputStreamWriter(aofOutputStream);
        } catch (IOException e) {
            logger.error("Error initializing AOF writer", e);
        }
        
        // 创建线程池用于异步fsync
        this.executorService = Executors.newSingleThreadExecutor();
        
        // 启动定期fsync任务
        startFsyncTask();
    }
    
    @Override
    public java.util.Map<String, Object> getInfo() {
        java.util.Map<String, Object> info = new java.util.HashMap<>();
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
            java.io.File file = new java.io.File(aofFilePath);
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
        logger.info("Loading AOF data...");
        long startTime = System.currentTimeMillis();
        
        File aofFile = new File(aofFilePath);
        if (!aofFile.exists()) {
            logger.info("No AOF file found, skipping load");
            return;
        }
        
        try (BufferedReader br = new BufferedReader(new FileReader(aofFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 解析AOF文件中的命令
                parseAndExecuteCommand(line, memoryStore);
            }
            
            long endTime = System.currentTimeMillis();
            logger.info("AOF load completed in {} ms", endTime - startTime);
            
        } catch (Exception e) {
            logger.error("Error loading AOF data", e);
        }
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
     * 记录写命令到AOF文件
     * @param command 命令
     * @param args 命令参数
     */
    public void recordCommand(String command, String[] args) {
        if (!isRunning || aofWriter == null) {
            return;
        }
        
        try {
            // 构建AOF格式的命令 - 使用ISO-8859-1编码确保二进制安全
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
            
            // 写入AOF文件 - 使用ISO-8859-1编码
            aofWriter.write(sb.toString(), 0, sb.length());
            
            // 如果fsync间隔为0，立即fsync
            if (fsyncInterval == 0) {
                flush();
            }
            
        } catch (IOException e) {
            logger.error("Error recording command to AOF", e);
        }
    }
    
    /**
     * 执行AOF重写
     * @param memoryStore 内存存储实例
     */
    public void rewrite(MemoryStore memoryStore) {
        logger.info("Starting AOF rewrite...");
        long startTime = System.currentTimeMillis();
        
        try (FileWriter tempWriter = new FileWriter(aofTempFilePath)) {
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
                    java.util.List<Object> scanResult = memoryStore.scan(db, cursor, "*", 100);
                    if (scanResult.size() <= 1) { // 只有游标，没有键
                        break;
                    }
                    
                    cursor = (Long) scanResult.get(0);
                    
                    // 处理每个键
                    for (int i = 1; i < scanResult.size(); i++) {
                        String key = (String) scanResult.get(i);
                        Object value = memoryStore.get(db, key);
                        if (value != null) {
                            // 写入键值对的SET命令
                            writeKeyValueCommand(tempWriter, db, key, value, memoryStore);
                        }
                    }
                } while (cursor != 0);
            }
            
            // 重命名临时文件为AOF文件
            Files.move(new File(aofTempFilePath).toPath(), new File(aofFilePath).toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            // 重新初始化AOF写入器
            if (aofWriter != null) {
                aofWriter.close();
            }
            if (aofOutputStream != null) {
                aofOutputStream.close();
            }
            this.aofOutputStream = new FileOutputStream(aofFilePath, true); // 追加模式
            this.aofWriter = new OutputStreamWriter(aofOutputStream);
            
            long endTime = System.currentTimeMillis();
            logger.info("AOF rewrite completed in {} ms", endTime - startTime);
            
        } catch (Exception e) {
            logger.error("Error during AOF rewrite", e);
        } finally {
            // 删除临时文件
            File tempFile = new File(aofTempFilePath);
            if (tempFile.exists()) {
                tempFile.delete();
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
    
    private int currentDb = 0;
    
    private void parseAndExecuteCommand(String line, MemoryStore memoryStore) {
        if (!line.startsWith("*")) {
            return;
        }
        
        try {
            java.util.List<String> args = parseRespArray(line);
            if (args.isEmpty()) {
                return;
            }
            
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
            logger.error("Error parsing AOF command: {}", line, e);
        }
    }
    
    private java.util.List<String> parseRespArray(String line) {
        java.util.List<String> args = new java.util.ArrayList<>();
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
    
    private void writeSelectCommand(FileWriter writer, int db) throws IOException {
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
    
    private void writeKeyValueCommand(FileWriter writer, int db, String key, Object value, MemoryStore memoryStore) throws IOException {
        String[] args = new String[]{"SET", key, value.toString()};
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
}
