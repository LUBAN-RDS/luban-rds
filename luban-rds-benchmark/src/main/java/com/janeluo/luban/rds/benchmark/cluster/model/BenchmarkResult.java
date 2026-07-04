package com.janeluo.luban.rds.benchmark.cluster.model;

import java.util.List;

public class BenchmarkResult {
    private String scenarioName;
    private String mode; // "single" | "cluster-3" | "cluster-5" | "cluster-7"
    private int threadCount;
    private int totalOperations;
    private long durationMs;
    private LatencyDistribution latency;
    private double throughputOpsPerSec;
    private double avgCpuPercent;
    private double avgHeapMB;
    private long gcCount;
    private long gcTimeMs;
    private int redirectCount;
    private List<Long> latencySamples;

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String name) { this.scenarioName = name; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getThreadCount() { return threadCount; }
    public void setThreadCount(int count) { this.threadCount = count; }
    public int getTotalOperations() { return totalOperations; }
    public void setTotalOperations(int ops) { this.totalOperations = ops; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long ms) { this.durationMs = ms; }
    public LatencyDistribution getLatency() { return latency; }
    public void setLatency(LatencyDistribution latency) { this.latency = latency; }
    public double getThroughputOpsPerSec() { return throughputOpsPerSec; }
    public void setThroughputOpsPerSec(double ops) { this.throughputOpsPerSec = ops; }
    public double getAvgCpuPercent() { return avgCpuPercent; }
    public void setAvgCpuPercent(double cpu) { this.avgCpuPercent = cpu; }
    public double getAvgHeapMB() { return avgHeapMB; }
    public void setAvgHeapMB(double mb) { this.avgHeapMB = mb; }
    public long getGcCount() { return gcCount; }
    public void setGcCount(long count) { this.gcCount = count; }
    public long getGcTimeMs() { return gcTimeMs; }
    public void setGcTimeMs(long ms) { this.gcTimeMs = ms; }
    public int getRedirectCount() { return redirectCount; }
    public void setRedirectCount(int count) { this.redirectCount = count; }
    public List<Long> getLatencySamples() { return latencySamples; }
    public void setLatencySamples(List<Long> samples) { this.latencySamples = samples; }
}
