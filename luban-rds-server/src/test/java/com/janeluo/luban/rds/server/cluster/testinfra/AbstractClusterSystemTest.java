package com.janeluo.luban.rds.server.cluster.testinfra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Path;
import java.time.Instant;

public abstract class AbstractClusterSystemTest {
    protected ClusterTestHarness harness;
    protected NetworkSimulator network;
    protected MetricsCollector metrics;

    @BeforeEach
    void setUp() {
        harness = new ClusterTestHarness();
        metrics = new MetricsCollector(
                getClass().getSimpleName(), getClass().getName(), "L1");
        metrics.startCollection(1000);
    }

    @AfterEach
    void tearDown() {
        metrics.writeToJson(getMetricsPath());
        if (harness != null) harness.stopAll();
    }

    protected Path getMetricsPath() {
        return Path.of("target", "test-metrics",
                "L1-" + getClass().getSimpleName() + "-" + Instant.now().toEpochMilli() + ".json");
    }
}
