package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.common.context.InfoProvider;
import com.janeluo.luban.rds.common.context.ServerContext;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MEMORY 命令处理器
 * 支持子命令：USAGE, STATS, PURGE, MALLOC-STATS, DOCTOR, HELP
 */
public class RdsMemoryCommandHandler implements CommandHandler {

    @Override
    public Set<String> supportedCommands() {
        return new HashSet<>(Collections.singletonList("MEMORY"));
    }

    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'memory' command\r\n";
        }

        String subcommand = args[1].toUpperCase();

        try {
            switch (subcommand) {
                case "USAGE":
                    return handleUsage(database, args, store);
                case "STATS":
                    return handleStats(database, store);
                case "PURGE":
                    return handlePurge();
                case "MALLOC-STATS":
                    return handleMallocStats();
                case "DOCTOR":
                    return handleDoctor(store);
                case "HELP":
                    return handleHelp();
                default:
                    return "-ERR unknown subcommand '" + subcommand + "'. Try MEMORY HELP.\r\n";
            }
        } catch (Exception e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }

    private Object handleUsage(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'memory usage' command\r\n";
        }

        String key = args[2];
        
        // 解析可选参数 SAMPLES
        int samples = 5; // 默认值
        if (args.length > 3) {
            if (args.length < 5 || !"SAMPLES".equalsIgnoreCase(args[3])) {
                return "-ERR syntax error\r\n";
            }
            try {
                samples = Integer.parseInt(args[4]);
                if (samples < 0) {
                    return "-ERR count can't be negative\r\n";
                }
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        }

        Long usage = store.getMemoryUsage(database, key);
        if (usage == null) {
            return null; // RESP null bulk string
        }
        return usage;
    }

    private Object handleStats(int database, MemoryStore store) {
        List<Object> stats = new ArrayList<>();
        
        long peakAllocated = store.getPeakUsedMemory();
        long usedMemory = store.getUsedMemory();
        
        // JVM 内存信息
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        long totalAllocated = heapUsage.getCommitted() + nonHeapUsage.getCommitted();
        
        // 1. peak.allocated
        stats.add("peak.allocated");
        stats.add(peakAllocated);
        
        // 2. total.allocated (JVM committed)
        stats.add("total.allocated");
        stats.add(totalAllocated);
        
        // 3. startup.allocated (估算)
        stats.add("startup.allocated");
        stats.add(1024 * 1024L); // 假设 1MB
        
        // 4. replication.backlog
        stats.add("replication.backlog");
        stats.add(0L);
        
        // 5. clients.slaves
        stats.add("clients.slaves");
        stats.add(0L);
        
        // 6. clients.normal
        stats.add("clients.normal");
        long clients = 0;
        InfoProvider provider = ServerContext.getInfoProvider();
        if (provider != null) {
            Map<String, Object> clientInfo = provider.getInfo("clients");
            if (clientInfo != null && clientInfo.containsKey("connected_clients")) {
                Object val = clientInfo.get("connected_clients");
                if (val instanceof Number) {
                    clients = ((Number) val).longValue();
                } else if (val instanceof String) {
                    try {
                        clients = Long.parseLong((String) val);
                    } catch (NumberFormatException e) {}
                }
            }
        }
        stats.add(clients);
        
        // 7. aof.buffer
        stats.add("aof.buffer");
        stats.add(0L);
        
        // 8. lua.caches
        stats.add("lua.caches");
        stats.add(0L);
        
        // 9. overhead.total (使用 usedMemory - dataset 估算，这里简化为 10% usedMemory)
        long overhead = (long) (usedMemory * 0.1);
        stats.add("overhead.total");
        stats.add(overhead);
        
        // 10. keys.count
        long keysCount = 0;
        if (store instanceof DefaultMemoryStore) {
            DefaultMemoryStore defaultStore = (DefaultMemoryStore) store;
            for (int i = 0; i < defaultStore.getMaxDatabases(); i++) {
                keysCount += defaultStore.dbsize(i);
            }
        } else {
            // fallback
            keysCount = store.dbsize(database);
        }
        stats.add("keys.count");
        stats.add(keysCount);
        
        // 11. dataset.bytes
        stats.add("dataset.bytes");
        stats.add(Math.max(0, usedMemory - overhead));
        
        // 12. total.fragmentation (JVM committed / used)
        double fragmentation = usedMemory > 0 ? (double) totalAllocated / usedMemory : 1.0;
        stats.add("total.fragmentation");
        stats.add(String.format("%.2f", fragmentation));
        
        // 13. rss-overhead-ratio
        stats.add("rss-overhead-ratio");
        stats.add("1.0");
        
        return stats;
    }

    private Object handlePurge() {
        System.gc();
        return "OK";
    }

    private Object handleMallocStats() {
        StringBuilder sb = new StringBuilder();
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        
        sb.append("___ JVM Memory Stats ___\n");
        sb.append("Heap Memory:\n");
        sb.append("  Init: ").append(formatBytes(heapUsage.getInit())).append("\n");
        sb.append("  Used: ").append(formatBytes(heapUsage.getUsed())).append("\n");
        sb.append("  Committed: ").append(formatBytes(heapUsage.getCommitted())).append("\n");
        sb.append("  Max: ").append(formatBytes(heapUsage.getMax())).append("\n");
        
        sb.append("Non-Heap Memory:\n");
        sb.append("  Init: ").append(formatBytes(nonHeapUsage.getInit())).append("\n");
        sb.append("  Used: ").append(formatBytes(nonHeapUsage.getUsed())).append("\n");
        sb.append("  Committed: ").append(formatBytes(nonHeapUsage.getCommitted())).append("\n");
        sb.append("  Max: ").append(formatBytes(nonHeapUsage.getMax())).append("\n");
        
        sb.append("Runtime:\n");
        sb.append("  Total Memory: ").append(formatBytes(Runtime.getRuntime().totalMemory())).append("\n");
        sb.append("  Free Memory: ").append(formatBytes(Runtime.getRuntime().freeMemory())).append("\n");
        sb.append("  Max Memory: ").append(formatBytes(Runtime.getRuntime().maxMemory())).append("\n");
        
        return sb.toString();
    }
    
    private String formatBytes(long bytes) {
        return String.format("%d (%s)", bytes, humanReadableByteCount(bytes, false));
    }
    
    private String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    private Object handleDoctor(MemoryStore store) {
        StringBuilder report = new StringBuilder();
        
        long used = store.getUsedMemory();
        long peak = store.getPeakUsedMemory();
        long free = Runtime.getRuntime().freeMemory();
        long total = Runtime.getRuntime().totalMemory();
        long max = Runtime.getRuntime().maxMemory();
        
        report.append("Luban-RDS Memory Doctor\n\n");
        
        if (used > max * 0.9) {
            report.append("WARNING: Memory usage is very high (>90% of max heap).\n");
            report.append("  - Check for large keys using 'MEMORY USAGE'.\n");
            report.append("  - Consider increasing max heap size (-Xmx).\n");
        }
        
        if (peak > 0 && used < peak * 0.5) {
            report.append("HINT: Current memory usage is much lower than peak.\n");
            report.append("  - This might indicate fragmentation or a recent large deletion.\n");
            report.append("  - 'MEMORY PURGE' (System.gc) might help release system memory.\n");
        }
        
        if (free < total * 0.1) {
             report.append("WARNING: Low free memory in committed heap.\n");
             report.append("  - JVM might expand heap soon.\n");
        }
        
        if (report.length() == "Luban-RDS Memory Doctor\n\n".length()) {
            report.append("Everything looks fine! The memory is healthy.\n");
        }
        
        return report.toString();
    }

    private Object handleHelp() {
        return Arrays.asList(
            "MEMORY DOCTOR                        - Outputs a memory diagnosis report.",
            "MEMORY MALLOC-STATS                  - Show internal memory allocator stats.",
            "MEMORY PURGE                         - Ask the allocator to release memory.",
            "MEMORY STATS                         - Show memory usage details.",
            "MEMORY USAGE <key> [SAMPLES <count>] - Estimate memory usage of a key."
        );
    }
}
