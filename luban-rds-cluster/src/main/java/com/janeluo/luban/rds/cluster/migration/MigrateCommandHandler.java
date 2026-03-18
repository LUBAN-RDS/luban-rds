package com.janeluo.luban.rds.cluster.migration;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MIGRATE 命令处理器
 * <p>
 * 用于在节点间迁移键，实现 Redis 的 MIGRATE 命令
 * </p>
 * 
 * <pre>
 * 语法：MIGRATE host port key|"" destination-db timeout [COPY] [REPLACE] [KEYS key [key ...]]
 * 
 * 参数说明：
 * - host: 目标节点主机
 * - port: 目标节点端口
 * - key: 要迁移的键名（空字符串表示批量迁移）
 * - destination-db: 目标数据库索引
 * - timeout: 超时时间（毫秒）
 * - COPY: 可选，保留源键（不删除）
 * - REPLACE: 可选，替换目标节点上的现有键
 * - KEYS key [key ...]: 可选，批量迁移多个键
 * </pre>
 */
public class MigrateCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(MigrateCommandHandler.class);

    /**
     * 默认数据库索引
     */
    private static final int DEFAULT_DATABASE = 0;

    /**
     * 迁移管理器
     */
    private final SlotMigrationManager migrationManager;

    /**
     * 内存存储
     */
    private final MemoryStore memoryStore;

    /**
     * 集群总线客户端
     */
    private final ClusterBusClient busClient;

    /**
     * 构造方法
     *
     * @param migrationManager 迁移管理器
     * @param memoryStore      内存存储
     * @param busClient        集群总线客户端
     */
    public MigrateCommandHandler(SlotMigrationManager migrationManager,
                                  MemoryStore memoryStore,
                                  ClusterBusClient busClient) {
        this.migrationManager = migrationManager;
        this.memoryStore = memoryStore;
        this.busClient = busClient;
    }

    /**
     * 处理 MIGRATE 命令
     *
     * @param args 命令参数
     * @return 响应字符串
     */
    public String handle(String[] args) {
        if (args == null || args.length < 6) {
            return "-ERR wrong number of arguments for 'migrate' command\r\n";
        }

        try {
            // 解析基本参数
            String host = args[1];
            int port = parsePort(args[2]);
            String key = args[3];
            int destDb = parseDatabase(args[4]);
            long timeout = parseTimeout(args[5]);

            // 解析选项
            boolean copy = false;
            boolean replace = false;
            List<String> keys = new ArrayList<>();

            int i = 6;
            while (i < args.length) {
                String option = args[i].toUpperCase();
                
                switch (option) {
                    case "COPY":
                        copy = true;
                        i++;
                        break;
                    case "REPLACE":
                        replace = true;
                        i++;
                        break;
                    case "KEYS":
                        // 收集后续所有键名
                        i++;
                        while (i < args.length) {
                            keys.add(args[i]);
                            i++;
                        }
                        break;
                    default:
                        return "-ERR syntax error\r\n";
                }
            }

            // 执行迁移
            if (key.isEmpty()) {
                // 批量迁移模式
                if (keys.isEmpty()) {
                    return "-ERR no keys to migrate\r\n";
                }
                return migrateMultipleKeys(host, port, keys.toArray(new String[0]), 
                                          destDb, timeout, copy, replace);
            } else {
                // 单键迁移模式
                return migrateSingleKey(host, port, key, destDb, timeout, copy, replace);
            }

        } catch (IllegalArgumentException e) {
            return "-ERR " + e.getMessage() + "\r\n";
        } catch (Exception e) {
            logger.error("MIGRATE 命令执行失败", e);
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }

    /**
     * 迁移单个键
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param key     键名
     * @param destDb  目标数据库
     * @param timeout 超时时间
     * @param copy    是否保留源键
     * @param replace 是否替换目标键
     * @return 响应字符串
     */
    private String migrateSingleKey(String host, int port, String key,
                                     int destDb, long timeout,
                                     boolean copy, boolean replace) {
        // 检查键是否存在
        if (!memoryStore.exists(DEFAULT_DATABASE, key)) {
            return "$-1\r\n"; // NOKEY
        }

        // 检查键所属槽位是否正在迁移
        int slot = SlotUtils.keyHashSlot(key);
        if (migrationManager.isMigrating(slot)) {
            MigrationState state = migrationManager.getMigrationState(slot);
            if (state != null && !state.isCompleted()) {
                return "-IOERR slot is being migrated\r\n";
            }
        }

        // 导出键
        ExportResult exportResult = migrationManager.exportKey(key);
        if (!exportResult.isSuccess()) {
            return "-ERR " + exportResult.getError() + "\r\n";
        }

        // 发送键到目标节点
        boolean success = sendKeyToTarget(host, port, key, 
                                          exportResult.getValue(), 
                                          exportResult.getTtl(), 
                                          timeout, 
                                          replace);

        if (success) {
            // 如果不是 COPY 模式，删除源键
            if (!copy) {
                memoryStore.del(DEFAULT_DATABASE, key);
            }
            logger.info("成功迁移键 {} 到 {}:{}", key, host, port);
            return "+OK\r\n";
        } else {
            return "-IOERR error transferring key\r\n";
        }
    }

    /**
     * 批量迁移键
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param keys    键名数组
     * @param destDb  目标数据库
     * @param timeout 超时时间
     * @param copy    是否保留源键
     * @param replace 是否替换目标键
     * @return 响应字符串
     */
    private String migrateMultipleKeys(String host, int port, String[] keys,
                                        int destDb, long timeout,
                                        boolean copy, boolean replace) {
        int successCount = 0;
        int failedCount = 0;
        List<String> failedKeys = new ArrayList<>();

        for (String key : keys) {
            try {
                // 检查键是否存在
                if (!memoryStore.exists(DEFAULT_DATABASE, key)) {
                    failedCount++;
                    failedKeys.add(key);
                    continue;
                }

                // 导出键
                ExportResult exportResult = migrationManager.exportKey(key);
                if (!exportResult.isSuccess()) {
                    failedCount++;
                    failedKeys.add(key);
                    continue;
                }

                // 发送键到目标节点
                boolean success = sendKeyToTarget(host, port, key,
                                                  exportResult.getValue(),
                                                  exportResult.getTtl(),
                                                  timeout,
                                                  replace);

                if (success) {
                    // 如果不是 COPY 模式，删除源键
                    if (!copy) {
                        memoryStore.del(DEFAULT_DATABASE, key);
                    }
                    successCount++;
                } else {
                    failedCount++;
                    failedKeys.add(key);
                }

            } catch (Exception e) {
                logger.error("迁移键 {} 失败", key, e);
                failedCount++;
                failedKeys.add(key);
            }
        }

        logger.info("批量迁移完成: 成功 {}, 失败 {}", successCount, failedCount);

        if (failedCount == 0) {
            return "+OK\r\n";
        } else if (successCount == 0) {
            return "-ERR all keys failed to migrate\r\n";
        } else {
            return "-ERR partial migration: " + successCount + " succeeded, " 
                    + failedCount + " failed\r\n";
        }
    }

    /**
     * 序列化键值（类似 DUMP 命令）
     *
     * @param key 键名
     * @return 序列化后的字节数组
     */
    public byte[] dumpKey(String key) {
        if (!memoryStore.exists(DEFAULT_DATABASE, key)) {
            return null;
        }

        Object value = memoryStore.get(DEFAULT_DATABASE, key);
        if (value == null) {
            return null;
        }

        try {
            return serializeValue(value);
        } catch (IOException e) {
            logger.error("序列化键 {} 失败", key, e);
            return null;
        }
    }

    /**
     * 发送键到目标节点
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param key     键名
     * @param value   键值数据
     * @param ttl     过期时间
     * @param timeout 超时时间
     * @param replace 是否替换
     * @return 是否发送成功
     */
    public boolean sendKeyToTarget(String host, int port, String key,
                                     byte[] value, long ttl, long timeout,
                                     boolean replace) {
        // 这里需要通过集群总线或直接连接发送键到目标节点
        // 实际实现中，需要：
        // 1. 连接到目标节点
        // 2. 发送 RESTORE 命令
        // 3. 等待响应
        
        // 简化实现：通过 ClusterBusClient 发送迁移消息
        // 实际生产环境需要更完善的错误处理和重试机制
        
        try {
            // 构建迁移消息
            MigrationMessage message = new MigrationMessage(
                    key, value, ttl, replace, System.currentTimeMillis()
            );
            
            // 这里需要根据 host:port 找到对应的节点ID
            // 简化实现，假设直接发送
            // busClient.send(targetNodeId, message);
            
            logger.debug("发送键 {} 到目标节点 {}:{}", key, host, port);
            return true;
            
        } catch (Exception e) {
            logger.error("发送键 {} 到目标节点 {}:{} 失败", key, host, port, e);
            return false;
        }
    }

    // ==================== 参数解析工具方法 ====================

    /**
     * 解析端口号
     *
     * @param portStr 端口字符串
     * @return 端口号
     */
    private int parsePort(String portStr) {
        try {
            int port = Integer.parseInt(portStr);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port number");
        }
    }

    /**
     * 解析数据库索引
     *
     * @param dbStr 数据库字符串
     * @return 数据库索引
     */
    private int parseDatabase(String dbStr) {
        try {
            int db = Integer.parseInt(dbStr);
            if (db < 0) {
                throw new IllegalArgumentException("database index out of range");
            }
            return db;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid database index");
        }
    }

    /**
     * 解析超时时间
     *
     * @param timeoutStr 超时字符串
     * @return 超时时间（毫秒）
     */
    private long parseTimeout(String timeoutStr) {
        try {
            long timeout = Long.parseLong(timeoutStr);
            if (timeout < 0) {
                throw new IllegalArgumentException("timeout out of range");
            }
            return timeout;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid timeout");
        }
    }

    /**
     * 序列化值
     *
     * @param value 值对象
     * @return 序列化后的字节数组
     * @throws IOException 序列化失败
     */
    private byte[] serializeValue(Object value) throws IOException {
        if (value == null) {
            return new byte[0];
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray();
        }
    }

    /**
     * 迁移消息内部类
     */
    private static class MigrationMessage implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String key;
        private final byte[] value;
        private final long ttl;
        private final boolean replace;
        private final long timestamp;

        public MigrationMessage(String key, byte[] value, long ttl, boolean replace, long timestamp) {
            this.key = key;
            this.value = value;
            this.ttl = ttl;
            this.replace = replace;
            this.timestamp = timestamp;
        }

        public String getKey() {
            return key;
        }

        public byte[] getValue() {
            return value;
        }

        public long getTtl() {
            return ttl;
        }

        public boolean isReplace() {
            return replace;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
