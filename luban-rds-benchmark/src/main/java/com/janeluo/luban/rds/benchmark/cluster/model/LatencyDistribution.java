package com.janeluo.luban.rds.benchmark.cluster.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LatencyDistribution {
    private final List<Long> samples;

    public LatencyDistribution() {
        this.samples = new ArrayList<>();
    }

    public void addSample(long latencyMicros) {
        samples.add(latencyMicros);
    }

    public long getP50() { return getPercentile(50); }
    public long getP95() { return getPercentile(95); }
    public long getP99() { return getPercentile(99); }

    public long getMin() {
        if (samples.isEmpty()) return 0;
        return Collections.min(samples);
    }

    public long getMax() {
        if (samples.isEmpty()) return 0;
        return Collections.max(samples);
    }

    public long getMean() {
        if (samples.isEmpty()) return 0;
        return samples.stream().mapToLong(Long::longValue).sum() / samples.size();
    }

    public long getPercentile(int percentile) {
        if (samples.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) (sorted.size() * percentile / 100.0);
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index);
    }

    public List<Long> getSamples() { return samples; }
    public int getCount() { return samples.size(); }
}
