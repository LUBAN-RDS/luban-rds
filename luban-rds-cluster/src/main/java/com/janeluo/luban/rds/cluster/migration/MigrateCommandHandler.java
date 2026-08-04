package com.janeluo.luban.rds.cluster.migration;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.MigrateKeyAckMessage;
import com.janeluo.luban.rds.cluster.gossip.MigrateKeyMessage;
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
     * sendAndWait 默认超时（毫秒，P1-17）。当 MIGRATE timeout=0 时使用此值，
     * 避免 Netty await(0) 立即返回导致必失败。
     */
    private static final long DEFAULT_MIGRATE_TIMEOUT_MS = 5000L;

    /**
     * 发送键到目标节点的结果（P1-17，区分 BUSYKEY 与普通失败）。
     */
    private enum SendResult {
        /** 发送并经目标节点 ACK 确认成功 */
        SUCCESS,
        /** 目标键已存在且未带 REPLACE（对齐 Redis BUSYKEY） */
        BUSYKEY,
        /** 其他失败（未连接、ACK 超时/失败、目标拒绝等） */
        FAILED
    }

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
     * 集群配置（用于根据 host:port 查找目标节点ID）
     */
    private final ClusterConfig clusterConfig;

    /**
     * 构造方法
     *
     * @param migrationManager 迁移管理器
     * @param memoryStore      内存存储
     * @param busClient        集群总线客户端
     * @param clusterConfig    集群配置
     */
    public MigrateCommandHandler(SlotMigrationManager migrationManager,
                                  MemoryStore memoryStore,
                                  ClusterBusClient busClient,
                                  ClusterConfig clusterConfig) {
        this.migrationManager = migrationManager;
        this.memoryStore = memoryStore;
        this.busClient = busClient;
        this.clusterConfig = clusterConfig;
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
            // P1-17：AUTH/AUTH2 参数解析（本项目内部总线无鉴权，解析后不强制，
            // 仅避免 Redis 客户端传入时报 syntax error；连接已鉴权时 Redis 允许忽略）
            String authUser = null;
            String authPassword = null;

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
                    case "AUTH":
                        // AUTH <password>（单参，Redis 7 也允许 AUTH <user> <password> 两参）
                        if (i + 1 >= args.length) {
                            return "-ERR syntax error\r\n";
                        }
                        authPassword = args[i + 1];
                        i += 2;
                        break;
                    case "AUTH2":
                        // AUTH2 <user> <password>（两参）
                        if (i + 2 >= args.length) {
                            return "-ERR syntax error\r\n";
                        }
                        authUser = args[i + 1];
                        authPassword = args[i + 2];
                        i += 3;
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

            // P1-17：key 与 KEYS 不能并存（Redis 7：key 非空时不得再用 KEYS）
            if (!key.isEmpty() && !keys.isEmpty()) {
                return "-ERR syntax error\r\n";
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
            // 对齐 Redis 7 MIGRATE：单键不存在时回复 +NOKEY（而非 bulk nil $-1）
            return "+NOKEY\r\n";
        }

        // MIGRATE 命令应能独立工作，不强制依赖 SETSLOT MIGRATING 状态。
        // 直接从内存存储导出键值（dumpKey 内部处理序列化）。
        byte[] valueBytes = dumpKey(key);
        if (valueBytes == null) {
            return "-ERR error dumping key\r\n";
        }

        // 获取 TTL
        long ttl = memoryStore.pttl(DEFAULT_DATABASE, key);
        if (ttl < 0) {
            ttl = 0;
        }

        // 发送键到目标节点（真正通过总线传输，等待目标节点 ACK）
        SendResult result = sendKeyToTarget(host, port, key, valueBytes, ttl, timeout, replace);

        if (result == SendResult.SUCCESS) {
            // 如果不是 COPY 模式，删除源键
            if (!copy) {
                memoryStore.del(DEFAULT_DATABASE, key);
            }
            logger.info("成功迁移键 {} 到 {}:{}", key, host, port);
            return "+OK\r\n";
        } else if (result == SendResult.BUSYKEY) {
            // P1-17：目标键已存在且未带 REPLACE，对齐 Redis -BUSYKEY 回复
            return "-BUSYKEY Target key name already exists.\r\n";
        } else {
            // 发送失败时不删除源键，避免数据丢失
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
        // 两阶段原子批量迁移：
        //   阶段 1（dump + transfer）：先 dump 所有键并累加大小校验，超限直接拒绝；
        //                            随后逐键发送并收集每个键的 ACK 结果，此阶段不删除任何源键。
        //   阶段 2（decide + del）：   仅当全部键 ACK 成功且非 COPY 模式时，统一删除所有源键；
        //                            任一键失败则源端不删，避免半迁移导致数据丢失。
        // 这样修复了原先“每键立即 del”的非原子行为，符合 Redis 7 多键 MIGRATE 的全有/全无语义。

        List<KeyDump> dumped = new ArrayList<>();
        List<String> dumpFailedKeys = new ArrayList<>();
        long totalSize = 0L;
        boolean sizeExceeded = false;

        // 阶段 1a：dump 所有键并校验累计大小
        for (String key : keys) {
            try {
                if (!memoryStore.exists(DEFAULT_DATABASE, key)) {
                    dumpFailedKeys.add(key);
                    continue;
                }
                byte[] valueBytes = dumpKey(key);
                if (valueBytes == null) {
                    dumpFailedKeys.add(key);
                    continue;
                }
                long ttl = memoryStore.pttl(DEFAULT_DATABASE, key);
                if (ttl < 0) {
                    ttl = 0;
                }
                totalSize += valueBytes.length;
                dumped.add(new KeyDump(key, valueBytes, ttl));

                // 累计超过单批上限立即中止，尚未发起传输
                if (totalSize > getMaxBatchSize()) {
                    sizeExceeded = true;
                    break;
                }
            } catch (Exception e) {
                logger.error("dump 键 {} 失败", key, e);
                dumpFailedKeys.add(key);
            }
        }

        if (sizeExceeded) {
            logger.warn("批量迁移中止：单批总大小超过 {} 字节（当前累计 {}）", getMaxBatchSize(), totalSize);
            return "-ERR command keys batch too large\r\n";
        }

        // 阶段 1b：逐键发送到目标节点，记录每个键的 ACK 结果（此阶段不删除源键）
        int successCount = 0;
        List<String> sendFailedKeys = new ArrayList<>();

        for (KeyDump kd : dumped) {
            try {
                SendResult result = sendKeyToTarget(host, port, kd.key, kd.value, kd.ttl, timeout, replace);
                if (result == SendResult.SUCCESS) {
                    successCount++;
                } else {
                    // P1-17：逐键语义——BUSYKEY/FAILED 仅计入本键失败，不影响其他键（对齐 Redis 7）
                    sendFailedKeys.add(kd.key);
                }
            } catch (Exception e) {
                logger.error("迁移键 {} 失败", kd.key, e);
                sendFailedKeys.add(kd.key);
            }
        }

        int failedCount = dumpFailedKeys.size() + sendFailedKeys.size();

        // 阶段 2：决策。全部成功且非 COPY 模式才统一删除源键；任一失败源端不删
        if (failedCount == 0) {
            if (!copy) {
                for (KeyDump kd : dumped) {
                    memoryStore.del(DEFAULT_DATABASE, kd.key);
                }
            }
            logger.info("批量迁移完成: 成功 {}", successCount);
            return "+OK\r\n";
        } else if (successCount == 0) {
            logger.info("批量迁移完成: 全部失败 {}", failedCount);
            return "-ERR all keys failed to migrate\r\n";
        } else {
            logger.info("批量迁移部分失败: 成功 {}, 失败 {}（源端不删除）", successCount, failedCount);
            return "-ERR partial migration: " + successCount + " succeeded, "
                    + failedCount + " failed\r\n";
        }
    }

    /**
     * 单批 MIGRATE 最大允许传输的字节总量（64MB）。
     * <p>
     * 可由测试覆写为更小阈值以便触发超限分支。
     * </p>
     *
     * @return 最大字节数
     */
    protected long getMaxBatchSize() {
        return 64L * 1024 * 1024;
    }

    /**
     * 已 dump 的键数据载体（键名 + 序列化字节数组 + TTL）。
     * 仅在批量迁移内部使用。
     */
    private static class KeyDump {
        final String key;
        final byte[] value;
        final long ttl;

        KeyDump(String key, byte[] value, long ttl) {
            this.key = key;
            this.value = value;
            this.ttl = ttl;
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
     * 发送键到目标节点（通过集群总线传输，等待目标节点 ACK 确认，P1-17）。
     * <p>
     * 返回 {@link SendResult} 以区分 BUSYKEY（目标键已存在且未带 REPLACE）与普通失败，
     * 使调用方能回送精确的 Redis 错误（-BUSYKEY）。
     * </p>
     *
     * @param host    目标主机
     * @param port    目标端口
     * @param key     键名
     * @param value   键值数据（序列化后的字节数组）
     * @param ttl     过期时间
     * @param timeout 超时时间（毫秒）；0 时使用内部默认值，避免 Netty await(0) 必失败
     * @param replace 是否替换
     * @return 发送结果
     */
    public SendResult sendKeyToTarget(String host, int port, String key,
                                     byte[] value, long ttl, long timeout,
                                     boolean replace) {
        if (busClient == null || clusterConfig == null) {
            logger.error("无法迁移键 {}: busClient 或 clusterConfig 未注入", key);
            return SendResult.FAILED;
        }

        // 根据 host:port 查找目标节点ID
        String targetNodeId = findNodeIdByAddress(host, port);
        if (targetNodeId == null) {
            logger.error("无法迁移键 {}: 未找到目标节点 {}:{}", key, host, port);
            return SendResult.FAILED;
        }

        // 获取本节点ID作为发送者
        ClusterNode myNode = clusterConfig.getMyNode();
        String senderNodeId = myNode != null ? myNode.getNodeId() : null;
        if (senderNodeId == null) {
            logger.error("无法迁移键 {}: 本节点ID未设置", key);
            return SendResult.FAILED;
        }

        try {
            MigrateKeyMessage message = new MigrateKeyMessage(senderNodeId, key, value, ttl, replace);
            logger.debug("发送键 {} 到目标节点 {}:{} (nodeId={})", key, host, port, targetNodeId);

            // P1-17：timeout=0 时用内部默认值，避免 Netty await(0) 立即返回必失败
            long effectiveTimeout = timeout > 0 ? timeout : DEFAULT_MIGRATE_TIMEOUT_MS;

            // 通过总线发送并等待 ACK
            GossipMessage response = busClient.sendAndWait(targetNodeId, message, effectiveTimeout);

            if (response instanceof MigrateKeyAckMessage) {
                MigrateKeyAckMessage ack = (MigrateKeyAckMessage) response;
                if (ack.isSuccess()) {
                    logger.debug("键 {} 迁移成功，目标节点已确认", key);
                    return SendResult.SUCCESS;
                } else {
                    // P1-17：ACK errorMessage 为 BUSYKEY 时映射为 BUSYKEY 结果
                    String errMsg = ack.getErrorMessage();
                    if (errMsg != null && errMsg.contains("BUSYKEY")) {
                        logger.warn("键 {} 迁移失败: BUSYKEY（目标键已存在且未带 REPLACE）", key);
                        return SendResult.BUSYKEY;
                    }
                    logger.error("键 {} 迁移失败: 目标节点返回错误: {}", key, errMsg);
                    return SendResult.FAILED;
                }
            }

            logger.error("键 {} 迁移失败: 未收到有效 ACK（响应为 null 或类型错误）", key);
            return SendResult.FAILED;

        } catch (Exception e) {
            logger.error("发送键 {} 到目标节点 {}:{} 失败", key, host, port, e);
            return SendResult.FAILED;
        }
    }

    /**
     * 根据 host:port 在集群配置中查找节点ID
     *
     * @param host 主机
     * @param port 端口
     * @return 节点ID，未找到返回 null
     */
    private String findNodeIdByAddress(String host, int port) {
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (host.equals(node.getIp()) && port == node.getPort()) {
                return node.getNodeId();
            }
        }
        return null;
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
            if (port <= 0 || port > 65535) {
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
}
