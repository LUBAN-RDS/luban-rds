package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.context.InfoProvider;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Legacy InfoProvider implementation to support existing tests and fallback behavior.
 */
public class LegacyInfoProvider implements InfoProvider {
    
    private final MemoryStore store;

    public LegacyInfoProvider(MemoryStore store) {
        this.store = store;
    }

    @Override
    public Map<String, Object> getInfo(String section) {
        Map<String, Object> info = new HashMap<>();
        if (section == null || section.isEmpty() || "all".equalsIgnoreCase(section) || "default".equalsIgnoreCase(section)) {
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
        } else {
             if ("server".equalsIgnoreCase(section)) info.putAll(getServerInfo());
             else if ("clients".equalsIgnoreCase(section)) info.putAll(getClientsInfo());
             else if ("memory".equalsIgnoreCase(section)) info.putAll(getMemoryInfo());
             else if ("persistence".equalsIgnoreCase(section)) info.putAll(getPersistenceInfo());
             else if ("stats".equalsIgnoreCase(section)) info.putAll(getStatsInfo());
             else if ("replication".equalsIgnoreCase(section)) info.putAll(getReplicationInfo());
             else if ("cpu".equalsIgnoreCase(section)) info.putAll(getCpuInfo());
             else if ("commandstats".equalsIgnoreCase(section)) info.putAll(getCommandStatsInfo());
             else if ("cluster".equalsIgnoreCase(section)) info.putAll(getClusterInfo());
             else if ("keyspace".equalsIgnoreCase(section)) info.putAll(getKeyspaceInfo());
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
        info.put("os", "Linux");
        info.put("arch_bits", "64");
        info.put("multiplexing_api", "netty");
        info.put("atomicvar_api", "java");
        info.put("gcc_version", "0.0.0");
        info.put("process_id", 0);
        info.put("process_supervised", "no");
        info.put("run_id", "00000000000000000000000000000000");
        info.put("tcp_port", 9736);
        info.put("server_time_usec", 0);
        info.put("uptime_in_seconds", 0);
        info.put("uptime_in_days", 0);
        info.put("hz", 10);
        info.put("configured_hz", 0);
        info.put("lru_clock", 0);
        info.put("executable", "/path/to/luban-rds");
        info.put("config_file", "/path/to/redis.conf");
        return info;
    }

    private Map<String, Object> getClientsInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("connected_clients", 0);
        info.put("cluster_connections", 0);
        info.put("maxclients", 10000);
        info.put("client_recent_max_input_buffer", 0);
        info.put("client_recent_max_output_buffer", 0);
        info.put("blocked_clients", 0);
        info.put("tracking_clients", 0);
        info.put("clients_in_timeout_table", 0);
        return info;
    }

    private Map<String, Object> getMemoryInfo() {
        Map<String, Object> info = new HashMap<>();
        long used = 0;
        long maxMem = 0;
        if (store instanceof DefaultMemoryStore) {
            DefaultMemoryStore ds = (DefaultMemoryStore) store;
            used = ds.getUsedMemory();
            maxMem = ds.getMaxMemory();
        }
        info.put("used_memory", used);
        info.put("used_memory_human", toHumanReadable(used));
        info.put("used_memory_rss", 0);
        info.put("used_memory_rss_human", "0B");
        info.put("used_memory_peak", 0);
        info.put("used_memory_peak_human", "0B");
        info.put("used_memory_peak_perc", "0.00%");
        info.put("used_memory_overhead", 0);
        info.put("used_memory_startup", 0);
        info.put("used_memory_dataset", 0);
        info.put("used_memory_dataset_perc", "0.00%");
        info.put("allocator_allocated", 0);
        info.put("allocator_active", 0);
        info.put("allocator_resident", 0);
        info.put("total_system_memory", 0);
        info.put("total_system_memory_human", "0B");
        info.put("used_memory_lua", 0);
        info.put("used_memory_lua_human", "0B");
        long scriptsBytes = RuntimeConfig.getCachedScriptsBytes();
        info.put("used_memory_scripts", scriptsBytes);
        info.put("used_memory_scripts_human", toHumanReadable(scriptsBytes));
        info.put("number_of_cached_scripts", RuntimeConfig.getCachedScriptsCount());
        info.put("maxmemory", maxMem);
        info.put("maxmemory_human", toHumanReadable(maxMem));
        String policy = "noeviction";
        if (store instanceof DefaultMemoryStore) {
            policy = ((DefaultMemoryStore) store).getMaxMemoryPolicy();
        }
        info.put("maxmemory_policy", policy);
        if (store instanceof DefaultMemoryStore) {
            DefaultMemoryStore ds = (DefaultMemoryStore) store;
            info.put("softmaxmemory_threshold_percent", ds.getSoftLimitPercent());
            info.put("softmaxmemory_warning", ds.isSoftLimitExceeded() ? 1 : 0);
        } else {
            info.put("softmaxmemory_threshold_percent", 0);
            info.put("softmaxmemory_warning", 0);
        }
        info.put("allocator_frag_ratio", 0.00);
        info.put("allocator_frag_bytes", 0);
        info.put("allocator_rss_ratio", 0.00);
        info.put("allocator_rss_bytes", 0);
        info.put("rss_overhead_ratio", 0.00);
        info.put("rss_overhead_bytes", 0);
        info.put("mem_fragmentation_ratio", 0.00);
        info.put("mem_fragmentation_bytes", 0);
        info.put("mem_not_counted_for_evict", 0);
        info.put("mem_replication_backlog", 0);
        info.put("mem_clients_slaves", 0);
        info.put("mem_clients_normal", 0);
        info.put("mem_aof_buffer", 0);
        info.put("mem_allocator", "jemalloc-5.1.0");
        info.put("active_defrag_running", 0);
        info.put("lazyfree_pending_objects", 0);
        return info;
    }

    private Map<String, Object> getPersistenceInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("loading", 0);
        info.put("current_cow_size", 0);
        info.put("current_cow_size_age", 0);
        info.put("current_fork_perc", 0.00);
        info.put("current_save_keys_processed", 0);
        info.put("current_save_keys_total", 0);
        info.put("rdb_changes_since_last_save", 0);
        info.put("rdb_bgsave_in_progress", 0);
        info.put("rdb_last_save_time", 0);
        info.put("rdb_last_bgsave_status", "ok");
        info.put("rdb_last_bgsave_time_sec", -1);
        info.put("rdb_current_bgsave_time_sec", -1);
        info.put("rdb_last_cow_size", 0);
        info.put("aof_enabled", 0);
        info.put("aof_rewrite_in_progress", 0);
        info.put("aof_rewrite_scheduled", 0);
        info.put("aof_last_rewrite_time_sec", -1);
        info.put("aof_current_rewrite_time_sec", -1);
        info.put("aof_last_bgrewrite_status", "ok");
        info.put("aof_last_write_status", "ok");
        info.put("aof_last_cow_size", 0);
        info.put("aof_current_size", 0);
        info.put("aof_base_size", 0);
        info.put("aof_pending_rewrite", 0);
        info.put("aof_buffer_length", 0);
        info.put("aof_rewrite_buffer_length", 0);
        info.put("aof_pending_bio_fsync", 0);
        info.put("aof_delayed_fsync", 0);
        return info;
    }

    private Map<String, Object> getStatsInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("total_connections_received", 0);
        info.put("total_commands_processed", 0);
        info.put("instantaneous_ops_per_sec", 0);
        info.put("total_net_input_bytes", 0);
        info.put("total_net_output_bytes", 0);
        info.put("instantaneous_input_kbps", 0.00);
        info.put("instantaneous_output_kbps", 0.00);
        info.put("rejected_connections", 0);
        info.put("sync_full", 0);
        info.put("sync_partial_ok", 0);
        info.put("sync_partial_err", 0);
        info.put("expired_keys", 0);
        info.put("expired_stale_perc", 0.00);
        info.put("expired_time_cap_reached_count", 0);
        info.put("evicted_keys", 0);
        info.put("keyspace_hits", RuntimeConfig.getKeyspaceHits());
        info.put("keyspace_misses", RuntimeConfig.getKeyspaceMisses());
        info.put("pubsub_channels", 0);
        info.put("pubsub_patterns", 0);
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
        return info;
    }

    private Map<String, Object> getCommandStatsInfo() {
        Map<String, Object> info = new HashMap<>();
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
        info.put("db0", "keys=0,expires=0,avg_ttl=0");
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
