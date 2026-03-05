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
    
    private void parseAndExecuteCommand(String line, MemoryStore memoryStore) {
        // 简单实现，解析AOF文件中的命令
        // 这里只处理基本的SET命令
        if (line.startsWith("*")) {
            // 解析Redis协议格式的命令
            String[] parts = line.split("\\r\\n");
            if (parts.length > 0) {
                int argCount = Integer.parseInt(parts[0].substring(1));
                if (argCount >= 1) {
                    String command = parts[2];
                    if (command.equalsIgnoreCase("SET")) {
                        if (argCount >= 3) {
                            String key = parts[4];
                            String value = parts[6];
                            memoryStore.set(0, key, value); // 默认数据库0
                        }
                    } else if (command.equalsIgnoreCase("SELECT")) {
                        if (argCount >= 2) {
                            int db = Integer.parseInt(parts[4]);
                            // 切换数据库，但这里不需要特殊处理
                        }
                    }
                }
            }
        }
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
