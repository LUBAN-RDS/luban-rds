package com.janeluo.luban.rds.server.cluster.testinfra;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector implements AutoCloseable {

    private final List<Long> latencySamples = new CopyOnWriteArrayList<>();
    private final List<ResourceSnapshot> resourceSnapshots = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private final AtomicInteger opsCount = new AtomicInteger(0);
    private final AtomicLong totalLatencyUs = new AtomicLong(0);
    private volatile long testStartTime;
    private volatile long testEndTime;
    private final String testName;
    private final String testClass;
    private final String layer;

    public MetricsCollector(String testName, String testClass, String layer) {
        this.testName = testName;
        this.testClass = testClass;
        this.layer = layer;
    }

    public void startCollection(long intervalMs) {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        testStartTime = System.currentTimeMillis();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-collector");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::collectResourceSnapshot, 0, intervalMs,
                TimeUnit.MILLISECONDS);
    }

    public void recordLatency(String operation, long latencyUs) {
        // operation 参数保留以备后续按操作类型分桶统计，当前未使用
        latencySamples.add(latencyUs);
        totalLatencyUs.addAndGet(latencyUs);
        opsCount.incrementAndGet();
    }

    public void recordThroughput(String scenario, int ops, long durationMs) {
        // scenario/ops/durationMs 参数保留以备后续扩展，当前未使用
        collectResourceSnapshot();
    }

    private void collectResourceSnapshot() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();
            double heapUsedMB = heapUsed / (1024.0 * 1024.0);

            long gcCount = 0;
            long gcTimeMs = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long c = gc.getCollectionCount();
                if (c >= 0) gcCount += c;
                long t = gc.getCollectionTime();
                if (t >= 0) gcTimeMs += t;
            }

            int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();

            double cpuUsage = -1;
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean osBean) {
                double load = osBean.getProcessCpuLoad();
                if (load >= 0) cpuUsage = load * 100;
            }

            resourceSnapshots.add(new ResourceSnapshot(
                    System.currentTimeMillis(), cpuUsage, heapUsedMB, gcCount, gcTimeMs, threadCount));
        } catch (Exception e) {
            System.out.println("收集资源指标失败: " + e.getMessage());
        }
    }

    public void writeToJson(Path path) {
        testEndTime = System.currentTimeMillis();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 手动拼接 JSON（避免 jackson 依赖）
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"testName\": \"").append(escapeJson(testName)).append("\",\n");
        sb.append("  \"testClass\": \"").append(escapeJson(testClass)).append("\",\n");
        sb.append("  \"layer\": \"").append(escapeJson(layer)).append("\",\n");
        sb.append("  \"timestamp\": ").append(testStartTime).append(",\n");
        sb.append("  \"duration\": ").append(testEndTime - testStartTime).append(",\n");

        // 延迟统计
        if (!latencySamples.isEmpty()) {
            List<Long> sorted = new ArrayList<>(latencySamples);
            Collections.sort(sorted);
            int n = sorted.size();
            int p95Index = Math.min((int) (n * 0.95), n - 1);
            int p99Index = Math.min((int) (n * 0.99), n - 1);
            sb.append("  \"latency\": {\n");
            sb.append("    \"count\": ").append(n).append(",\n");
            sb.append("    \"min\": ").append(sorted.get(0)).append(",\n");
            sb.append("    \"p50\": ").append(sorted.get(n / 2)).append(",\n");
            sb.append("    \"p95\": ").append(sorted.get(p95Index)).append(",\n");
            sb.append("    \"p99\": ").append(sorted.get(p99Index)).append(",\n");
            sb.append("    \"max\": ").append(sorted.get(n - 1)).append(",\n");
            sb.append("    \"mean\": ").append((double) totalLatencyUs.get() / n).append("\n");
            sb.append("  },\n");
        }

        // 资源采样
        sb.append("  \"resources\": [\n");
        for (int i = 0; i < resourceSnapshots.size(); i++) {
            ResourceSnapshot r = resourceSnapshots.get(i);
            sb.append("    {\"timestamp\":").append(r.timestamp)
                    .append(",\"cpuPercent\":").append(String.format(Locale.ROOT, "%.2f", r.cpuPercent))
                    .append(",\"heapMB\":").append(String.format(Locale.ROOT, "%.2f", r.heapMB))
                    .append(",\"gcCount\":").append(r.gcCount)
                    .append(",\"gcTimeMs\":").append(r.gcTimeMs)
                    .append(",\"threads\":").append(r.threadCount).append("}");
            if (i < resourceSnapshots.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, sb.toString());
        } catch (Exception e) {
            System.out.println("写入指标 JSON 失败: " + path + " : " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    static class ResourceSnapshot {
        final long timestamp;
        final double cpuPercent;
        final double heapMB;
        final long gcCount;
        final long gcTimeMs;
        final int threadCount;

        ResourceSnapshot(long timestamp, double cpuPercent, double heapMB,
                         long gcCount, long gcTimeMs, int threadCount) {
            this.timestamp = timestamp;
            this.cpuPercent = cpuPercent;
            this.heapMB = heapMB;
            this.gcCount = gcCount;
            this.gcTimeMs = gcTimeMs;
            this.threadCount = threadCount;
        }
    }
}
