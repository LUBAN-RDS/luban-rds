package com.janeluo.luban.rds.benchmark.api;

public class BenchmarkConfig {
    private String host = "127.0.0.1";
    private int port = 9736;
    private int threads = 10;
    private int totalOperations = 100000;
    private int valueSize = 100;
    private int durationSeconds = 0; // 0 means use totalOperations
    private String keyPrefix = "bench";
    private boolean monitorMemory = false;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getTotalOperations() {
        return totalOperations;
    }

    public void setTotalOperations(int totalOperations) {
        this.totalOperations = totalOperations;
    }

    public int getValueSize() {
        return valueSize;
    }

    public void setValueSize(int valueSize) {
        this.valueSize = valueSize;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public boolean isMonitorMemory() {
        return monitorMemory;
    }

    public void setMonitorMemory(boolean monitorMemory) {
        this.monitorMemory = monitorMemory;
    }

    @Override
    public String toString() {
        return "BenchmarkConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", threads=" + threads +
                ", totalOperations=" + totalOperations +
                ", valueSize=" + valueSize +
                ", durationSeconds=" + durationSeconds +
                ", keyPrefix='" + keyPrefix + '\'' +
                ", monitorMemory=" + monitorMemory +
                '}';
    }
}
