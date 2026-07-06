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
        info.put("redis_version", RuntimeConfig.VERSION);
        info.put("redis_git_sha1", RuntimeConfig.GIT_SHA1);
        info.put("redis_git_dirty", 0);
        info.put("redis_build_id", RuntimeConfig.BUILD_ID);
        info.put("redis_mode", "standalone");
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch") + " " + System.getProperty("os.version"));
        info.put("arch_bits", System.getProperty("os.arch").contains("64") ? "64" : "32");
        info.put("multiplexing_api", "netty");
        info.put("atomicvar_api", "java-atomic");
        info.put("gcc_version", "0.0.0");
        
        String pid = runtimeMXBean.getName().split("@")[0];
        info.put("process_id", pid);
        info.put("process_supervised", "no");
        info.put("run_id", RuntimeConfig.getRunId());
        info.put("tcp_port", server.getPort());
        info.put("server_time_usec", System.currentTimeMillis() * 1000);
        long uptime = runtimeMXBean.getUptime();
        info.put("uptime_in_seconds", uptime / 1000);
        info.put("uptime_in_days", uptime / (1000 * 60 * 60 * 24));
        info.put("hz", 10);
        info.put("configured_hz", 10);
        info.put("lru_clock", (System.currentTimeMillis() / 1000) & 0x00FFFFFF);
        info.put("executable", System.getProperty("java.home") + "/bin/java");
        info.put("config_file", server.getConfig().getPersistMode().equals("none") ? "" : "luban-rds.conf");
        
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
        info.put("maxclients", server.getConfig().getMaxclients());
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
        long jvmTotalMemory = runtime.totalMemory();
        long jvmFreeMemory = runtime.freeMemory();
        long jvmMaxMemory = runtime.maxMemory();
        long jvmUsedMemory = jvmTotalMemory - jvmFreeMemory;
        
        long used = 0;
        long peak = 0;
        long maxMemConfig = 0;
        String policy = "noeviction";
        double fragmentationRatio = 0.0;
        int totalKeys = 0;
        int expiredKeysCount = 0;
        
        MemoryStore store = server.getMemoryStore();
        if (store instanceof DefaultMemoryStore) {
            DefaultMemoryStore ds = (DefaultMemoryStore) store;
            used = ds.getUsedMemory();
            peak = ds.getPeakUsedMemory();
            maxMemConfig = ds.getMaxMemory();
            policy = ds.getMaxMemoryPolicy();
            fragmentationRatio = ds.getMemoryFragmentationRatio();
            info.put("softmaxmemory_threshold_percent", ds.getSoftLimitPercent());
            info.put("softmaxmemory_warning", ds.isSoftLimitExceeded() ? 1 : 0);
            MemoryStore.MemoryStats stats = ds.getMemoryStats();
            if (stats != null) {
                totalKeys = stats.getTotalKeys();
                expiredKeysCount = stats.getExpiredKeys();
            }
        } else {
            info.put("softmaxmemory_threshold_percent", 0);
            info.put("softmaxmemory_warning", 0);
        }
        
        info.put("used_memory", used);
        info.put("used_memory_human", toHumanReadable(used));
        info.put("used_memory_rss", jvmTotalMemory);
        info.put("used_memory_rss_human", toHumanReadable(jvmTotalMemory));
        info.put("used_memory_peak", peak);
        info.put("used_memory_peak_human", toHumanReadable(peak));
        double peakPerc = peak > 0 ? (used * 100.0 / peak) : 0.0;
        info.put("used_memory_peak_perc", String.format("%.2f%%", peakPerc));
        long overhead = jvmUsedMemory - used;
        info.put("used_memory_overhead", Math.max(0, overhead));
        info.put("used_memory_startup", 0);
        info.put("used_memory_dataset", used);
        info.put("used_memory_dataset_perc", used > 0 ? "100.00%" : "0.00%");
        info.put("allocator_allocated", jvmUsedMemory);
        info.put("allocator_active", jvmTotalMemory);
        info.put("allocator_resident", jvmTotalMemory);
        info.put("total_system_memory", jvmMaxMemory);
        info.put("total_system_memory_human", toHumanReadable(jvmMaxMemory));
        info.put("used_memory_lua", 0);
        info.put("used_memory_lua_human", "0B");
        
        long scriptsBytes = RuntimeConfig.getCachedScriptsBytes();
        info.put("used_memory_scripts", scriptsBytes);
        info.put("used_memory_scripts_human", toHumanReadable(scriptsBytes));
        info.put("number_of_cached_scripts", RuntimeConfig.getCachedScriptsCount());
        
        info.put("maxmemory", maxMemConfig);
        info.put("maxmemory_human", toHumanReadable(maxMemConfig));
        info.put("maxmemory_policy", policy);
        
        double allocatorFragRatio = jvmTotalMemory > 0 ? (double)(jvmTotalMemory - jvmUsedMemory) / jvmTotalMemory : 0.0;
        info.put("allocator_frag_ratio", String.format("%.2f", allocatorFragRatio));
        info.put("allocator_frag_bytes", Math.max(0, jvmTotalMemory - jvmUsedMemory));
        info.put("allocator_rss_ratio", 0.00);
        info.put("allocator_rss_bytes", 0);
        info.put("rss_overhead_ratio", 0.00);
        info.put("rss_overhead_bytes", 0);
        info.put("mem_fragmentation_ratio", String.format("%.2f", fragmentationRatio));
        info.put("mem_fragmentation_bytes", Math.max(0, overhead));
        info.put("mem_not_counted_for_evict", 0);
        info.put("mem_replication_backlog", 0);
        info.put("mem_clients_slaves", 0);
        info.put("mem_clients_normal", 0);
        info.put("mem_aof_buffer", 0);
        info.put("mem_allocator", "jvm");
        info.put("active_defrag_running", 0);
        info.put("lazyfree_pending_objects", 0);
        info.put("total_keys", totalKeys);
        info.put("expired_keys_in_memory", expiredKeysCount);
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
        info.put("instantaneous_ops_per_sec", 0);
        info.put("total_net_input_bytes", RuntimeConfig.getTotalNetInputBytes());
        info.put("total_net_output_bytes", RuntimeConfig.getTotalNetOutputBytes());
        info.put("instantaneous_input_kbps", 0.00);
        info.put("instantaneous_output_kbps", 0.00);
        info.put("rejected_connections", RuntimeConfig.getRejectedConnections());
        info.put("sync_full", RuntimeConfig.getSyncFull());
        info.put("sync_partial_ok", RuntimeConfig.getSyncPartialOk());
        info.put("sync_partial_err", RuntimeConfig.getSyncPartialErr());
        info.put("expired_keys", RuntimeConfig.getExpiredKeys());
        info.put("expired_stale_perc", 0.00);
        info.put("expired_time_cap_reached_count", 0);
        info.put("evicted_keys", RuntimeConfig.getEvictedKeys());
        info.put("keyspace_hits", RuntimeConfig.getKeyspaceHits());
        info.put("keyspace_misses", RuntimeConfig.getKeyspaceMisses());
        PubSubManager pubSubManager = RedisServerHandler.getPubSubManager();
        info.put("pubsub_channels", pubSubManager.getChannelCount());
        info.put("pubsub_patterns", pubSubManager.getPatternCount());
        info.put("latest_fork_usec", 0);
        info.put("total_forks", 0);
        info.put("migrate_cached_sockets", 0);
        info.put("slave_expires_tracked_keys", 0);
        info.put("active_defrag_hits", 0);
        info.put("active_defrag_misses", 0);
        info.put("active_defrag_key_hits", 0);
        info.put("active_defrag_key_misses", 0);
        info.put("tracking_total_keys", 0);
        info.put("tracking_total_items", 0);
        info.put("tracking_total_prefixes", 0);
        info.put("unexpected_error_replies", 0);
        info.put("oom_error_replies", RuntimeConfig.getErrorRepliesOom());
        info.put("total_error_replies", RuntimeConfig.getErrorRepliesTotal());
        info.put("dump_payload_sanitizations", 0);
        info.put("total_reads_processed", 0);
        info.put("total_writes_processed", 0);
        info.put("script_executions", RuntimeConfig.getScriptExecutions());
        info.put("script_timeouts", RuntimeConfig.getScriptTimeouts());
        info.put("script_kills", RuntimeConfig.getScriptKills());
        long totalMs = RuntimeConfig.getScriptTotalTimeMs();
        long executions = RuntimeConfig.getScriptExecutions();
        long avgMs = executions > 0 ? (totalMs / executions) : 0;
        long maxMs = RuntimeConfig.getScriptMaxTimeMs();
        info.put("script_avg_time_ms", avgMs);
        info.put("script_max_time_ms", maxMs);
        info.put("lua_max_script_bytes", RuntimeConfig.getLuaMaxScriptBytes());
        info.put("lua_max_return_bytes", RuntimeConfig.getLuaMaxReturnBytes());
        info.put("lua_max_ops_per_script", RuntimeConfig.getLuaMaxOpsPerScript());
        info.put("lua_yield_ms", RuntimeConfig.getLuaYieldMs());
        info.put("metrics_enabled", RuntimeConfig.isMetricsEnabled() ? 1 : 0);
        info.put("lua_sandbox_enabled", RuntimeConfig.isLuaSandboxEnabled() ? 1 : 0);
        info.put("lua_allowed_modules", RuntimeConfig.getLuaAllowedModules());
        info.put("lua_blocked_functions", RuntimeConfig.getLuaBlockedFunctions());
        long lastResetMs = RuntimeConfig.getLastResetTimeMs();
        info.put("stats_last_reset_time_ms", lastResetMs);
        String iso = lastResetMs > 0 ? java.time.Instant.ofEpochMilli(lastResetMs).toString() : "-";
        info.put("stats_last_reset_time_iso", iso);
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
        info.put("cluster_enabled", server.isClusterEnabled() ? 1 : 0);
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
