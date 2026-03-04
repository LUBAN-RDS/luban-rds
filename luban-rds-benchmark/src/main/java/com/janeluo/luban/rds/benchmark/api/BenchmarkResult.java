package com.janeluo.luban.rds.benchmark.api;

public class BenchmarkResult {
    private String name;
    private long operations;
    private double durationSeconds;
    private double opsPerSec;
    private double avgLatencyMs;
    private long errorCount;

    public BenchmarkResult(String name, long operations, double durationSeconds, long errorCount) {
        this.name = name;
        this.operations = operations;
        this.durationSeconds = durationSeconds;
        this.errorCount = errorCount;
        this.opsPerSec = durationSeconds > 0 ? operations / durationSeconds : 0;
        this.avgLatencyMs = operations > 0 ? (durationSeconds * 1000) / operations : 0;
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

    @Override
    public String toString() {
        return String.format("%-15s: %,15.0f ops/sec | Avg Latency: %.2f ms | Errors: %d", 
                name, opsPerSec, avgLatencyMs, errorCount);
    }
}
