package com.janeluo.luban.rds.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置文件加载器
 * 支持 Redis 风格的配置文件格式
 */
public class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    /**
     * 从文件路径加载配置
     *
     * @param configPath 配置文件路径
     * @return RedisConfig 配置对象
     */
    public static RdsConfig load(String configPath) {
        RdsConfig config = new RdsConfig();
        
        if (configPath == null || configPath.isEmpty()) {
            logger.info("未指定配置文件，使用默认配置");
            return config;
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            logger.warn("配置文件不存在: {}，使用默认配置", configPath);
            return config;
        }

        try {
            Map<String, String> properties = parseConfigFile(configPath);
            applyProperties(config, properties);
            logger.info("配置文件加载成功: {}", configPath);
        } catch (IOException e) {
            logger.error("加载配置文件失败: {}", configPath, e);
        }

        return config;
    }

    /**
     * 从类路径加载配置
     *
     * @param resourceName 资源名称
     * @return RedisConfig 配置对象
     */
    public static RdsConfig loadFromClasspath(String resourceName) {
        RdsConfig config = new RdsConfig();

        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                logger.warn("类路径下未找到配置文件: {}，使用默认配置", resourceName);
                return config;
            }

            Map<String, String> properties = parseConfigStream(is);
            applyProperties(config, properties);
            logger.info("从类路径加载配置成功: {}", resourceName);
        } catch (IOException e) {
            logger.error("从类路径加载配置失败: {}", resourceName, e);
        }

        return config;
    }

    /**
     * 解析配置文件
     */
    private static Map<String, String> parseConfigFile(String configPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(configPath), StandardCharsets.UTF_8)) {
            return parseReader(reader);
        }
    }

    /**
     * 解析配置流
     */
    private static Map<String, String> parseConfigStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return parseReader(reader);
        }
    }

    /**
     * 解析配置内容
     */
    private static Map<String, String> parseReader(BufferedReader reader) throws IOException {
        Map<String, String> properties = new HashMap<>();
        String line;

        while ((line = reader.readLine()) != null) {
            // 去除首尾空白
            line = line.trim();

            // 跳过空行和注释行
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // 解析键值对
            int spaceIndex = line.indexOf(' ');
            if (spaceIndex > 0) {
                String key = line.substring(0, spaceIndex).trim();
                String value = line.substring(spaceIndex + 1).trim();
                
                // 去除值两端的引号
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                
                properties.put(key, value);
            }
        }

        return properties;
    }

    /**
     * 将解析的属性应用到配置对象
     */
    private static void applyProperties(RdsConfig config, Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            try {
                switch (key) {
                    // 网络配置
                    case "bind":
                        config.setBind(value);
                        break;
                    case "port":
                        config.setPort(Integer.parseInt(value));
                        break;
                    case "tcp-backlog":
                        config.setTcpBacklog(Integer.parseInt(value));
                        break;
                    case "timeout":
                        config.setTimeout(Integer.parseInt(value));
                        break;
                    case "tcp-keepalive":
                        config.setTcpKeepalive(Integer.parseInt(value));
                        break;

                    // 通用配置
                    case "daemonize":
                        config.setDaemonize("yes".equalsIgnoreCase(value));
                        break;
                    case "loglevel":
                        config.setLoglevel(value);
                        break;
                    case "logfile":
                        config.setLogfile(value);
                        break;
                    case "databases":
                        config.setDatabases(Integer.parseInt(value));
                        break;

                    // 持久化配置
                    case "persist-mode":
                        config.setPersistMode(value);
                        break;
                    case "dir":
                        config.setDir(value);
                        break;
                    case "dbfilename":
                        config.setDbfilename(value);
                        break;
                    case "rdb-save-interval":
                        config.setRdbSaveInterval(Integer.parseInt(value));
                        break;
                    case "appendfilename":
                        config.setAppendfilename(value);
                        break;
                    case "appendfsync":
                        config.setAppendfsync(value);
                        break;
                    case "aof-fsync-interval":
                        config.setAofFsyncInterval(Integer.parseInt(value));
                        break;

                    // 内存管理
                    case "maxmemory":
                        config.setMaxmemory(parseMemorySize(value));
                        break;
                    case "maxmemory-policy":
                        config.setMaxmemoryPolicy(value);
                        break;

                    // 安全配置
                    case "requirepass":
                        config.setRequirepass(value);
                        break;
                    
                    // SlowLog 配置
                    case "slowlog-log-slower-than":
                        config.setSlowlogLogSlowerThan(Long.parseLong(value));
                        break;
                    case "slowlog-max-len":
                        config.setSlowlogMaxLen(Long.parseLong(value));
                        break;

                    // Monitor 配置
                    case "monitor-max-clients":
                        config.setMonitorMaxClients(Integer.parseInt(value));
                        break;
                    case "maxclients":
                        config.setMaxclients(Integer.parseInt(value));
                        break;

                    // 内存池配置
                    case "leak-detection":
                        config.setLeakDetection(value);
                        break;

                    // 多线程配置
                    case "io-threads":
                        config.setIoThreads(Integer.parseInt(value));
                        break;
                    case "worker-threads":
                        config.setWorkerThreads(Integer.parseInt(value));
                        break;
                    case "business-threads":
                        config.setBusinessThreads(Integer.parseInt(value));
                        break;

                    // 内存优化配置
                    case "use-pool":
                        config.setUsePool("yes".equalsIgnoreCase(value));
                        break;
                    case "memory-frag-threshold":
                        config.setMemoryFragThreshold(Integer.parseInt(value));
                        break;

                    // 集群配置
                    case "cluster-enabled":
                        config.setClusterEnabled("yes".equalsIgnoreCase(value));
                        break;
                    case "cluster-config-file":
                        config.setClusterConfigFile(value);
                        break;
                    case "cluster-node-timeout":
                        config.setClusterNodeTimeout(Long.parseLong(value));
                        break;
                    case "cluster-failover-grace-period":
                        config.setClusterFailoverGracePeriod(Long.parseLong(value));
                        break;
                    case "cluster-announce-ip":
                        config.setClusterAnnounceIp(value);
                        break;
                    case "cluster-announce-port":
                        config.setClusterAnnouncePort(Integer.parseInt(value));
                        break;
                    case "cluster-announce-bus-port":
                        config.setClusterAnnounceBusPort(Integer.parseInt(value));
                        break;
                    case "cluster-slave-validity-factor":
                        config.setClusterSlaveValidityFactor(Integer.parseInt(value));
                        break;
                    case "cluster-migration-barrier":
                        config.setClusterMigrationBarrier(Integer.parseInt(value));
                        break;
                    case "cluster-require-full-coverage":
                        config.setClusterRequireFullCoverage("yes".equalsIgnoreCase(value));
                        break;

                    // 主从复制配置
                    case "replicaof":
                    case "slaveof":
                        config.setReplicaof(value);
                        break;
                    case "masterauth":
                        config.setMasterauth(value);
                        break;
                    case "slave-read-only":
                        config.setSlaveReadOnly("yes".equalsIgnoreCase(value));
                        break;
                    case "repl-timeout":
                        config.setReplTimeout(Integer.parseInt(value));
                        break;
                    case "repl-backlog-size":
                        config.setReplBacklogSize(parseMemorySize(value));
                        break;
                    case "repl-backlog-ttl":
                        config.setReplBacklogTtl(Integer.parseInt(value));
                        break;
                    case "repl-ping-slave-period":
                        config.setReplPingSlavePeriod(Integer.parseInt(value));
                        break;
                    case "repl-reconnect-interval":
                        config.setReplReconnectInterval(Long.parseLong(value));
                        break;
                    case "repl-tcp-keepalive":
                        config.setReplTcpKeepalive(Integer.parseInt(value));
                        break;
                    case "repl-disable-tcp-nodelay":
                        config.setReplDisableTcpNodelay("yes".equalsIgnoreCase(value));
                        break;

                    // 哨兵配置
                    case "sentinel-enabled":
                        config.setSentinelEnabled("yes".equalsIgnoreCase(value));
                        break;
                    case "sentinel-port":
                        config.setSentinelPort(Integer.parseInt(value));
                        break;
                    case "sentinel-config-file":
                        config.setSentinelConfigFile(value);
                        break;
                    case "sentinel-monitor":
                        config.setSentinelMonitor(value);
                        break;
                    case "sentinel-down-after-milliseconds":
                        config.setSentinelDownAfterMilliseconds(Long.parseLong(value));
                        break;
                    case "sentinel-failover-timeout":
                        config.setSentinelFailoverTimeout(Long.parseLong(value));
                        break;
                    case "sentinel-parallel-syncs":
                        config.setSentinelParallelSyncs(Integer.parseInt(value));
                        break;
                    case "sentinel-announce-ip":
                        config.setSentinelAnnounceIp(value);
                        break;
                    case "sentinel-announce-port":
                        config.setSentinelAnnouncePort(Integer.parseInt(value));
                        break;
                    case "sentinel-heartbeat-interval":
                        config.setSentinelHeartbeatInterval(Long.parseLong(value));
                        break;

                    // Lua 配置
                    case "lua-timeout":
                        config.setLuaTimeout(Long.parseLong(value));
                        break;
                    case "lua-sandbox-enabled":
                        config.setLuaSandboxEnabled("yes".equalsIgnoreCase(value));
                        break;
                    case "lua-max-script-bytes":
                        config.setLuaMaxScriptBytes(Long.parseLong(value));
                        break;
                    case "lua-max-return-bytes":
                        config.setLuaMaxReturnBytes(Long.parseLong(value));
                        break;
                    case "lua-max-ops-per-script":
                        config.setLuaMaxOpsPerScript(Long.parseLong(value));
                        break;
                    case "lua-yield-ms":
                        config.setLuaYieldMs(Long.parseLong(value));
                        break;
                    case "lua-allowed-modules":
                        config.setLuaAllowedModules(value);
                        break;
                    case "lua-blocked-functions":
                        config.setLuaBlockedFunctions(value);
                        break;

                    default:
                        logger.debug("未知配置项: {} = {}", key, value);
                        break;
                }
            } catch (NumberFormatException e) {
                logger.warn("配置项 {} 的值 {} 格式错误", key, value);
            }
        }
    }

    /**
     * 解析内存大小配置
     * 支持单位：b, kb, mb, gb（不区分大小写）
     */
    private static long parseMemorySize(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        value = value.toLowerCase().trim();
        
        long multiplier = 1;
        String numPart = value;

        if (value.endsWith("gb")) {
            multiplier = 1024L * 1024L * 1024L;
            numPart = value.substring(0, value.length() - 2);
        } else if (value.endsWith("mb")) {
            multiplier = 1024L * 1024L;
            numPart = value.substring(0, value.length() - 2);
        } else if (value.endsWith("kb")) {
            multiplier = 1024L;
            numPart = value.substring(0, value.length() - 2);
        } else if (value.endsWith("b")) {
            numPart = value.substring(0, value.length() - 1);
        }

        return Long.parseLong(numPart.trim()) * multiplier;
    }
}
