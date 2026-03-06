package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.context.InfoProvider;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * Luban RDS信息提供者
 * 
 * <p>提供INFO命令所需的各部分信息，包括：
 * <ul>
 *   <li>Server - 服务器信息</li>
 *   <li>Clients - 客户端信息</li>
 *   <li>Memory - 内存信息</li>
 *   <li>Persistence - 持久化信息</li>
 *   <li>Stats - 统计信息</li>
 *   <li>Replication - 复制信息</li>
 *   <li>CPU - CPU信息</li>
 *   <li>CommandStats - 命令统计</li>
 *   <li>Cluster - 集群信息</li>
 *   <li>Keyspace - 键空间信息</li>
 *   <li>Modules - 模块信息</li>
 *   <li>ErrorStats - 错误统计</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class LubanInfoProvider implements InfoProvider {
    
    private final NettyRedisServer server;
    private final RuntimeMXBean runtimeMXBean;
    private final OperatingSystemMXBean osMXBean;

    public LubanInfoProvider(NettyRedisServer server) {
        this.server = server;
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public Map<String, Object> getInfo(String section) {
        Map<String, Object> info = new HashMap<>();
        if (section == null || section.isEmpty() || "all".equalsIgnoreCase(section) || "default".equalsIgnoreCase(section)) {
            // 返回所有默认板块
            info.putAll(getServerInfo());
            info.putAll(getClientsInfo());
            info.putAll(getMemoryInfo());
            info.putAll(getPersistenceInfo());
            info.putAll(getStatsInfo());
            info.putAll(getReplicationInfo());
            info.putAll(getCpuInfo());
            info.putAll(getCommandStatsInfo());
            info.putAll(getClusterInfo());
            info.putAll(getKeyspaceInfo());
            // 扩展板块
            info.putAll(getModulesInfo());
            info.putAll(getErrorStatsInfo());
        } else {
            switch (section.toLowerCase()) {
                case "server": return getServerInfo();
                case "clients": return getClientsInfo();
                case "memory": return getMemoryInfo();
                case "persistence": return getPersistenceInfo();
                case "stats": return getStatsInfo();
                case "replication": return getReplicationInfo();
                case "cpu": return getCpuInfo();
                case "commandstats": return getCommandStatsInfo();
                case "cluster": return getClusterInfo();
                case "keyspace": return getKeyspaceInfo();
                case "modules": return getModulesInfo();
                case "errorstats": return getErrorStatsInfo();
                default: break;
            }
        }
        return info;
    }

    private Map<String, Object> getServerInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("redis_version", "1.0.0");
        info.put("redis_git_sha1", "00000000");
        info.put("redis_git_dirty", 0);
        info.put("redis_build_id", "00000000");
        info.put("redis_mode", "standalone");
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch") + " " + System.getProperty("os.version"));
        info.put("arch_bits", System.getProperty("os.arch").contains("64") ? "64" : "32");
        info.put("multiplexing_api", "netty");
        info.put("atomicvar_api", "java-atomic");
        info.put("gcc_version", "0.0.0"); // N/A for Java
        
        String pid = runtimeMXBean.getName().split("@")[0];
        info.put("process_id", pid);
        info.put("process_supervised", "no");
        info.put("run_id", "00000000000000000000000000000000"); // TODO: Generate unique run_id
        info.put("tcp_port", server.getPort());
        info.put("server_time_usec", System.currentTimeMillis() * 1000);
        long uptime = runtimeMXBean.getUptime();
        info.put("uptime_in_seconds", uptime / 1000);
        info.put("uptime_in_days", uptime / (1000 * 60 * 60 * 24));
        info.put("hz", 10); // Netty event loop hz is dynamic, use 10 as placeholder
        info.put("configured_hz", 10);
        info.put("lru_clock", (System.currentTimeMillis() / 1000) & 0x00FFFFFF);
        info.put("executable", System.getProperty("java.home") + "/bin/java");
        info.put("config_file", "luban-rds.conf"); // Placeholder
        
        // Java specific
        info.put("java_version", System.getProperty("java.version"));
        info.put("java_vendor", System.getProperty("java.vendor"));
        info.put("jvm_version", System.getProperty("java.vm.version"));
        info.put("jvm_vendor", System.getProperty("java.vm.vendor"));
        info.put("jvm_name", System.getProperty("java.vm.name"));
        
        return info;
    }

    private Map<String, Object> getClientsInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("connected_clients", RedisServerHandler.getCurrentConnections());
        info.put("cluster_connections", 0);
        info.put("maxclients", 10000); // TODO: Configurable
        info.put("client_recent_max_input_buffer", 0);
        info.put("client_recent_max_output_buffer", 0);
        info.put("blocked_clients", 0);
        info.put("tracking_clients", 0);
        info.put("clients_in_timeout_table", 0);
        return info;
    }

    private Map<String, Object> getMemoryInfo() {
        Map<String, Object> info = new HashMap<>();
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long usedMemory = totalMemory - freeMemory;
        
        info.put("used_memory", usedMemory);
        info.put("used_memory_human", toHumanReadable(usedMemory));
        info.put("used_memory_rss", totalMemory); // JVM heap size as RSS approximation
        info.put("used_memory_rss_human", toHumanReadable(totalMemory));
        info.put("used_memory_peak", usedMemory); // Simple approximation
        info.put("used_memory_peak_human", toHumanReadable(usedMemory));
        info.put("used_memory_peak_perc", "100%"); // Placeholder
        info.put("used_memory_overhead", 0);
        info.put("used_memory_startup", 0);
        info.put("used_memory_dataset", usedMemory);
        info.put("used_memory_dataset_perc", "100%");
        info.put("allocator_allocated", usedMemory);
        info.put("allocator_active", totalMemory);
        info.put("allocator_resident", totalMemory);
        info.put("total_system_memory", maxMemory);
        info.put("total_system_memory_human", toHumanReadable(maxMemory));
        info.put("used_memory_lua", 0); // TODO: Track Lua memory
        info.put("used_memory_lua_human", "0B");
        
        long scriptsBytes = RuntimeConfig.getCachedScriptsBytes();
        info.put("used_memory_scripts", scriptsBytes);
        info.put("used_memory_scripts_human", toHumanReadable(scriptsBytes));
        info.put("number_of_cached_scripts", RuntimeConfig.getCachedScriptsCount());
        
        long maxMemConfig = 0;
        String policy = "noeviction";
        MemoryStore store = server.getMemoryStore();
        if (store instanceof DefaultMemoryStore) {
            DefaultMemoryStore ds = (DefaultMemoryStore) store;
            maxMemConfig = ds.getMaxMemory();
            policy = ds.getMaxMemoryPolicy();
            info.put("softmaxmemory_threshold_percent", ds.getSoftLimitPercent());
            info.put("softmaxmemory_warning", ds.isSoftLimitExceeded() ? 1 : 0);
        } else {
            info.put("softmaxmemory_threshold_percent", 0);
            info.put("softmaxmemory_warning", 0);
        }
        info.put("maxmemory", maxMemConfig);
        info.put("maxmemory_human", toHumanReadable(maxMemConfig));
        info.put("maxmemory_policy", policy);
        
        info.put("mem_fragmentation_ratio", 0.0);
        info.put("mem_allocator", "jvm");
        return info;
    }

    private Map<String, Object> getPersistenceInfo() {
        PersistService ps = server.getPersistService();
        if (ps != null) {
            return ps.getInfo();
        }
        return new HashMap<>();
    }

    private Map<String, Object> getStatsInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("total_connections_received", RedisServerHandler.getTotalConnectionsReceived());
        info.put("total_commands_processed", RedisServerHandler.getTotalCommandsProcessed());
        info.put("instantaneous_ops_per_sec", 0); // TODO: Calculate OPS
        info.put("total_net_input_bytes", 0);
        info.put("total_net_output_bytes", 0);
        info.put("instantaneous_input_kbps", 0.00);
        info.put("instantaneous_output_kbps", 0.00);
        info.put("rejected_connections", 0);
        info.put("sync_full", 0);
        info.put("sync_partial_ok", 0);
        info.put("sync_partial_err", 0);
        info.put("expired_keys", 0); // TODO: Track expired keys
        info.put("expired_stale_perc", 0.00);
        info.put("expired_time_cap_reached_count", 0);
        info.put("evicted_keys", 0); // TODO: Track evicted keys
        info.put("keyspace_hits", RuntimeConfig.getKeyspaceHits());
        info.put("keyspace_misses", RuntimeConfig.getKeyspaceMisses());
        info.put("pubsub_channels", 0); // TODO: Expose from PubSubManager
        info.put("pubsub_patterns", 0);
        info.put("latest_fork_usec", 0);
        info.put("total_forks", 0);
        return info;
    }

    private Map<String, Object> getReplicationInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("role", "master");
        info.put("connected_slaves", 0);
        info.put("master_failover_state", "no-failover");
        info.put("master_replid", "0000000000000000000000000000000000000000");
        info.put("master_replid2", "0000000000000000000000000000000000000000");
        info.put("master_repl_offset", 0);
        info.put("second_repl_offset", -1);
        info.put("repl_backlog_active", 0);
        info.put("repl_backlog_size", 1048576);
        info.put("repl_backlog_first_byte_offset", 0);
        info.put("repl_backlog_histlen", 0);
        return info;
    }

    private Map<String, Object> getCpuInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("used_cpu_sys", 0.00);
        info.put("used_cpu_user", 0.00);
        info.put("used_cpu_sys_children", 0.00);
        info.put("used_cpu_user_children", 0.00);
        
        // Try to get system load average
        try {
            double load = osMXBean.getSystemLoadAverage();
            info.put("os_cpu_load_average", load);
            info.put("os_available_processors", osMXBean.getAvailableProcessors());
        } catch (Exception e) {
            // ignore
        }
        return info;
    }

    private Map<String, Object> getCommandStatsInfo() {
        Map<String, Object> info = new HashMap<>();
        // Placeholder for command stats until we implement per-command tracking
        String zeroStat = "calls=0,usec=0,usec_per_call=0.00";
        info.put("cmdstat_ping", zeroStat);
        info.put("cmdstat_echo", zeroStat);
        info.put("cmdstat_select", zeroStat);
        info.put("cmdstat_set", zeroStat);
        info.put("cmdstat_get", zeroStat);
        info.put("cmdstat_del", zeroStat);
        info.put("cmdstat_exists", zeroStat);
        info.put("cmdstat_expire", zeroStat);
        info.put("cmdstat_ttl", zeroStat);
        info.put("cmdstat_info", zeroStat);
        info.put("cmdstat_scan", zeroStat);
        return info;
    }

    private Map<String, Object> getClusterInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("cluster_enabled", 0);
        return info;
    }

    private Map<String, Object> getKeyspaceInfo() {
        Map<String, Object> info = new HashMap<>();
        MemoryStore store = server.getMemoryStore();
        // Assuming single DB for now or iterating 16
        for (int i = 0; i < 16; i++) {
            long size = store.dbsize(i);
            if (size > 0) {
                // keys=X,expires=Y,avg_ttl=Z
                info.put("db" + i, "keys=" + size + ",expires=0,avg_ttl=0");
            }
        }
        return info;
    }
    
    private Map<String, Object> getModulesInfo() {
        Map<String, Object> info = new HashMap<>();
        // No modules system yet
        return info;
    }
    
    private Map<String, Object> getErrorStatsInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("errorstat_ERR", "count=" + RuntimeConfig.getErrorRepliesTotal());
        info.put("errorstat_OOM", "count=" + RuntimeConfig.getErrorRepliesOom());
        return info;
    }

    private String toHumanReadable(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.2fKB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2fMB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2fGB", gb);
    }
}
