package com.janeluo.luban.rds.benchmark.api;

public class BenchmarkResult {
    private String name;
    private long operations;
    private double durationSeconds;
    private double opsPerSec;
    private double avgLatencyMs;
    private long errorCount;
    private int threads;

    public BenchmarkResult(String name, long operations, double durationSeconds, long errorCount) {
        this(name, operations, durationSeconds, errorCount, 0, 0);
    }

    public BenchmarkResult(String name, long operations, double durationSeconds, long errorCount, 
            double avgLatencyMs, int threads) {
        this.name = name;
        this.operations = operations;
        this.durationSeconds = durationSeconds;
        this.errorCount = errorCount;
        this.avgLatencyMs = avgLatencyMs;
        this.threads = threads;
        this.opsPerSec = durationSeconds > 0 ? operations / durationSeconds : 0;
    }

    public String getName() {
        return name;
    }

    public long getOperations() {
        return operations;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public double getOpsPerSec() {
        return opsPerSec;
    }

    public double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public int getThreads() {
        return threads;
    }

    @Override
    public String toString() {
        return String.format("%-15s: %,15.0f ops/sec | Avg Latency: %.2f ms | Errors: %d", 
                name, opsPerSec, avgLatencyMs, errorCount);
    }
}
